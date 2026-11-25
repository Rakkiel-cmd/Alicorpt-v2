package pe.com.alicorp.bi;

import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.staticfiles.Location;
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

import java.util.Map;

/**
 * Punto de entrada principal de Alicorp BI v2 en Java 21+.
 * Levanta el servidor Javalin, inicializa el pool relacional 3FN (Supabase PostgreSQL / H2),
 * configura el enrutamiento web ordenado por secciones y delega los endpoints a cada módulo.
 */
public class AlicorpBiApplication {

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
        });

        // 3. Rutas limpias y compatibilidad con enlaces antiguos preservando ?key=... / ?q=...
        Map<String, String> aliasPaginas = Map.ofEntries(
                Map.entry("/", "/portal_institucional/index.html"),
                Map.entry("/index.html", "/portal_institucional/index.html"),
                Map.entry("/clientes.html", "/portal_institucional/clientes.html"),
                Map.entry("/inversionistas.html", "/portal_institucional/inversionistas.html"),
                Map.entry("/etica.html", "/portal_institucional/etica.html"),
                Map.entry("/sostenibilidad.html", "/portal_institucional/sostenibilidad.html"),
                Map.entry("/tienda.html", "/tienda_virtual/tienda.html"),
                Map.entry("/contacto.html", "/contacto_soporte/contacto.html"),
                Map.entry("/admin_login.html", "/acceso_seguridad/admin_login.html"),
                Map.entry("/registro.html", "/acceso_seguridad/registro.html"),
                Map.entry("/dashboard.html", "/dashboard_bi/dashboard.html")
        );
        aliasPaginas.forEach((origen, destino) -> app.get(origen, ctx -> redirigirConQuery(ctx, destino)));

        // 4. Registrar controladores REST por módulo web
        AccesoBiometriaController.registrar(app, motorBiometria, motorSeguridad, servicioEquipo);
        TiendaBuscadorController.registrar(app, servicioBuscadorIA);
        DashboardAnaliticaController.registrar(app, motorDl4j, motorSmile);
        RecomendadorController.registrar(app, motorSeguridad);
        ContactoSoporteController.registrar(app);
        ChatbotController.registrar(app, servicioBuscadorIA, motorSeguridad);

        app.start(puerto);
        System.out.println("=========================================================================");
        System.out.println(" ALICORP BI v2 (100% JAVA 21+ · ARQUITECTURA MODULAR POR SECCIONES)");
        System.out.println(" Servidor activo en: http://localhost:" + puerto);
        System.out.println("=========================================================================");
    }

    private static void redirigirConQuery(Context ctx, String destino) {
        String qs = ctx.queryString();
        ctx.redirect(qs != null && !qs.isBlank() ? destino + "?" + qs : destino);
    }
}
