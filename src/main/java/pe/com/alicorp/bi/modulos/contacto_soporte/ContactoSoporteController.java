package pe.com.alicorp.bi.modulos.contacto_soporte;

import io.javalin.Javalin;
import pe.com.alicorp.bi.config.DatabaseConfig;
import pe.com.alicorp.bi.modulos.acceso_biometria.ServicioActividadEquipo;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

/**
 * Controlador REST de la sección Web "Contacto, Incidencias y Fallas Web"
 * (contacto_soporte/contacto.html, dashboard_bi/#incidencias).
 */
@SuppressWarnings("unchecked")
public class ContactoSoporteController {

    public static void registrar(Javalin app) {
        app.post("/api/contacto", ctx -> {
            Map<String, Object> body = ctx.bodyAsClass(Map.class);
            String nombre = String.valueOf(body.getOrDefault("nombre", "")).trim();
            String correo = String.valueOf(body.getOrDefault("correo", body.getOrDefault("email", ""))).trim();
            String telefono = String.valueOf(body.getOrDefault("telefono", "")).trim();
            String asunto = String.valueOf(body.getOrDefault("asunto", "Consulta General")).trim();
            String mensaje = String.valueOf(body.getOrDefault("mensaje", "")).trim();
            try (Connection conn = DatabaseConfig.getDataSource().getConnection();
                 PreparedStatement ps = conn.prepareStatement(
                         "INSERT INTO INCIDENCIAS_CONTACTO (nombre, correo, telefono, asunto, mensaje) VALUES (?, ?, ?, ?, ?)")) {
                ps.setString(1, nombre);
                ps.setString(2, correo);
                ps.setString(3, telefono);
                ps.setString(4, asunto);
                ps.setString(5, mensaje);
                ps.executeUpdate();
            } catch (SQLException e) {
                ctx.status(500).json(Map.of("success", false, "error", e.getMessage()));
                return;
            }
            ctx.json(Map.of("success", true, "message", "Mensaje registrado en la base de datos relacional."));
        });

        app.get("/api/incidencias", ctx -> {
            List<Map<String, Object>> lista = new ArrayList<>();
            try (Connection conn = DatabaseConfig.getDataSource().getConnection();
                 PreparedStatement ps = conn.prepareStatement("SELECT * FROM INCIDENCIAS_CONTACTO ORDER BY fecha_creacion DESC");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String estRaw = rs.getString("estado");
                    String estUi = "resuelto".equalsIgnoreCase(estRaw) || "leido".equalsIgnoreCase(estRaw) ? "leido" : "en_espera";
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getInt("id_incidencia"));
                    m.put("nombre_cliente", rs.getString("nombre"));
                    m.put("nombre", rs.getString("nombre"));
                    m.put("correo", rs.getString("correo"));
                    m.put("telefono", rs.getString("telefono"));
                    m.put("asunto", rs.getString("asunto"));
                    m.put("mensaje", rs.getString("mensaje"));
                    m.put("estado", estUi);
                    m.put("fecha_registro", rs.getTimestamp("fecha_creacion").toInstant().toString());
                    lista.add(m);
                }
            } catch (SQLException e) {
                System.err.println("[!] Error en /api/incidencias: " + e.getMessage());
            }
            ctx.json(Map.of("success", true, "fuente", "Supabase PostgreSQL (3FN)", "incidencias", lista));
        });

        app.post("/api/incidencias/cambiar_estado", ctx -> {
            Map<String, Object> body = ctx.bodyAsClass(Map.class);
            int id = ((Number) body.get("id")).intValue();
            String est = String.valueOf(body.getOrDefault("estado", "leido"));
            String estDb = "leido".equalsIgnoreCase(est) || "resuelto".equalsIgnoreCase(est) ? "resuelto" : "pendiente";
            String actor = String.valueOf(body.getOrDefault("usuario", "")).trim();
            String estadoPrevio = ServicioActividadEquipo.compartido().estadoPrevioIncidencia(id);
            try (Connection conn = DatabaseConfig.getDataSource().getConnection();
                 PreparedStatement ps = conn.prepareStatement("UPDATE INCIDENCIAS_CONTACTO SET estado = ? WHERE id_incidencia = ?")) {
                ps.setString(1, estDb);
                ps.setInt(2, id);
                ps.executeUpdate();
            } catch (SQLException e) {
                ctx.status(500).json(Map.of("success", false, "error", e.getMessage()));
                return;
            }
            ServicioActividadEquipo.compartido().registrarAtencionIncidencia(id, estadoPrevio, estDb, actor);
            ctx.json(Map.of("success", true));
        });

        app.get("/api/fallas_web", ctx -> ctx.json(Map.of("success", true, "fuente", "Supabase PostgreSQL (3FN)", "fallas", listarFallasWeb(false))));
        app.get("/api/fallas_web/escanear", ctx -> ctx.json(Map.of("success", true, "fuente", "Supabase PostgreSQL (3FN)", "fallas", listarFallasWeb(true))));
        app.post("/api/fallas_web/escanear", ctx -> ctx.json(Map.of("success", true, "fuente", "Supabase PostgreSQL (3FN)", "fallas", listarFallasWeb(true))));

        app.post("/api/fallas_web/reportar", ctx -> {
            Map<String, Object> body = ctx.bodyAsClass(Map.class);
            String ruta = String.valueOf(body.getOrDefault("ruta", body.getOrDefault("origen", "/")));
            String tipo = String.valueOf(body.getOrDefault("tipo", body.getOrDefault("tipo_falla", "Error Web")));
            String msg = String.valueOf(body.getOrDefault("mensaje", body.getOrDefault("titulo_error", "Error reportado")));
            String det = String.valueOf(body.getOrDefault("detalle_tecnico", ""));
            String grav = String.valueOf(body.getOrDefault("gravedad", "Media"));
            try (Connection conn = DatabaseConfig.getDataSource().getConnection();
                 PreparedStatement ps = conn.prepareStatement(
                         "INSERT INTO FALLAS_WEB_SISTEMA (ruta, tipo, gravedad, mensaje, detalle_tecnico) VALUES (?, ?, ?, ?, ?)")) {
                ps.setString(1, ruta);
                ps.setString(2, tipo);
                ps.setString(3, grav);
                ps.setString(4, msg);
                ps.setString(5, det);
                ps.executeUpdate();
            } catch (SQLException e) {
                ctx.status(500).json(Map.of("success", false, "error", e.getMessage()));
                return;
            }
            ctx.json(Map.of("success", true));
        });

        app.post("/api/fallas_web/cambiar_estado", ctx -> {
            Map<String, Object> body = ctx.bodyAsClass(Map.class);
            int id = parseIntOrDefault(String.valueOf(body.getOrDefault("id", "-1")), -1);
            String cod = String.valueOf(body.getOrDefault("codigo_unico", "")).trim();
            String estUi = String.valueOf(body.getOrDefault("estado", "resuelto")).trim().toLowerCase();
            String estDb = "resuelto".equals(estUi) ? "resuelto" : ("en_proceso".equals(estUi) || "en_revision".equals(estUi) ? "en_revision" : "pendiente");
            String actor = String.valueOf(body.getOrDefault("usuario", "")).trim();
            int idFalla = ServicioActividadEquipo.compartido().resolverIdFalla(id, cod);
            String estadoPrevio = ServicioActividadEquipo.compartido().estadoPrevioFalla(idFalla);
            try (Connection conn = DatabaseConfig.getDataSource().getConnection()) {
                if (id > 0) {
                    try (PreparedStatement ps = conn.prepareStatement("UPDATE FALLAS_WEB_SISTEMA SET estado = ? WHERE id_falla = ?")) {
                        ps.setString(1, estDb);
                        ps.setInt(2, id);
                        ps.executeUpdate();
                    }
                } else if (!cod.isEmpty()) {
                    try (PreparedStatement ps = conn.prepareStatement("UPDATE FALLAS_WEB_SISTEMA SET estado = ? WHERE codigo_unico = ?")) {
                        ps.setString(1, estDb);
                        ps.setString(2, cod);
                        ps.executeUpdate();
                    }
                }
            } catch (SQLException e) {
                ctx.status(500).json(Map.of("success", false, "error", e.getMessage()));
                return;
            }
            ServicioActividadEquipo.compartido().registrarAtencionFalla(idFalla, estadoPrevio, estDb, actor);
            ctx.json(Map.of("success", true));
        });
    }

    private static List<Map<String, Object>> listarFallasWeb(boolean ejecutarEscaneo) {
        List<Map<String, Object>> lista = new ArrayList<>();
        try (Connection conn = DatabaseConfig.getDataSource().getConnection()) {
            if (ejecutarEscaneo) {
                int bajos = 0;
                List<String> skusBajos = new ArrayList<>();
                try (PreparedStatement psS = conn.prepareStatement(
                        "SELECT sku_codigo, nombre_producto, stock_disponible FROM PRODUCTOS WHERE stock_disponible <= 180 ORDER BY stock_disponible ASC FETCH FIRST 6 ROWS ONLY");
                     ResultSet rsS = psS.executeQuery()) {
                    while (rsS.next()) {
                        bajos++;
                        skusBajos.add(rsS.getString("nombre_producto") + " [" + rsS.getString("sku_codigo") + ": " + rsS.getInt("stock_disponible") + " und.]");
                    }
                }
                if (bajos > 0) {
                    try (PreparedStatement psCnt = conn.prepareStatement("SELECT COUNT(*) FROM PRODUCTOS WHERE stock_disponible <= 180");
                         ResultSet rsC = psCnt.executeQuery()) {
                        if (rsC.next()) bajos = rsC.getInt(1);
                    }
                    String titulo = "Escasez de inventario: " + bajos + " productos por debajo del stock de seguridad mayorista (<= 180 und.)";
                    String det = "SKUs con menor inventario actual: " + String.join(", ", skusBajos);
                    try (PreparedStatement psUp = conn.prepareStatement(
                            "UPDATE FALLAS_WEB_SISTEMA SET mensaje = ?, detalle_tecnico = ?, ocurrencias = ocurrencias + 1, fecha_reporte = CURRENT_TIMESTAMP WHERE codigo_unico = 'inventario-escasez-stock-minimo'")) {
                        psUp.setString(1, titulo);
                        psUp.setString(2, det);
                        if (psUp.executeUpdate() == 0) {
                            try (PreparedStatement psIn = conn.prepareStatement(
                                    "INSERT INTO FALLAS_WEB_SISTEMA (codigo_unico, ruta, tipo, gravedad, mensaje, detalle_tecnico, ocurrencias, estado) VALUES ('inventario-escasez-stock-minimo', 'Almacén / Control de Stock', 'Escasez de Inventario', 'Alta', ?, ?, 1, 'pendiente')")) {
                                psIn.setString(1, titulo);
                                psIn.setString(2, det);
                                psIn.executeUpdate();
                            }
                        }
                    } catch (Exception ignored) {}
                }
            }

            try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM FALLAS_WEB_SISTEMA ORDER BY fecha_reporte DESC");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String estRaw = rs.getString("estado");
                    String estUi = "resuelto".equalsIgnoreCase(estRaw) ? "resuelto"
                            : ("en_revision".equalsIgnoreCase(estRaw) || "en_proceso".equalsIgnoreCase(estRaw) ? "en_proceso" : "sin_resolver");
                    String ts = rs.getTimestamp("fecha_reporte").toInstant().toString();
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getInt("id_falla"));
                    m.put("codigo_unico", rs.getString("codigo_unico") != null ? rs.getString("codigo_unico") : "falla-" + rs.getInt("id_falla"));
                    m.put("titulo_error", rs.getString("mensaje"));
                    m.put("detalle_tecnico", rs.getString("detalle_tecnico") != null ? rs.getString("detalle_tecnico") : "");
                    m.put("tipo_falla", rs.getString("tipo"));
                    m.put("gravedad", rs.getString("gravedad") != null ? rs.getString("gravedad") : "Media");
                    m.put("origen", rs.getString("ruta"));
                    m.put("estado", estUi);
                    m.put("ocurrencias", rs.getObject("ocurrencias") != null ? rs.getInt("ocurrencias") : 1);
                    m.put("actualizado_por", "detector_automatico");
                    m.put("fecha_deteccion", ts);
                    m.put("ultima_deteccion", ts);
                    lista.add(m);
                }
            }
        } catch (SQLException e) {
            System.err.println("[!] Error en listarFallasWeb: " + e.getMessage());
        }
        return lista;
    }

    private static int parseIntOrDefault(String s, int def) {
        if (s == null || s.isBlank()) return def;
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
