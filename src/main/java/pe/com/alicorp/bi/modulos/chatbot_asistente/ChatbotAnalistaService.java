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
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static pe.com.alicorp.bi.modulos.chatbot_asistente.MotorIntenciones.*;

/**
 * Chatbot de analistas: tickets en tiempo real (pendientes / resueltos por el analista),
 * avisos periódicos con el avance y explicación de las pantallas y resultados del dashboard.
 */
public class ChatbotAnalistaService {

    static final ZoneOffset ZONA_PERU = ZoneOffset.ofHours(-5);

    private static final String[] FRASES = {
            "Sigue con el mismo ritmo.", "Un ticket a la vez.", "Buen avance.", "Constancia y orden.",
            "Vas bien, continúa.", "Paso a paso se completa.", "Buen trabajo hasta ahora."};
    private int turnoFrase = 0;

    static LocalDate hoyPeru() { return OffsetDateTime.now(ZONA_PERU).toLocalDate(); }
    static Timestamp inicioDia(LocalDate d) { return Timestamp.from(d.atStartOfDay().toInstant(ZONA_PERU)); }

    public static List<String> chipsIniciales() {
        return List.of("¿Cuántos tickets pendientes hay?", "¿Cuántos he resuelto hoy?", "Mi avance de la semana",
                "Explícame esta pantalla", "¿Qué es el R²?", "Ver tickets más antiguos");
    }

    // ------------------------------------------------------------------ Datos en tiempo real
    /** Conteos actuales de tickets y avance del analista. Barato: 5 consultas COUNT. */
    public Map<String, Object> pulso(String usuario) {
        Map<String, Object> m = new LinkedHashMap<>();
        ServicioActividadEquipo.compartido().asegurarEsquemaAtencion();
        LocalDate hoy = hoyPeru();
        try (Connection c = DatabaseConfig.getDataSource().getConnection()) {
            int incPend = contar(c, "SELECT COUNT(*) FROM INCIDENCIAS_CONTACTO WHERE estado = 'pendiente'");
            int fallaPend = contar(c, "SELECT COUNT(*) FROM FALLAS_WEB_SISTEMA WHERE estado = 'pendiente'");
            int enProceso = contar(c, "SELECT COUNT(*) FROM INCIDENCIAS_CONTACTO WHERE estado = 'en_revision'")
                    + contar(c, "SELECT COUNT(*) FROM FALLAS_WEB_SISTEMA WHERE estado = 'en_revision'");
            Integer idAdmin = idAdmin(c, usuario);
            int hoyMios = idAdmin == null ? 0 : resueltos(c, idAdmin, inicioDia(hoy));
            int semanaMios = idAdmin == null ? 0 : resueltos(c, idAdmin, inicioDia(hoy.minusDays(6)));
            int totalMios = idAdmin == null ? 0 : resueltos(c, idAdmin, null);
            int hoyEquipo = resueltos(c, null, inicioDia(hoy));
            m.put("incidencias_pendientes", incPend);
            m.put("fallas_pendientes", fallaPend);
            m.put("pendientes", incPend + fallaPend);
            m.put("en_proceso", enProceso);
            m.put("resueltos_hoy", hoyMios);
            m.put("resueltos_semana", semanaMios);
            m.put("resueltos_total", totalMios);
            m.put("resueltos_equipo_hoy", hoyEquipo);
            m.put("success", true);
        } catch (SQLException e) {
            System.err.println("[CHATBOT-ANALISTA] pulso: " + e.getMessage());
            m.put("success", false);
        }
        return m;
    }

    /** Mensaje corto para la notificación emergente periódica (se desvanece en el navegador). */
    public Map<String, Object> mensajePulso(String usuario) {
        Map<String, Object> p = pulso(usuario);
        if (!Boolean.TRUE.equals(p.get("success"))) return p;
        int pend = (int) p.get("pendientes");
        int hoy = (int) p.get("resueltos_hoy");
        String frase = FRASES[Math.floorMod(turnoFrase++, FRASES.length)];
        String texto;
        if (pend == 0) {
            texto = "No hay tickets pendientes. Resueltos por ti hoy: " + hoy + ".";
        } else if (hoy == 0) {
            texto = "Quedan " + pend + " tickets pendientes. Aún no resuelves ninguno hoy.";
        } else {
            texto = "Llevas " + hoy + " " + (hoy == 1 ? "ticket resuelto" : "tickets resueltos") + " hoy. Quedan " + pend + " pendientes. " + frase;
        }
        p.put("texto", texto);
        return p;
    }

    // ------------------------------------------------------------------ Conversación
    public Map<String, Object> responder(String mensajeOriginal, String usuario, String vista, Map<String, String> datosPantalla) {
        String norm = normalizar(mensajeOriginal);
        if (norm.isEmpty()) return RespuestaChat.texto("Escribe tu pregunta o toca una de las opciones.").build();

        // Explicaciones de conceptos / pantalla (primero: "qué es el R²" no debe confundirse con tickets)
        Optional<String> concepto = ExplicadorDashboard.explicarConcepto(norm, datosPantalla);
        if (concepto.isPresent()) {
            return RespuestaChat.texto(concepto.get()).chip("Explícame esta pantalla").chip("¿Cuántos tickets pendientes hay?").build();
        }
        if (tiene(norm, "explica*", "explicame esta pantalla", "que significa", "no entiendo", "que veo", "esta pantalla", "resultado*", "interpret*", "prediccion*")) {
            return RespuestaChat.texto(ExplicadorDashboard.explicarPantalla(vista, datosPantalla))
                    .chip("¿Qué es el R²?").chip("¿Cuántos tickets pendientes hay?").build();
        }
        if (tiene(norm, "mas antiguo*", "antiguos", "cuales son", "lista", "detalle", "mostrar tickets", "ver tickets")) {
            return listarPendientes();
        }
        if (tiene(norm, "ticket*", "pendiente*", "resuelto*", "resolv*", "avance", "progreso", "cuanto*", "cuantos", "llevo", "queda*", "incidencia*", "falla*", "resumen")) {
            return resumenTickets(usuario, norm);
        }
        if (tiene(norm, "hola", "buenas", "buenos dias", "buenas tardes", "hey")) {
            Map<String, Object> p = pulso(usuario);
            String extra = Boolean.TRUE.equals(p.get("success")) ? " Ahora mismo hay " + p.get("pendientes") + " tickets pendientes." : "";
            return RespuestaChat.texto("Hola. Puedo darte el estado de los tickets en tiempo real y explicarte los resultados del dashboard." + extra)
                    .chip("¿Cuántos tickets pendientes hay?").chip("Explícame esta pantalla").build();
        }
        if (tiene(norm, "gracias", "ok", "listo", "chau")) {
            return RespuestaChat.texto("Para eso estoy.").chip("¿Cuántos tickets pendientes hay?").build();
        }
        Optional<String> ia = AsistenteIAOpcional.compartido().responder("analista", usuario, mensajeOriginal);
        if (ia.isPresent()) {
            return RespuestaChat.texto(ia.get()).origen("ia").chip("Explícame esta pantalla").chip("¿Cuántos tickets pendientes hay?").build();
        }
        return RespuestaChat.texto("No tengo una respuesta para eso. Puedo informarte de tickets (pendientes, resueltos, avance) y explicarte conceptos o resultados del dashboard.")
                .chip("¿Cuántos tickets pendientes hay?").chip("Explícame esta pantalla").chip("¿Qué es el R²?").build();
    }

    private Map<String, Object> resumenTickets(String usuario, String norm) {
        Map<String, Object> p = pulso(usuario);
        if (!Boolean.TRUE.equals(p.get("success"))) {
            return RespuestaChat.texto("No pude consultar los tickets en este momento. Intenta de nuevo en unos segundos.").build();
        }
        StringBuilder t = new StringBuilder();
        t.append("Pendientes ahora: **").append(p.get("pendientes")).append("** (")
                .append(p.get("incidencias_pendientes")).append(" mensajes de clientes y ")
                .append(p.get("fallas_pendientes")).append(" fallas del sistema)");
        if ((int) p.get("en_proceso") > 0) t.append(", más ").append(p.get("en_proceso")).append(" en revisión");
        t.append(".\nResueltos por ti: **").append(p.get("resueltos_hoy")).append("** hoy, **")
                .append(p.get("resueltos_semana")).append("** en los últimos 7 días y **")
                .append(p.get("resueltos_total")).append("** en total registrado.\n")
                .append("El equipo resolvió ").append(p.get("resueltos_equipo_hoy")).append(" hoy.");
        return RespuestaChat.texto(t.toString())
                .accion("🎫 Ir a Incidencias", "vista", "incidencias")
                .chip("Ver tickets más antiguos").chip("Explícame esta pantalla").build();
    }

    private Map<String, Object> listarPendientes() {
        List<String> filas = new ArrayList<>();
        String sql = "SELECT id_incidencia, asunto, fecha_creacion FROM INCIDENCIAS_CONTACTO WHERE estado = 'pendiente' ORDER BY fecha_creacion ASC";
        try (Connection c = DatabaseConfig.getDataSource().getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setMaxRows(5);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Timestamp f = rs.getTimestamp("fecha_creacion");
                    String asunto = rs.getString("asunto");
                    filas.add("#" + rs.getInt("id_incidencia") + " · " + (asunto == null ? "Sin asunto" : asunto) + " · hace " + hace(f));
                }
            }
        } catch (SQLException e) {
            return RespuestaChat.texto("No pude consultar los tickets en este momento.").build();
        }
        if (filas.isEmpty()) return RespuestaChat.texto("No hay mensajes de clientes pendientes.").chip("¿Cuántos tickets pendientes hay?").build();
        return RespuestaChat.texto("Los más antiguos sin resolver:\n" + String.join("\n", filas))
                .accion("🎫 Ir a Incidencias", "vista", "incidencias").chip("¿Cuántos tickets pendientes hay?").build();
    }

    // ------------------------------------------------------------------ SQL auxiliar
    private static String hace(Timestamp f) {
        if (f == null) return "—";
        Duration d = Duration.between(f.toInstant(), Instant.now());
        long min = Math.max(0, d.toMinutes());
        if (min < 60) return min + " min";
        if (min < 1440) return (min / 60) + " h";
        return (min / 1440) + " d";
    }

    private static int contar(Connection c, String sql) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    private static Integer idAdmin(Connection c, String usuario) throws SQLException {
        if (usuario == null || usuario.isBlank()) return null;
        try (PreparedStatement ps = c.prepareStatement("SELECT id_admin FROM ADMINISTRADORES WHERE UPPER(usuario) = UPPER(?)")) {
            ps.setString(1, usuario.trim());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : null;
            }
        }
    }

    /** Tickets pasados a "resuelto" (por un analista concreto o por todos) desde una fecha (o desde siempre). */
    static int resueltos(Connection c, Integer idAdmin, Timestamp desde) throws SQLException {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM HISTORIAL_ATENCION WHERE estado_nuevo = 'resuelto'");
        if (idAdmin != null) sql.append(" AND id_admin = ?");
        if (desde != null) sql.append(" AND fecha_evento >= ?");
        try (PreparedStatement ps = c.prepareStatement(sql.toString())) {
            int i = 1;
            if (idAdmin != null) ps.setInt(i++, idAdmin);
            if (desde != null) ps.setTimestamp(i, desde);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }
}
