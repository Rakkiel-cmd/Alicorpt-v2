package pe.com.alicorp.bi.modulos.chatbot_asistente;

import io.javalin.Javalin;
import io.javalin.http.Context;
import pe.com.alicorp.bi.modulos.acceso_biometria.ServicioSeguridadRelacional;
import pe.com.alicorp.bi.modulos.tienda_buscador_ia.ServicioBuscadorIA;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Endpoints REST de los tres chatbots (no son agentes: solo responden y consultan, no ejecutan acciones).
 *   POST /api/chatbot/cliente           → público (tienda y portal)
 *   POST /api/chatbot/contexto          → rol del usuario logueado (decide qué chatbot se muestra en el dashboard)
 *   POST /api/chatbot/analista          → analistas y superadmins activos
 *   GET  /api/chatbot/analista/pulso    → conteos y mensaje periódico para la notificación emergente
 *   POST /api/chatbot/admin             → solo superadmin activo
 * El usuario se toma de la sesión del servidor y, si no hay, del campo "usuario" (mismo criterio que el resto del proyecto).
 */
public final class ChatbotController {

    private ChatbotController() {}

    public static void registrar(Javalin app, ServicioBuscadorIA buscador, ServicioSeguridadRelacional seguridad) {
        ChatbotClienteService cliente = new ChatbotClienteService(buscador);
        ChatbotAnalistaService analista = new ChatbotAnalistaService();
        ChatbotAdminService admin = new ChatbotAdminService();

        app.post("/api/chatbot/cliente", ctx -> {
            Map<String, Object> body = cuerpo(ctx);
            ctx.json(cliente.responder(texto(body, "mensaje"), ctx.ip()));
        });

        app.post("/api/chatbot/contexto", ctx -> {
            Map<String, Object> perfil = perfilActivo(ctx, seguridad, cuerpo(ctx).get("usuario"));
            if (perfil == null) {
                ctx.status(401).json(Map.of("success", false, "message", "Sin sesión activa."));
                return;
            }
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("success", true);
            r.put("usuario", perfil.get("usuario"));
            r.put("nombre", perfil.get("nombre_completo"));
            r.put("rol", Boolean.TRUE.equals(perfil.get("es_superadmin")) ? "admin" : "analista");
            r.put("ia_habilitada", AsistenteIAOpcional.compartido().habilitada());
            r.put("chips_analista", ChatbotAnalistaService.chipsIniciales());
            r.put("chips_admin", ChatbotAdminService.chipsIniciales());
            ctx.json(r);
        });

        app.post("/api/chatbot/analista", ctx -> {
            Map<String, Object> body = cuerpo(ctx);
            Map<String, Object> perfil = perfilActivo(ctx, seguridad, body.get("usuario"));
            if (perfil == null) { sinAcceso(ctx); return; }
            ctx.json(analista.responder(texto(body, "mensaje"), String.valueOf(perfil.get("usuario")),
                    texto(body, "vista"), datos(body.get("datos"))));
        });

        app.get("/api/chatbot/analista/pulso", ctx -> {
            Map<String, Object> perfil = perfilActivo(ctx, seguridad, ctx.queryParam("usuario"));
            if (perfil == null) { sinAcceso(ctx); return; }
            ctx.json(analista.mensajePulso(String.valueOf(perfil.get("usuario"))));
        });

        app.post("/api/chatbot/admin", ctx -> {
            Map<String, Object> body = cuerpo(ctx);
            Map<String, Object> perfil = perfilActivo(ctx, seguridad, body.get("usuario"));
            if (perfil == null || !Boolean.TRUE.equals(perfil.get("es_superadmin"))) { sinAcceso(ctx); return; }
            ctx.json(admin.responder(texto(body, "mensaje"), String.valueOf(perfil.get("usuario"))));
        });
    }

    // ------------------------------------------------------------------ Utilidades
    private static Map<String, Object> perfilActivo(Context ctx, ServicioSeguridadRelacional seguridad, Object usuarioSolicitado) {
        Object deSesion = ctx.req().getSession(false) != null ? ctx.req().getSession(false).getAttribute("usuario") : null;
        String usuario = deSesion != null ? String.valueOf(deSesion)
                : (usuarioSolicitado != null ? String.valueOf(usuarioSolicitado).trim() : "");
        if (usuario.isBlank()) return null;
        Map<String, Object> perfil = seguridad.obtenerPerfilUsuario3FN(usuario);
        if (perfil == null || !"activo".equalsIgnoreCase(String.valueOf(perfil.get("estado")))) return null;
        return perfil;
    }

    private static void sinAcceso(Context ctx) {
        ctx.status(403).json(Map.of("success", false, "message", "No tienes acceso a este asistente."));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cuerpo(Context ctx) {
        try {
            Map<String, Object> m = ctx.bodyAsClass(Map.class);
            return m != null ? m : Map.of();
        } catch (RuntimeException e) {
            return Map.of();
        }
    }

    private static String texto(Map<String, Object> body, String clave) {
        Object v = body.get(clave);
        String s = v == null ? "" : String.valueOf(v).trim();
        return s.length() > 400 ? s.substring(0, 400) : s;
    }

    /** Cifras visibles en la pantalla del analista (etiqueta → valor), recortadas por seguridad. */
    private static Map<String, String> datos(Object crudo) {
        Map<String, String> r = new LinkedHashMap<>();
        if (crudo instanceof Map<?, ?> m) {
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (r.size() >= 30) break;
                String k = String.valueOf(e.getKey());
                String v = String.valueOf(e.getValue());
                r.put(k.length() > 60 ? k.substring(0, 60) : k, v.length() > 60 ? v.substring(0, 60) : v);
            }
        }
        return r;
    }
}
