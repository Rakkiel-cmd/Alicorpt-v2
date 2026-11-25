package pe.com.alicorp.bi.modulos.tienda_buscador_ia;

import pe.com.alicorp.bi.config.DatabaseConfig;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * DAO / Repository JDBC conectado a la base de datos Supabase PostgreSQL (3FN)
 * mediante el pool HikariCP configurado en DatabaseConfig.
 * Carga de forma optimizada (GROUP BY) el catálogo de productos y las métricas
 * agregadas reales de la tabla TRANSACCIONES_ALICORP.
 */
public class BuscadorCatalogoRepository {

    /**
     * Carga el catálogo completo enriquecido con agregaciones SQL (GROUP BY) sobre TRANSACCIONES_ALICORP.
     */
    public List<ProductoCatalogoIA> cargarCatalogoAgregado() {
        Map<Integer, Map<String, Double>> afinidadesPorProducto = cargarAfinidadesPorTienda();
        List<ProductoCatalogoIA> catalogo = new ArrayList<>();

        String sql = """
            SELECT p.id_producto,
                   p.sku_codigo,
                   p.nombre_producto,
                   p.marca,
                   p.presentacion,
                   c.nombre_categoria,
                   p.precio_base,
                   p.stock_disponible,
                   COALESCE(AVG(t.precio_unitario), p.precio_base) AS precio_promedio,
                   COALESCE(AVG(t.stock_actual), p.stock_disponible) AS stock_promedio,
                   COALESCE(AVG(t.temperatura_zona), 23.5) AS temp_promedio,
                   COALESCE(SUM(t.cantidad_vendida), 0) AS volumen_unidades,
                   COALESCE(SUM(t.ventas_totales), 0) AS ventas_totales,
                   COUNT(t.id_transaccion) AS total_trx
            FROM PRODUCTOS p
            INNER JOIN CATEGORIAS_PRODUCTO c ON p.id_categoria = c.id_categoria
            LEFT JOIN TRANSACCIONES_ALICORP t ON t.id_producto = p.id_producto
            WHERE p.activo = 'S'
            GROUP BY p.id_producto, p.sku_codigo, p.nombre_producto, p.marca,
                     p.presentacion, c.nombre_categoria, p.precio_base, p.stock_disponible
            ORDER BY p.nombre_producto ASC
            """;

        try (Connection conn = DatabaseConfig.getDataSource().getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {

            while (rs.next()) {
                int idProd = rs.getInt("id_producto");
                double precioProm = Math.round(rs.getDouble("precio_promedio") * 100.0) / 100.0;
                int stockDisp = rs.getInt("stock_disponible");
                double stockProm = Math.round(rs.getDouble("stock_promedio") * 10.0) / 10.0;
                double tempProm = Math.round(rs.getDouble("temp_promedio") * 10.0) / 10.0;
                long volUnid = rs.getLong("volumen_unidades");
                double vtasTot = Math.round(rs.getDouble("ventas_totales") * 100.0) / 100.0;
                long totalTrx = rs.getLong("total_trx");

                catalogo.add(new ProductoCatalogoIA(
                        idProd,
                        rs.getString("sku_codigo"),
                        rs.getString("nombre_producto"),
                        rs.getString("marca"),
                        rs.getString("presentacion"),
                        rs.getString("nombre_categoria"),
                        precioProm > 0 ? precioProm : rs.getDouble("precio_base"),
                        stockDisp,
                        stockProm,
                        tempProm,
                        volUnid,
                        vtasTot,
                        totalTrx,
                        afinidadesPorProducto.getOrDefault(idProd, Map.of())
                ));
            }
        } catch (SQLException e) {
            System.err.println("[!] Error cargando catálogo agregado desde Supabase: " + e.getMessage());
        }
        return catalogo;
    }

    /**
     * Calcula la afinidad histórica por tienda para cada producto mediante GROUP BY (id_producto, nombre_tienda).
     */
    private Map<Integer, Map<String, Double>> cargarAfinidadesPorTienda() {
        Map<Integer, Map<String, Double>> ventasPorProdTienda = new HashMap<>();
        Map<Integer, Double> maxVentaPorProd = new HashMap<>();

        String sql = """
            SELECT t.id_producto,
                   s.nombre_tienda,
                   SUM(t.ventas_totales) AS ventas_tienda,
                   SUM(t.cantidad_vendida) AS unidades_tienda
            FROM TRANSACCIONES_ALICORP t
            INNER JOIN TIENDAS s ON t.id_tienda = s.id_tienda
            GROUP BY t.id_producto, s.nombre_tienda
            """;

        try (Connection conn = DatabaseConfig.getDataSource().getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {

            while (rs.next()) {
                int idProd = rs.getInt("id_producto");
                String tienda = rs.getString("nombre_tienda");
                double ventas = rs.getDouble("ventas_tienda");
                ventasPorProdTienda
                        .computeIfAbsent(idProd, k -> new LinkedHashMap<>())
                        .put(tienda, ventas);
                maxVentaPorProd.merge(idProd, ventas, (a, b) -> Math.max(a, b));
            }
        } catch (SQLException e) {
            System.err.println("[!] Error cargando afinidad por tienda desde Supabase: " + e.getMessage());
        }

        // Normalizar afinidad [0.0, 1.0] relativa al mejor desempeño del producto por tienda
        Map<Integer, Map<String, Double>> afinidades = new HashMap<>();
        for (Map.Entry<Integer, Map<String, Double>> entry : ventasPorProdTienda.entrySet()) {
            int idProd = entry.getKey();
            double maxV = Math.max(1.0, maxVentaPorProd.getOrDefault(idProd, 1.0));
            Map<String, Double> norm = new LinkedHashMap<>();
            for (Map.Entry<String, Double> tEntry : entry.getValue().entrySet()) {
                double score = Math.min(1.0, Math.max(0.0, tEntry.getValue() / maxV));
                norm.put(tEntry.getKey(), Math.round(score * 10000.0) / 10000.0);
            }
            afinidades.put(idProd, norm);
        }
        return afinidades;
    }

    /**
     * Lista las tiendas activas disponibles en la tabla TIENDAS de Supabase.
     */
    public List<String> listarTiendas() {
        List<String> tiendas = new ArrayList<>();
        String sql = "SELECT nombre_tienda FROM TIENDAS ORDER BY id_tienda ASC";
        try (Connection conn = DatabaseConfig.getDataSource().getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                tiendas.add(rs.getString("nombre_tienda"));
            }
        } catch (SQLException e) {
            System.err.println("[!] Error listando tiendas desde Supabase: " + e.getMessage());
        }
        if (tiendas.isEmpty()) {
            tiendas.add("Hipermercado Centro");
            tiendas.add("Mayorista San Martín");
            tiendas.add("Supermercado Lima Norte");
            tiendas.add("Tienda Express Los Olivos");
        }
        return tiendas;
    }
}
