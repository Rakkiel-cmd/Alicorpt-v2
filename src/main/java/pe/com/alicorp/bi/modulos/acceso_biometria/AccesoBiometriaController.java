package pe.com.alicorp.bi.modulos.acceso_biometria;

import io.javalin.Javalin;
import pe.com.alicorp.bi.config.DatabaseConfig;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;

/**
 * Controlador REST de la sección Web "Acceso, Biometría 128-D y Seguridad SuperAdmin"
 * (acceso_seguridad/admin_login.html, acceso_seguridad/registro.html, dashboard_bi/#seguridad y #administracion).
 */
@SuppressWarnings("unchecked")
public class AccesoBiometriaController {

    private static final ZoneOffset ZONA_PERU = ZoneOffset.ofHours(-5);

    public static void registrar(
            Javalin app,
            ServicioBiometriaJavaCV motorBiometria,
            ServicioSeguridadRelacional motorSeguridad,
            ServicioActividadEquipo servicioEquipo) {

        app.post("/api/seguridad/validar_key", ctx -> {
            Map<String, Object> body = ctx.bodyAsClass(Map.class);
            String key = String.valueOf(body.getOrDefault("key", "")).trim();
            boolean valido = motorSeguridad.validarClaveAcceso(key);
            if (valido) {
                ctx.json(Map.of(
                        "success", true,
                        "desbloqueado", true,
                        "message", "Llave corporativa verificada."
                ));
            } else {
                ctx.status(403).json(Map.of(
                        "success", false,
                        "desbloqueado", false,
                        "message", "Llave de acceso inválida."
                ));
            }
        });

        app.post("/api/login_password", ctx -> {
            Map<String, Object> body = ctx.bodyAsClass(Map.class);
            String usuario = String.valueOf(body.getOrDefault("usuario", "")).trim();
            String password = String.valueOf(body.getOrDefault("password", ""));

            Map<String, Object> perfil = motorSeguridad.obtenerPerfilUsuario3FN(usuario);
            if (perfil == null) {
                ctx.status(401).json(Map.of("success", false, "message", "Usuario no registrado."));
                return;
            }
            if (!"activo".equalsIgnoreCase(String.valueOf(perfil.get("estado")))) {
                ctx.status(403).json(Map.of("success", false, "message", "Cuenta suspendida por un Super Administrador."));
                return;
            }
            if (!password.equals(perfil.get("password_hash"))) {
                ctx.status(401).json(Map.of("success", false, "message", "Contraseña incorrecta."));
                return;
            }

            ctx.req().getSession(true).setAttribute("usuario", perfil.get("usuario"));
            ctx.json(Map.of(
                    "success", true,
                    "usuario", perfil.get("usuario"),
                    "nombre_completo", perfil.get("nombre_completo"),
                    "rol", perfil.get("rol"),
                    "es_superadmin", perfil.get("es_superadmin")
            ));
        });

        app.post("/api/login_facial", ctx -> {
            Map<String, Object> body = ctx.bodyAsClass(Map.class);
            String imagenB64 = String.valueOf(body.getOrDefault("image", ""));
            double[] vectorVivo = motorBiometria.extraerEmbedding128D(imagenB64);
            if (vectorVivo == null) {
                ctx.status(400).json(Map.of("success", false, "message", "No se detectó un rostro claro frente a la cámara."));
                return;
            }

            Map<String, Object> mejorAdmin = null;
            double mejorDist = Double.MAX_VALUE;
            double[] mejorVec = null;

            for (Map<String, Object> u : motorSeguridad.listarUsuariosConEstado3FN()) {
                if (!"activo".equalsIgnoreCase(String.valueOf(u.get("estado")))) continue;
                Map<String, Object> p = motorSeguridad.obtenerPerfilUsuario3FN(String.valueOf(u.get("usuario")));
                double[] vecGuardado = motorBiometria.parsearVectorJson(String.valueOf(p.get("face_encoding")));
                if (vecGuardado != null) {
                    double dist = motorBiometria.calcularDistanciaEuclidiana(vectorVivo, vecGuardado);
                    if (dist < mejorDist) {
                        mejorDist = dist;
                        mejorAdmin = p;
                        mejorVec = vecGuardado;
                    }
                }
            }

            if (mejorAdmin != null && mejorVec != null && mejorDist <= ServicioBiometriaJavaCV.UMBRAL_DISTANCIA) {
                ctx.req().getSession(true).setAttribute("usuario", mejorAdmin.get("usuario"));
                double porcentaje = Math.max(70.0, Math.min(99.9, Math.round((1.0 - mejorDist / 1.5) * 1000.0) / 10.0));
                List<Double> vecLista = new ArrayList<>();
                for (double v : mejorVec) vecLista.add(v);
                Map<String, Object> resp = new LinkedHashMap<>();
                resp.put("success", true);
                resp.put("message", "Identidad biométrica verificada: " + mejorAdmin.get("nombre_completo"));
                resp.put("usuario", mejorAdmin.get("usuario"));
                resp.put("nombre_completo", mejorAdmin.get("nombre_completo"));
                resp.put("rol", mejorAdmin.get("rol"));
                resp.put("es_superadmin", mejorAdmin.get("es_superadmin"));
                resp.put("face_encoding", vecLista);
                resp.put("distancia", Math.round(mejorDist * 10000.0) / 10000.0);
                resp.put("porcentaje", porcentaje);
                resp.put("redirect", "/dashboard_bi/dashboard.html");
                ctx.json(resp);
                return;
            }
            ctx.status(401).json(Map.of("success", false, "message", "Rostro no coincide con ningún administrador autorizado. Si aún no registraste tu rostro, usa '+ Nuevo Registro'."));
        });

        app.post("/api/verificar_rostro", ctx -> {
            Map<String, Object> body = ctx.bodyAsClass(Map.class);
            String imagenB64 = String.valueOf(body.getOrDefault("image", ""));
            double[] vector = motorBiometria.extraerEmbedding128D(imagenB64);
            if (vector == null) {
                ctx.status(400).json(Map.of("success", false, "message", "No se detectó un rostro válido en la captura."));
                return;
            }
            ctx.json(Map.of(
                    "success", true,
                    "message", "Rostro validado y vectorizado en 128-D correctamente (Norma L2: " + motorBiometria.calcularNormaL2(vector) + ")."
            ));
        });

        app.post("/api/registrar_admin_web", ctx -> {
            Map<String, Object> body = ctx.bodyAsClass(Map.class);
            String nombre = String.valueOf(body.getOrDefault("nombre_completo", "")).trim();
            String usuario = String.valueOf(body.getOrDefault("usuario", "")).trim();
            String password = String.valueOf(body.getOrDefault("password", ""));
            String imagenB64 = String.valueOf(body.getOrDefault("image", ""));

            double[] vector = motorBiometria.extraerEmbedding128D(imagenB64);
            if (vector == null) {
                ctx.status(400).json(Map.of("success", false, "message", "Captura facial inválida. Toma la foto nuevamente."));
                return;
            }
            double l2 = motorBiometria.calcularNormaL2(vector);
            String error = motorSeguridad.registrarAdministrador3FN(usuario, nombre, password, vector, l2);
            if (error != null) {
                ctx.status(400).json(Map.of("success", false, "message", error));
                return;
            }
            ctx.json(Map.of(
                    "success", true,
                    "usuario", usuario,
                    "nombre_completo", nombre,
                    "l2_norm", l2,
                    "message", "Administrador registrado con biometría 128-D en BD 3FN."
            ));
        });

        app.post("/api/logout", ctx -> {
            if (ctx.req().getSession(false) != null) {
                ctx.req().getSession(false).invalidate();
            }
            ctx.json(Map.of("success", true));
        });

        app.get("/api/usuario_perfil", ctx -> {
            String usuario = ctx.queryParam("usuario");
            Map<String, Object> perfil = motorSeguridad.obtenerPerfilUsuario3FN(usuario);
            if (perfil == null) {
                ctx.status(404).json(Map.of("success", false, "message", "Usuario no encontrado."));
                return;
            }
            double[] vec = motorBiometria.parsearVectorJson(String.valueOf(perfil.get("face_encoding")));
            List<Double> vectorLista = new ArrayList<>();
            if (vec != null) {
                for (double d : vec) vectorLista.add(d);
            }
            ctx.json(Map.of(
                    "success", true,
                    "usuario", perfil.get("usuario"),
                    "nombre_completo", perfil.get("nombre_completo"),
                    "rol", perfil.get("rol"),
                    "estado", perfil.get("estado"),
                    "es_superadmin", perfil.get("es_superadmin"),
                    "vector_128d", vectorLista,
                    "dimensiones", vectorLista.size()
            ));
        });

        // SuperAdmin, Usuarios, Roles, Auditoría y Actividad
        app.get("/api/superadmin/usuarios", ctx -> {
            String solicitante = ctx.queryParam("usuario");
            if (!motorSeguridad.esSuperAdminActivo(solicitante)) {
                ctx.status(403).json(Map.of("success", false, "message", "Exclusivo para Super Administradores."));
                return;
            }
            List<Map<String, Object>> usuarios = motorSeguridad.listarUsuariosConEstado3FN();
            servicioEquipo.enriquecerUsuarios(usuarios);
            long conectados = usuarios.stream().filter(u -> Boolean.TRUE.equals(u.get("conectado"))).count();
            ctx.json(Map.of("success", true, "usuarios", usuarios, "total", usuarios.size(), "conectados", conectados));
        });

        app.get("/api/superadmin/vector", ctx -> {
            String solicitante = ctx.queryParam("usuario");
            String objetivo = ctx.queryParam("objetivo");
            if (objetivo == null || objetivo.isBlank()) objetivo = solicitante;

            Map<String, Object> p = motorSeguridad.obtenerPerfilUsuario3FN(objetivo);
            if (p == null) {
                ctx.status(404).json(Map.of("success", false, "message", "Usuario no encontrado."));
                return;
            }
            double[] vec = motorBiometria.parsearVectorJson(String.valueOf(p.get("face_encoding")));
            List<Double> lista = new ArrayList<>();
            if (vec != null) {
                for (double d : vec) lista.add(d);
            }
            ctx.json(Map.of(
                    "success", true,
                    "usuario", p.get("usuario"),
                    "nombre_completo", p.get("nombre_completo"),
                    "dimensiones", lista.size(),
                    "vector", lista,
                    "l2_norm", motorBiometria.calcularNormaL2(vec)
            ));
        });

        app.get("/api/superadmin/auditoria", ctx -> {
            String solicitante = ctx.queryParam("usuario");
            if (!motorSeguridad.esSuperAdminActivo(solicitante)) {
                ctx.status(403).json(Map.of("success", false, "message", "Exclusivo para Super Administradores."));
                return;
            }
            ctx.json(Map.of("success", true, "registros", motorSeguridad.listarAuditoria3FN()));
        });

        app.get("/api/superadmin/auditoria_filtrada", ctx -> {
            if (!motorSeguridad.esSuperAdminActivo(ctx.queryParam("usuario"))) {
                ctx.status(403).json(Map.of("success", false, "message", "Exclusivo para Super Administradores."));
                return;
            }
            try {
                ctx.json(servicioEquipo.auditoriaFiltrada(ctx.queryParam("desde"), ctx.queryParam("hasta"),
                        ctx.queryParam("accion"), ctx.queryParam("colaborador"), ctx.queryParam("q"),
                        ctx.queryParam("pagina"), ctx.queryParam("por_pagina")));
            } catch (IllegalArgumentException e) {
                ctx.status(400).json(Map.of("success", false, "message", e.getMessage()));
            } catch (Exception e) {
                System.err.println("[!] Error en /api/superadmin/auditoria_filtrada: " + e.getMessage());
                ctx.status(500).json(Map.of("success", false, "message", "No se pudo consultar la auditoría."));
            }
        });

        app.get("/api/superadmin/equipo", ctx -> {
            if (!motorSeguridad.esSuperAdminActivo(ctx.queryParam("usuario"))) {
                ctx.status(403).json(Map.of("success", false, "message", "Exclusivo para Super Administradores."));
                return;
            }
            try {
                ctx.json(servicioEquipo.equipo(ctx.queryParam("periodo"), ctx.queryParam("ref")));
            } catch (IllegalArgumentException e) {
                ctx.status(400).json(Map.of("success", false, "message", e.getMessage()));
            } catch (Exception e) {
                System.err.println("[!] Error en /api/superadmin/equipo: " + e.getMessage());
                ctx.status(500).json(Map.of("success", false, "message", "No se pudo calcular la actividad del equipo."));
            }
        });

        app.get("/api/seguridad/info_clave", ctx -> {
            String solicitante = ctx.queryParam("usuario");
            if (!motorSeguridad.esSuperAdminActivo(solicitante)) {
                ctx.status(403).json(Map.of("success", false, "es_superadmin", false, "message", "No autorizado."));
                return;
            }
            ctx.json(Map.of(
                    "success", true,
                    "es_superadmin", true,
                    "clave_actual", motorSeguridad.obtenerClaveAccesoActual()
            ));
        });

        app.get("/api/actividad/hoy", ctx -> {
            String usuario = ctx.queryParam("usuario");
            String fechaPeru = OffsetDateTime.now(ZONA_PERU).toLocalDate().toString();
            int segs = usuario != null ? motorSeguridad.obtenerTiempoHoy3FN(usuario, fechaPeru) : 0;
            ctx.json(Map.of("success", true, "fecha", fechaPeru, "tiempo_segundos", segs));
        });

        app.post("/api/actividad/latido", ctx -> {
            Map<String, Object> body = ctx.bodyAsClass(Map.class);
            String usuario = String.valueOf(body.getOrDefault("usuario", "")).trim();
            String fechaCliente = String.valueOf(body.getOrDefault("fecha", "")).trim();
            String fechaHoyPeru = OffsetDateTime.now(ZONA_PERU).toLocalDate().toString();
            int delta = ((Number) body.getOrDefault("segundos_delta", 15)).intValue();
            int totalCli = ((Number) body.getOrDefault("tiempo_total_hoy", 0)).intValue();

            boolean cambioDia = !fechaCliente.isEmpty() && !fechaCliente.equals(fechaHoyPeru);
            String fechaGuardar = cambioDia ? fechaHoyPeru : (fechaCliente.isEmpty() ? fechaHoyPeru : fechaCliente);
            int deltaGuardar = cambioDia ? 0 : delta;
            int totalBD = motorSeguridad.registrarLatido3FN(usuario, fechaGuardar, deltaGuardar, cambioDia ? 0 : totalCli);

            ctx.json(Map.of(
                    "success", true,
                    "fecha", fechaHoyPeru,
                    "tiempo_segundos", totalBD,
                    "reset_medianoche", cambioDia
            ));
        });

        app.get("/api/actividad/historial", ctx -> {
            String usuario = ctx.queryParam("usuario");
            ctx.json(Map.of("success", true, "historial", motorSeguridad.obtenerHistorialActividad3FN(usuario)));
        });

        app.post("/api/superadmin/suspender", ctx -> {
            Map<String, Object> body = ctx.bodyAsClass(Map.class);
            String admin = String.valueOf(body.getOrDefault("usuario", body.getOrDefault("admin_usuario", body.getOrDefault("solicitante", ""))));
            String objetivo = String.valueOf(body.getOrDefault("objetivo", body.getOrDefault("usuario_objetivo", "")));
            Object motivoRaw = body.get("motivo");
            String motivo = motivoRaw == null ? "" : String.valueOf(motivoRaw).trim();
            boolean ok = motorSeguridad.cambiarEstadoUsuario3FN(admin, objetivo, 2, "SUSPENSION", motivo);
            ctx.json(Map.of("success", ok, "message", ok ? "Usuario suspendido correctamente." : "No autorizado."));
        });

        app.post("/api/superadmin/reactivar", ctx -> {
            Map<String, Object> body = ctx.bodyAsClass(Map.class);
            String admin = String.valueOf(body.getOrDefault("usuario", body.getOrDefault("admin_usuario", body.getOrDefault("solicitante", ""))));
            String objetivo = String.valueOf(body.getOrDefault("objetivo", body.getOrDefault("usuario_objetivo", "")));
            boolean ok = motorSeguridad.cambiarEstadoUsuario3FN(admin, objetivo, 1, "REACTIVACION");
            ctx.json(Map.of("success", ok, "message", ok ? "Usuario reactivado correctamente." : "No autorizado."));
        });

        app.post("/api/superadmin/ascender_rol", ctx -> {
            Map<String, Object> body = ctx.bodyAsClass(Map.class);
            String admin = String.valueOf(body.getOrDefault("usuario", ""));
            String objetivo = String.valueOf(body.getOrDefault("objetivo", ""));
            String nuevoRol = String.valueOf(body.getOrDefault("nuevo_rol", "superadmin")).toLowerCase();

            if (!motorSeguridad.esSuperAdminActivo(admin)) {
                ctx.status(403).json(Map.of("success", false, "message", "No autorizado."));
                return;
            }
            String sql = """
                UPDATE ADMINISTRADORES
                SET id_rol = (SELECT id_rol FROM ROLES_SEGURIDAD WHERE codigo_rol = ?)
                WHERE UPPER(usuario) = UPPER(?)
                """;
            try (Connection conn = DatabaseConfig.getDataSource().getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, nuevoRol);
                ps.setString(2, objetivo);
                ps.executeUpdate();
            } catch (SQLException e) {
                ctx.status(500).json(Map.of("success", false, "message", "Error al actualizar rol: " + e.getMessage()));
                return;
            }
            motorSeguridad.registrarAuditoria3FN(admin, objetivo, "CAMBIO_ROL_3FN", "Rol actualizado a " + nuevoRol);
            ctx.json(Map.of("success", true, "message", "Rol actualizado en tabla ADMINISTRADORES (FK ROLES_SEGURIDAD)."));
        });

        app.post("/api/superadmin/degradar_rol", ctx -> {
            Map<String, Object> body = ctx.bodyAsClass(Map.class);
            String admin = String.valueOf(body.getOrDefault("usuario", ""));
            String objetivo = String.valueOf(body.getOrDefault("objetivo", ""));
            if (!motorSeguridad.esSuperAdminActivo(admin)) {
                ctx.status(403).json(Map.of("success", false, "message", "No autorizado."));
                return;
            }
            try (Connection conn = DatabaseConfig.getDataSource().getConnection();
                 PreparedStatement ps = conn.prepareStatement("UPDATE ADMINISTRADORES SET id_rol = 1 WHERE LOWER(usuario) = LOWER(?)")) {
                ps.setString(1, objetivo);
                ps.executeUpdate();
            } catch (SQLException e) {
                ctx.status(500).json(Map.of("success", false, "message", "Error al actualizar rol: " + e.getMessage()));
                return;
            }
            motorSeguridad.registrarAuditoria3FN(admin, objetivo, "DEGRADACION_ROL", "Rol cambiado a analista");
            ctx.json(Map.of("success", true, "message", "Rol cambiado a Analista."));
        });

        app.post("/api/superadmin/actualizar_email", ctx -> {
            Map<String, Object> body = ctx.bodyAsClass(Map.class);
            String admin = String.valueOf(body.getOrDefault("usuario", ""));
            String objetivo = String.valueOf(body.getOrDefault("objetivo", ""));
            String email = String.valueOf(body.getOrDefault("email", "")).trim();
            if (!motorSeguridad.esSuperAdminActivo(admin)) {
                ctx.status(403).json(Map.of("success", false, "message", "No autorizado."));
                return;
            }
            try (Connection conn = DatabaseConfig.getDataSource().getConnection();
                 PreparedStatement ps = conn.prepareStatement("UPDATE ADMINISTRADORES SET email = ? WHERE LOWER(usuario) = LOWER(?)")) {
                ps.setString(1, email);
                ps.setString(2, objetivo);
                ps.executeUpdate();
            } catch (SQLException e) {
                ctx.status(500).json(Map.of("success", false, "message", "Error al actualizar correo: " + e.getMessage()));
                return;
            }
            motorSeguridad.registrarAuditoria3FN(admin, objetivo, "ACTUALIZACION_EMAIL", "Correo actualizado a " + email);
            ctx.json(Map.of("success", true, "message", "Correo actualizado."));
        });

        app.get("/api/seguridad/generar_clave_random", ctx -> {
            String nueva = String.format("%06d", 100000 + new Random().nextInt(900000));
            ctx.json(Map.of("success", true, "clave_sugerida", nueva, "clave", nueva));
        });

        app.get("/api/superadmin/clave/solicitudes", ctx -> {
            String admin = ctx.queryParam("usuario");
            if (!motorSeguridad.esSuperAdminActivo(admin)) {
                ctx.status(403).json(Map.of("success", false, "message", "No autorizado."));
                return;
            }
            ctx.json(Map.of("success", true, "solicitudes", motorSeguridad.listarSolicitudesClave3FN()));
        });

        app.post("/api/superadmin/clave/solicitar", ctx -> {
            Map<String, Object> body = ctx.bodyAsClass(Map.class);
            String admin = String.valueOf(body.getOrDefault("usuario", "")).trim();
            String pass = String.valueOf(body.getOrDefault("password", ""));
            String clave = String.valueOf(body.getOrDefault("clave_propuesta", body.getOrDefault("nueva_clave", ""))).trim();

            Map<String, Object> perfil = motorSeguridad.obtenerPerfilUsuario3FN(admin);
            if (perfil == null || !Boolean.TRUE.equals(perfil.get("es_superadmin"))) {
                ctx.status(403).json(Map.of("success", false, "message", "No autorizado."));
                return;
            }
            if (!pass.isEmpty() && !pass.equals(perfil.get("password_hash"))) {
                ctx.status(401).json(Map.of("success", false, "message", "Contraseña de Super Admin incorrecta."));
                return;
            }
            boolean ok = motorSeguridad.crearSolicitudClave3FN(admin, clave);
            ctx.json(Map.of("success", ok, "message", ok ? "Solicitud creada. Otro Super Admin debe aprobarla." : "Error al crear solicitud."));
        });

        app.post("/api/superadmin/clave/aprobar", ctx -> {
            Map<String, Object> body = ctx.bodyAsClass(Map.class);
            int id = Integer.parseInt(String.valueOf(body.getOrDefault("id", "0")).trim());
            String admin = String.valueOf(body.getOrDefault("usuario", "")).trim();
            String pass = String.valueOf(body.getOrDefault("password", ""));
            Map<String, Object> perfil = motorSeguridad.obtenerPerfilUsuario3FN(admin);
            if (perfil != null && !pass.isEmpty() && !pass.equals(perfil.get("password_hash"))) {
                ctx.status(401).json(Map.of("success", false, "message", "Contraseña de confirmación incorrecta."));
                return;
            }
            boolean aprobar = !"rechazar".equalsIgnoreCase(String.valueOf(body.getOrDefault("accion", "aprobar")));
            String err = motorSeguridad.resolverSolicitudClave3FN(id, admin, aprobar);
            if (err != null) {
                ctx.status(400).json(Map.of("success", false, "message", err));
                return;
            }
            ctx.json(Map.of("success", true, "message", "Solicitud procesada."));
        });

        app.post("/api/superadmin/clave/rechazar", ctx -> {
            Map<String, Object> body = ctx.bodyAsClass(Map.class);
            int id = Integer.parseInt(String.valueOf(body.getOrDefault("id", "0")).trim());
            String admin = String.valueOf(body.getOrDefault("usuario", "")).trim();
            String err = motorSeguridad.resolverSolicitudClave3FN(id, admin, false);
            if (err != null) {
                ctx.status(400).json(Map.of("success", false, "message", err));
                return;
            }
            ctx.json(Map.of("success", true, "message", "Solicitud rechazada."));
        });
    }
}
