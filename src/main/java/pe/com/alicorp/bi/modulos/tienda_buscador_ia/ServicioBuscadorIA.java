package pe.com.alicorp.bi.modulos.tienda_buscador_ia;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Servicio orquestador del "Buscador Predictivo con Ranking por IA".
 * Combina:
 *  1. Carga agregada desde Supabase PostgreSQL (BuscadorCatalogoRepository).
 *  2. Autocompletado estrictamente recursivo con Árbol de Prefijos (TrieProductos).
 *  3. Ranking Inteligente con Smile ML RidgeRegression (RankingProductosSmileIA).
 */
public class ServicioBuscadorIA {

    private final BuscadorCatalogoRepository repository;
    private volatile TrieProductos trie;
    private volatile RankingProductosSmileIA motorRanking;
    private volatile List<String> tiendasDisponibles = List.of();
    private volatile long ultimaCargaMs = 0L;
    private static final long TTL_CACHE_MS = 5 * 60 * 1000L; // 5 minutos

    public ServicioBuscadorIA() {
        this.repository = new BuscadorCatalogoRepository();
    }

    /**
     * Inicializa o refresca el Árbol Trie y el modelo Smile ML de forma segura por hilos.
     */
    public synchronized void asegurarIndiceCargado(boolean forzar) {
        long ahora = System.currentTimeMillis();
        if (!forzar && trie != null && motorRanking != null && (ahora - ultimaCargaMs) < TTL_CACHE_MS) {
            return;
        }
        List<ProductoCatalogoIA> catalogo = repository.cargarCatalogoAgregado();
        List<String> tiendas = repository.listarTiendas();

        TrieProductos nuevoTrie = new TrieProductos();
        for (ProductoCatalogoIA p : catalogo) {
            nuevoTrie.insertarProducto(p);
        }

        RankingProductosSmileIA nuevoRanking = new RankingProductosSmileIA();
        nuevoRanking.entrenarModelo(catalogo);

        this.trie = nuevoTrie;
        this.motorRanking = nuevoRanking;
        this.tiendasDisponibles = tiendas;
        this.ultimaCargaMs = ahora;
    }

    /**
     * Ejecuta la búsqueda recursiva en el Trie, mide su tiempo en milisegundos y
     * retorna la comparativa completa entre Orden Tradicional (Alfabético) y Ranking IA (Smile ML).
     */
    public Map<String, Object> buscarConComparativa(String query, String tienda, int limite) {
        asegurarIndiceCargado(false);

        String q = query != null ? query.trim() : "";
        String tiendaSel = tienda != null ? tienda.trim() : "";
        int lim = Math.max(1, Math.min(limite, 60));

        // 1. Medición exacta en milisegundos de la búsqueda recursiva en el Árbol Trie
        long tInicio = System.nanoTime();
        List<ProductoCatalogoIA> candidatos = trie.buscarPrefijoRecursivo(q);
        long tFin = System.nanoTime();
        double tiempoRecursivoMs = Math.round(((tFin - tInicio) / 1_000_000.0) * 1000.0) / 1000.0;

        // 2. Orden Tradicional (Alfabético A-Z)
        List<ProductoCatalogoIA> ordenTradicional = new ArrayList<>(candidatos);
        ordenTradicional.sort(Comparator.comparing(ProductoCatalogoIA::getNombre, String.CASE_INSENSITIVE_ORDER));

        Map<String, Integer> posTradicionalPorSku = new HashMap<>();
        for (int i = 0; i < ordenTradicional.size(); i++) {
            posTradicionalPorSku.put(ordenTradicional.get(i).getSku(), i + 1);
        }

        // 3. Ranking Inteligente (IA con Smile ML)
        List<RankingProductosSmileIA.ResultadoRankeado> ordenIA = motorRanking.rankearCandidatos(candidatos, q, tiendaSel);

        List<Map<String, Object>> listaTradicional = new ArrayList<>();
        for (int i = 0; i < Math.min(lim, ordenTradicional.size()); i++) {
            ProductoCatalogoIA p = ordenTradicional.get(i);
            Map<String, Object> item = convertirProductoBase(p, tiendaSel);
            item.put("posicion_tradicional", i + 1);
            listaTradicional.add(item);
        }

        List<Map<String, Object>> listaIA = new ArrayList<>();
        for (int i = 0; i < Math.min(lim, ordenIA.size()); i++) {
            RankingProductosSmileIA.ResultadoRankeado r = ordenIA.get(i);
            ProductoCatalogoIA p = r.getProducto();
            int posTrad = posTradicionalPorSku.getOrDefault(p.getSku(), i + 1);
            int posIA = i + 1;

            Map<String, Object> item = convertirProductoBase(p, tiendaSel);
            item.put("puntaje_ia", r.getPuntajeIA());
            item.put("posicion_ia", posIA);
            item.put("posicion_tradicional", posTrad);
            item.put("delta_posicion", posTrad - posIA);
            item.put("score_rotacion", r.getScoreRotacion());
            item.put("score_stock", r.getScoreStock());
            item.put("score_afinidad_tienda", r.getScoreAfinidadTienda());
            item.put("score_contenido", r.getScoreContenido());
            item.put("razon_ranking", r.getRazonRanking());
            listaIA.add(item);
        }

        Map<String, Object> respuesta = new LinkedHashMap<>();
        respuesta.put("success", true);
        respuesta.put("ok", true);
        respuesta.put("query", q);
        respuesta.put("tienda", tiendaSel.isEmpty() ? "Todas las tiendas" : tiendaSel);
        respuesta.put("tiendas_disponibles", tiendasDisponibles);
        respuesta.put("total_indexados_trie", trie.getTotalProductosIndexados());
        respuesta.put("total_encontrados", candidatos.size());
        respuesta.put("tiempo_recursivo_ms", tiempoRecursivoMs);
        respuesta.put("estructura_datos", "Árbol Trie Recursivo (TrieProductos.java)");
        respuesta.put("motor_ia", "Smile ML 3.1.1 (RidgeRegression + Contenido + Rotación + Stock + Afinidad)");
        respuesta.put("fuente_datos", "Supabase PostgreSQL JDBC (TRANSACCIONES_ALICORP + PRODUCTOS 3FN)");
        respuesta.put("resultados_tradicional", listaTradicional);
        respuesta.put("resultados_ia", listaIA);
        return respuesta;
    }

    public List<String> obtenerTiendasDisponibles() {
        asegurarIndiceCargado(false);
        return tiendasDisponibles;
    }

    private Map<String, Object> convertirProductoBase(ProductoCatalogoIA p, String tiendaSel) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("sku", p.getSku());
        m.put("nombre", p.getNombre());
        m.put("marca", p.getMarca());
        m.put("presentacion", p.getPresentacion());
        m.put("categoria", p.getCategoria());
        m.put("precio", p.getPrecioUnitario());
        m.put("stock", p.getStockActual());
        m.put("stock_promedio", p.getStockPromedioHistorico());
        m.put("temperatura_promedio", p.getTemperaturaPromedio());
        m.put("volumen_historico", p.getVolumenHistoricoUnidades());
        m.put("ventas_historicas", p.getVentasTotalesHistoricas());
        m.put("total_transacciones", p.getTotalTransacciones());
        m.put("afinidad_tienda", Math.round(p.getAfinidadParaTienda(tiendaSel) * 1000.0) / 10.0);
        return m;
    }
}
