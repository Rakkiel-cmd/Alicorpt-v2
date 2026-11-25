package pe.com.alicorp.bi.modulos.estrategia_comercial;

import pe.com.alicorp.bi.config.DatabaseConfig;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Repository JDBC conectado a Supabase PostgreSQL (org.postgresql:postgresql via HikariCP).
 * Ejecuta consultas SQL agregadas (GROUP BY por tienda, producto y mes) sobre
 * TRANSACCIONES_ALICORP sin alterar el esquema ni cargar las 100,000 filas crudas en RAM.
 */
public class RecomendadorSupabaseRepository {

    /**
     * Consulta agregada por tienda, producto y mes (ideal para entrenar el Árbol de Decisión
     * y ejecutar K-Fold Cross-Validation sin saturar memoria).
     */
    public List<DatosTiendaProducto> cargarAgregadosMensuales() {
        List<DatosTiendaProducto> lista = new ArrayList<>();
        String sql = """
            SELECT s.nombre_tienda,
                   p.nombre_producto,
                   TO_CHAR(t.fecha_operacion, 'YYYY-MM') AS periodo_mes,
                   AVG(t.precio_unitario) AS precio_prom,
                   AVG(t.stock_actual) AS stock_prom,
                   AVG(t.temperatura_zona) AS temp_prom,
                   AVG(t.cantidad_vendida) AS cant_prom,
                   SUM(t.ventas_totales) AS ventas_tot,
                   COUNT(*) AS trx
            FROM TRANSACCIONES_ALICORP t
            INNER JOIN TIENDAS s ON t.id_tienda = s.id_tienda
            INNER JOIN PRODUCTOS p ON t.id_producto = p.id_producto
            GROUP BY s.nombre_tienda, p.nombre_producto, TO_CHAR(t.fecha_operacion, 'YYYY-MM')
            ORDER BY s.nombre_tienda, p.nombre_producto, periodo_mes
            """;

        try (Connection conn = DatabaseConfig.getDataSource().getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                lista.add(new DatosTiendaProducto(
                        rs.getString("nombre_tienda"),
                        rs.getString("nombre_producto"),
                        rs.getString("periodo_mes"),
                        redondear(rs.getDouble("precio_prom"), 2),
                        redondear(rs.getDouble("stock_prom"), 1),
                        redondear(rs.getDouble("temp_prom"), 1),
                        redondear(rs.getDouble("cant_prom"), 2),
                        redondear(rs.getDouble("ventas_tot"), 2),
                        rs.getLong("trx")
                ));
            }
        } catch (SQLException e) {
            System.err.println("[!] Error cargando agregados mensuales desde Supabase: " + e.getMessage());
        }
        return lista;
    }

    /**
     * Consulta agregada por tienda y producto (resumen consolidado para filtrado colaborativo
     * y evaluación de estrategia actual por tienda).
     */
    public List<DatosTiendaProducto> cargarResumenPorTiendaProducto() {
        List<DatosTiendaProducto> lista = new ArrayList<>();
        String sql = """
            SELECT s.nombre_tienda,
                   p.nombre_producto,
                   AVG(t.precio_unitario) AS precio_prom,
                   AVG(t.stock_actual) AS stock_prom,
                   AVG(t.temperatura_zona) AS temp_prom,
                   AVG(t.cantidad_vendida) AS cant_prom,
                   SUM(t.ventas_totales) AS ventas_tot,
                   COUNT(*) AS trx
            FROM TRANSACCIONES_ALICORP t
            INNER JOIN TIENDAS s ON t.id_tienda = s.id_tienda
            INNER JOIN PRODUCTOS p ON t.id_producto = p.id_producto
            GROUP BY s.nombre_tienda, p.nombre_producto
            ORDER BY s.nombre_tienda, p.nombre_producto
            """;

        try (Connection conn = DatabaseConfig.getDataSource().getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                lista.add(new DatosTiendaProducto(
                        rs.getString("nombre_tienda"),
                        rs.getString("nombre_producto"),
                        "CONSOLIDADO",
                        redondear(rs.getDouble("precio_prom"), 2),
                        redondear(rs.getDouble("stock_prom"), 1),
                        redondear(rs.getDouble("temp_prom"), 1),
                        redondear(rs.getDouble("cant_prom"), 2),
                        redondear(rs.getDouble("ventas_tot"), 2),
                        rs.getLong("trx")
                ));
            }
        } catch (SQLException e) {
            System.err.println("[!] Error cargando resumen tienda-producto desde Supabase: " + e.getMessage());
        }
        return lista;
    }

    private static double redondear(double val, int decimales) {
        double factor = Math.pow(10, decimales);
        return Math.round(val * factor) / factor;
    }
}
