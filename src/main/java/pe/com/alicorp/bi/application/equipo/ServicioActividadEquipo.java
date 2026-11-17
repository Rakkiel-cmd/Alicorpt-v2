package pe.com.alicorp.bi.application.equipo;

import pe.com.alicorp.bi.application.equipo.AnalisisEquipo.Rango;
import pe.com.alicorp.bi.config.DatabaseConfig;

import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Dashboard "Actividad del equipo", auditoría filtrada y registro de atención de tickets.
 *
 * Diseño liviano: consultas con columnas mínimas y rango de fechas, resultado en caché 60 s,
 * auditoría filtrada y paginada en la base de datos. SQL portable (H2 modo Oracle y PostgreSQL).
 */
public class ServicioActividadEquipo {

    private static final ZoneOffset ZONA_PERU = ZoneOffset.ofHours(-5);
    private static final long TTL_MS = 60_000;
    private static final Set<String> ESTADOS_ATENDIDO = Set.of("en_revision", "resuelto");
    public static final List<String> ACCIONES_VALIDAS = List.of(
            "suspension", "reactivacion", "ascenso_rol", "degradacion_rol", "cambio_clave", "actualizacion_email");

    private static final ServicioActividadEquipo COMPARTIDO = new ServicioActividadEquipo();

    /** Instancia única: así la caché y la migración se comparten entre todas las rutas. */
    public static ServicioActividadEquipo compartido() { return COMPARTIDO; }

    private final Map<String, Object[]> cache = new ConcurrentHashMap<>();
    private volatile boolean esquemaListo = false;
    private volatile long ultimoIntentoEsquema = 0;

    // ------------------------------------------------------------------ SQL
    static final String SQL_ADMINS = """
            SELECT a.usuario, a.nombre_completo, r.codigo_rol
            FROM ADMINISTRADORES a
            INNER JOIN ROLES_SEGURIDAD r ON a.id_rol = r.id_rol
            """;

    static final String SQL_ACTIVIDAD = """
            SELECT a.usuario, act.fecha_sesion, act.tiempo_segundos, act.primer_ingreso
            FROM ACTIVIDAD_USUARIOS act
            INNER JOIN ADMINISTRADORES a ON act.id_admin = a.id_admin
            WHERE act.fecha_sesion >= ? AND act.fecha_sesion < ?
            ORDER BY act.fecha_sesion
            FETCH FIRST 20000 ROWS ONLY
            """;

    static final String SQL_ACCIONES_PERIODO = """
            SELECT ej.usuario, aud.accion, aud.detalle
            FROM AMPLIACION_AUDITORIA aud
            INNER JOIN ADMINISTRADORES ej ON aud.id_admin_ejecutor = ej.id_admin
            WHERE aud.fecha_evento >= ? AND aud.fecha_evento < ?
            FETCH FIRST 20000 ROWS ONLY
            """;

    static final String SQL_HISTORIAL_ATENCION = """
            SELECT a.usuario, h.tipo, h.id_ticket, h.estado_nuevo, h.fecha_evento
            FROM HISTORIAL_ATENCION h
            INNER JOIN ADMINISTRADORES a ON h.id_admin = a.id_admin
            WHERE h.fecha_evento >= ?
            FETCH FIRST 20000 ROWS ONLY
            """;

    static final String SQL_INCIDENCIAS_ATENDIDAS = """
            SELECT a.usuario, t.fecha_creacion AS fecha_inicio, t.fecha_atencion, t.fecha_resolucion
            FROM INCIDENCIAS_CONTACTO t
            INNER JOIN ADMINISTRADORES a ON t.id_admin_atendio = a.id_admin
            WHERE t.fecha_atencion >= ? AND t.fecha_atencion < ?
            """;

    static final String SQL_FALLAS_ATENDIDAS = """
            SELECT a.usuario, t.fecha_reporte AS fecha_inicio, t.fecha_atencion, t.fecha_resolucion
            FROM FALLAS_WEB_SISTEMA t
            INNER JOIN ADMINISTRADORES a ON t.id_admin_atendio = a.id_admin
            WHERE t.fecha_atencion >= ? AND t.fecha_atencion < ?
            """;

    static final String SQL_PENDIENTES_INCIDENCIAS = "SELECT COUNT(*) FROM INCIDENCIAS_CONTACTO WHERE estado <> 'resuelto'";
    static final String SQL_PENDIENTES_FALLAS = "SELECT COUNT(*) FROM FALLAS_WEB_SISTEMA WHERE estado <> 'resuelto'";

    // Auditoría filtrada (se arma con fragmentos fijos; los valores del usuario van siempre como parámetros)
    static final String SQL_AUD_FROM = """
            FROM AMPLIACION_AUDITORIA aud
            INNER JOIN ADMINISTRADORES ej ON aud.id_admin_ejecutor = ej.id_admin
            LEFT JOIN ADMINISTRADORES obj ON aud.id_admin_objetivo = obj.id_admin
            WHERE 1 = 1
            """;
    static final String COND_DESDE = " AND aud.fecha_evento >= ?";
    static final String COND_HASTA = " AND aud.fecha_evento < ?";
    static final String COND_COLABORADOR = " AND (UPPER(ej.usuario) LIKE ? OR UPPER(obj.usuario) LIKE ?)";
    static final String COND_TEXTO = " AND (UPPER(ej.usuario) LIKE ? OR UPPER(obj.usuario) LIKE ? OR UPPER(COALESCE(aud.detalle, '')) LIKE ?)";
    static final Map<String, String> COND_ACCION = Map.of(
            "suspension", " AND UPPER(aud.accion) LIKE '%SUSP%'",
            "reactivacion", " AND UPPER(aud.accion) LIKE '%REACT%'",
            "ascenso_rol", " AND (UPPER(aud.accion) LIKE '%ASCEN%' OR (UPPER(aud.accion) LIKE '%CAMBIO%ROL%' AND UPPER(COALESCE(aud.detalle, '')) LIKE '%SUPERADMIN%'))",
            "degradacion_rol", " AND (UPPER(aud.accion) LIKE '%DEGRAD%' OR (UPPER(aud.accion) LIKE '%CAMBIO%ROL%' AND UPPER(COALESCE(aud.detalle, '')) NOT LIKE '%SUPERADMIN%'))",
            "cambio_clave", " AND UPPER(aud.accion) LIKE '%CLAVE%'",
            "actualizacion_email", " AND (UPPER(aud.accion) LIKE '%EMAIL%' OR UPPER(aud.accion) LIKE '%CORREO%')");
    static final String SQL_AUD_SELECT_CONTEO = "SELECT COUNT(*) ";
    static final String SQL_AUD_SELECT_PAGINA = """
            SELECT aud.id_auditoria, ej.usuario AS admin_usuario, obj.usuario AS usuario_objetivo,
                   aud.accion, aud.detalle, aud.fecha_evento
            """;
    static final String SQL_AUD_ORDEN = " ORDER BY aud.fecha_evento DESC, aud.id_auditoria DESC";

    // Lista de colaboradores: última actividad y suspensión vigente
    static final String SQL_ULTIMA_ACTIVIDAD = """
            SELECT a.usuario, MAX(act.fecha_sesion) AS ultima, MAX(act.ultimo_latido) AS latido
            FROM ACTIVIDAD_USUARIOS act
            INNER JOIN ADMINISTRADORES a ON act.id_admin = a.id_admin
            WHERE act.tiempo_segundos > 0 AND act.fecha_sesion >= ?
            GROUP BY a.usuario
            """;
    static final String SQL_SUSPENSIONES = """
            SELECT obj.usuario AS objetivo, ej.usuario AS ejecutor, aud.detalle, aud.fecha_evento
            FROM AMPLIACION_AUDITORIA aud
            INNER JOIN ADMINISTRADORES ej ON aud.id_admin_ejecutor = ej.id_admin
            INNER JOIN ADMINISTRADORES obj ON aud.id_admin_objetivo = obj.id_admin
            WHERE UPPER(aud.accion) LIKE '%SUSP%'
            ORDER BY aud.fecha_evento DESC
            FETCH FIRST 200 ROWS ONLY
            """;

    // Registro de atención
    static final String SQL_ID_ADMIN = "SELECT id_admin FROM ADMINISTRADORES WHERE UPPER(usuario) = UPPER(?)";
    static final String SQL_ESTADO_INCIDENCIA = "SELECT estado FROM INCIDENCIAS_CONTACTO WHERE id_incidencia = ?";
    static final String SQL_ESTADO_FALLA = "SELECT estado FROM FALLAS_WEB_SISTEMA WHERE id_falla = ?";
    static final String SQL_ID_FALLA_POR_CODIGO = "SELECT id_falla FROM FALLAS_WEB_SISTEMA WHERE codigo_unico = ?";
    static final String SQL_ATENDER_INCIDENCIA_RESUELTA = """
            UPDATE INCIDENCIAS_CONTACTO
            SET id_admin_atendio = COALESCE(id_admin_atendio, ?),
                fecha_atencion = COALESCE(fecha_atencion, CURRENT_TIMESTAMP),
                fecha_resolucion = CURRENT_TIMESTAMP
            WHERE id_incidencia = ?
            """;
    static final String SQL_REABRIR_INCIDENCIA = "UPDATE INCIDENCIAS_CONTACTO SET fecha_resolucion = NULL WHERE id_incidencia = ?";
    static final String SQL_ATENDER_FALLA = """
            UPDATE FALLAS_WEB_SISTEMA
            SET id_admin_atendio = COALESCE(id_admin_atendio, ?),
                fecha_atencion = COALESCE(fecha_atencion, CURRENT_TIMESTAMP),
                fecha_resolucion = CASE WHEN ? = 1 THEN CURRENT_TIMESTAMP ELSE NULL END
            WHERE id_falla = ?
            """;
    static final String SQL_INSERTAR_HISTORIAL = """
            INSERT INTO HISTORIAL_ATENCION (tipo, id_ticket, estado_anterior, estado_nuevo, id_admin)
            VALUES (?, ?, ?, ?, ?)
            """;

    /** Migración idempotente: se ejecuta sola la primera vez (equivale a database/atencion_tickets_3fn.sql). */
    static final List<String> DDL_ATENCION = List.of(
            "ALTER TABLE INCIDENCIAS_CONTACTO ADD COLUMN IF NOT EXISTS id_admin_atendio INT",
            "ALTER TABLE INCIDENCIAS_CONTACTO ADD COLUMN IF NOT EXISTS fecha_atencion TIMESTAMP",
            "ALTER TABLE INCIDENCIAS_CONTACTO ADD COLUMN IF NOT EXISTS fecha_resolucion TIMESTAMP",
            "ALTER TABLE FALLAS_WEB_SISTEMA ADD COLUMN IF NOT EXISTS id_admin_atendio INT",
            "ALTER TABLE FALLAS_WEB_SISTEMA ADD COLUMN IF NOT EXISTS fecha_atencion TIMESTAMP",
            "ALTER TABLE FALLAS_WEB_SISTEMA ADD COLUMN IF NOT EXISTS fecha_resolucion TIMESTAMP",
            """
            CREATE TABLE IF NOT EXISTS HISTORIAL_ATENCION (
                id_historial    BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
                tipo            VARCHAR(20) NOT NULL CHECK (tipo IN ('incidencia', 'falla')),
                id_ticket       INT         NOT NULL,
                estado_anterior VARCHAR(20) NULL,
                estado_nuevo    VARCHAR(20) NOT NULL,
                id_admin        INT         NOT NULL,
                fecha_evento    TIMESTAMP   DEFAULT CURRENT_TIMESTAMP NOT NULL,
                CONSTRAINT fk_hist_atencion_admin FOREIGN KEY (id_admin)
                    REFERENCES ADMINISTRADORES (id_admin) ON DELETE CASCADE
            )
            """,
            "CREATE INDEX IF NOT EXISTS idx_hist_atencion_fecha ON HISTORIAL_ATENCION (fecha_evento)",
            "CREATE INDEX IF NOT EXISTS idx_hist_atencion_admin ON HISTORIAL_ATENCION (id_admin, fecha_evento)",
            "CREATE INDEX IF NOT EXISTS idx_inc_fecha_atencion ON INCIDENCIAS_CONTACTO (fecha_atencion)",
            "CREATE INDEX IF NOT EXISTS idx_falla_fecha_atencion ON FALLAS_WEB_SISTEMA (fecha_atencion)",
            "CREATE INDEX IF NOT EXISTS idx_aud_fecha_evento ON AMPLIACION_AUDITORIA (fecha_evento)");

    // ------------------------------------------------------------------ Utilidades
    private static Timestamp inicioDia(LocalDate d) {
        return Timestamp.from(d.atStartOfDay().toInstant(ZONA_PERU));
    }

    private static LocalDate hoyPeru() {
        return OffsetDateTime.now(ZONA_PERU).toLocalDate();
    }

    private static Instant instante(Timestamp t) {
        return t == null ? null : t.toInstant();
    }

    private static double minutos(Instant a, Instant b) {
        return Math.max(0.0, Duration.between(a, b).toMillis() / 60000.0);
    }

    // ------------------------------------------------------------------ Migración
    /** Crea columnas e historial de atención si no existen. Devuelve false si no se pudo (sin permisos, etc.). */
    public synchronized boolean asegurarEsquemaAtencion() {
        if (esquemaListo) return true;
        long ahora = System.currentTimeMillis();
        if (ahora - ultimoIntentoEsquema < 60_000) return false;      // no insistir en cada petición
        ultimoIntentoEsquema = ahora;
        boolean todoOk = true;
        try (Connection conn = DatabaseConfig.getDataSource().getConnection()) {
            for (String ddl : DDL_ATENCION) {
                try (Statement st = conn.createStatement()) {
                    st.execute(ddl.trim());
                } catch (SQLException e) {
                    todoOk = false;
                    System.err.println("[ATENCION] No se pudo aplicar: " + ddl.trim().split("\n")[0] + " -> " + e.getMessage());
                }
            }
        } catch (SQLException e) {
            System.err.println("[ATENCION] Sin conexión para la migración: " + e.getMessage());
            return false;
        }
        esquemaListo = todoOk;
        return todoOk;
    }

    // ------------------------------------------------------------------ Registro de atención
    private Integer idAdmin(Connection conn, String usuario) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(SQL_ID_ADMIN)) {
            ps.setString(1, usuario.trim());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : null;
            }
        }
    }

    private String estadoPrevio(String sql, int id) {
        try (Connection conn = DatabaseConfig.getDataSource().getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (SQLException e) {
            return null;
        }
    }

    public String estadoPrevioIncidencia(int id) { return estadoPrevio(SQL_ESTADO_INCIDENCIA, id); }

    public String estadoPrevioFalla(int idFalla) { return idFalla > 0 ? estadoPrevio(SQL_ESTADO_FALLA, idFalla) : null; }

    /** Devuelve el id de la falla (por id o por código único); -1 si no se encuentra. */
    public int resolverIdFalla(int id, String codigoUnico) {
        if (id > 0) return id;
        if (codigoUnico == null || codigoUnico.isBlank()) return -1;
        try (Connection conn = DatabaseConfig.getDataSource().getConnection();
             PreparedStatement ps = conn.prepareStatement(SQL_ID_FALLA_POR_CODIGO)) {
            ps.setString(1, codigoUnico.trim());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : -1;
            }
        } catch (SQLException e) {
            return -1;
        }
    }

    /** Llamar DESPUÉS de cambiar el estado con éxito. Nunca lanza: si falla, el cambio de estado sigue válido. */
    public void registrarAtencionIncidencia(int id, String estadoPrevio, String estadoNuevo, String usuario) {
        registrarAtencion("incidencia", id, estadoPrevio, estadoNuevo, usuario);
    }

    public void registrarAtencionFalla(int idFalla, String estadoPrevio, String estadoNuevo, String usuario) {
        if (idFalla > 0) registrarAtencion("falla", idFalla, estadoPrevio, estadoNuevo, usuario);
    }

    private void registrarAtencion(String tipo, int id, String previo, String nuevo, String usuario) {
        if (usuario == null || usuario.isBlank() || nuevo == null || nuevo.equalsIgnoreCase(previo)) return;
        if (!asegurarEsquemaAtencion()) return;
        try (Connection conn = DatabaseConfig.getDataSource().getConnection()) {
            Integer idAdm = idAdmin(conn, usuario);
            if (idAdm == null) return;                                // sin usuario válido no se puede atribuir
            if ("incidencia".equals(tipo)) {
                boolean resuelta = "resuelto".equalsIgnoreCase(nuevo);
                try (PreparedStatement ps = conn.prepareStatement(resuelta ? SQL_ATENDER_INCIDENCIA_RESUELTA : SQL_REABRIR_INCIDENCIA)) {
                    if (resuelta) { ps.setInt(1, idAdm); ps.setInt(2, id); } else { ps.setInt(1, id); }
                    ps.executeUpdate();
                }
            } else if (!"pendiente".equalsIgnoreCase(nuevo)) {
                try (PreparedStatement ps = conn.prepareStatement(SQL_ATENDER_FALLA)) {
                    ps.setInt(1, idAdm);
                    ps.setInt(2, "resuelto".equalsIgnoreCase(nuevo) ? 1 : 0);
                    ps.setInt(3, id);
                    ps.executeUpdate();
                }
            }
            try (PreparedStatement ps = conn.prepareStatement(SQL_INSERTAR_HISTORIAL)) {
                ps.setString(1, tipo);
                ps.setInt(2, id);
                if (previo == null) ps.setNull(3, java.sql.Types.VARCHAR); else ps.setString(3, previo);
                ps.setString(4, nuevo);
                ps.setInt(5, idAdm);
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            System.err.println("[ATENCION] No se registró la atención (" + tipo + " " + id + "): " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ Lista de colaboradores (campos extra)
    /** Agrega ultima_actividad, ultimo_latido (solo de hoy) y suspension a cada colaborador. No altera lo existente. */
    public void enriquecerUsuarios(List<Map<String, Object>> usuarios) {
        LocalDate hoy = hoyPeru();
        Map<String, String> ultimaFecha = new HashMap<>();
        Map<String, String> latidoHoy = new HashMap<>();
        try (Connection conn = DatabaseConfig.getDataSource().getConnection();
             PreparedStatement ps = conn.prepareStatement(SQL_ULTIMA_ACTIVIDAD)) {
            ps.setDate(1, Date.valueOf(hoy.minusDays(90)));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String u = rs.getString("usuario");
                    Date f = rs.getDate("ultima");
                    if (f != null) ultimaFecha.put(u, f.toString());
                    Timestamp l = rs.getTimestamp("latido");
                    if (l != null && l.toInstant().atOffset(ZONA_PERU).toLocalDate().equals(hoy)) {
                        latidoHoy.put(u, l.toInstant().toString());
                    }
                }
            }
        } catch (SQLException e) {
            System.err.println("[EQUIPO] Sin última actividad: " + e.getMessage());
        }

        Map<String, Map<String, Object>> suspensiones = new HashMap<>();
        boolean hayAlguno = usuarios.stream().anyMatch(u -> "suspendido".equalsIgnoreCase(String.valueOf(u.get("estado"))));
        if (hayAlguno) {
            try (Connection conn = DatabaseConfig.getDataSource().getConnection();
                 PreparedStatement ps = conn.prepareStatement(SQL_SUSPENSIONES);
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String objetivo = rs.getString("objetivo");
                    if (suspensiones.containsKey(objetivo)) continue;       // ya está la más reciente
                    String detalle = rs.getString("detalle");
                    String motivo = detalle == null || detalle.startsWith("Cambio de estado aplicado") ? "" : detalle;
                    Timestamp t = rs.getTimestamp("fecha_evento");
                    Map<String, Object> s = new LinkedHashMap<>();
                    s.put("por", rs.getString("ejecutor"));
                    s.put("motivo", motivo);
                    s.put("fecha", t == null ? null : t.toInstant().toString());
                    suspensiones.put(objetivo, s);
                }
            } catch (SQLException e) {
                System.err.println("[EQUIPO] Sin datos de suspensión: " + e.getMessage());
            }
        }

        for (Map<String, Object> u : usuarios) {
            String nombre = String.valueOf(u.get("usuario"));
            u.put("ultima_actividad", ultimaFecha.get(nombre));
            u.put("ultimo_latido", latidoHoy.get(nombre));
            boolean susp = "suspendido".equalsIgnoreCase(String.valueOf(u.get("estado")));
            u.put("suspension", susp ? suspensiones.get(nombre) : null);
        }
    }

    // ------------------------------------------------------------------ Auditoría filtrada y paginada
    public Map<String, Object> auditoriaFiltrada(String desdeTxt, String hastaTxt, String accion,
                                                 String colaborador, String texto, String paginaTxt, String porPaginaTxt) {
        int pagina, porPagina;
        LocalDate desde = null, hasta = null;
        try {
            pagina = Math.max(1, paginaTxt == null || paginaTxt.isBlank() ? 1 : Integer.parseInt(paginaTxt.trim()));
            porPagina = Math.min(200, Math.max(5, porPaginaTxt == null || porPaginaTxt.isBlank() ? 20 : Integer.parseInt(porPaginaTxt.trim())));
            if (desdeTxt != null && !desdeTxt.isBlank()) desde = LocalDate.parse(desdeTxt.trim());
            if (hastaTxt != null && !hastaTxt.isBlank()) hasta = LocalDate.parse(hastaTxt.trim());
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Parámetros de fecha o página no válidos.");
        }
        String acc = accion == null ? "" : accion.trim();
        if (!acc.isEmpty() && !COND_ACCION.containsKey(acc)) throw new IllegalArgumentException("Acción no válida.");
        String colab = AnalisisEquipo.sanitizarLike(colaborador, 60);
        String txt = AnalisisEquipo.sanitizarLike(texto, 80);

        StringBuilder where = new StringBuilder();
        List<Object> params = new ArrayList<>();
        if (desde != null) { where.append(COND_DESDE); params.add(inicioDia(desde)); }
        if (hasta != null) { where.append(COND_HASTA); params.add(inicioDia(hasta.plusDays(1))); }
        if (!acc.isEmpty()) where.append(COND_ACCION.get(acc));
        if (!colab.isEmpty()) { where.append(COND_COLABORADOR); params.add("%" + colab + "%"); params.add("%" + colab + "%"); }
        if (!txt.isEmpty()) { where.append(COND_TEXTO); for (int i = 0; i < 3; i++) params.add("%" + txt + "%"); }

        String sqlConteo = SQL_AUD_SELECT_CONTEO + SQL_AUD_FROM + where;
        // OFFSET/FETCH llevan enteros ya validados (no texto del usuario), por eso van en línea
        String sqlPagina = SQL_AUD_SELECT_PAGINA + SQL_AUD_FROM + where + SQL_AUD_ORDEN
                + " OFFSET " + ((pagina - 1) * porPagina) + " ROWS FETCH NEXT " + porPaginaSeguro(porPagina) + " ROWS ONLY";

        List<Map<String, Object>> registros = new ArrayList<>();
        long total;
        try (Connection conn = DatabaseConfig.getDataSource().getConnection()) {
            try (PreparedStatement ps = conn.prepareStatement(sqlConteo)) {
                enlazar(ps, params);
                try (ResultSet rs = ps.executeQuery()) { rs.next(); total = rs.getLong(1); }
            }
            try (PreparedStatement ps = conn.prepareStatement(sqlPagina)) {
                enlazar(ps, params);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String detalle = rs.getString("detalle");
                        Timestamp t = rs.getTimestamp("fecha_evento");
                        Map<String, Object> f = new LinkedHashMap<>();
                        f.put("id", rs.getInt("id_auditoria"));
                        f.put("usuario_afectado", rs.getString("usuario_objetivo"));
                        f.put("accion", AnalisisEquipo.normalizarAccion(rs.getString("accion"), detalle));
                        f.put("realizado_por", rs.getString("admin_usuario"));
                        f.put("motivo", detalle);
                        f.put("fecha", t == null ? null : t.toInstant().toString());
                        registros.add(f);
                    }
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("No se pudo consultar la auditoría: " + e.getMessage(), e);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("success", true);
        out.put("registros", registros);
        out.put("total", total);
        out.put("pagina", pagina);
        out.put("por_pagina", porPagina);
        out.put("paginas", Math.max(1, (int) ((total + porPagina - 1) / porPagina)));
        out.put("acciones", ACCIONES_VALIDAS);
        return out;
    }

    private static int porPaginaSeguro(int v) { return Math.min(200, Math.max(5, v)); }

    private static void enlazar(PreparedStatement ps, List<Object> params) throws SQLException {
        for (int i = 0; i < params.size(); i++) {
            Object p = params.get(i);
            if (p instanceof Timestamp t) ps.setTimestamp(i + 1, t); else ps.setString(i + 1, String.valueOf(p));
        }
    }

    // ------------------------------------------------------------------ Dashboard de equipo
    public Map<String, Object> equipo(String periodo, String ref) {
        String per = "anio".equals(periodo) ? "anio" : "mes";
        LocalDate hoy = hoyPeru();
        Rango rango = AnalisisEquipo.rangoPeriodo(per, ref, hoy);          // lanza IllegalArgumentException si ref es inválida
        String clave = per + "|" + rango.etiqueta();
        Object[] enCache = cache.get(clave);
        if (enCache != null && System.currentTimeMillis() - (Long) enCache[0] < TTL_MS) {
            @SuppressWarnings("unchecked") Map<String, Object> m = (Map<String, Object>) enCache[1];
            return m;
        }
        Map<String, Object> resultado;
        try {
            resultado = calcularEquipo(per, rango, hoy);
        } catch (SQLException e) {
            throw new IllegalStateException("No se pudo calcular la actividad del equipo: " + e.getMessage(), e);
        }
        if (cache.size() > 20) cache.clear();
        cache.put(clave, new Object[]{System.currentTimeMillis(), resultado});
        return resultado;
    }

    private Map<String, Object> calcularEquipo(String periodo, Rango rango, LocalDate hoy) throws SQLException {
        LocalDate semanaFin = AnalisisEquipo.inicioSemana(hoy).minusWeeks(1);          // última semana completa
        LocalDate inicioHist = semanaFin.minusWeeks(AnalisisEquipo.SEMANAS_HISTORIAL - 1L);
        LocalDate consultaDesde = rango.desde().isBefore(inicioHist) ? rango.desde() : inicioHist;
        LocalDate consultaHasta = rango.hastaExclusivo().isAfter(hoy.plusDays(1)) ? rango.hastaExclusivo() : hoy.plusDays(1);

        Map<String, String> nombres = new HashMap<>();
        Set<String> superadmins = new TreeSet<>();
        Map<String, double[]> acumulado = new LinkedHashMap<>();                       // usuario -> {segundos, dias}
        int[] horas = new int[24];
        Map<String, Map<LocalDate, Double>> segundosPorDia = new LinkedHashMap<>();
        Map<String, Map<String, Integer>> acciones = new LinkedHashMap<>();

        try (Connection conn = DatabaseConfig.getDataSource().getConnection()) {
            try (PreparedStatement ps = conn.prepareStatement(SQL_ADMINS); ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String u = rs.getString("usuario");
                    String n = rs.getString("nombre_completo");
                    nombres.put(u, n == null || n.isBlank() ? u : n);
                    if ("superadmin".equalsIgnoreCase(rs.getString("codigo_rol"))) superadmins.add(u);
                }
            }
            try (PreparedStatement ps = conn.prepareStatement(SQL_ACTIVIDAD)) {
                ps.setDate(1, Date.valueOf(consultaDesde));
                ps.setDate(2, Date.valueOf(consultaHasta));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String u = rs.getString("usuario");
                        LocalDate d = rs.getDate("fecha_sesion").toLocalDate();
                        int seg = rs.getInt("tiempo_segundos");
                        segundosPorDia.computeIfAbsent(u, k -> new LinkedHashMap<>()).merge(d, (double) seg, Double::sum);
                        if (!d.isBefore(rango.desde()) && d.isBefore(rango.hastaExclusivo()) && seg > 0) {
                            double[] a = acumulado.computeIfAbsent(u, k -> new double[2]);
                            a[0] += seg;
                            a[1] += 1;
                            Timestamp pi = rs.getTimestamp("primer_ingreso");
                            if (pi != null) horas[pi.toInstant().atOffset(ZONA_PERU).getHour()]++;
                        }
                    }
                }
            }
            try (PreparedStatement ps = conn.prepareStatement(SQL_ACCIONES_PERIODO)) {
                ps.setTimestamp(1, inicioDia(rango.desde()));
                ps.setTimestamp(2, inicioDia(rango.hastaExclusivo()));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String actor = rs.getString("usuario");
                        if (actor == null || actor.equalsIgnoreCase("sistema") || actor.equalsIgnoreCase("sistema_sync")) continue;
                        String clave = AnalisisEquipo.normalizarAccion(rs.getString("accion"), rs.getString("detalle"));
                        acciones.computeIfAbsent(actor, k -> new LinkedHashMap<>()).merge(clave, 1, Integer::sum);
                    }
                }
            }
        }

        long diasTranscurridos = Math.max(1, java.time.temporal.ChronoUnit.DAYS.between(rango.desde(),
                rango.hastaExclusivo().isAfter(hoy.plusDays(1)) ? hoy.plusDays(1) : rango.hastaExclusivo()));

        List<Map<String, Object>> ranking = new ArrayList<>();
        for (Map.Entry<String, double[]> e : acumulado.entrySet()) {
            double seg = e.getValue()[0], dias = e.getValue()[1];
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("usuario", e.getKey());
            f.put("nombre", nombres.getOrDefault(e.getKey(), e.getKey()));
            f.put("segundos", (long) seg);
            f.put("dias_activos", (int) dias);
            f.put("promedio_dia_seg", dias > 0 ? (long) (seg / dias) : 0L);
            f.put("constancia_pct", AnalisisEquipo.redondear(100.0 * dias / diasTranscurridos, 1));
            ranking.add(f);
        }
        ranking.sort((a, b) -> Long.compare((Long) b.get("segundos"), (Long) a.get("segundos")));

        List<Map<String, Object>> listaAcciones = new ArrayList<>();
        for (Map.Entry<String, Map<String, Integer>> e : acciones.entrySet()) {
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("usuario", e.getKey());
            f.put("nombre", nombres.getOrDefault(e.getKey(), e.getKey()));
            f.put("total", e.getValue().values().stream().mapToInt(Integer::intValue).sum());
            f.put("por_accion", e.getValue());
            listaAcciones.add(f);
        }
        listaAcciones.sort((a, b) -> Integer.compare((Integer) b.get("total"), (Integer) a.get("total")));

        List<Map<String, Object>> proyeccion = new ArrayList<>();
        for (Map.Entry<String, double[]> e : AnalisisEquipo.seriesSemanales(segundosPorDia, semanaFin).entrySet()) {
            double[] horasSemana = Arrays.stream(e.getValue()).map(s -> s / 3600.0).toArray();
            Map<String, Object> p = AnalisisEquipo.proyectar(horasSemana);
            p.put("usuario", e.getKey());
            p.put("nombre", nombres.getOrDefault(e.getKey(), e.getKey()));
            p.put("metrica", "horas_activas");
            proyeccion.add(p);
        }
        proyeccion.sort(Comparator.comparing((Map<String, Object> p) -> !"ok".equals(p.get("estado")))
                .thenComparing(p -> -((Number) p.getOrDefault("proximo_mes", 0)).doubleValue()));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("success", true);
        out.put("periodo", periodo);
        out.put("etiqueta", rango.etiqueta());
        out.put("desde", rango.desde().toString());
        out.put("hasta", rango.hastaExclusivo().minusDays(1).toString());
        out.put("min_semanas", AnalisisEquipo.MIN_SEMANAS);
        out.put("ranking", ranking);
        out.put("horas_pico", Arrays.stream(horas).boxed().toList());
        out.put("acciones", listaAcciones);
        out.put("proyeccion", proyeccion);
        out.put("tickets", ticketsEquipo(rango, nombres, semanaFin, inicioHist));
        out.put("usuarios_superadmin", new ArrayList<>(superadmins));
        return out;
    }

    /** Métricas de atención. Si la migración no se pudo aplicar, lo dice sin romper el resto del dashboard. */
    private Map<String, Object> ticketsEquipo(Rango rango, Map<String, String> nombres, LocalDate semanaFin, LocalDate inicioHist) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (!asegurarEsquemaAtencion()) {
            out.put("disponible", false);
            out.put("mensaje", "No se pudo preparar el registro de atención en la base de datos. Ejecuta database/atencion_tickets_3fn.sql con un usuario con permisos.");
            return out;
        }
        Map<String, Set<String>> atendidos = new LinkedHashMap<>();
        Map<String, Map<LocalDate, Set<String>>> porDia = new LinkedHashMap<>();
        Map<String, List<Double>> primera = new HashMap<>(), resol = new HashMap<>();
        Map<String, int[]> r24 = new HashMap<>();                                    // {resueltos, dentro de 24 h}
        long pendInc, pendFal;
        LocalDate iniHist = rango.desde().isBefore(inicioHist) ? rango.desde() : inicioHist;

        try (Connection conn = DatabaseConfig.getDataSource().getConnection()) {
            try (PreparedStatement ps = conn.prepareStatement(SQL_HISTORIAL_ATENCION)) {
                ps.setTimestamp(1, inicioDia(iniHist));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        if (!ESTADOS_ATENDIDO.contains(String.valueOf(rs.getString("estado_nuevo")))) continue;
                        String u = rs.getString("usuario");
                        String ticket = rs.getString("tipo") + ":" + rs.getInt("id_ticket");
                        LocalDate dia = rs.getTimestamp("fecha_evento").toInstant().atOffset(ZONA_PERU).toLocalDate();
                        porDia.computeIfAbsent(u, k -> new LinkedHashMap<>()).computeIfAbsent(dia, k -> new HashSet<>()).add(ticket);
                        if (!dia.isBefore(rango.desde()) && dia.isBefore(rango.hastaExclusivo())) {
                            atendidos.computeIfAbsent(u, k -> new HashSet<>()).add(ticket);
                        }
                    }
                }
            }
            for (String sql : List.of(SQL_INCIDENCIAS_ATENDIDAS, SQL_FALLAS_ATENDIDAS)) {
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setTimestamp(1, inicioDia(rango.desde()));
                    ps.setTimestamp(2, inicioDia(rango.hastaExclusivo()));
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            String u = rs.getString("usuario");
                            Instant inicio = instante(rs.getTimestamp("fecha_inicio"));
                            Instant atencion = instante(rs.getTimestamp("fecha_atencion"));
                            Instant resolucion = instante(rs.getTimestamp("fecha_resolucion"));
                            if (inicio == null) continue;
                            if (atencion != null) primera.computeIfAbsent(u, k -> new ArrayList<>()).add(minutos(inicio, atencion));
                            if (resolucion != null) {
                                double m = minutos(inicio, resolucion);
                                resol.computeIfAbsent(u, k -> new ArrayList<>()).add(m);
                                int[] c = r24.computeIfAbsent(u, k -> new int[2]);
                                c[0]++;
                                if (m <= 24 * 60) c[1]++;
                            }
                        }
                    }
                }
            }
            try (PreparedStatement ps = conn.prepareStatement(SQL_PENDIENTES_INCIDENCIAS); ResultSet rs = ps.executeQuery()) {
                rs.next(); pendInc = rs.getLong(1);
            }
            try (PreparedStatement ps = conn.prepareStatement(SQL_PENDIENTES_FALLAS); ResultSet rs = ps.executeQuery()) {
                rs.next(); pendFal = rs.getLong(1);
            }
        } catch (SQLException e) {
            System.err.println("[EQUIPO] Tickets no disponibles: " + e.getMessage());
            out.put("disponible", false);
            out.put("mensaje", "No se pudo leer el registro de atención de tickets.");
            return out;
        }

        Set<String> usuarios = new TreeSet<>();
        usuarios.addAll(atendidos.keySet());
        usuarios.addAll(primera.keySet());
        usuarios.addAll(resol.keySet());
        List<Map<String, Object>> filas = new ArrayList<>();
        for (String u : usuarios) {
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("usuario", u);
            f.put("nombre", nombres.getOrDefault(u, u));
            f.put("atendidos", atendidos.getOrDefault(u, Set.of()).size());
            f.put("primera_resp_min", promedio(primera.get(u)));
            f.put("resolucion_min", promedio(resol.get(u)));
            int[] c = r24.get(u);
            f.put("pct_24h", c == null || c[0] == 0 ? null : AnalisisEquipo.redondear(100.0 * c[1] / c[0], 1));
            filas.add(f);
        }
        filas.sort((a, b) -> Integer.compare((Integer) b.get("atendidos"), (Integer) a.get("atendidos")));

        Map<String, Map<LocalDate, Double>> conteoPorDia = new LinkedHashMap<>();
        porDia.forEach((u, dias) -> {
            Map<LocalDate, Double> m = new LinkedHashMap<>();
            dias.forEach((d, set) -> m.put(d, (double) set.size()));
            conteoPorDia.put(u, m);
        });
        List<Map<String, Object>> proy = new ArrayList<>();
        for (Map.Entry<String, double[]> e : AnalisisEquipo.seriesSemanales(conteoPorDia, semanaFin).entrySet()) {
            Map<String, Object> p = AnalisisEquipo.proyectar(e.getValue());
            p.put("usuario", e.getKey());
            p.put("nombre", nombres.getOrDefault(e.getKey(), e.getKey()));
            p.put("metrica", "tickets");
            proy.add(p);
        }
        Map<String, Object> pendientes = new LinkedHashMap<>();
        pendientes.put("incidencias", pendInc);
        pendientes.put("fallas", pendFal);
        out.put("disponible", true);
        out.put("filas", filas);
        out.put("proyeccion", proy);
        out.put("pendientes", pendientes);
        return out;
    }

    private static Double promedio(List<Double> v) {
        if (v == null || v.isEmpty()) return null;
        return AnalisisEquipo.redondear(v.stream().mapToDouble(Double::doubleValue).sum() / v.size(), 1);
    }
}
