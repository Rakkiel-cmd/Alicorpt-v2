package pe.com.alicorp.bi.modulos.acceso_biometria;

import io.javalin.Javalin;
import pe.com.alicorp.bi.AlicorpBiApplication;
import pe.com.alicorp.bi.config.DatabaseConfig;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;

/**
 * Controlador REST de la sección Web "Acceso, Biometría 128-D y Seguridad SuperAdmin"
 * (acceso_seguridad/admin_login.html, dashboard_bi/#perfil, #seguridad y #administracion).
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
                AlicorpBiApplication.habilitarKeyEnSesion(ctx);
                AlicorpBiApplication.aplicarNoCache(ctx);
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
            if (!AlicorpBiApplication.tieneKeyHabilitada(ctx)
                    && !AlicorpBiApplication.tieneSesionAutenticada(ctx, motorSeguridad)) {
                ctx.status(403).json(Map.of("success", false, "message", "Debes validar la llave de acceso corporativa primero."));
                return;
            }
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
            if (!ServicioSeguridadRelacional.verificarPassword(password, perfil.get("password_hash"))) {
                ctx.status(401).json(Map.of("success", false, "message", "Contraseña incorrecta."));
                return;
            }

            // Evaluar estrictamente en 3FN si el trabajador posee un vector numérico válido de 128 dimensiones
            double[] vecGuardado = ServicioBiometriaJavaCV.parsearVector128DEstricto(perfil.get("face_encoding"));
            boolean requiereFacial = vecGuardado != null;
            var ses = ctx.req().getSession(true);
            ses.setAttribute(AlicorpBiApplication.ATTR_BIOMETRIA_REGISTRADA, Boolean.FALSE);
            if (requiereFacial) {
                ses.removeAttribute(AlicorpBiApplication.ATTR_USUARIO_AUTENTICADO);
                ses.setAttribute(AlicorpBiApplication.ATTR_USUARIO_PENDIENTE_FACIAL, perfil.get("usuario"));
            } else {
                // Nuevo trabajador sin vector válido 128-D: sesión autenticada pero con biometria_registrada = false
                ses.removeAttribute(AlicorpBiApplication.ATTR_USUARIO_PENDIENTE_FACIAL);
                ses.setAttribute(AlicorpBiApplication.ATTR_USUARIO_AUTENTICADO, perfil.get("usuario"));
            }
            AlicorpBiApplication.aplicarNoCache(ctx);
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("success", true);
            resp.put("requiere_facial", requiereFacial);
            resp.put("biometria_registrada", false);
            resp.put("biometria_pendiente", !requiereFacial);
            resp.put("usuario", perfil.get("usuario"));
            resp.put("nombre_completo", perfil.get("nombre_completo"));
            resp.put("rol", perfil.get("rol"));
            resp.put("es_superadmin", perfil.get("es_superadmin"));
            if (!requiereFacial) {
                resp.put("redirect", "/dashboard_bi/dashboard.html#perfil");
            }
            ctx.json(resp);
        });

        app.post("/api/login_facial", ctx -> {
            if (!AlicorpBiApplication.tieneKeyHabilitada(ctx)
                    && !AlicorpBiApplication.tieneSesionAutenticada(ctx, motorSeguridad)) {
                ctx.status(403).json(Map.of("success", false, "message", "Debes validar la llave de acceso corporativa primero."));
                return;
            }
            Map<String, Object> body = ctx.bodyAsClass(Map.class);
            String imagenB64 = String.valueOf(body.getOrDefault("image", ""));
            double[] vectorVivo = motorBiometria.extraerEmbedding128D(imagenB64);
            if (vectorVivo == null) {
                ctx.status(400).json(Map.of("success", false, "message", "No se detectó un rostro claro frente a la cámara."));
                return;
            }

            var sesActual = ctx.req().getSession(false);
            Object pendienteObj = sesActual != null ? sesActual.getAttribute(AlicorpBiApplication.ATTR_USUARIO_PENDIENTE_FACIAL) : null;
            String usuarioPendiente = pendienteObj != null ? String.valueOf(pendienteObj).trim() : "";

            Map<String, Object> mejorAdmin = null;
            double[] mejorVec = null;

            if (!usuarioPendiente.isEmpty()) {
                // Paso 2 del login tradicional: validar estrictamente contra el usuario cuyas credenciales se verificaron
                Map<String, Object> p = motorSeguridad.obtenerPerfilUsuario3FN(usuarioPendiente);
                if (p != null && "activo".equalsIgnoreCase(String.valueOf(p.get("estado")))) {
                    Object rawF = p.get("face_encoding");
                    double[] vecGuardado = rawF != null ? motorBiometria.parsearVectorJson(String.valueOf(rawF)) : null;
                    if (vecGuardado != null && motorBiometria.esMatchBiometrico(vectorVivo, vecGuardado)) {
                        mejorAdmin = p;
                        mejorVec = vecGuardado;
                    }
                }
            } else {
                // Acceso directo biométrico (1:N entre usuarios activos con face_encoding NOT NULL)
                double mejorScore = -Double.MAX_VALUE;
                for (Map<String, Object> u : motorSeguridad.listarUsuariosConEstado3FN()) {
                    if (!"activo".equalsIgnoreCase(String.valueOf(u.get("estado")))) continue;
                    Map<String, Object> p = motorSeguridad.obtenerPerfilUsuario3FN(String.valueOf(u.get("usuario")));
                    Object rawF = p != null ? p.get("face_encoding") : null;
                    double[] vecGuardado = rawF != null ? motorBiometria.parsearVectorJson(String.valueOf(rawF)) : null;
                    if (vecGuardado != null) {
                        double score = motorBiometria.puntajeSimilitud(vectorVivo, vecGuardado);
                        if (score > mejorScore) {
                            mejorScore = score;
                            mejorAdmin = p;
                            mejorVec = vecGuardado;
                        }
                    }
                }
            }

            if (mejorAdmin != null && mejorVec != null && motorBiometria.esMatchBiometrico(vectorVivo, mejorVec)) {
                var ses = ctx.req().getSession(true);
                ses.setAttribute(AlicorpBiApplication.ATTR_USUARIO_AUTENTICADO, mejorAdmin.get("usuario"));
                ses.setAttribute(AlicorpBiApplication.ATTR_BIOMETRIA_REGISTRADA, Boolean.TRUE);
                ses.removeAttribute(AlicorpBiApplication.ATTR_USUARIO_PENDIENTE_FACIAL);
                AlicorpBiApplication.aplicarNoCache(ctx);
                double mejorDist = motorBiometria.calcularDistanciaEuclidiana(vectorVivo, mejorVec);
                double porcentaje = motorBiometria.calcularPorcentajeConfianza(vectorVivo, mejorVec);
                List<Double> vecLista = new ArrayList<>();
                for (double v : mejorVec) vecLista.add(v);
                Map<String, Object> resp = new LinkedHashMap<>();
                resp.put("success", true);
                resp.put("message", "Identidad biométrica verificada: " + mejorAdmin.get("nombre_completo"));
                resp.put("usuario", mejorAdmin.get("usuario"));
                resp.put("nombre_completo", mejorAdmin.get("nombre_completo"));
                resp.put("rol", mejorAdmin.get("rol"));
                resp.put("es_superadmin", mejorAdmin.get("es_superadmin"));
                resp.put("biometria_registrada", true);
                resp.put("biometria_pendiente", false);
                resp.put("face_encoding", vecLista);
                resp.put("distancia", Math.round(mejorDist * 10000.0) / 10000.0);
                resp.put("similitud_coseno", Math.round(motorBiometria.calcularSimilitudCoseno(vectorVivo, mejorVec) * 10000.0) / 10000.0);
                resp.put("porcentaje", porcentaje);
                resp.put("motor_neural", motorBiometria.isMotorNeuralActivo() ? "YuNet + SFace 128-D (ONNX)" : "Spatial ROI");
                resp.put("redirect", "/dashboard_bi/dashboard.html");
                ctx.json(resp);
                return;
            }
            String msgError = !usuarioPendiente.isEmpty()
                    ? "El rostro capturado no coincide con la biometría registrada de @" + usuarioPendiente + "."
                    : "Rostro no coincide con ningún colaborador con biometría activa. Si es tu primer ingreso, accede con tu usuario y contraseña.";
            ctx.status(401).json(Map.of("success", false, "message", msgError));
        });

        app.post("/api/logout", ctx -> {
            if (ctx.req().getSession(false) != null) {
                ctx.req().getSession(false).invalidate();
            }
            ctx.removeCookie("JSESSIONID", "/");
            AlicorpBiApplication.aplicarNoCache(ctx);
            ctx.json(Map.of("success", true, "redirect", "/portal_institucional/index.html"));
        });

        app.get("/api/logout", ctx -> {
            if (ctx.req().getSession(false) != null) {
                ctx.req().getSession(false).invalidate();
            }
            ctx.removeCookie("JSESSIONID", "/");
            AlicorpBiApplication.aplicarNoCache(ctx);
            ctx.redirect("/portal_institucional/index.html");
        });

        app.get("/api/usuario_perfil", ctx -> {
            var ses = ctx.req().getSession(false);
            Object sesUser = ses != null ? ses.getAttribute("usuario") : null;
            String usuarioSesion = sesUser != null ? String.valueOf(sesUser).trim() : "";
            String usuarioParam = ctx.queryParam("usuario") != null ? ctx.queryParam("usuario").trim() : "";
            String usuario = !usuarioSesion.isEmpty() ? usuarioSesion : usuarioParam;
            boolean bioActivaSesion = !usuarioSesion.isEmpty()
                    && ses != null
                    && Boolean.TRUE.equals(ses.getAttribute(AlicorpBiApplication.ATTR_BIOMETRIA_REGISTRADA))
                    && motorSeguridad.tieneBiometriaRegistrada3FN(usuarioSesion, motorBiometria);
            if (bioActivaSesion && !usuarioParam.isEmpty() && !usuarioParam.equalsIgnoreCase(usuarioSesion)
                    && motorSeguridad.esSuperAdminActivo(usuarioSesion)) {
                usuario = usuarioParam;
            }
            Map<String, Object> perfil = motorSeguridad.obtenerPerfilUsuario3FN(usuario);
            if (perfil == null) {
                ctx.status(404).json(Map.of("success", false, "message", "Usuario no encontrado."));
                return;
            }
            double[] vec = ServicioBiometriaJavaCV.parsearVector128DEstricto(perfil.get("face_encoding"));
            List<Double> vectorLista = new ArrayList<>();
            if (vec != null) {
                for (double d : vec) vectorLista.add(d);
            }
            boolean tieneBio = (vec != null && vectorLista.size() == 128);
            if (ses != null && usuario.equalsIgnoreCase(usuarioSesion)) {
                ses.setAttribute(AlicorpBiApplication.ATTR_BIOMETRIA_REGISTRADA, tieneBio);
            }
            AlicorpBiApplication.aplicarNoCache(ctx);
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("success", true);
            resp.put("usuario", perfil.get("usuario"));
            resp.put("nombre_completo", perfil.get("nombre_completo"));
            resp.put("rol", perfil.get("rol"));
            resp.put("estado", perfil.get("estado"));
            resp.put("es_superadmin", perfil.get("es_superadmin"));
            resp.put("biometria_registrada", tieneBio);
            resp.put("tiene_biometria", tieneBio);
            resp.put("biometria_pendiente", !tieneBio);
            resp.put("vector_128d", vectorLista);
            resp.put("dimensiones", vectorLista.size());
            ctx.json(resp);
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
            if (solicitante == null || solicitante.isBlank()) {
                ctx.status(403).json(Map.of("success", false, "message", "Acceso denegado: sesión requerida."));
                return;
            }
            if (objetivo == null || objetivo.isBlank()) objetivo = solicitante;
            boolean esPropio = objetivo.trim().equalsIgnoreCase(solicitante.trim());
            if (!esPropio && !motorSeguridad.esSuperAdminActivo(solicitante)) {
                ctx.status(403).json(Map.of("success", false, "message", "Exclusivo para Super Administradores."));
                return;
            }

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
            try {
                Object sesUser = ctx.req().getSession(false) != null ? ctx.req().getSession(false).getAttribute("usuario") : null;
                String usuario = sesUser != null ? String.valueOf(sesUser).trim() : "";
                if (usuario.isEmpty()) {
                    ctx.status(401).json(Map.of("success", false, "message", "Sesión no autenticada."));
                    return;
                }
                String fechaPeru = OffsetDateTime.now(ZONA_PERU).toLocalDate().toString();
                int segs = motorSeguridad.obtenerTiempoHoy3FN(usuario, fechaPeru);
                ctx.json(Map.of("success", true, "usuario", usuario, "fecha", fechaPeru, "tiempo_segundos", segs));
            } catch (Exception e) {
                ctx.status(500).json(Map.of("success", false, "message", "Error consultando actividad del día."));
            }
        });

        app.post("/api/actividad/latido", ctx -> {
            try {
                Object sesUser = ctx.req().getSession(false) != null ? ctx.req().getSession(false).getAttribute("usuario") : null;
                String usuario = sesUser != null ? String.valueOf(sesUser).trim() : "";
                if (usuario.isEmpty()) {
                    ctx.status(401).json(Map.of("success", false, "message", "Sesión no autenticada."));
                    return;
                }
                Map<String, Object> body = ctx.bodyAsClass(Map.class);
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
                        "usuario", usuario,
                        "fecha", fechaHoyPeru,
                        "tiempo_segundos", totalBD,
                        "reset_medianoche", cambioDia
                ));
            } catch (Exception e) {
                ctx.status(500).json(Map.of("success", false, "message", "Error registrando latido de sesión."));
            }
        });

        app.get("/api/actividad/historial", ctx -> {
            try {
                Object sesUser = ctx.req().getSession(false) != null ? ctx.req().getSession(false).getAttribute("usuario") : null;
                String usuarioSesion = sesUser != null ? String.valueOf(sesUser).trim() : "";
                if (usuarioSesion.isEmpty()) {
                    ctx.status(401).json(Map.of("success", false, "message", "Sesión no autenticada."));
                    return;
                }
                String usuarioObj = ctx.queryParam("usuario") != null ? ctx.queryParam("usuario").trim() : "";
                String usuario = usuarioObj.isEmpty() ? usuarioSesion : usuarioObj;
                if (!usuario.equalsIgnoreCase(usuarioSesion) && !motorSeguridad.esSuperAdminActivo(usuarioSesion)) {
                    ctx.status(403).json(Map.of("success", false, "message", "No autorizado para consultar el historial de otro usuario."));
                    return;
                }
                String fechaHoyPeru = OffsetDateTime.now(ZONA_PERU).toLocalDate().toString();
                ctx.json(Map.of(
                        "success", true,
                        "usuario", usuario,
                        "fecha_hoy", fechaHoyPeru,
                        "historial", motorSeguridad.obtenerHistorialActividad3FN(usuario)
                ));
            } catch (Exception e) {
                ctx.status(500).json(Map.of("success", false, "message", "Error consultando historial en base de datos."));
            }
        });

        app.post("/api/superadmin/suspender", ctx -> {
            Map<String, Object> body = ctx.bodyAsClass(Map.class);
            String admin = String.valueOf(body.getOrDefault("usuario", body.getOrDefault("admin_usuario", body.getOrDefault("solicitante", "")))).trim();
            if (!motorSeguridad.esSuperAdminActivo(admin)) {
                ctx.status(403).json(Map.of("success", false, "message", "Exclusivo para Super Administradores."));
                return;
            }
            String objetivo = String.valueOf(body.getOrDefault("objetivo", body.getOrDefault("usuario_objetivo", "")));
            Object motivoRaw = body.get("motivo");
            String motivo = motivoRaw == null ? "" : String.valueOf(motivoRaw).trim();
            boolean ok = motorSeguridad.cambiarEstadoUsuario3FN(admin, objetivo, 2, "SUSPENSION", motivo);
            ctx.json(Map.of("success", ok, "message", ok ? "Usuario suspendido correctamente." : "No se pudo suspender el usuario."));
        });

        app.post("/api/superadmin/reactivar", ctx -> {
            Map<String, Object> body = ctx.bodyAsClass(Map.class);
            String admin = String.valueOf(body.getOrDefault("usuario", body.getOrDefault("admin_usuario", body.getOrDefault("solicitante", "")))).trim();
            if (!motorSeguridad.esSuperAdminActivo(admin)) {
                ctx.status(403).json(Map.of("success", false, "message", "Exclusivo para Super Administradores."));
                return;
            }
            String objetivo = String.valueOf(body.getOrDefault("objetivo", body.getOrDefault("usuario_objetivo", "")));
            boolean ok = motorSeguridad.cambiarEstadoUsuario3FN(admin, objetivo, 1, "REACTIVACION");
            ctx.json(Map.of("success", ok, "message", ok ? "Usuario reactivado correctamente." : "No se pudo reactivar el usuario."));
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
                 PreparedStatement ps = conn.prepareStatement(
                         "UPDATE ADMINISTRADORES SET id_rol = (SELECT id_rol FROM ROLES_SEGURIDAD WHERE codigo_rol = 'analista') WHERE UPPER(usuario) = UPPER(?)")) {
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
            if (!pass.isEmpty() && !ServicioSeguridadRelacional.verificarPassword(pass, perfil.get("password_hash"))) {
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
            if (!motorSeguridad.esSuperAdminActivo(admin)) {
                ctx.status(403).json(Map.of("success", false, "message", "Exclusivo para Super Administradores."));
                return;
            }
            String pass = String.valueOf(body.getOrDefault("password", ""));
            Map<String, Object> perfil = motorSeguridad.obtenerPerfilUsuario3FN(admin);
            if (perfil != null && !pass.isEmpty() && !ServicioSeguridadRelacional.verificarPassword(pass, perfil.get("password_hash"))) {
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
            if (!motorSeguridad.esSuperAdminActivo(admin)) {
                ctx.status(403).json(Map.of("success", false, "message", "Exclusivo para Super Administradores."));
                return;
            }
            String err = motorSeguridad.resolverSolicitudClave3FN(id, admin, false);
            if (err != null) {
                ctx.status(400).json(Map.of("success", false, "message", err));
                return;
            }
            ctx.json(Map.of("success", true, "message", "Solicitud rechazada."));
        });

        // --- MÓDULO TRANSVERSAL: MI PERFIL Y SEGURIDAD (ANALISTA Y SUPERADMIN) ---
        app.post("/api/usuario/reautenticar_biometria", ctx -> {
            try {
                Map<String, Object> body = ctx.bodyAsClass(Map.class);
                Object sesionUser = ctx.req().getSession(false) != null
                        ? ctx.req().getSession(false).getAttribute("usuario")
                        : null;
                String usuario = String.valueOf(body.getOrDefault("usuario", sesionUser != null ? sesionUser : "")).trim();
                String frameB64 = String.valueOf(body.getOrDefault("frame_base64", body.getOrDefault("image", "")));

                Map<String, Object> resultado = motorSeguridad.reautenticarBiometriaUsuario(usuario, frameB64, motorBiometria);
                int status = resultado.get("http_status") instanceof Number n ? n.intValue() : 200;
                resultado.remove("http_status");
                ctx.status(status).json(resultado);
            } catch (Exception e) {
                ctx.status(500).json(Map.of("success", false, "message", "Error al re-verificar biometría facial."));
            }
        });

        app.post("/api/usuario/actualizar_credenciales", ctx -> {
            try {
                var ses = ctx.req().getSession(false);
                Object sesionUser = ses != null ? ses.getAttribute("usuario") : null;
                String usuarioActual = sesionUser != null ? String.valueOf(sesionUser).trim() : "";
                if (usuarioActual.isEmpty()) {
                    ctx.status(401).json(Map.of("success", false, "message", "Sesión no autenticada."));
                    return;
                }
                Map<String, Object> body = ctx.bodyAsClass(Map.class);
                String tokenVerif = String.valueOf(body.getOrDefault("token_verificacion", "")).trim();
                String nuevoUsuario = body.get("nuevo_usuario") != null ? String.valueOf(body.get("nuevo_usuario")).trim() : "";
                String nuevoPassword = body.get("nuevo_password") != null ? String.valueOf(body.get("nuevo_password")) : "";
                String nuevoFrame = body.get("nuevo_frame_base64") != null ? String.valueOf(body.get("nuevo_frame_base64")) : "";

                Map<String, Object> resultado = motorSeguridad.actualizarCredencialesCompletas(
                        usuarioActual, tokenVerif, nuevoUsuario, nuevoPassword, nuevoFrame, motorBiometria);
                int status = resultado.get("http_status") instanceof Number n ? n.intValue() : 200;
                resultado.remove("http_status");
                if (Boolean.TRUE.equals(resultado.get("success"))) {
                    var sesUpd = ctx.req().getSession(true);
                    if (resultado.get("usuario") != null) {
                        sesUpd.setAttribute(AlicorpBiApplication.ATTR_USUARIO_AUTENTICADO, String.valueOf(resultado.get("usuario")));
                    }
                    if (Boolean.TRUE.equals(resultado.get("biometria_registrada")) || Boolean.TRUE.equals(resultado.get("tiene_biometria"))) {
                        sesUpd.setAttribute(AlicorpBiApplication.ATTR_BIOMETRIA_REGISTRADA, Boolean.TRUE);
                    }
                }
                ctx.status(status).json(resultado);
            } catch (Exception e) {
                ctx.status(500).json(Map.of("success", false, "message", "Error interno al actualizar credenciales."));
            }
        });

        app.post("/api/usuario/registrar_biometria_inicial", ctx -> {
            try {
                var ses = ctx.req().getSession(false);
                Object sesionUser = ses != null ? ses.getAttribute("usuario") : null;
                String usuarioActual = sesionUser != null ? String.valueOf(sesionUser).trim() : "";
                if (usuarioActual.isEmpty()) {
                    ctx.status(401).json(Map.of("success", false, "message", "Sesión no autenticada."));
                    return;
                }
                Map<String, Object> body = ctx.bodyAsClass(Map.class);
                String frameB64 = String.valueOf(body.getOrDefault("frame_base64", body.getOrDefault("nuevo_frame_base64", body.getOrDefault("image", ""))));
                if (frameB64.isBlank()) {
                    ctx.status(400).json(Map.of("success", false, "message", "Captura facial obligatoria."));
                    return;
                }
                String nuevoUsuario = body.get("nuevo_usuario") != null ? String.valueOf(body.get("nuevo_usuario")).trim() : "";
                String nuevoPassword = body.get("nuevo_password") != null ? String.valueOf(body.get("nuevo_password")) : "";

                Map<String, Object> resultado = motorSeguridad.actualizarCredencialesCompletas(
                        usuarioActual, "", nuevoUsuario, nuevoPassword, frameB64, motorBiometria);
                int status = resultado.get("http_status") instanceof Number n ? n.intValue() : 200;
                resultado.remove("http_status");
                if (Boolean.TRUE.equals(resultado.get("success")) && Boolean.TRUE.equals(resultado.get("biometria_registrada"))) {
                    var sesUpd = ctx.req().getSession(true);
                    if (resultado.get("usuario") != null) {
                        sesUpd.setAttribute(AlicorpBiApplication.ATTR_USUARIO_AUTENTICADO, String.valueOf(resultado.get("usuario")));
                    }
                    sesUpd.setAttribute(AlicorpBiApplication.ATTR_BIOMETRIA_REGISTRADA, Boolean.TRUE);
                }
                ctx.status(status).json(resultado);
            } catch (Exception e) {
                ctx.status(500).json(Map.of("success", false, "message", "Error interno al registrar biometría inicial."));
            }
        });
    }
}
