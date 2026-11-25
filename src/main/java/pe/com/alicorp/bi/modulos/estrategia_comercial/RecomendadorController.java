package pe.com.alicorp.bi.modulos.estrategia_comercial;

import io.javalin.Javalin;
import io.javalin.http.Context;
import pe.com.alicorp.bi.modulos.acceso_biometria.ServicioSeguridadRelacional;

import java.util.Map;

/**
 * Controlador REST de la sección Web "Estrategia Comercial y Reabastecimiento"
 * (dashboard_bi/dashboard.html#estrategia).
 * Acceso EXCLUSIVO para Super Administradores activos en BD 3FN:
 *  - GET /api/recomendador/estrategias?tienda={nombre}&usuario={superadmin}
 *  - GET /api/recomendador/validacion?usuario={superadmin}
 */
public class RecomendadorController {

    public static void registrar(Javalin app, ServicioSeguridadRelacional motorSeguridad) {
        EstrategiaDecisionService servicio = new EstrategiaDecisionService();

        app.get("/api/recomendador/estrategias", ctx -> {
            if (!esSuperAdminAutorizado(ctx, motorSeguridad)) {
                ctx.status(403).json(Map.of(
                        "success", false,
                        "message", "Acceso restringido: exclusivo para Super Administradores."
                ));
                return;
            }
            String tienda = ctx.queryParam("tienda");
            ctx.json(servicio.obtenerEstrategiasPorTienda(tienda));
        });

        app.get("/api/recomendador/validacion", ctx -> {
            if (!esSuperAdminAutorizado(ctx, motorSeguridad)) {
                ctx.status(403).json(Map.of(
                        "success", false,
                        "message", "Acceso restringido: exclusivo para Super Administradores."
                ));
                return;
            }
            ctx.json(servicio.obtenerValidacionModelo());
        });
    }

    private static boolean esSuperAdminAutorizado(Context ctx, ServicioSeguridadRelacional motorSeguridad) {
        Object sesionUser = ctx.req().getSession(false) != null
                ? ctx.req().getSession(false).getAttribute("usuario")
                : null;
        String usuario = ctx.queryParam("usuario");
        if ((usuario == null || usuario.isBlank()) && sesionUser != null) {
            usuario = String.valueOf(sesionUser);
        }
        if (usuario == null || usuario.isBlank()) {
            return false;
        }
        return motorSeguridad.esSuperAdminActivo(usuario.trim());
    }
}
