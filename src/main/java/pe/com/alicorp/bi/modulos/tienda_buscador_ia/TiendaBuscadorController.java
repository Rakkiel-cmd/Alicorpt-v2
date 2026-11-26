package pe.com.alicorp.bi.modulos.tienda_buscador_ia;

import io.javalin.Javalin;
import pe.com.alicorp.bi.application.mail.CorreoPedido;
import pe.com.alicorp.bi.application.search.MetricasBusqueda;
import pe.com.alicorp.bi.application.search.RankingBusqueda;
import pe.com.alicorp.bi.application.search.TrieAutocompletado;
import pe.com.alicorp.bi.config.DatabaseConfig;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.*;
import java.text.Normalizer;
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
            Map<String, Object> respuesta = conFotos(servicioBuscadorIA.buscarConComparativa(q, tienda, limite));
            ctx.json(personalizarRespuestaIA(respuesta, ctx.queryParam("modo"), ctx.queryParam("perfil"), limite));
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
            Map<String, String> emojis = EMOJIS_CATEGORIA;
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
            // Un producto con foto y stock por categoria, para que la portada se vea variada
            Map<String, Map<String, Object>> porCategoria = new LinkedHashMap<>();
            for (Map<String, Object> item : catalogoCompleto()) {
                if (item.get("foto") == null || ((Number) item.get("stock")).intValue() <= 0) {
                    continue;
                }
                porCategoria.putIfAbsent(String.valueOf(item.get("categoria")), item);
            }
            List<Map<String, Object>> items = new ArrayList<>(porCategoria.values());
            if (items.size() > 12) {
                items = new ArrayList<>(items.subList(0, 12));
            }
            ctx.json(Map.of("ok", true, "items", items));
        });

        app.get("/api/tienda/productos", ctx -> {
            String cat = ctx.queryParam("categoria");
            String marca = ctx.queryParam("marca");
            String orden = ctx.queryParam("orden");
            int pagina = parseIntOrDefault(ctx.queryParam("pagina"), 1);
            int porPagina = parseIntOrDefault(ctx.queryParam("por_pagina"), 24);

            List<Map<String, Object>> catalogo = catalogoCompleto();
            Set<String> marcas = new TreeSet<>();
            for (Map<String, Object> item : catalogo) {
                marcas.add(String.valueOf(item.get("marca")));
            }
            List<Map<String, Object>> todos = new ArrayList<>();
            for (Map<String, Object> item : catalogo) {
                boolean okCat = cat == null || cat.isBlank() || cat.trim().equalsIgnoreCase(String.valueOf(item.get("categoria")));
                boolean okMarca = marca == null || marca.isBlank() || marca.trim().equalsIgnoreCase(String.valueOf(item.get("marca")));
                if (okCat && okMarca) {
                    todos.add(item);
                }
            }
            if ("nombre".equals(orden)) {
                todos.sort(Comparator.comparing(m -> String.valueOf(m.get("nombre")), String.CASE_INSENSITIVE_ORDER));
            } else if ("precio_asc".equals(orden)) {
                todos.sort(Comparator.comparingDouble(m -> ((Number) m.get("precio")).doubleValue()));
            } else if ("precio_desc".equals(orden)) {
                todos.sort(Comparator.comparingDouble((Map<String, Object> m) -> ((Number) m.get("precio")).doubleValue()).reversed());
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
            // modo "ia": reordena con el historial de la persona y el stock; modo "alfabetico": orden tradicional
            String modo = RankingBusqueda.normalizarModo(ctx.queryParam("modo"));
            RankingBusqueda.Perfil perfil = RankingBusqueda.Perfil.desdeTexto(ctx.queryParam("perfil"));
            List<Map<String, Object>> base = catalogoCompleto();
            List<Map<String, Object>> items;
            if (q == null || q.isBlank()) {
                items = base.size() > limite ? new ArrayList<>(base.subList(0, limite)) : base;
            } else {
                items = RankingBusqueda.reordenar(buscarEnMemoria(base, q), perfil, modo, limite);
            }
            boolean personalizado = RankingBusqueda.MODO_IA.equals(modo) && !perfil.estaVacio();
            ctx.json(Map.of("ok", true, "items", items, "total", items.size(),
                    "modo", modo, "personalizado", personalizado));
        });

        app.get("/api/tienda/sugerencias", ctx -> {
            String q = ctx.queryParam("q");
            int limite = Math.min(Math.max(parseIntOrDefault(ctx.queryParam("limite"), 8), 1), 20);
            String prefijo = normalizarTexto(q == null ? "" : q).replaceAll("\\s+", " ");
            if (prefijo.length() < 2) {
                ctx.json(Map.of("ok", true, "items", List.of(), "ms", 0.0));
                return;
            }
            TrieAutocompletado trie = trieCatalogo();
            long t0 = System.nanoTime();
            String modo = RankingBusqueda.normalizarModo(ctx.queryParam("modo"));
            RankingBusqueda.Perfil perfil = RankingBusqueda.Perfil.desdeTexto(ctx.queryParam("perfil"));
            // Se piden mas candidatas de las que se muestran y despues se reordenan segun el modo
            List<TrieAutocompletado.Sugerencia> candidatas = trie.autocompletar(prefijo, 80);
            List<TrieAutocompletado.Sugerencia> encontradas = RankingBusqueda.reordenarSugerencias(candidatas, perfil, modo, limite);
            double ms = (System.nanoTime() - t0) / 1_000_000.0;
            boolean usaPerfil = RankingBusqueda.MODO_IA.equals(modo) && !perfil.estaVacio();
            List<Map<String, Object>> items = new ArrayList<>();
            for (TrieAutocompletado.Sugerencia sug : encontradas) {
                boolean paraTi = usaPerfil && perfil.afinidadSugerencia(sug) >= 0.2;
                items.add(Map.of("texto", sug.texto(), "tipo", sug.tipo(), "para_ti", paraTi));
            }
            ctx.json(Map.of("ok", true, "items", items, "ms", ms, "modo", modo));
        });

        // Productos que mas se parecen a los gustos de la persona (necesita su historial)
        app.get("/api/tienda/recomendados", ctx -> {
            int cantidad = Math.min(Math.max(parseIntOrDefault(ctx.queryParam("limite"), 8), 1), 24);
            RankingBusqueda.Perfil perfil = RankingBusqueda.Perfil.desdeTexto(ctx.queryParam("perfil"));
            List<Map<String, Object>> items = RankingBusqueda.recomendar(catalogoCompleto(), perfil, cantidad);
            ctx.json(Map.of("ok", true, "items", items, "personalizado", !perfil.estaVacio()));
        });

        // Metricas de la comparativa del informe: busqueda alfabetica (antes) contra ranking con IA (despues)
        app.post("/api/tienda/metricas", ctx -> {
            Map<String, Object> body = ctx.bodyAsClass(Map.class);
            boolean guardado = METRICAS_BUSQUEDA.registrarDesdeMapa(body);
            if (!guardado) {
                ctx.status(400);
            }
            ctx.json(Map.of("ok", guardado));
        });
        app.get("/api/tienda/metricas/resumen", ctx -> ctx.json(METRICAS_BUSQUEDA.resumen()));
        // Borrar las metricas para empezar una prueba limpia: solo desde el mismo computador del servidor
        app.post("/api/tienda/metricas/reiniciar", ctx -> {
            String ip = String.valueOf(ctx.ip());
            if (!(ip.equals("127.0.0.1") || ip.equals("::1") || ip.equals("0:0:0:0:0:0:0:1"))) {
                ctx.status(403).json(Map.of("ok", false, "error", "Solo se puede reiniciar desde el computador del servidor."));
                return;
            }
            METRICAS_BUSQUEDA.reiniciar();
            ctx.json(Map.of("ok", true));
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
                                "SELECT id_producto, precio_base, stock_disponible, nombre_producto FROM PRODUCTOS WHERE sku_codigo = ?")) {
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
                                lineas.add(new Object[]{idProd, cant, precio, sub, rs.getString("nombre_producto")});
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
                    catalogoCache = null;
                    // Correo de seguimiento: va en segundo plano y nunca rompe la compra
                    List<CorreoPedido.Linea> detalleCorreo = new ArrayList<>();
                    for (Object[] l : lineas) {
                        detalleCorreo.add(new CorreoPedido.Linea(String.valueOf(l[4]), (Integer) l[1], (Double) l[3]));
                    }
                    boolean correoEnviado = CorreoPedido.enviarSeguimiento(correo, nombre, compraId, total, direccion, detalleCorreo);
                    ctx.json(Map.of(
                            "ok", true,
                            "compra_id", compraId,
                            "total", total,
                            "guardado_en", "base_3fn_local",
                            "correo_enviado", correoEnviado
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
                    // Mismos datos con los nombres que usa el panel (dashboard.html)
                    c.put("cliente_nombre", c.get("cliente"));
                    c.put("cliente_correo", c.get("correo"));
                    c.put("fecha_compra", c.get("fecha"));
                    c.put("items", new ArrayList<Map<String, Object>>());
                    compras.add(c);
                }
                // Detalle (productos, cantidades y subtotales) de todas las compras en una sola consulta
                Map<Integer, Map<String, Object>> porId = new HashMap<>();
                for (Map<String, Object> c : compras) {
                    porId.put((Integer) c.get("id"), c);
                }
                try (PreparedStatement psDet = conn.prepareStatement(
                        "SELECT d.id_compra, p.nombre_producto, d.cantidad, d.subtotal FROM COMPRAS_TIENDA_DETALLE d INNER JOIN PRODUCTOS p ON d.id_producto = p.id_producto ORDER BY d.id_compra");
                     ResultSet rsDet = psDet.executeQuery()) {
                    while (rsDet.next()) {
                        Map<String, Object> compra = porId.get(rsDet.getInt("id_compra"));
                        if (compra == null) {
                            continue;
                        }
                        int cantidad = rsDet.getInt("cantidad");
                        double subtotal = rsDet.getDouble("subtotal");
                        Map<String, Object> item = new LinkedHashMap<>();
                        item.put("producto", rsDet.getString("nombre_producto"));
                        item.put("cantidad", cantidad);
                        item.put("precio_unitario", cantidad > 0 ? subtotal / cantidad : 0.0);
                        item.put("subtotal", subtotal);
                        @SuppressWarnings("unchecked")
                        List<Map<String, Object>> lista = (List<Map<String, Object>>) compra.get("items");
                        lista.add(item);
                    }
                }
            } catch (SQLException e) {
                System.err.println("[!] Error en /api/tienda/compras: " + e.getMessage());
            }
            ctx.json(Map.of("ok", true, "fuente", "supabase", "compras", compras, "total", compras.size()));
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

    // ---------------------------------------------------------------------
    // Catalogo en memoria (se refresca cada 30 s y tras cada compra) y buscador por frases
    // ---------------------------------------------------------------------
    private static final int LIMITE_CATALOGO = 20000;
    private static volatile List<Map<String, Object>> catalogoCache = null;
    private static volatile long catalogoCacheTs = 0L;

    private static final Set<String> PALABRAS_VACIAS = Set.of(
            "para", "de", "del", "la", "el", "los", "las", "un", "una", "unos", "unas", "y", "e", "o",
            "en", "con", "por", "que", "al", "a", "mi", "quiero", "necesito", "busco", "comprar", "algo");

    private static final Map<String, List<String>> SINONIMOS = Map.ofEntries(
            Map.entry("freir", List.of("aceite", "vegetal", "girasol")),
            Map.entry("cocinar", List.of("aceite", "arroz", "fideo", "salsa", "sal")),
            Map.entry("lavar", List.of("detergente", "jabon", "lejia", "suavizante", "lavavajilla")),
            Map.entry("ropa", List.of("detergente", "suavizante", "lejia", "jabon")),
            Map.entry("limpiar", List.of("limpiador", "desinfectante", "lejia", "detergente")),
            Map.entry("bebe", List.of("infantil", "panal", "formula", "toallitas")),
            Map.entry("lonchera", List.of("galleta", "jugo", "snack", "yogurt", "gelatina")),
            Map.entry("espagueti", List.of("spaghetti", "fideo", "tallarin", "pasta")),
            Map.entry("spaghetti", List.of("espagueti", "fideo", "tallarin", "pasta")),
            Map.entry("fideo", List.of("espagueti", "spaghetti", "tallarin", "pasta")),
            Map.entry("comida", List.of("alimento", "croqueta")),
            Map.entry("perro", List.of("mascota", "canino")),
            Map.entry("gato", List.of("mascota", "felino")),
            Map.entry("desayuno", List.of("cereal", "avena", "leche", "mermelada", "cafe")),
            Map.entry("postre", List.of("gelatina", "flan", "chocolate", "galleta")),
            Map.entry("dulce", List.of("chocolate", "caramelo", "galleta", "golosina"))
    );

    // Emoji de respaldo por categoria (se usa si una foto no carga)
    private static final Map<String, String> EMOJIS_CATEGORIA = Map.ofEntries(
            Map.entry("Abarrotes", "🛒"), Map.entry("Cuidado Personal y Salud", "🧴"),
            Map.entry("Mascotas", "🐶"), Map.entry("Panadería y Pastelería", "🍞"),
            Map.entry("Limpieza", "🧼"), Map.entry("Desayunos", "🥣"),
            Map.entry("Bebé e Infantil", "🍼"), Map.entry("Quesos y Fiambres", "🧀"),
            Map.entry("Bebidas", "🥤"), Map.entry("Lácteos y Huevos", "🥛"),
            Map.entry("Carnes, Aves y Pescados", "🥩"), Map.entry("Congelados", "🧊"),
            Map.entry("Pollo Rostizado y Comidas Preparadas", "🍗"), Map.entry("Mercado Saludable", "🥗"),
            Map.entry("Limpieza del Hogar", "🧼"), Map.entry("Pastas y Harinas", "🍝"),
            Map.entry("Salsas y Untables", "🥣"));

    private static volatile Map<String, String[]> datosExtraCache = null;

    /** sku -> {foto, subcategoria, etiquetas}; se lee una sola vez del archivo catalogo_extra.tsv (dentro del jar). */
    private static Map<String, String[]> datosExtra() {
        Map<String, String[]> m = datosExtraCache;
        if (m != null) {
            return m;
        }
        m = new HashMap<>();
        try (var in = TiendaBuscadorController.class.getResourceAsStream("/catalogo_extra.tsv")) {
            if (in != null) {
                try (BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                    String linea;
                    while ((linea = br.readLine()) != null) {
                        String[] c = linea.split("\\t", -1);
                        if (c.length >= 4) {
                            m.put(c[0], new String[]{c[1], c[2], c[3]});
                        }
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[!] No se pudo leer catalogo_extra.tsv: " + e.getMessage());
        }
        datosExtraCache = m;
        return m;
    }

    /**
     * El Buscador IA devuelve los productos sin la foto; aqui se les agrega "foto", "subcategoria" y "etiquetas" desde
     * catalogo_extra.tsv (por SKU), igual que en el resto de la tienda, para que las tarjetas muestren la foto real.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> conFotos(Map<String, Object> respuesta) {
        Map<String, Object> salida = new LinkedHashMap<>(respuesta);
        for (String clave : List.of("resultados_ia", "resultados_tradicional")) {
            if (!(respuesta.get(clave) instanceof List<?> lista)) {
                continue;
            }
            List<Map<String, Object>> nueva = new ArrayList<>();
            for (Object o : lista) {
                Map<String, Object> item = new LinkedHashMap<>((Map<String, Object>) o);
                String[] extra = datosExtra().get(String.valueOf(item.get("sku")));
                if (extra != null) {
                    item.put("foto", extra[0]);
                    item.putIfAbsent("subcategoria", extra[1]);
                    item.putIfAbsent("etiquetas", extra[2]);
                }
                nueva.add(item);
            }
            salida.put(clave, nueva);
        }
        return salida;
    }

    // ---------------------------------------------------------------------
    /**
     * Capa de personalizacion sobre el ranking de Smile ML: si la persona tiene historial (perfil), la lista "IA" se reordena
     * sumando la afinidad con sus categorias, marcas y productos. Sin historial la respuesta queda tal cual la calcula el servicio.
     * La lista tradicional (A-Z) nunca se toca: es la linea base de la comparativa.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> personalizarRespuestaIA(Map<String, Object> respuesta, String modoParam, String perfilParam, int limite) {
        String modo = RankingBusqueda.normalizarModo(modoParam);
        RankingBusqueda.Perfil perfil = RankingBusqueda.Perfil.desdeTexto(perfilParam);
        Map<String, Object> salida = new LinkedHashMap<>(respuesta);
        salida.put("modo", modo);
        boolean personalizado = false;
        Object lista = respuesta.get("resultados_ia");
        if (RankingBusqueda.MODO_IA.equals(modo) && !perfil.estaVacio() && lista instanceof List<?> l && !l.isEmpty()) {
            List<Map<String, Object>> reordenada = RankingBusqueda.reordenar((List<Map<String, Object>>) l, perfil, modo, Math.max(limite, l.size()));
            for (int i = 0; i < reordenada.size(); i++) {
                Map<String, Object> it = reordenada.get(i);
                it.put("posicion_ia", i + 1);
                Object trad = it.get("posicion_tradicional");
                if (trad instanceof Number n) it.put("delta_posicion", n.intValue() - (i + 1));
            }
            salida.put("resultados_ia", reordenada);
            personalizado = true;
        }
        salida.put("personalizado", personalizado);
        return salida;
    }

    // Trie para autocompletar (se reconstruye cuando se refresca el catalogo)
    // ---------------------------------------------------------------------
    private static final MetricasBusqueda METRICAS_BUSQUEDA = new MetricasBusqueda(archivoMetricas());

    /** El archivo queda en la carpeta del usuario (fuera del repositorio) para que git no lo vea. */
    private static Path archivoMetricas() {
        String fijo = System.getenv("METRICAS_BUSQUEDA_ARCHIVO");
        if (fijo != null && !fijo.isBlank()) {
            return Path.of(fijo.trim());
        }
        return Path.of(System.getProperty("user.home", "."), ".alicorp_bi", "metricas_busqueda.tsv");
    }

    private static final Object TRIE_LOCK = new Object();
    private static volatile TrieAutocompletado trieCache = null;
    private static volatile List<Map<String, Object>> trieBase = null;

    private static TrieAutocompletado trieCatalogo() {
        List<Map<String, Object>> base = catalogoCompleto();
        TrieAutocompletado t = trieCache;
        if (t != null && trieBase == base) {
            return t;
        }
        synchronized (TRIE_LOCK) {
            if (trieCache != null && trieBase == base) {
                return trieCache;
            }
            TrieAutocompletado nuevo = new TrieAutocompletado();
            Set<String> marcas = new HashSet<>();
            Set<String> categorias = new HashSet<>();
            for (Map<String, Object> item : base) {
                String nombre = String.valueOf(item.get("nombre"));
                int stock = ((Number) item.get("stock")).intValue();
                // Los productos con stock salen primero; los agotados quedan al final
                double puntaje = stock > 0 ? 10.0 + Math.min(stock, 5000) / 5000.0 : 0.0;
                TrieAutocompletado.Sugerencia sug = new TrieAutocompletado.Sugerencia(nombre, "producto", puntaje,
                        String.valueOf(item.get("categoria")), String.valueOf(item.get("marca")));
                // Se inserta el nombre completo y cada sufijo que empieza en una palabra,
                // asi "primor" encuentra "Aceite Primor 1L"
                String[] palabras = normalizarTexto(nombre).replaceAll("\\s+", " ").split(" ");
                for (int i = 0; i < palabras.length; i++) {
                    if (palabras[i].length() < 2) {
                        continue;
                    }
                    nuevo.insertar(String.join(" ", Arrays.copyOfRange(palabras, i, palabras.length)), sug);
                }
                marcas.add(String.valueOf(item.get("marca")));
                categorias.add(String.valueOf(item.get("categoria")));
            }
            for (String m : marcas) {
                nuevo.insertar(normalizarTexto(m), new TrieAutocompletado.Sugerencia(m, "marca", 15.0));
            }
            for (String c : categorias) {
                nuevo.insertar(normalizarTexto(c), new TrieAutocompletado.Sugerencia(c, "categoria", 16.0));
            }
            trieCache = nuevo;
            trieBase = base;
            return nuevo;
        }
    }

    private static List<Map<String, Object>> catalogoCompleto() {
        long ahora = System.currentTimeMillis();
        List<Map<String, Object>> c = catalogoCache;
        if (c == null || ahora - catalogoCacheTs > 30_000L) {
            c = consultarProductos(null, null, null, null, 1, LIMITE_CATALOGO);
            if (!c.isEmpty()) {
                catalogoCache = c;
                catalogoCacheTs = ahora;
            }
        }
        return c;
    }

    private static String normalizarTexto(String s) {
        if (s == null) {
            return "";
        }
        String n = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        return n.toLowerCase(Locale.ROOT).trim();
    }

    private static String raizPalabra(String p) {
        if (p.length() > 5 && p.endsWith("es")) {
            return p.substring(0, p.length() - 2);
        }
        if (p.length() > 3 && p.endsWith("s")) {
            return p.substring(0, p.length() - 1);
        }
        return p;
    }

    /** Busca por frases: ignora tildes y palabras vacias, usa sinonimos y exige que cada palabra (o su sinonimo) aparezca. */
    private static List<Map<String, Object>> buscarEnMemoria(List<Map<String, Object>> base, String q) {
        String limpio = normalizarTexto(q).replaceAll("[^a-z0-9 ]", " ");
        List<String> tokens = new ArrayList<>();
        for (String t : limpio.split("\\s+")) {
            if (t.isBlank() || PALABRAS_VACIAS.contains(t)) {
                continue;
            }
            tokens.add(raizPalabra(t));
        }
        if (tokens.isEmpty()) {
            for (String t : limpio.split("\\s+")) {
                if (!t.isBlank()) {
                    tokens.add(raizPalabra(t));
                }
            }
        }
        if (tokens.isEmpty()) {
            return base;
        }
        List<List<String>> grupos = new ArrayList<>();
        for (String t : tokens) {
            List<String> g = new ArrayList<>();
            g.add(t);
            g.addAll(SINONIMOS.getOrDefault(t, List.of()));
            grupos.add(g);
        }
        List<Map<String, Object>> exactos = new ArrayList<>();
        List<Object[]> parciales = new ArrayList<>();
        int indice = 0;
        for (Map<String, Object> item : base) {
            String texto = normalizarTexto(item.get("nombre") + " " + item.get("marca") + " "
                    + item.get("presentacion") + " " + item.get("categoria") + " "
                    + item.get("subcategoria") + " " + item.get("etiquetas"));
            int aciertos = 0;
            for (List<String> g : grupos) {
                for (String termino : g) {
                    if (texto.contains(termino)) {
                        aciertos++;
                        break;
                    }
                }
            }
            if (aciertos == grupos.size()) {
                exactos.add(item);
            } else if (aciertos > 0) {
                parciales.add(new Object[]{aciertos, indice, item});
            }
            indice++;
        }
        if (!exactos.isEmpty()) {
            return exactos;
        }
        parciales.sort((a, b) -> {
            int c = Integer.compare((Integer) b[0], (Integer) a[0]);
            return c != 0 ? c : Integer.compare((Integer) a[1], (Integer) b[1]);
        });
        List<Map<String, Object>> salida = new ArrayList<>();
        for (Object[] p : parciales) {
            salida.add((Map<String, Object>) p[2]);
        }
        return salida;
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
        boolean hayBusqueda = q != null && !q.isBlank();
        if ("precio_asc".equals(orden)) {
            sql.append(" ORDER BY p.precio_base ASC");
        } else if ("precio_desc".equals(orden)) {
            sql.append(" ORDER BY p.precio_base DESC");
        } else {
            sql.append(" ORDER BY p.id_producto ASC");
        }
        sql.append(" FETCH FIRST ? ROWS ONLY");
        params.add(hayBusqueda ? LIMITE_CATALOGO : limite);

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
                    item.put("imagen", EMOJIS_CATEGORIA.getOrDefault(rs.getString("nombre_categoria"), "📦"));
                    String[] extra = datosExtra().get(rs.getString("sku_codigo"));
                    if (extra != null) {
                        item.put("foto", extra[0]);
                        item.put("subcategoria", extra[1]);
                        item.put("etiquetas", extra[2]);
                    }
                    lista.add(item);
                }
            }
        } catch (SQLException e) {
            System.err.println("[!] Error en consultarProductos: " + e.getMessage());
        }
        if (hayBusqueda) {
            lista = buscarEnMemoria(lista, q);
            if (lista.size() > limite) {
                lista = new ArrayList<>(lista.subList(0, limite));
            }
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
