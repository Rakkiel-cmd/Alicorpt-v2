package pe.com.alicorp.bi.modulos.tienda_buscador_ia;

import io.javalin.Javalin;
import pe.com.alicorp.bi.config.DatabaseConfig;

import java.sql.*;
import java.util.*;

/**
 * Controlador REST de la sección Web "Tienda en Línea & Buscador Predictivo IA"
 * (tienda_virtual/tienda.html, portal_institucional/index.html, dashboard_bi/#compras).
 */
@SuppressWarnings("unchecked")
public class TiendaBuscadorController {

    public static void registrar(Javalin app, ServicioBuscadorIA servicioBuscadorIA) {
        // 1. Buscador Predictivo con Árbol Trie Recursivo + Ranking Smile ML
        app.get("/api/buscador-ia", ctx -> {
            String q = ctx.queryParam("q");
            String tienda = ctx.queryParam("tienda");
            int limite = ctx.queryParamAsClass("limite", Integer.class).getOrDefault(48);
            ctx.json(servicioBuscadorIA.buscarConComparativa(q, tienda, limite));
        });

        app.get("/api/buscador-ia/tiendas", ctx -> {
            ctx.json(Map.of(
                    "success", true,
                    "tiendas", servicioBuscadorIA.obtenerTiendasDisponibles()
            ));
        });

        // 2. Estado, categorías y catálogo E-Commerce 3FN
        app.get("/api/tienda/estado", ctx -> {
            int totalProductos = 0;
            try (Connection conn = DatabaseConfig.getDataSource().getConnection();
                 PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM PRODUCTOS WHERE activo = 'S'");
                 ResultSet rs = ps.executeQuery()) {
                if (rs.next()) totalProductos = rs.getInt(1);
            } catch (SQLException e) {
                System.err.println("[!] Error en /api/tienda/estado: " + e.getMessage());
            }
            ctx.json(Map.of(
                    "ok", true,
                    "total_productos", totalProductos,
                    "buscador_listo", true,
                    "fuente", "supabase",
                    "motor", "Java 26 + Supabase 3FN",
                    "modo", "supabase_3fn"
            ));
        });

        app.get("/api/tienda/categorias", ctx -> {
            List<Map<String, Object>> categorias = new ArrayList<>();
            Map<String, String> emojis = Map.of(
                    "Abarrotes", "🫒",
                    "Limpieza del Hogar", "🧼",
                    "Pastas y Harinas", "🍝",
                    "Salsas y Untables", "🥣"
            );
            String sql = """
                SELECT c.nombre_categoria, COUNT(p.id_producto) AS cantidad
                FROM CATEGORIAS_PRODUCTO c
                LEFT JOIN PRODUCTOS p ON p.id_categoria = c.id_categoria AND p.activo = 'S'
                GROUP BY c.id_categoria, c.nombre_categoria
                ORDER BY c.id_categoria
                """;
            try (Connection conn = DatabaseConfig.getDataSource().getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql);
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String nombre = rs.getString("nombre_categoria");
                    categorias.add(Map.of(
                            "categoria", nombre,
                            "emoji", emojis.getOrDefault(nombre, "📦"),
                            "cantidad", rs.getInt("cantidad")
                    ));
                }
            } catch (SQLException e) {
                System.err.println("[!] Error en /api/tienda/categorias: " + e.getMessage());
            }
            ctx.json(Map.of("ok", true, "categorias", categorias));
        });

        app.get("/api/tienda/destacados", ctx -> {
            List<Map<String, Object>> items = consultarProductos(null, null, null, null, 1, 8);
            ctx.json(Map.of("ok", true, "items", items));
        });

        app.get("/api/tienda/productos", ctx -> {
            String cat = ctx.queryParam("categoria");
            String marca = ctx.queryParam("marca");
            String orden = ctx.queryParam("orden");
            int pagina = parseIntOrDefault(ctx.queryParam("pagina"), 1);
            int porPagina = parseIntOrDefault(ctx.queryParam("por_pagina"), 24);

            List<Map<String, Object>> todos = consultarProductos(cat, marca, null, orden, 1, 500);
            Set<String> marcas = new TreeSet<>();
            for (Map<String, Object> item : consultarProductos(null, null, null, null, 1, 500)) {
                marcas.add(String.valueOf(item.get("marca")));
            }
            int total = todos.size();
            int paginas = Math.max(1, (int) Math.ceil(total / (double) porPagina));
            int desde = Math.min((pagina - 1) * porPagina, total);
            int hasta = Math.min(desde + porPagina, total);

            ctx.json(Map.of(
                    "ok", true,
                    "items", todos.subList(desde, hasta),
                    "total", total,
                    "pagina", pagina,
                    "paginas", paginas,
                    "marcas", marcas
            ));
        });

        app.get("/api/tienda/buscar", ctx -> {
            String q = ctx.queryParam("q");
            int limite = parseIntOrDefault(ctx.queryParam("limite"), 48);
            List<Map<String, Object>> items = consultarProductos(null, null, q, null, 1, limite);
            ctx.json(Map.of("ok", true, "items", items, "total", items.size()));
        });

        // 3. Compras transaccionales y seguimiento de pedidos
        app.post("/api/tienda/comprar", ctx -> {
            Map<String, Object> body = ctx.bodyAsClass(Map.class);
            String nombre = String.valueOf(body.getOrDefault("nombre", "")).trim();
            String correo = String.valueOf(body.getOrDefault("correo", "")).trim();
            String telefono = String.valueOf(body.getOrDefault("telefono", "")).trim();
            String direccion = String.valueOf(body.getOrDefault("direccion", "")).trim();
            List<Map<String, Object>> items = (List<Map<String, Object>>) body.get("items");

            if (nombre.isEmpty() || correo.isEmpty() || items == null || items.isEmpty()) {
                ctx.status(400).json(Map.of("ok", false, "error", "Completa tus datos y agrega al menos un producto."));
                return;
            }

            try (Connection conn = DatabaseConfig.getDataSource().getConnection()) {
                conn.setAutoCommit(false);
                try {
                    double total = 0.0;
                    List<Object[]> lineas = new ArrayList<>();
                    for (Map<String, Object> it : items) {
                        String sku = String.valueOf(it.get("sku"));
                        int cant = ((Number) it.get("cantidad")).intValue();
                        try (PreparedStatement psP = conn.prepareStatement(
                                "SELECT id_producto, precio_base, stock_disponible FROM PRODUCTOS WHERE sku_codigo = ?")) {
                            psP.setString(1, sku);
                            try (ResultSet rs = psP.executeQuery()) {
                                if (!rs.next()) {
                                    conn.rollback();
                                    ctx.status(400).json(Map.of("ok", false, "error", "Producto no encontrado: " + sku));
                                    return;
                                }
                                int idProd = rs.getInt("id_producto");
                                double precio = rs.getDouble("precio_base");
                                int stock = rs.getInt("stock_disponible");
                                if (stock < cant) {
                                    conn.rollback();
                                    ctx.status(400).json(Map.of("ok", false, "error", "Stock insuficiente para SKU " + sku));
                                    return;
                                }
                                double sub = Math.round(precio * cant * 100.0) / 100.0;
                                total += sub;
                                lineas.add(new Object[]{idProd, cant, precio, sub});
                            }
                        }
                    }
                    total = Math.round(total * 100.0) / 100.0;

                    int compraId;
                    try (PreparedStatement psC = conn.prepareStatement(
                            "INSERT INTO COMPRAS_TIENDA (cliente_nombre, cliente_correo, cliente_telefono, cliente_direccion, total, estado) VALUES (?, ?, ?, ?, ?, 'pendiente')",
                            Statement.RETURN_GENERATED_KEYS)) {
                        psC.setString(1, nombre);
                        psC.setString(2, correo);
                        psC.setString(3, telefono);
                        psC.setString(4, direccion);
                        psC.setDouble(5, total);
                        psC.executeUpdate();
                        try (ResultSet keys = psC.getGeneratedKeys()) {
                            keys.next();
                            compraId = keys.getInt(1);
                        }
                    }

                    for (Object[] l : lineas) {
                        try (PreparedStatement psD = conn.prepareStatement(
                                "INSERT INTO COMPRAS_TIENDA_DETALLE (id_compra, id_producto, cantidad, precio_unitario, subtotal) VALUES (?, ?, ?, ?, ?)")) {
                            psD.setInt(1, compraId);
                            psD.setInt(2, (Integer) l[0]);
                            psD.setInt(3, (Integer) l[1]);
                            psD.setDouble(4, (Double) l[2]);
                            psD.setDouble(5, (Double) l[3]);
                            psD.executeUpdate();
                        }
                        try (PreparedStatement psS = conn.prepareStatement(
                                "UPDATE PRODUCTOS SET stock_disponible = stock_disponible - ? WHERE id_producto = ?")) {
                            psS.setInt(1, (Integer) l[1]);
                            psS.setInt(2, (Integer) l[0]);
                            psS.executeUpdate();
                        }
                    }
                    conn.commit();
                    ctx.json(Map.of(
                            "ok", true,
                            "compra_id", compraId,
                            "total", total,
                            "guardado_en", "base_3fn_local",
                            "correo_enviado", false
                    ));
                } catch (Exception e) {
                    conn.rollback();
                    ctx.status(500).json(Map.of("ok", false, "error", e.getMessage()));
                } finally {
                    conn.setAutoCommit(true);
                }
            }
        });

        app.post("/api/tienda/pedido", ctx -> {
            Map<String, Object> body = ctx.bodyAsClass(Map.class);
            int idCompra = parseIntOrDefault(String.valueOf(body.getOrDefault("compra_id", "")), -1);
            String correo = String.valueOf(body.getOrDefault("correo", "")).trim();
            if (idCompra <= 0 || correo.isEmpty()) {
                ctx.status(400).json(Map.of("ok", false, "error", "Ingresa el número de compra y correo."));
                return;
            }
            try (Connection conn = DatabaseConfig.getDataSource().getConnection();
                 PreparedStatement ps = conn.prepareStatement(
                         "SELECT id_compra, cliente_nombre, total, estado, fecha_compra FROM COMPRAS_TIENDA WHERE id_compra = ? AND LOWER(cliente_correo) = LOWER(?)")) {
                ps.setInt(1, idCompra);
                ps.setString(2, correo);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        ctx.status(404).json(Map.of("ok", false, "error", "No se encontró el pedido con ese correo."));
                        return;
                    }
                    List<Map<String, Object>> items = new ArrayList<>();
                    try (PreparedStatement psDet = conn.prepareStatement(
                            "SELECT p.nombre_producto, d.cantidad, d.subtotal FROM COMPRAS_TIENDA_DETALLE d INNER JOIN PRODUCTOS p ON d.id_producto = p.id_producto WHERE d.id_compra = ?")) {
                        psDet.setInt(1, idCompra);
                        try (ResultSet rsD = psDet.executeQuery()) {
                            while (rsD.next()) {
                                items.add(Map.of(
                                        "producto", rsD.getString("nombre_producto"),
                                        "cantidad", rsD.getInt("cantidad"),
                                        "subtotal", rsD.getDouble("subtotal")
                                ));
                            }
                        }
                    }
                    ctx.json(Map.of("ok", true, "pedido", Map.of(
                            "id", rs.getInt("id_compra"),
                            "cliente", rs.getString("cliente_nombre"),
                            "total", rs.getDouble("total"),
                            "estado", rs.getString("estado"),
                            "fecha", rs.getTimestamp("fecha_compra").toInstant().toString(),
                            "items", items
                    )));
                }
            } catch (SQLException e) {
                ctx.status(500).json(Map.of("ok", false, "error", e.getMessage()));
            }
        });

        app.get("/api/tienda/compras", ctx -> {
            List<Map<String, Object>> compras = new ArrayList<>();
            String sql = "SELECT id_compra, cliente_nombre, cliente_correo, cliente_telefono, cliente_direccion, total, estado, fecha_compra FROM COMPRAS_TIENDA ORDER BY fecha_compra DESC FETCH FIRST 200 ROWS ONLY";
            try (Connection conn = DatabaseConfig.getDataSource().getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql);
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> c = new LinkedHashMap<>();
                    c.put("id", rs.getInt("id_compra"));
                    c.put("cliente", rs.getString("cliente_nombre"));
                    c.put("correo", rs.getString("cliente_correo"));
                    c.put("telefono", rs.getString("cliente_telefono"));
                    c.put("direccion", rs.getString("cliente_direccion"));
                    c.put("total", rs.getDouble("total"));
                    c.put("estado", rs.getString("estado"));
                    c.put("fecha", rs.getTimestamp("fecha_compra").toInstant().toString());
                    compras.add(c);
                }
            } catch (SQLException e) {
                System.err.println("[!] Error en /api/tienda/compras: " + e.getMessage());
            }
            ctx.json(Map.of("ok", true, "compras", compras, "total", compras.size()));
        });

        app.post("/api/tienda/compras/estado", ctx -> {
            Map<String, Object> body = ctx.bodyAsClass(Map.class);
            int id = parseIntOrDefault(String.valueOf(body.getOrDefault("id", body.getOrDefault("compra_id", ""))), -1);
            String estado = String.valueOf(body.getOrDefault("estado", "pendiente")).trim().toLowerCase();
            try (Connection conn = DatabaseConfig.getDataSource().getConnection();
                 PreparedStatement ps = conn.prepareStatement("UPDATE COMPRAS_TIENDA SET estado = ? WHERE id_compra = ?")) {
                ps.setString(1, estado);
                ps.setInt(2, id);
                ps.executeUpdate();
            } catch (SQLException e) {
                ctx.status(500).json(Map.of("ok", false, "error", e.getMessage()));
                return;
            }
            ctx.json(Map.of("ok", true, "estado", estado));
        });
    }

    private static List<Map<String, Object>> consultarProductos(String categoria, String marca, String q, String orden, int pagina, int limite) {
        List<Map<String, Object>> lista = new ArrayList<>();
        StringBuilder sql = new StringBuilder("""
            SELECT p.sku_codigo, p.nombre_producto, p.marca, p.presentacion, p.stock_disponible,
                   p.precio_base, c.nombre_categoria
            FROM PRODUCTOS p
            INNER JOIN CATEGORIAS_PRODUCTO c ON p.id_categoria = c.id_categoria
            WHERE p.activo = 'S'
            """);
        List<Object> params = new ArrayList<>();
        if (categoria != null && !categoria.isBlank()) {
            sql.append(" AND LOWER(c.nombre_categoria) = LOWER(?)");
            params.add(categoria.trim());
        }
        if (marca != null && !marca.isBlank()) {
            sql.append(" AND LOWER(p.marca) = LOWER(?)");
            params.add(marca.trim());
        }
        if (q != null && !q.isBlank()) {
            sql.append(" AND (LOWER(p.nombre_producto) LIKE LOWER(?) OR LOWER(p.marca) LIKE LOWER(?) OR LOWER(c.nombre_categoria) LIKE LOWER(?))");
            String like = "%" + q.trim() + "%";
            params.add(like);
            params.add(like);
            params.add(like);
        }
        if ("precio_asc".equals(orden)) {
            sql.append(" ORDER BY p.precio_base ASC");
        } else if ("precio_desc".equals(orden)) {
            sql.append(" ORDER BY p.precio_base DESC");
        } else {
            sql.append(" ORDER BY p.id_producto ASC");
        }
        sql.append(" FETCH FIRST ? ROWS ONLY");
        params.add(limite);

        try (Connection conn = DatabaseConfig.getDataSource().getConnection();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                ps.setObject(i + 1, params.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("sku", rs.getString("sku_codigo"));
                    item.put("nombre", rs.getString("nombre_producto"));
                    item.put("marca", rs.getString("marca"));
                    item.put("presentacion", rs.getString("presentacion"));
                    item.put("stock", rs.getInt("stock_disponible"));
                    item.put("precio", rs.getDouble("precio_base"));
                    item.put("categoria", rs.getString("nombre_categoria"));
                    lista.add(item);
                }
            }
        } catch (SQLException e) {
            System.err.println("[!] Error en consultarProductos: " + e.getMessage());
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
