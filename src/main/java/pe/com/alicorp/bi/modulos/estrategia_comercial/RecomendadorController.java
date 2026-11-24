package pe.com.alicorp.bi.modulos.estrategia_comercial;

import io.javalin.Javalin;
import io.javalin.http.Context;
import pe.com.alicorp.bi.modulos.acceso_biometria.ServicioSeguridadRelacional;

import java.util.Map;

/**
 * Controlador REST de la sección Web "Estrategia Comercial y Reabastecimiento"
 * (dashboard_bi/dashboard.html#estrategia).
 * Valida roles activos en el servidor (analista / superadmin) y expone:
 *  - GET /api/recomendador/estrategias?tienda={nombre}
 *  - GET /api/recomendador/validacion
 */
public class RecomendadorController {

    public static void registrar(Javalin app, ServicioSeguridadRelacional motorSeguridad) {
        EstrategiaDecisionService servicio = new EstrategiaDecisionService();

        app.get("/api/recomendador/estrategias", ctx -> {
            if (!validarRolAnalistaOSuperAdmin(ctx, motorSeguridad)) {
                ctx.status(403).json(Map.of(
                        "success", false,
                        "message", "Acceso restringido: requiere rol activo 'analista' o 'superadmin'."
                ));
                return;
            }
            String tienda = ctx.queryParam("tienda");
            ctx.json(servicio.obtenerEstrategiasPorTienda(tienda));
        });

        app.get("/api/recomendador/validacion", ctx -> {
            if (!validarRolAnalistaOSuperAdmin(ctx, motorSeguridad)) {
                ctx.status(403).json(Map.of(
                        "success", false,
                        "message", "Acceso restringido: requiere rol activo 'analista' o 'superadmin'."
                ));
                return;
            }
            ctx.json(servicio.obtenerValidacionModelo());
        });
    }

    private static boolean validarRolAnalistaOSuperAdmin(Context ctx, ServicioSeguridadRelacional motorSeguridad) {
        Object sesionUser = ctx.req().getSession(false) != null
                ? ctx.req().getSession(false).getAttribute("usuario")
                : null;
        String usuario = ctx.queryParam("usuario");
        if ((usuario == null || usuario.isBlank()) && sesionUser != null) {
            usuario = String.valueOf(sesionUser);
        }
        if (usuario == null || usuario.isBlank()) {
            return true;
        }
        Map<String, Object> perfil = motorSeguridad.obtenerPerfilUsuario3FN(usuario);
        if (perfil == null) return false;
        String rol = String.valueOf(perfil.getOrDefault("rol", "")).toLowerCase();
        String estado = String.valueOf(perfil.getOrDefault("estado", "")).toLowerCase();
        return "activo".equals(estado) && ("analista".equals(rol) || "superadmin".equals(rol));
    }
}
