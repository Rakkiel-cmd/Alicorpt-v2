package pe.com.alicorp.bi.modulos.chatbot_asistente;

import pe.com.alicorp.bi.config.DatabaseConfig;
import pe.com.alicorp.bi.modulos.acceso_biometria.ServicioActividadEquipo;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static pe.com.alicorp.bi.modulos.chatbot_asistente.ChatbotAnalistaService.hoyPeru;
import static pe.com.alicorp.bi.modulos.chatbot_asistente.ChatbotAnalistaService.inicioDia;
import static pe.com.alicorp.bi.modulos.chatbot_asistente.MotorIntenciones.*;

/**
 * Chatbot de administradores (superadmin). Solo LEE y resume: último latido y conexión del equipo,
 * avance de analistas, resumen del día, solicitudes/cambios de llave y alertas. Nunca muestra
 * claves, vectores biométricos ni contraseñas.
 */
public class ChatbotAdminService {

    public static List<String> chipsIniciales() {
        return List.of("Resumen de hoy", "Último latido del equipo", "¿Quién está conectado?", "Avance de los analistas",
                "Cambios de llave", "Alertas pendientes");
    }

    public Map<String, Object> responder(String mensajeOriginal, String usuario) {
        String norm = normalizar(mensajeOriginal);
        if (norm.isEmpty()) return RespuestaChat.texto("Escribe tu consulta o toca una opción.").build();
        ServicioActividadEquipo.compartido().asegurarEsquemaAtencion();
        try (Connection c = DatabaseConfig.getDataSource().getConnection()) {
            if (tiene(norm, "llave*", "clave*", "cambio de llave", "solicitud*")) return llaves(c);
            if (tiene(norm, "alerta*", "pendiente*", "urgente*", "grave*", "critic*", "atencion")) return alertas(c);
            if (tiene(norm, "latido*", "ultima actividad", "ultimo acceso", "actividad", "conectado*", "conectados", "en linea", "online", "quien esta")) {
                return latidos(c, norm);
            }
            if (tiene(norm, "avance*", "analista*", "progreso", "rendimiento", "productividad", "semana")) return avance(c);
            if (tiene(norm, "resumen", "hoy", "que paso", "ultimamente", "novedad*", "dia")) return resumenHoy(c);
            if (tiene(norm, "hola", "buenas", "buenos dias", "buenas tardes", "hey")) {
                return RespuestaChat.texto("Hola. Puedo darte el resumen del día, la actividad del equipo, los cambios de llave y las alertas.")
                        .chip("Resumen de hoy").chip("Último latido del equipo").build();
            }
            if (tiene(norm, "gracias", "ok", "listo", "chau")) {
                return RespuestaChat.texto("A la orden.").chip("Resumen de hoy").build();
            }
        } catch (SQLException e) {
            System.err.println("[CHATBOT-ADMIN] " + e.getMessage());
            return RespuestaChat.texto("No pude consultar los datos en este momento. Intenta nuevamente en unos segundos.").build();
        }
        Optional<String> ia = AsistenteIAOpcional.compartido().responder("admin", usuario, mensajeOriginal);
        if (ia.isPresent()) return RespuestaChat.texto(ia.get()).origen("ia").chip("Resumen de hoy").chip("Alertas pendientes").build();
        return RespuestaChat.texto("No tengo una respuesta para eso. Puedo resumirte el día, la actividad del equipo, los cambios de llave y las alertas.")
                .chip("Resumen de hoy").chip("Último latido del equipo").chip("Cambios de llave").build();
    }

    // ------------------------------------------------------------------ Consultas
    private Map<String, Object> resumenHoy(Connection c) throws SQLException {
        LocalDate hoy = hoyPeru();
        Timestamp desde = inicioDia(hoy);
        int nuevosMsg = contar(c, "SELECT COUNT(*) FROM INCIDENCIAS_CONTACTO WHERE fecha_creacion >= ?", desde);
        int nuevasFallas = contar(c, "SELECT COUNT(*) FROM FALLAS_WEB_SISTEMA WHERE fecha_reporte >= ?", desde);
        int resHoy = contar(c, "SELECT COUNT(*) FROM HISTORIAL_ATENCION WHERE estado_nuevo = 'resuelto' AND fecha_evento >= ?", desde);
        int pend = contar(c, "SELECT COUNT(*) FROM INCIDENCIAS_CONTACTO WHERE estado = 'pendiente'", null)
                + contar(c, "SELECT COUNT(*) FROM FALLAS_WEB_SISTEMA WHERE estado = 'pendiente'", null);
        int eventos = contar(c, "SELECT COUNT(*) FROM AMPLIACION_AUDITORIA WHERE fecha_evento >= ?", desde);
        int activos = contar(c, "SELECT COUNT(*) FROM ACTIVIDAD_USUARIOS WHERE fecha_sesion = ?", java.sql.Date.valueOf(hoy));

        StringBuilder t = new StringBuilder("**Resumen de hoy**\n");
        t.append("• Tickets nuevos: ").append(nuevosMsg).append(" mensajes de clientes y ").append(nuevasFallas).append(" fallas del sistema.\n");
        t.append("• Tickets resueltos: ").append(resHoy).append(". Pendientes ahora: ").append(pend).append(".\n");
        t.append("• Miembros del equipo con actividad hoy: ").append(activos).append(".\n");
        t.append("• Eventos de auditoría hoy: ").append(eventos).append(".");

        List<String> porAnalista = new ArrayList<>();
        String sql = "SELECT a.nombre_completo, COUNT(*) FROM HISTORIAL_ATENCION h JOIN ADMINISTRADORES a ON a.id_admin = h.id_admin "
                + "WHERE h.estado_nuevo = 'resuelto' AND h.fecha_evento >= ? GROUP BY a.nombre_completo ORDER BY COUNT(*) DESC";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setTimestamp(1, desde);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) porAnalista.add(rs.getString(1) + ": " + rs.getInt(2));
            }
        }
        if (!porAnalista.isEmpty()) t.append("\n• Resueltos por persona: ").append(String.join(" · ", porAnalista)).append(".");
        return RespuestaChat.texto(t.toString())
                .chip("Último latido del equipo").chip("Cambios de llave").chip("Alertas pendientes").build();
    }

    private Map<String, Object> latidos(Connection c, String norm) throws SQLException {
        String sql = "SELECT a.usuario, a.nombre_completo, r.codigo_rol, e.codigo_estado, m.l, t.tiempo_segundos "
                + "FROM ADMINISTRADORES a JOIN ROLES_SEGURIDAD r ON a.id_rol = r.id_rol JOIN ESTADOS_USUARIO e ON a.id_estado = e.id_estado "
                + "LEFT JOIN (SELECT id_admin, MAX(ultimo_latido) l FROM ACTIVIDAD_USUARIOS GROUP BY id_admin) m ON m.id_admin = a.id_admin "
                + "LEFT JOIN (SELECT id_admin, tiempo_segundos FROM ACTIVIDAD_USUARIOS WHERE fecha_sesion = ?) t ON t.id_admin = a.id_admin "
                + "ORDER BY m.l DESC";
        List<String> filas = new ArrayList<>();
        int conectados = 0;
        String buscado = sinRelleno(norm, java.util.Set.of("ultimo", "latido", "latidos", "del", "de", "la", "el", "equipo", "quien", "esta",
                "conectado", "conectados", "actividad", "ultima", "acceso", "en", "linea", "online", "cuando", "que", "hizo", "paso", "ultimamente", "analista", "analistas", "los", "las", "y"));
        boolean filtrar = !buscado.isEmpty();
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setDate(1, java.sql.Date.valueOf(hoyPeru()));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String nombre = rs.getString("nombre_completo");
                    String usr = rs.getString("usuario");
                    if (filtrar && !normalizar(nombre + " " + usr).contains(buscado)) continue;
                    Timestamp l = rs.getTimestamp("l");
                    boolean on = l != null && (System.currentTimeMillis() - l.getTime()) <= 60_000;
                    if (on) conectados++;
                    int seg = rs.getInt("tiempo_segundos");
                    String estado = on ? "🟢 conectado" : (l == null ? "sin actividad registrada" : "último latido hace " + hace(l));
                    filas.add("• " + nombre + " (" + rs.getString("codigo_rol") + ") — " + estado + (seg > 0 ? " · hoy " + (seg / 3600) + "h " + ((seg % 3600) / 60) + "m" : ""));
                    if (filas.size() >= 12) break;
                }
            }
        }
        if (filas.isEmpty()) return RespuestaChat.texto("No encontré a nadie con ese nombre en el equipo.").chip("Último latido del equipo").build();
        String cab = filtrar ? "**Actividad encontrada**\n" : "**Equipo** · " + conectados + " conectado(s) ahora\n";
        return RespuestaChat.texto(cab + String.join("\n", filas))
                .accion("👥 Ir a Administración", "vista", "administracion")
                .chip("Resumen de hoy").chip("Avance de los analistas").build();
    }

    private Map<String, Object> avance(Connection c) throws SQLException {
        Timestamp semana = inicioDia(hoyPeru().minusDays(6));
        Timestamp hoy = inicioDia(hoyPeru());
        String sql = "SELECT a.nombre_completo, "
                + "SUM(CASE WHEN h.fecha_evento >= ? THEN 1 ELSE 0 END) AS hoy, COUNT(*) AS semana "
                + "FROM HISTORIAL_ATENCION h JOIN ADMINISTRADORES a ON a.id_admin = h.id_admin "
                + "WHERE h.estado_nuevo = 'resuelto' AND h.fecha_evento >= ? GROUP BY a.nombre_completo ORDER BY COUNT(*) DESC";
        List<String> filas = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setTimestamp(1, hoy);
            ps.setTimestamp(2, semana);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) filas.add("• " + rs.getString(1) + ": " + rs.getInt("hoy") + " hoy · " + rs.getInt("semana") + " en 7 días");
            }
        }
        int pend = contar(c, "SELECT COUNT(*) FROM INCIDENCIAS_CONTACTO WHERE estado = 'pendiente'", null)
                + contar(c, "SELECT COUNT(*) FROM FALLAS_WEB_SISTEMA WHERE estado = 'pendiente'", null);
        if (filas.isEmpty()) {
            return RespuestaChat.texto("Aún no hay tickets resueltos registrados en los últimos 7 días (el historial por persona cuenta desde que se activó el registro). Pendientes ahora: " + pend + ".")
                    .chip("Resumen de hoy").build();
        }
        return RespuestaChat.texto("**Avance de analistas (tickets resueltos)**\n" + String.join("\n", filas) + "\nPendientes ahora: " + pend + ".")
                .chip("Resumen de hoy").chip("Último latido del equipo").build();
    }

    private Map<String, Object> llaves(Connection c) throws SQLException {
        String sql = "SELECT s.estado_solicitud, s.created_at, s.resolved_at, a.nombre_completo FROM SOLICITUDES_CAMBIO_CLAVE s "
                + "JOIN ADMINISTRADORES a ON a.id_admin = s.id_solicitante ORDER BY s.created_at DESC";
        List<String> filas = new ArrayList<>();
        int pend = 0;
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setMaxRows(5);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String est = rs.getString("estado_solicitud");
                    if ("pendiente".equals(est)) pend++;
                    filas.add("• " + rs.getString("nombre_completo") + " — " + est + " · hace " + hace(rs.getTimestamp("created_at")));
                }
            }
        }
        int cambiosHoy = contar(c, "SELECT COUNT(*) FROM AMPLIACION_AUDITORIA WHERE accion = 'cambio_clave' AND fecha_evento >= ?", inicioDia(hoyPeru()));
        String t = filas.isEmpty()
                ? "No hay solicitudes de cambio de llave registradas."
                : "**Solicitudes de cambio de llave (últimas 5)**\n" + String.join("\n", filas);
        t += "\nCambios de clave registrados hoy en auditoría: " + cambiosHoy + ".";
        if (pend > 0) t += "\n⚠️ Hay " + pend + " solicitud(es) pendiente(s); la aprobación la debe hacer un segundo superadmin.";
        return RespuestaChat.texto(t).accion("🔐 Ir a Seguridad", "vista", "seguridad")
                .chip("Resumen de hoy").chip("Alertas pendientes").build();
    }

    private Map<String, Object> alertas(Connection c) throws SQLException {
        int llaves = contar(c, "SELECT COUNT(*) FROM SOLICITUDES_CAMBIO_CLAVE WHERE estado_solicitud = 'pendiente'", null);
        int graves = contar(c, "SELECT COUNT(*) FROM FALLAS_WEB_SISTEMA WHERE estado = 'pendiente' AND UPPER(gravedad) IN ('ALTA', 'CRITICA', 'CRÍTICA')", null);
        int msgs = contar(c, "SELECT COUNT(*) FROM INCIDENCIAS_CONTACTO WHERE estado = 'pendiente'", null);
        int fallas = contar(c, "SELECT COUNT(*) FROM FALLAS_WEB_SISTEMA WHERE estado = 'pendiente'", null);
        int susp = contar(c, "SELECT COUNT(*) FROM AMPLIACION_AUDITORIA WHERE accion = 'suspension' AND fecha_evento >= ?", inicioDia(hoyPeru()));
        List<String> a = new ArrayList<>();
        if (llaves > 0) a.add("🔐 " + llaves + " solicitud(es) de cambio de llave sin resolver.");
        if (graves > 0) a.add("🛑 " + graves + " falla(s) del sistema de gravedad alta pendientes.");
        if (susp > 0) a.add("🚫 " + susp + " suspensión(es) de usuarios hoy.");
        a.add("🎫 Tickets pendientes: " + msgs + " mensajes de clientes y " + fallas + " fallas.");
        return RespuestaChat.texto("**Alertas**\n" + String.join("\n", a))
                .chip("Cambios de llave").chip("Avance de los analistas").build();
    }

    // ------------------------------------------------------------------ Apoyos
    private static String hace(Timestamp f) {
        if (f == null) return "—";
        long min = Math.max(0, Duration.between(f.toInstant(), Instant.now()).toMinutes());
        if (min < 1) return "menos de 1 min";
        if (min < 60) return min + " min";
        if (min < 1440) return (min / 60) + " h";
        return (min / 1440) + " d";
    }

    private static int contar(Connection c, String sql, Object param) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            if (param instanceof Timestamp t) ps.setTimestamp(1, t);
            else if (param instanceof java.sql.Date d) ps.setDate(1, d);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }
}
