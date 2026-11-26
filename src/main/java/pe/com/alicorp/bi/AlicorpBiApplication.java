package pe.com.alicorp.bi;

import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.staticfiles.Location;
import jakarta.servlet.http.HttpSession;
import org.eclipse.jetty.http.HttpCookie;
import org.eclipse.jetty.server.ForwardedRequestCustomizer;
import org.eclipse.jetty.server.session.SessionHandler;
import pe.com.alicorp.bi.config.DatabaseConfig;
import pe.com.alicorp.bi.modulos.acceso_biometria.AccesoBiometriaController;
import pe.com.alicorp.bi.modulos.acceso_biometria.ServicioActividadEquipo;
import pe.com.alicorp.bi.modulos.acceso_biometria.ServicioBiometriaJavaCV;
import pe.com.alicorp.bi.modulos.acceso_biometria.ServicioSeguridadRelacional;
import pe.com.alicorp.bi.modulos.chatbot_asistente.ChatbotController;
import pe.com.alicorp.bi.modulos.contacto_soporte.ContactoSoporteController;
import pe.com.alicorp.bi.modulos.dashboard_prediccion.DashboardAnaliticaController;
import pe.com.alicorp.bi.modulos.dashboard_prediccion.ServicioAnaliticaSmile;
import pe.com.alicorp.bi.modulos.dashboard_prediccion.ServicioRedNeuronalDL4J;
import pe.com.alicorp.bi.modulos.estrategia_comercial.RecomendadorController;
import pe.com.alicorp.bi.modulos.tienda_buscador_ia.ServicioBuscadorIA;
import pe.com.alicorp.bi.modulos.tienda_buscador_ia.TiendaBuscadorController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

/**
 * Punto de entrada principal de Alicorp BI v2 en Java 21+.
 * Levanta Javalin detrás de proxy HTTPS (Cloudflare Tunnel), aplica defensa en 3 capas
 * (Key en sesión servidor -> Autenticación estricta -> Middleware de Dashboard y API privada)
 * y sirve las vistas administrativas desde protected_views/ fuera de la carpeta pública.
 */
public class AlicorpBiApplication {

    public static final String ATTR_KEY_HABILITADA = "key_habilitada";
    public static final String ATTR_KEY_TIMESTAMP = "key_habilitada_en";
    public static final String ATTR_USUARIO_AUTENTICADO = "usuario";
    public static final String ATTR_USUARIO_PENDIENTE_FACIAL = "usuario_pendiente_facial";
    public static final String ATTR_BIOMETRIA_REGISTRADA = "biometria_registrada";
    public static final String ATTR_BIOMETRIA_VERIFICADA = "biometria_registrada";
    private static final long KEY_TTL_MS = 30 * 60 * 1000L; // 30 minutos para completar login

    private static final Set<String> API_PUBLICA = Set.of(
            "/api/seguridad/validar_key",
            "/api/login_password",
            "/api/login_facial",
            "/api/logout",
            "/api/buscador-ia",
            "/api/buscador-ia/tiendas",
            "/api/tienda/estado",
            "/api/tienda/categorias",
            "/api/tienda/destacados",
            "/api/tienda/productos",
            "/api/tienda/buscar",
            "/api/tienda/sugerencias",
            "/api/tienda/recomendados",
            "/api/tienda/metricas",
            "/api/tienda/metricas/resumen",
            "/api/tienda/metricas/reiniciar",
            "/api/tienda/comprar",
            "/api/tienda/pedido",
            "/api/contacto",
            "/api/fallas_web/reportar",
            "/api/chatbot/cliente"
    );

    /**
     * ÚNICAS rutas permitidas cuando biometria_registrada == false:
     * cerrar sesión, consultar datos básicos del propio perfil, cambiar usuario/contraseña y registrar rostro por primera vez.
     */
    private static final Set<String> API_PERMITIDA_SIN_BIOMETRIA = Set.of(
            "/api/logout",
            "/api/usuario_perfil",
            "/api/usuario/actualizar_credenciales",
            "/api/usuario/registrar_biometria_inicial"
    );

    public static void main(String[] args) {
        // 1. Inicializar Pool Relacional 3FN (Supabase PostgreSQL / H2)
        DatabaseConfig.getDataSource();

        // 2. Instanciar servicios de IA, Biometría, Seguridad y Buscador
        ServicioRedNeuronalDL4J motorDl4j = new ServicioRedNeuronalDL4J();
        ServicioAnaliticaSmile motorSmile = new ServicioAnaliticaSmile();
        ServicioBiometriaJavaCV motorBiometria = new ServicioBiometriaJavaCV();
        ServicioSeguridadRelacional motorSeguridad = new ServicioSeguridadRelacional();
        ServicioActividadEquipo servicioEquipo = ServicioActividadEquipo.compartido();
        ServicioBuscadorIA servicioBuscadorIA = new ServicioBuscadorIA();

        int puerto = Integer.parseInt(System.getenv().getOrDefault("PORT", "5000"));

        Javalin app = Javalin.create(config -> {
            config.staticFiles.add(staticFiles -> {
                staticFiles.hostedPath = "/";
                staticFiles.directory = "frontend_corporativo";
                staticFiles.location = Location.EXTERNAL;
            });

            // Proxy inverso HTTPS (Cloudflare Tunnel): confía en X-Forwarded-Proto / X-Forwarded-Host
            config.jetty.modifyHttpConfiguration(httpConfig ->
                    httpConfig.addCustomizer(new ForwardedRequestCustomizer())
            );

            // Cookies HttpOnly, SameSite=Lax, Path=/, sin fijar Domain a localhost y Secure bajo HTTPS
            config.jetty.modifyServletContextHandler(handler -> {
                SessionHandler sessionHandler = handler.getSessionHandler();
                if (sessionHandler == null) {
                    sessionHandler = new SessionHandler();
                    handler.setSessionHandler(sessionHandler);
                }
                sessionHandler.setHttpOnly(true);
                sessionHandler.setSameSite(HttpCookie.SameSite.LAX);
                sessionHandler.setSecureRequestOnly(true);
                sessionHandler.getSessionCookieConfig().setPath("/");
                sessionHandler.getSessionCookieConfig().setDomain(null);
            });
        });

        // ================= CAPA 1: HABILITACIÓN DE KEY EN SESIÓN SERVIDOR =================
        app.before(ctx -> {
            String keyParam = extraerKeyDeQuery(ctx);
            if (keyParam != null && !keyParam.isBlank() && motorSeguridad.validarClaveAcceso(keyParam)) {
                habilitarKeyEnSesion(ctx);
            }
        });

        // ================= CAPA 3: BLOQUEO TOTAL DE API PRIVADA + ANTI-DEVTOOLS BIOMÉTRICO =================
        app.before("/api/*", ctx -> {
            String ruta = normalizarRuta(ctx.path());
            if (API_PUBLICA.contains(ruta)) {
                return;
            }
            if (!tieneSesionAutenticada(ctx, motorSeguridad)) {
                aplicarNoCache(ctx);
                ctx.status(401).json(Map.of(
                        "success", false,
                        "message", "No autorizado. Inicia sesión como personal autorizado."
                ));
                ctx.skipRemainingHandlers();
                return;
            }
            if (!API_PERMITIDA_SIN_BIOMETRIA.contains(ruta)
                    && !tieneBiometriaActivaEnServidor(ctx, motorSeguridad, motorBiometria)) {
                aplicarNoCache(ctx);
                ctx.status(403).json(Map.of(
                        "error", "BIOMETRIC_REQUIRED",
                        "code", "BIOMETRIC_REQUIRED",
                        "success", false,
                        "biometria_registrada", false,
                        "biometria_pendiente", true,
                        "message", "Debes registrar tu rostro 128-D en 'Mi Perfil y Seguridad' para desbloquear los datos."
                ));
                ctx.skipRemainingHandlers();
            }
        });

        // ================= VISTAS ADMINISTRATIVAS PROTEGIDAS (FUERA DE ESTÁTICOS PÚBLICOS) =================
        app.get("/acceso_seguridad/admin_login.html", ctx -> servirVistaLogin(ctx, motorSeguridad));
        app.get("/admin_login.html", ctx -> servirVistaLogin(ctx, motorSeguridad));

        app.get("/dashboard_bi/dashboard.html", ctx -> servirVistaDashboard(ctx, motorSeguridad));
        app.get("/dashboard.html", ctx -> servirVistaDashboard(ctx, motorSeguridad));

        // 3. Alias públicos preservando query strings (?key=..., ?q=...)
        Map<String, String> aliasPublicos = Map.ofEntries(
                Map.entry("/", "/portal_institucional/index.html"),
                Map.entry("/index.html", "/portal_institucional/index.html"),
                Map.entry("/clientes.html", "/portal_institucional/clientes.html"),
                Map.entry("/inversionistas.html", "/portal_institucional/inversionistas.html"),
                Map.entry("/etica.html", "/portal_institucional/etica.html"),
                Map.entry("/sostenibilidad.html", "/portal_institucional/sostenibilidad.html"),
                Map.entry("/tienda.html", "/tienda_virtual/tienda.html"),
                Map.entry("/contacto.html", "/contacto_soporte/contacto.html"),
                Map.entry("/comparativa_busqueda.html", "/tienda_virtual/comparativa_busqueda.html")
        );
        aliasPublicos.forEach((origen, destino) -> app.get(origen, ctx -> redirigirConQuery(ctx, destino)));

        // 4. Registrar controladores REST por módulo web
        AccesoBiometriaController.registrar(app, motorBiometria, motorSeguridad, servicioEquipo);
        TiendaBuscadorController.registrar(app, servicioBuscadorIA);
        DashboardAnaliticaController.registrar(app, motorDl4j, motorSmile);
        RecomendadorController.registrar(app, motorSeguridad);
        ContactoSoporteController.registrar(app);
        ChatbotController.registrar(app, servicioBuscadorIA, motorSeguridad);

        app.start(puerto);
        System.out.println("=========================================================================");
        System.out.println(" ALICORP BI v2 (100% JAVA 21+ · SEGURIDAD 3 CAPAS + CLOUDFLARE HTTPS)");
        System.out.println(" Servidor activo en: http://localhost:" + puerto);
        System.out.println("=========================================================================");
    }

    public static void habilitarKeyEnSesion(Context ctx) {
        HttpSession ses = ctx.req().getSession(true);
        ses.setAttribute(ATTR_KEY_HABILITADA, Boolean.TRUE);
        ses.setAttribute(ATTR_KEY_TIMESTAMP, System.currentTimeMillis());
    }

    public static boolean tieneKeyHabilitada(Context ctx) {
        HttpSession ses = ctx.req().getSession(false);
        if (ses == null) return false;
        if (!Boolean.TRUE.equals(ses.getAttribute(ATTR_KEY_HABILITADA))) return false;
        Object tsObj = ses.getAttribute(ATTR_KEY_TIMESTAMP);
        if (tsObj instanceof Long ts && (System.currentTimeMillis() - ts) > KEY_TTL_MS) {
            ses.removeAttribute(ATTR_KEY_HABILITADA);
            ses.removeAttribute(ATTR_KEY_TIMESTAMP);
            return false;
        }
        return true;
    }

    public static boolean tieneSesionAutenticada(Context ctx, ServicioSeguridadRelacional motorSeguridad) {
        HttpSession ses = ctx.req().getSession(false);
        if (ses == null) return false;
        Object usrObj = ses.getAttribute(ATTR_USUARIO_AUTENTICADO);
        if (usrObj == null) return false;
        String usuario = String.valueOf(usrObj).trim();
        if (usuario.isEmpty()) return false;
        if (!motorSeguridad.esUsuarioActivo3FN(usuario)) {
            ses.invalidate();
            return false;
        }
        return true;
    }

    public static boolean tieneBiometriaActivaEnServidor(
            Context ctx,
            ServicioSeguridadRelacional motorSeguridad,
            ServicioBiometriaJavaCV motorBiometria) {
        HttpSession ses = ctx.req().getSession(false);
        if (ses == null) return false;
        if (!Boolean.TRUE.equals(ses.getAttribute(ATTR_BIOMETRIA_REGISTRADA))) {
            return false;
        }
        Object usrObj = ses.getAttribute(ATTR_USUARIO_AUTENTICADO);
        if (usrObj == null) return false;
        String usuario = String.valueOf(usrObj).trim();
        if (usuario.isEmpty()) return false;
        boolean tieneBioEnBd = motorSeguridad.tieneBiometriaRegistrada3FN(usuario, motorBiometria);
        if (!tieneBioEnBd) {
            ses.setAttribute(ATTR_BIOMETRIA_REGISTRADA, Boolean.FALSE);
            return false;
        }
        return true;
    }

    public static void aplicarNoCache(Context ctx) {
        ctx.header("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0");
        ctx.header("Pragma", "no-cache");
        ctx.header("Expires", "0");
    }

    private static void servirVistaLogin(Context ctx, ServicioSeguridadRelacional motorSeguridad) {
        aplicarNoCache(ctx);
        if (!tieneKeyHabilitada(ctx) && !tieneSesionAutenticada(ctx, motorSeguridad)) {
            ctx.redirect("/tienda_virtual/tienda.html");
            return;
        }
        enviarHtmlProtegido(ctx, Path.of("protected_views", "admin_login.html"));
    }

    private static void servirVistaDashboard(Context ctx, ServicioSeguridadRelacional motorSeguridad) {
        aplicarNoCache(ctx);
        if (!tieneSesionAutenticada(ctx, motorSeguridad)) {
            ctx.redirect(tieneKeyHabilitada(ctx)
                    ? "/acceso_seguridad/admin_login.html"
                    : "/tienda_virtual/tienda.html");
            return;
        }
        enviarHtmlProtegido(ctx, Path.of("protected_views", "dashboard.html"));
    }

    private static void enviarHtmlProtegido(Context ctx, Path archivo) {
        try {
            String html = Files.readString(archivo, StandardCharsets.UTF_8);
            ctx.contentType("text/html; charset=UTF-8");
            ctx.result(html);
        } catch (IOException e) {
            ctx.status(500).result("Error cargando vista protegida.");
        }
    }

    private static String extraerKeyDeQuery(Context ctx) {
        for (String param : new String[]{"key", "clave", "access", "token"}) {
            String val = ctx.queryParam(param);
            if (val != null && !val.isBlank()) return val.trim();
        }
        return null;
    }

    private static String normalizarRuta(String path) {
        if (path == null) return "";
        return path.length() > 1 && path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
    }

    private static void redirigirConQuery(Context ctx, String destino) {
        String qs = ctx.queryString();
        ctx.redirect(qs != null && !qs.isBlank() ? destino + "?" + qs : destino);
    }
}
