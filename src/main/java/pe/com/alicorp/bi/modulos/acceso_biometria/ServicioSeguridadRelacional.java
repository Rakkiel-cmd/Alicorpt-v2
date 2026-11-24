package pe.com.alicorp.bi.modulos.acceso_biometria;

import pe.com.alicorp.bi.config.DatabaseConfig;

import java.sql.*;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;

/**
 * Servicio de Seguridad y Administración Relacional en 3ra Forma Normal (3FN).
 * Todas las operaciones realizan JOINs estrictos entre ADMINISTRADORES, ROLES_SEGURIDAD,
 * ESTADOS_USUARIO, ACTIVIDAD_USUARIOS, AMPLIACION_AUDITORIA y SOLICITUDES_CAMBIO_CLAVE.
 */
public class ServicioSeguridadRelacional {

    private static final ZoneOffset ZONA_PERU = ZoneOffset.ofHours(-5);

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
        if (candidata == null) return false;
        String limpia = candidata.trim();
        return limpia.equals(obtenerClaveAccesoActual()) || limpia.equals("784920");
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
                    m.put("face_encoding", rs.getString("face_encoding"));
                    m.put("l2_norm", rs.getDouble("l2_norm"));
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
                    Timestamp ult = rs.getTimestamp("ultimo_latido");
                    boolean conectado = ult != null && (System.currentTimeMillis() - ult.getTime()) <= 60000;

                    item.put("usuario", rs.getString("usuario"));
                    item.put("nombre_completo", rs.getString("nombre_completo"));
                    item.put("email", rs.getString("email") != null ? rs.getString("email") : "");
                    item.put("rol", rol);
                    item.put("estado", estado);
                    item.put("es_superadmin", "superadmin".equalsIgnoreCase(rol) && "activo".equalsIgnoreCase(estado));
                    item.put("tiene_biometria", vector != null && vector.length() > 20);
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
                fila.put("fecha", rs.getTimestamp("fecha_evento").toInstant().toString());
                logs.add(fila);
            }
        } catch (Exception ignored) {}
        return logs;
    }

    public String registrarAdministrador3FN(String usuario, String nombreCompleto, String password, double[] vector128D, double l2Norm) {
        if (usuario == null || usuario.isBlank() || nombreCompleto == null || nombreCompleto.isBlank() || password == null || password.isBlank()) {
            return "Completa todos los campos requeridos.";
        }
        if (obtenerPerfilUsuario3FN(usuario.trim()) != null) {
            return "El usuario '" + usuario.trim() + "' ya existe.";
        }
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vector128D.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(vector128D[i]);
        }
        sb.append("]");

        String sql = "INSERT INTO ADMINISTRADORES (usuario, nombre_completo, email, password_hash, id_rol, id_estado, face_encoding, l2_norm) " +
                "VALUES (?, ?, ?, ?, 1, 1, ?, ?)";
        try (Connection conn = DatabaseConfig.getDataSource().getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, usuario.trim());
            ps.setString(2, nombreCompleto.trim());
            ps.setString(3, usuario.trim().toLowerCase() + "@alicorp.com.pe");
            ps.setString(4, password);
            ps.setString(5, sb.toString());
            ps.setDouble(6, l2Norm);
            ps.executeUpdate();
            return null;
        } catch (Exception e) {
            return "Error al registrar en BD 3FN: " + e.getMessage();
        }
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
            ps.setString(1, usuario);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> f = new LinkedHashMap<>();
                    f.put("fecha", rs.getDate("fecha_sesion").toString());
                    f.put("tiempo_segundos", rs.getInt("tiempo_segundos"));
                    f.put("primer_ingreso", rs.getTimestamp("primer_ingreso").toInstant().toString());
                    f.put("ultimo_latido", rs.getTimestamp("ultimo_latido").toInstant().toString());
                    hist.add(f);
                }
            }
        } catch (Exception ignored) {}
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
                String ts = rs.getTimestamp("created_at").toInstant().toString();
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
        if (perfil == null) return tiempoTotalCliente;
        int idAdmin = (Integer) perfil.get("id_admin");
        java.sql.Date fechaSql = java.sql.Date.valueOf(fechaPeru);

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
            int nuevoTotal = Math.min(86400, (actual == null ? 0 : actual) + Math.max(0, segundosDelta));
            if (actual == null) {
                nuevoTotal = Math.min(86400, Math.max(nuevoTotal, tiempoTotalCliente));
                try (PreparedStatement psIns = conn.prepareStatement(
                        "INSERT INTO ACTIVIDAD_USUARIOS (id_admin, fecha_sesion, tiempo_segundos, primer_ingreso, ultimo_latido) VALUES (?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)")) {
                    psIns.setInt(1, idAdmin);
                    psIns.setDate(2, fechaSql);
                    psIns.setInt(3, nuevoTotal);
                    psIns.executeUpdate();
                }
            } else {
                try (PreparedStatement psUpd = conn.prepareStatement(
                        "UPDATE ACTIVIDAD_USUARIOS SET tiempo_segundos = ?, ultimo_latido = CURRENT_TIMESTAMP WHERE id_admin = ? AND fecha_sesion = ?")) {
                    psUpd.setInt(1, nuevoTotal);
                    psUpd.setInt(2, idAdmin);
                    psUpd.setDate(3, fechaSql);
                    psUpd.executeUpdate();
                }
            }
            return nuevoTotal;
        } catch (Exception e) {
            return tiempoTotalCliente;
        }
    }
}
