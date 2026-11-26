package pe.com.alicorp.bi.modulos.acceso_biometria;

import pe.com.alicorp.bi.config.DatabaseConfig;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.*;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Servicio de Seguridad y Administración Relacional en 3ra Forma Normal (3FN).
 * Todas las operaciones realizan JOINs estrictos entre ADMINISTRADORES, ROLES_SEGURIDAD,
 * ESTADOS_USUARIO, ACTIVIDAD_USUARIOS, AMPLIACION_AUDITORIA y SOLICITUDES_CAMBIO_CLAVE.
 */
public class ServicioSeguridadRelacional {

    private static final ZoneOffset ZONA_PERU = ZoneOffset.ofHours(-5);
    private static final long TOKEN_REAUTH_TTL_MS = 5 * 60 * 1000L; // 5 minutos
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private static Calendar utcCal() {
        return Calendar.getInstance(TimeZone.getTimeZone("UTC"));
    }

    private static Timestamp getTimestampUtc(ResultSet rs, String col) throws SQLException {
        return rs.getTimestamp(col, utcCal());
    }

    private record TokenReauth(int idAdmin, String usuario, long expiraEnMs) {}

    private final ConcurrentHashMap<String, TokenReauth> tokensReauth = new ConcurrentHashMap<>();

    public static String hashPasswordSeguro(String passwordPlano) {
        if (passwordPlano == null) return "";
        try {
            byte[] salt = new byte[16];
            SECURE_RANDOM.nextBytes(salt);
            String saltHex = bytesAHex(salt);
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest((saltHex + ":" + passwordPlano).getBytes(StandardCharsets.UTF_8));
            return "sha256$" + saltHex + "$" + bytesAHex(digest);
        } catch (Exception e) {
            return passwordPlano;
        }
    }

    public static boolean verificarPassword(String passwordPlano, Object almacenadoObj) {
        if (passwordPlano == null || almacenadoObj == null) return false;
        String almacenado = String.valueOf(almacenadoObj);
        if (almacenado.startsWith("sha256$")) {
            String[] partes = almacenado.split("\\$");
            if (partes.length != 3) return false;
            try {
                MessageDigest md = MessageDigest.getInstance("SHA-256");
                byte[] calculado = md.digest((partes[1] + ":" + passwordPlano).getBytes(StandardCharsets.UTF_8));
                byte[] esperado = hexABytes(partes[2]);
                return MessageDigest.isEqual(calculado, esperado);
            } catch (Exception e) {
                return false;
            }
        }
        return passwordPlano.equals(almacenado);
    }

    private static String bytesAHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private static byte[] hexABytes(String hex) {
        int len = hex.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                    + Character.digit(hex.charAt(i + 1), 16));
        }
        return data;
    }

    public String obtenerClaveAccesoActual() {
        String sql = "SELECT valor_parametro FROM CONFIGURACION_SEGURIDAD WHERE clave_parametro = 'clave_acceso_personal'";
        try (Connection conn = DatabaseConfig.getDataSource().getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                return rs.getString(1);
            }
        } catch (Exception ignored) {}
        return "784920";
    }

    public boolean validarClaveAcceso(String candidata) {
        if (candidata == null || candidata.isBlank()) return false;
        String limpia = candidata.trim();
        String envKey = System.getenv("ADMIN_ACCESS_KEY");
        if (envKey != null && !envKey.isBlank() && limpia.equals(envKey.trim())) {
            return true;
        }
        return limpia.equals(obtenerClaveAccesoActual());
    }

    public boolean esUsuarioActivo3FN(String usuario) {
        Map<String, Object> p = obtenerPerfilUsuario3FN(usuario);
        return p != null && "activo".equalsIgnoreCase(String.valueOf(p.get("estado")));
    }

    /**
     * Consulta un administrador aplicando INNER JOIN con las tablas maestras ROLES_SEGURIDAD y ESTADOS_USUARIO.
     */
    public Map<String, Object> obtenerPerfilUsuario3FN(String usuario) {
        if (usuario == null || usuario.isBlank()) return null;
        String sql = """
            SELECT a.id_admin, a.usuario, a.nombre_completo, a.email, a.password_hash,
                   r.codigo_rol, e.codigo_estado, a.face_encoding, a.l2_norm
            FROM ADMINISTRADORES a
            INNER JOIN ROLES_SEGURIDAD r ON a.id_rol = r.id_rol
            INNER JOIN ESTADOS_USUARIO e ON a.id_estado = e.id_estado
            WHERE UPPER(a.usuario) = UPPER(?)
            """;
        try (Connection conn = DatabaseConfig.getDataSource().getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, usuario.trim());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id_admin", rs.getInt("id_admin"));
                    m.put("usuario", rs.getString("usuario"));
                    m.put("nombre_completo", rs.getString("nombre_completo"));
                    m.put("email", rs.getString("email"));
                    m.put("password_hash", rs.getString("password_hash"));
                    m.put("rol", rs.getString("codigo_rol"));
                    m.put("estado", rs.getString("codigo_estado"));
                    String faceRaw = rs.getString("face_encoding");
                    boolean tieneBio = ServicioBiometriaJavaCV.esVector128DValido(faceRaw);
                    m.put("face_encoding", tieneBio ? faceRaw : null);
                    m.put("l2_norm", rs.getObject("l2_norm") != null ? rs.getDouble("l2_norm") : null);
                    m.put("biometria_registrada", tieneBio);
                    m.put("tiene_biometria", tieneBio);
                    m.put("biometria_pendiente", !tieneBio);
                    m.put("es_superadmin", "superadmin".equalsIgnoreCase(rs.getString("codigo_rol"))
                            && "activo".equalsIgnoreCase(rs.getString("codigo_estado")));
                    return m;
                }
            }
        } catch (Exception e) {
            System.err.println("[!] Error consultando perfil 3FN: " + e.getMessage());
        }
        return null;
    }

    public boolean esSuperAdminActivo(String usuario) {
        Map<String, Object> p = obtenerPerfilUsuario3FN(usuario);
        return p != null && Boolean.TRUE.equals(p.get("es_superadmin"));
    }

    /**
     * Registra un evento en AMPLIACION_AUDITORIA respetando las llaves foráneas hacia ADMINISTRADORES.
     */
    public void registrarAuditoria3FN(String usuarioEjecutor, String usuarioObjetivo, String accion, String detalle) {
        Map<String, Object> ejecutor = obtenerPerfilUsuario3FN(usuarioEjecutor);
        if (ejecutor == null) return;
        Map<String, Object> objetivo = usuarioObjetivo != null ? obtenerPerfilUsuario3FN(usuarioObjetivo) : null;

        String sql = "INSERT INTO AMPLIACION_AUDITORIA (id_admin_ejecutor, id_admin_objetivo, accion, detalle) VALUES (?, ?, ?, ?)";
        try (Connection conn = DatabaseConfig.getDataSource().getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, (Integer) ejecutor.get("id_admin"));
            if (objetivo != null) {
                ps.setInt(2, (Integer) objetivo.get("id_admin"));
            } else {
                ps.setNull(2, Types.INTEGER);
            }
            ps.setString(3, accion);
            ps.setString(4, detalle);
            ps.executeUpdate();
        } catch (Exception e) {
            System.err.println("[!] Error insertando auditoría 3FN: " + e.getMessage());
        }
    }

    public List<Map<String, Object>> listarUsuariosConEstado3FN() {
        List<Map<String, Object>> lista = new ArrayList<>();
        LocalDate hoyPeru = OffsetDateTime.now(ZONA_PERU).toLocalDate();
        String sql = """
            SELECT a.id_admin, a.usuario, a.nombre_completo, a.email,
                   r.codigo_rol, e.codigo_estado, a.face_encoding,
                   act.tiempo_segundos, act.ultimo_latido
            FROM ADMINISTRADORES a
            INNER JOIN ROLES_SEGURIDAD r ON a.id_rol = r.id_rol
            INNER JOIN ESTADOS_USUARIO e ON a.id_estado = e.id_estado
            LEFT JOIN ACTIVIDAD_USUARIOS act
                   ON a.id_admin = act.id_admin AND act.fecha_sesion = ?
            ORDER BY a.id_admin ASC
            """;
        try (Connection conn = DatabaseConfig.getDataSource().getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setDate(1, java.sql.Date.valueOf(hoyPeru));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> item = new LinkedHashMap<>();
                    String rol = rs.getString("codigo_rol");
                    String estado = rs.getString("codigo_estado");
                    String vector = rs.getString("face_encoding");
                    int segs = rs.getInt("tiempo_segundos");
                    Timestamp ult = getTimestampUtc(rs, "ultimo_latido");
                    long diffMs = ult != null ? (System.currentTimeMillis() - ult.getTime()) : Long.MAX_VALUE;
                    boolean conectado = ult != null && diffMs >= -5000 && diffMs <= 60000;

                    item.put("usuario", rs.getString("usuario"));
                    item.put("nombre_completo", rs.getString("nombre_completo"));
                    item.put("email", rs.getString("email") != null ? rs.getString("email") : "");
                    item.put("rol", rol);
                    item.put("estado", estado);
                    boolean tieneBio = ServicioBiometriaJavaCV.esVector128DValido(vector);
                    item.put("es_superadmin", "superadmin".equalsIgnoreCase(rol) && "activo".equalsIgnoreCase(estado));
                    item.put("biometria_registrada", tieneBio);
                    item.put("tiene_biometria", tieneBio);
                    item.put("conectado", conectado);
                    item.put("tiempo_hoy_segundos", segs);
                    item.put("tiempo_segundos_hoy", segs);
                    lista.add(item);
                }
            }
        } catch (Exception e) {
            System.err.println("[!] Error listando usuarios 3FN: " + e.getMessage());
        }
        return lista;
    }

    public List<Map<String, Object>> listarAuditoria3FN() {
        List<Map<String, Object>> logs = new ArrayList<>();
        String sql = """
            SELECT aud.id_auditoria, ej.usuario AS admin_usuario, obj.usuario AS usuario_objetivo,
                   aud.accion, aud.detalle, aud.fecha_evento
            FROM AMPLIACION_AUDITORIA aud
            INNER JOIN ADMINISTRADORES ej ON aud.id_admin_ejecutor = ej.id_admin
            LEFT JOIN ADMINISTRADORES obj ON aud.id_admin_objetivo = obj.id_admin
            ORDER BY aud.fecha_evento DESC
            FETCH FIRST 100 ROWS ONLY
            """;
        try (Connection conn = DatabaseConfig.getDataSource().getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String accRaw = rs.getString("accion") != null ? rs.getString("accion").toLowerCase() : "auditoria";
                if (accRaw.contains("susp")) accRaw = "suspension";
                else if (accRaw.contains("react")) accRaw = "reactivacion";
                else if (accRaw.contains("ascen")) accRaw = "ascenso_rol";
                Map<String, Object> fila = new LinkedHashMap<>();
                fila.put("id", rs.getInt("id_auditoria"));
                fila.put("admin_usuario", rs.getString("admin_usuario"));
                fila.put("realizado_por", rs.getString("admin_usuario"));
                fila.put("usuario_objetivo", rs.getString("usuario_objetivo"));
                fila.put("usuario_afectado", rs.getString("usuario_objetivo"));
                fila.put("accion", accRaw);
                fila.put("detalle", rs.getString("detalle"));
                fila.put("motivo", rs.getString("detalle"));
                Timestamp fe = getTimestampUtc(rs, "fecha_evento");
                fila.put("fecha", fe != null ? fe.toInstant().toString() : null);
                logs.add(fila);
            }
        } catch (Exception ignored) {}
        return logs;
    }

    /**
     * Evalúa estrictamente en 3FN si el usuario activo posee un vector biométrico válido de 128 dimensiones en Supabase.
     */
    public boolean tieneBiometriaRegistrada3FN(String usuario, ServicioBiometriaJavaCV motorBiometria) {
        Map<String, Object> p = obtenerPerfilUsuario3FN(usuario);
        if (p == null || !"activo".equalsIgnoreCase(String.valueOf(p.get("estado")))) return false;
        return Boolean.TRUE.equals(p.get("biometria_registrada"));
    }

    public boolean cambiarEstadoUsuario3FN(String adminEjecutor, String usuarioObjetivo, int nuevoIdEstado, String accionNombre) {
        return cambiarEstadoUsuario3FN(adminEjecutor, usuarioObjetivo, nuevoIdEstado, accionNombre, null);
    }

    /** Igual que el anterior, pero guarda el motivo escrito por el Super Admin en el detalle de la auditoría. */
    public boolean cambiarEstadoUsuario3FN(String adminEjecutor, String usuarioObjetivo, int nuevoIdEstado, String accionNombre, String motivo) {
        Map<String, Object> ejec = obtenerPerfilUsuario3FN(adminEjecutor);
        Map<String, Object> obj = obtenerPerfilUsuario3FN(usuarioObjetivo);
        if (ejec == null || obj == null || !Boolean.TRUE.equals(ejec.get("es_superadmin"))) return false;
        try (Connection conn = DatabaseConfig.getDataSource().getConnection()) {
            try (PreparedStatement ps = conn.prepareStatement("UPDATE ADMINISTRADORES SET id_estado = ? WHERE id_admin = ?")) {
                ps.setInt(1, nuevoIdEstado);
                ps.setInt(2, (Integer) obj.get("id_admin"));
                ps.executeUpdate();
            }
            try (PreparedStatement psAud = conn.prepareStatement(
                    "INSERT INTO AMPLIACION_AUDITORIA (id_admin_ejecutor, id_admin_objetivo, accion, detalle) VALUES (?, ?, ?, ?)")) {
                psAud.setInt(1, (Integer) ejec.get("id_admin"));
                psAud.setInt(2, (Integer) obj.get("id_admin"));
                psAud.setString(3, accionNombre);
                String detalleAud = (motivo != null && !motivo.isBlank())
                        ? (motivo.trim().length() > 450 ? motivo.trim().substring(0, 450) : motivo.trim())
                        : "Cambio de estado aplicado por @" + adminEjecutor + " sobre @" + usuarioObjetivo;
                psAud.setString(4, detalleAud);
                psAud.executeUpdate();
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public List<Map<String, Object>> obtenerHistorialActividad3FN(String usuario) {
        List<Map<String, Object>> hist = new ArrayList<>();
        if (usuario == null || usuario.isBlank()) return hist;
        String sql = """
            SELECT act.fecha_sesion, act.tiempo_segundos, act.primer_ingreso, act.ultimo_latido
            FROM ACTIVIDAD_USUARIOS act
            INNER JOIN ADMINISTRADORES a ON act.id_admin = a.id_admin
            WHERE LOWER(a.usuario) = LOWER(?)
            ORDER BY act.fecha_sesion DESC
            FETCH FIRST 30 ROWS ONLY
            """;
        try (Connection conn = DatabaseConfig.getDataSource().getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, usuario.trim());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    java.sql.Date fecha = rs.getDate("fecha_sesion");
                    Timestamp primer = getTimestampUtc(rs, "primer_ingreso");
                    Timestamp ultimo = getTimestampUtc(rs, "ultimo_latido");
                    Map<String, Object> f = new LinkedHashMap<>();
                    f.put("fecha", fecha != null ? fecha.toString() : "");
                    f.put("tiempo_segundos", rs.getInt("tiempo_segundos"));
                    f.put("primer_ingreso", primer != null ? primer.toInstant().toString() : null);
                    f.put("ultimo_latido", ultimo != null ? ultimo.toInstant().toString() : null);
                    hist.add(f);
                }
            }
        } catch (Exception e) {
            System.err.println("[!] Error consultando historial de actividad 3FN: " + e.getMessage());
        }
        return hist;
    }

    public String actualizarClaveAccesoDirecta(String adminUsuario, String nuevaClave) {
        Map<String, Object> ejec = obtenerPerfilUsuario3FN(adminUsuario);
        if (ejec == null || !Boolean.TRUE.equals(ejec.get("es_superadmin"))) return null;
        try (Connection conn = DatabaseConfig.getDataSource().getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "UPDATE CONFIGURACION_SEGURIDAD SET valor_parametro = ?, id_actualizado_por = ?, updated_at = CURRENT_TIMESTAMP WHERE clave_parametro = 'clave_acceso_personal'")) {
            ps.setString(1, nuevaClave);
            ps.setInt(2, (Integer) ejec.get("id_admin"));
            ps.executeUpdate();
            return nuevaClave;
        } catch (Exception e) {
            return null;
        }
    }

    public boolean crearSolicitudClave3FN(String solicitante, String nuevaClave) {
        Map<String, Object> sol = obtenerPerfilUsuario3FN(solicitante);
        if (sol == null || !Boolean.TRUE.equals(sol.get("es_superadmin"))) return false;
        try (Connection conn = DatabaseConfig.getDataSource().getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO SOLICITUDES_CAMBIO_CLAVE (nueva_clave, id_solicitante, estado_solicitud) VALUES (?, ?, 'pendiente')")) {
            ps.setString(1, nuevaClave);
            ps.setInt(2, (Integer) sol.get("id_admin"));
            ps.executeUpdate();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public List<Map<String, Object>> listarSolicitudesClave3FN() {
        List<Map<String, Object>> lista = new ArrayList<>();
        String sql = """
            SELECT s.id_solicitud, s.nueva_clave, a.usuario AS solicitante, ap.usuario AS aprobador,
                   s.estado_solicitud, s.created_at
            FROM SOLICITUDES_CAMBIO_CLAVE s
            INNER JOIN ADMINISTRADORES a ON s.id_solicitante = a.id_admin
            LEFT JOIN ADMINISTRADORES ap ON s.id_aprobador = ap.id_admin
            ORDER BY s.created_at DESC
            FETCH FIRST 20 ROWS ONLY
            """;
        try (Connection conn = DatabaseConfig.getDataSource().getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String estRaw = rs.getString("estado_solicitud");
                String estUi = "aprobada".equalsIgnoreCase(estRaw) ? "aprobado"
                        : ("rechazada".equalsIgnoreCase(estRaw) ? "rechazado" : "pendiente");
                Timestamp createdTs = getTimestampUtc(rs, "created_at");
                String ts = createdTs != null ? createdTs.toInstant().toString() : null;
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", rs.getInt("id_solicitud"));
                m.put("nueva_clave", rs.getString("nueva_clave"));
                m.put("solicitante", rs.getString("solicitante"));
                m.put("solicitado_por", rs.getString("solicitante"));
                m.put("aprobado_por", rs.getString("aprobador"));
                m.put("estado", estUi);
                m.put("fecha", ts);
                m.put("creado_en", ts);
                lista.add(m);
            }
        } catch (Exception ignored) {}
        return lista;
    }

    public String resolverSolicitudClave3FN(int idSolicitud, String aprobador, boolean aprobar) {
        Map<String, Object> ap = obtenerPerfilUsuario3FN(aprobador);
        if (ap == null || !Boolean.TRUE.equals(ap.get("es_superadmin"))) return "No autorizado.";
        int idAp = (Integer) ap.get("id_admin");
        try (Connection conn = DatabaseConfig.getDataSource().getConnection()) {
            String nuevaClave = null;
            int idSol = -1;
            try (PreparedStatement ps = conn.prepareStatement("SELECT nueva_clave, id_solicitante FROM SOLICITUDES_CAMBIO_CLAVE WHERE id_solicitud = ? AND estado_solicitud = 'pendiente'")) {
                ps.setInt(1, idSolicitud);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        nuevaClave = rs.getString("nueva_clave");
                        idSol = rs.getInt("id_solicitante");
                    }
                }
            }
            if (nuevaClave == null) return "Solicitud no encontrada o ya procesada.";
            if (idSol == idAp && aprobar) return "Otro SuperAdmin distinto debe aprobar esta solicitud (regla CHK_DOBLE_SUPERADMIN).";

            try (PreparedStatement ps = conn.prepareStatement("UPDATE SOLICITUDES_CAMBIO_CLAVE SET estado_solicitud = ?, id_aprobador = ?, resolved_at = CURRENT_TIMESTAMP WHERE id_solicitud = ?")) {
                ps.setString(1, aprobar ? "aprobada" : "rechazada");
                if (idSol != idAp) ps.setInt(2, idAp);
                else ps.setNull(2, java.sql.Types.INTEGER);
                ps.setInt(3, idSolicitud);
                ps.executeUpdate();
            }
            if (aprobar) {
                actualizarClaveAccesoDirecta(aprobador, nuevaClave);
            }
            return null;
        } catch (Exception e) {
            return e.getMessage();
        }
    }

    public int obtenerTiempoHoy3FN(String usuario, String fechaPeru) {
        String sql = """
            SELECT act.tiempo_segundos
            FROM ACTIVIDAD_USUARIOS act
            INNER JOIN ADMINISTRADORES a ON act.id_admin = a.id_admin
            WHERE LOWER(a.usuario) = LOWER(?) AND act.fecha_sesion = ?
            """;
        try (Connection conn = DatabaseConfig.getDataSource().getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, usuario);
            ps.setDate(2, java.sql.Date.valueOf(fechaPeru));
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getInt("tiempo_segundos");
            }
        } catch (Exception ignored) {}
        return 0;
    }

    public int registrarLatido3FN(String usuario, String fechaPeru, int segundosDelta, int tiempoTotalCliente) {
        Map<String, Object> perfil = obtenerPerfilUsuario3FN(usuario);
        if (perfil == null) return 0;
        int idAdmin = (Integer) perfil.get("id_admin");
        java.sql.Date fechaSql = java.sql.Date.valueOf(fechaPeru);
        int deltaSeguro = Math.max(0, Math.min(120, segundosDelta));
        Timestamp ahoraUtc = Timestamp.from(Instant.now());

        try (Connection conn = DatabaseConfig.getDataSource().getConnection()) {
            Integer actual = null;
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT tiempo_segundos FROM ACTIVIDAD_USUARIOS WHERE id_admin = ? AND fecha_sesion = ?")) {
                ps.setInt(1, idAdmin);
                ps.setDate(2, fechaSql);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) actual = rs.getInt(1);
                }
            }
            if (actual == null) {
                int nuevoTotal = deltaSeguro;
                try (PreparedStatement psIns = conn.prepareStatement(
                        "INSERT INTO ACTIVIDAD_USUARIOS (id_admin, fecha_sesion, tiempo_segundos, primer_ingreso, ultimo_latido) VALUES (?, ?, ?, ?, ?)")) {
                    psIns.setInt(1, idAdmin);
                    psIns.setDate(2, fechaSql);
                    psIns.setInt(3, nuevoTotal);
                    psIns.setTimestamp(4, ahoraUtc, utcCal());
                    psIns.setTimestamp(5, ahoraUtc, utcCal());
                    psIns.executeUpdate();
                }
                return nuevoTotal;
            } else {
                int nuevoTotal = Math.min(86400, actual + deltaSeguro);
                try (PreparedStatement psUpd = conn.prepareStatement(
                        "UPDATE ACTIVIDAD_USUARIOS SET tiempo_segundos = ?, ultimo_latido = ? WHERE id_admin = ? AND fecha_sesion = ?")) {
                    psUpd.setInt(1, nuevoTotal);
                    psUpd.setTimestamp(2, ahoraUtc, utcCal());
                    psUpd.setInt(3, idAdmin);
                    psUpd.setDate(4, fechaSql);
                    psUpd.executeUpdate();
                }
                return nuevoTotal;
            }
        } catch (Exception e) {
            return Math.max(0, tiempoTotalCliente);
        }
    }

    /**
     * Re-verifica la identidad biométrica facial 128-D de un usuario activo contra su vector
     * almacenado en ADMINISTRADORES y emite un token temporal (5 min) si distancia <= 0.78.
     */
    public Map<String, Object> reautenticarBiometriaUsuario(
            String usuario,
            String frameBase64,
            ServicioBiometriaJavaCV motorBiometria) {
        Map<String, Object> resp = new LinkedHashMap<>();
        if (usuario == null || usuario.isBlank() || frameBase64 == null || frameBase64.isBlank()) {
            resp.put("success", false);
            resp.put("http_status", 400);
            resp.put("message", "Usuario y captura facial son obligatorios.");
            return resp;
        }

        Map<String, Object> perfil = obtenerPerfilUsuario3FN(usuario.trim());
        if (perfil == null || !"activo".equalsIgnoreCase(String.valueOf(perfil.get("estado")))) {
            resp.put("success", false);
            resp.put("http_status", 401);
            resp.put("message", "Usuario no encontrado o cuenta inactiva.");
            return resp;
        }

        double[] embeddingActual = motorBiometria.parsearVectorJson(String.valueOf(perfil.get("face_encoding")));
        if (embeddingActual == null) {
            resp.put("success", false);
            resp.put("http_status", 400);
            resp.put("message", "Tu cuenta no cuenta con un vector biométrico 128-D registrado.");
            return resp;
        }

        double[] nuevoEmbedding = motorBiometria.extraerEmbedding128D(frameBase64);
        if (nuevoEmbedding == null) {
            resp.put("success", false);
            resp.put("http_status", 400);
            resp.put("message", "No se pudo extraer un rostro válido. Mejora la iluminación e inténtalo de nuevo.");
            return resp;
        }

        double distancia = motorBiometria.compararVectores(embeddingActual, nuevoEmbedding);
        double distRedondeada = Math.round(distancia * 10000.0) / 10000.0;
        if (!motorBiometria.esMatchBiometrico(nuevoEmbedding, embeddingActual)) {
            double cos = Math.round(motorBiometria.calcularSimilitudCoseno(nuevoEmbedding, embeddingActual) * 10000.0) / 10000.0;
            resp.put("success", false);
            resp.put("http_status", 401);
            resp.put("distancia", distRedondeada);
            resp.put("similitud_coseno", cos);
            resp.put("umbral", motorBiometria.isMotorNeuralActivo()
                    ? ServicioBiometriaJavaCV.UMBRAL_COSENO_SFACE
                    : ServicioBiometriaJavaCV.UMBRAL_DISTANCIA);
            resp.put("message", motorBiometria.isMotorNeuralActivo()
                    ? String.format("Identidad no verificada (similitud coseno %.3f < %.3f).", cos, ServicioBiometriaJavaCV.UMBRAL_COSENO_SFACE)
                    : String.format("Identidad no verificada (distancia %.4f > %.2f).", distRedondeada, ServicioBiometriaJavaCV.UMBRAL_DISTANCIA));
            return resp;
        }

        // Limpiar tokens expirados y emitir token temporal de verificación
        long ahora = System.currentTimeMillis();
        tokensReauth.entrySet().removeIf(e -> e.getValue().expiraEnMs() < ahora);

        byte[] rawToken = new byte[24];
        SECURE_RANDOM.nextBytes(rawToken);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(rawToken);
        int idAdmin = (Integer) perfil.get("id_admin");
        String usuarioCanon = String.valueOf(perfil.get("usuario"));
        tokensReauth.put(token, new TokenReauth(idAdmin, usuarioCanon, ahora + TOKEN_REAUTH_TTL_MS));

        double precision = motorBiometria.calcularPorcentajeConfianza(nuevoEmbedding, embeddingActual);
        resp.put("success", true);
        resp.put("http_status", 200);
        resp.put("distancia", distRedondeada);
        resp.put("similitud_coseno", Math.round(motorBiometria.calcularSimilitudCoseno(nuevoEmbedding, embeddingActual) * 10000.0) / 10000.0);
        resp.put("umbral", motorBiometria.isMotorNeuralActivo()
                ? ServicioBiometriaJavaCV.UMBRAL_COSENO_SFACE
                : ServicioBiometriaJavaCV.UMBRAL_DISTANCIA);
        resp.put("precision", precision);
        resp.put("token_verificacion", token);
        resp.put("expira_segundos", (int) (TOKEN_REAUTH_TTL_MS / 1000L));
        resp.put("message", "Identidad biométrica verificada. Formulario desbloqueado por 5 minutos.");
        return resp;
    }

    /**
     * Actualiza de forma atómica usuario, contraseña (hash SHA-256 con salt) y/o vector facial 128-D.
     * - Si el usuario ya posee biometría (face_encoding NOT NULL), exige token biométrico vigente.
     * - Si el usuario tiene biometría pendiente (face_encoding IS NULL), permite activar su rostro inicial
     *   y/o actualizar sus credenciales desde su sesión autenticada, registrando trazabilidad en AMPLIACION_AUDITORIA.
     */
    public Map<String, Object> actualizarCredencialesCompletas(
            String usuarioActual,
            String tokenVerificacion,
            String nuevoUsuario,
            String nuevoPassword,
            String nuevoFrameBase64,
            ServicioBiometriaJavaCV motorBiometria) {

        Map<String, Object> resp = new LinkedHashMap<>();
        if (usuarioActual == null || usuarioActual.isBlank()) {
            resp.put("success", false);
            resp.put("http_status", 401);
            resp.put("message", "Sesión de usuario no válida.");
            return resp;
        }

        Map<String, Object> perfilActual = obtenerPerfilUsuario3FN(usuarioActual.trim());
        if (perfilActual == null || !"activo".equalsIgnoreCase(String.valueOf(perfilActual.get("estado")))) {
            resp.put("success", false);
            resp.put("http_status", 401);
            resp.put("message", "Usuario no encontrado o cuenta suspendida.");
            return resp;
        }

        int idAdminActual = (Integer) perfilActual.get("id_admin");
        String usuarioCanon = String.valueOf(perfilActual.get("usuario"));
        Object rawFaceActual = perfilActual.get("face_encoding");
        boolean yaTieneBiometria = rawFaceActual != null
                && motorBiometria.parsearVectorJson(String.valueOf(rawFaceActual)) != null;

        if (yaTieneBiometria) {
            if (tokenVerificacion == null || tokenVerificacion.isBlank()) {
                resp.put("success", false);
                resp.put("http_status", 401);
                resp.put("message", "Se requiere verificación biométrica vigente para modificar credenciales.");
                return resp;
            }
            TokenReauth tok = tokensReauth.get(tokenVerificacion.trim());
            long ahora = System.currentTimeMillis();
            if (tok == null || tok.expiraEnMs() < ahora || !tok.usuario().equalsIgnoreCase(usuarioCanon)) {
                tokensReauth.remove(tokenVerificacion.trim());
                resp.put("success", false);
                resp.put("http_status", 401);
                resp.put("message", "Token de verificación biométrica inválido o expirado. Vuelve a escanear tu rostro.");
                return resp;
            }
        }

        String uNuevo = nuevoUsuario != null ? nuevoUsuario.trim() : "";
        String pNuevo = nuevoPassword != null ? nuevoPassword : "";
        String fNuevo = nuevoFrameBase64 != null ? nuevoFrameBase64.trim() : "";

        boolean cambiaUsuario = !uNuevo.isEmpty() && !uNuevo.equals(usuarioCanon);
        boolean cambiaPassword = !pNuevo.isEmpty();
        boolean cambiaBiometria = !fNuevo.isEmpty();

        if (!cambiaUsuario && !cambiaPassword && !cambiaBiometria) {
            resp.put("success", false);
            resp.put("http_status", 400);
            resp.put("message", "Debes modificar al menos un campo (usuario, contraseña o rostro 128-D).");
            return resp;
        }

        if (cambiaUsuario && (uNuevo.length() < 3 || uNuevo.length() > 40 || !uNuevo.matches("[A-Za-z0-9._-]+"))) {
            resp.put("success", false);
            resp.put("http_status", 400);
            resp.put("message", "El nuevo usuario debe tener entre 3 y 40 caracteres alfanuméricos (., _, -).");
            return resp;
        }

        if (cambiaPassword) {
            boolean largoOk = pNuevo.length() >= 8;
            boolean mayusOk = pNuevo.matches(".*[A-Z].*");
            boolean minusOk = pNuevo.matches(".*[a-z].*");
            boolean numOk = pNuevo.matches(".*[0-9].*");
            if (!largoOk || !mayusOk || !minusOk || !numOk) {
                resp.put("success", false);
                resp.put("http_status", 400);
                resp.put("message", "La nueva contraseña debe tener mínimo 8 caracteres, una mayúscula, una minúscula y un número.");
                return resp;
            }
        }

        double[] nuevoVector = null;
        String nuevoVectorJson = null;
        double nuevaNormaL2 = 0.0;
        if (cambiaBiometria) {
            nuevoVector = motorBiometria.extraerEmbedding128D(fNuevo);
            if (nuevoVector == null) {
                resp.put("success", false);
                resp.put("http_status", 400);
                resp.put("message", "No se pudo extraer el nuevo vector facial 128-D. Intenta capturar con mejor luz.");
                return resp;
            }
            nuevoVectorJson = motorBiometria.serializarVectorJson(nuevoVector);
            nuevaNormaL2 = motorBiometria.calcularNormaL2(nuevoVector);
        }

        try (Connection conn = DatabaseConfig.getDataSource().getConnection()) {
            boolean prevAuto = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                // 1. Verificar colisión de nuevo usuario si cambió
                if (cambiaUsuario) {
                    try (PreparedStatement psDup = conn.prepareStatement(
                            "SELECT 1 FROM ADMINISTRADORES WHERE UPPER(usuario) = UPPER(?) AND id_admin <> ?")) {
                        psDup.setString(1, uNuevo);
                        psDup.setInt(2, idAdminActual);
                        try (ResultSet rs = psDup.executeQuery()) {
                            if (rs.next()) {
                                conn.rollback();
                                resp.put("success", false);
                                resp.put("http_status", 409);
                                resp.put("message", "El nombre de usuario '" + uNuevo + "' ya está en uso.");
                                return resp;
                            }
                        }
                    }
                }

                // 2. Construir UPDATE dinámico sobre ADMINISTRADORES
                List<String> sets = new ArrayList<>();
                List<Object> params = new ArrayList<>();
                if (cambiaUsuario) {
                    sets.add("usuario = ?");
                    params.add(uNuevo);
                }
                if (cambiaPassword) {
                    sets.add("password_hash = ?");
                    params.add(hashPasswordSeguro(pNuevo));
                }
                if (cambiaBiometria) {
                    sets.add("face_encoding = ?");
                    params.add(nuevoVectorJson);
                    sets.add("l2_norm = ?");
                    params.add(nuevaNormaL2);
                }
                params.add(idAdminActual);

                String sqlUpd = "UPDATE ADMINISTRADORES SET " + String.join(", ", sets) + " WHERE id_admin = ?";
                try (PreparedStatement psUpd = conn.prepareStatement(sqlUpd)) {
                    for (int i = 0; i < params.size(); i++) {
                        Object val = params.get(i);
                        if (val instanceof Integer iv) psUpd.setInt(i + 1, iv);
                        else if (val instanceof Double dv) psUpd.setDouble(i + 1, dv);
                        else psUpd.setString(i + 1, String.valueOf(val));
                    }
                    psUpd.executeUpdate();
                }

                // 3. Registrar trazabilidad en AMPLIACION_AUDITORIA dentro de la misma transacción
                String accionAud = (!yaTieneBiometria && cambiaBiometria)
                        ? "ACTIVACION_BIOMETRICA_INICIAL"
                        : "MODIFICACION_CREDENCIALES_PROPIAS";
                String detalleAud = (!yaTieneBiometria && cambiaBiometria)
                        ? "Registro facial 128-D inicial completado desde Mi Perfil (desbloqueo de cuenta)"
                        : (yaTieneBiometria
                            ? "Actualización de usuario/clave/biometría tras re-autenticación facial"
                            : "Actualización inicial de usuario/contraseña en cuenta con biometría pendiente");
                try (PreparedStatement psAud = conn.prepareStatement(
                        "INSERT INTO AMPLIACION_AUDITORIA (id_admin_ejecutor, id_admin_objetivo, accion, detalle) VALUES (?, ?, ?, ?)")) {
                    psAud.setInt(1, idAdminActual);
                    psAud.setInt(2, idAdminActual);
                    psAud.setString(3, accionAud);
                    psAud.setString(4, detalleAud);
                    psAud.executeUpdate();
                }

                conn.commit();
                if (tokenVerificacion != null && !tokenVerificacion.isBlank()) {
                    tokensReauth.remove(tokenVerificacion.trim());
                }

                boolean tieneBioFinal = yaTieneBiometria || cambiaBiometria;
                String usuarioFinal = cambiaUsuario ? uNuevo : usuarioCanon;
                List<Double> vecLista = new ArrayList<>();
                if (nuevoVector != null) {
                    for (double d : nuevoVector) vecLista.add(d);
                }
                resp.put("success", true);
                resp.put("http_status", 200);
                resp.put("usuario", usuarioFinal);
                resp.put("biometria_actualizada", cambiaBiometria);
                resp.put("biometria_registrada", tieneBioFinal);
                resp.put("tiene_biometria", tieneBioFinal);
                resp.put("biometria_pendiente", !tieneBioFinal);
                resp.put("biometria_activada_inicial", !yaTieneBiometria && cambiaBiometria);
                if (!vecLista.isEmpty()) {
                    resp.put("vector_128d", vecLista);
                }
                resp.put("l2_norm", cambiaBiometria ? nuevaNormaL2 : null);
                String msgOk = (!yaTieneBiometria && cambiaBiometria)
                        ? "Biometría 128-D registrada exitosamente. Acceso completo al Dashboard desbloqueado."
                        : "Credenciales actualizadas exitosamente";
                resp.put("mensaje", msgOk);
                resp.put("message", msgOk);
                return resp;
            } catch (SQLException ex) {
                try { conn.rollback(); } catch (SQLException ignored) {}
                resp.put("success", false);
                resp.put("http_status", 500);
                resp.put("message", "Error transaccional al actualizar credenciales: " + ex.getMessage());
                return resp;
            } finally {
                try { conn.setAutoCommit(prevAuto); } catch (SQLException ignored) {}
            }
        } catch (SQLException e) {
            resp.put("success", false);
            resp.put("http_status", 500);
            resp.put("message", "Error de conexión con base de datos: " + e.getMessage());
            return resp;
        }
    }
}
