package pe.com.alicorp.bi.modulos.estrategia_comercial;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Servicio de Filtrado Colaborativo entre Tiendas en Java puro.
 * Calcula la Similitud del Coseno y la Correlación de Pearson sobre arreglos double[] nativos
 * entre las 4 tiendas de Alicorp (Hipermercado Centro, Mayorista San Martín,
 * Supermercado Lima Norte y Tienda Express Los Olivos) usando volumen de ventas y rotación.
 */
public class ColaborativoTiendasService {

    public static class ResultadoColaborativo {
        private final String tiendaObjetivo;
        private final String tiendaSimilar;
        private final double similitudCoseno;
        private final double correlacionPearson;
        private final List<Map<String, Object>> matrizSimilitud;
        private final Map<String, String> insightsPorProducto;

        public ResultadoColaborativo(
                String tiendaObjetivo,
                String tiendaSimilar,
                double similitudCoseno,
                double correlacionPearson,
                List<Map<String, Object>> matrizSimilitud,
                Map<String, String> insightsPorProducto) {
            this.tiendaObjetivo = tiendaObjetivo;
            this.tiendaSimilar = tiendaSimilar;
            this.similitudCoseno = similitudCoseno;
            this.correlacionPearson = correlacionPearson;
            this.matrizSimilitud = matrizSimilitud;
            this.insightsPorProducto = insightsPorProducto;
        }

        public String getTiendaObjetivo() { return tiendaObjetivo; }
        public String getTiendaSimilar() { return tiendaSimilar; }
        public double getSimilitudCoseno() { return similitudCoseno; }
        public double getCorrelacionPearson() { return correlacionPearson; }
        public List<Map<String, Object>> getMatrizSimilitud() { return matrizSimilitud; }
        public Map<String, String> getInsightsPorProducto() { return insightsPorProducto; }
    }

    /**
     * Analiza la similitud entre tiendas y genera recomendaciones colaborativas para replicar
     * estrategias comerciales exitosas de la tienda más afín.
     */
    public ResultadoColaborativo analizarSimilitudTiendas(String tiendaObjetivo, List<DatosTiendaProducto> resumen) {
        Set<String> tiendasSet = new LinkedHashSet<>();
        Set<String> productosSet = new LinkedHashSet<>();
        Map<String, Map<String, DatosTiendaProducto>> tabla = new LinkedHashMap<>();

        for (DatosTiendaProducto d : resumen) {
            tiendasSet.add(d.getTienda());
            productosSet.add(d.getProducto());
            tabla.computeIfAbsent(d.getTienda(), k -> new LinkedHashMap<>()).put(d.getProducto(), d);
        }

        List<String> tiendas = new ArrayList<>(tiendasSet);
        List<String> productos = new ArrayList<>(productosSet);
        if (tiendas.isEmpty()) {
            return new ResultadoColaborativo(tiendaObjetivo, "Mayorista San Martín", 0.0, 0.0, List.of(), Map.of());
        }

        String objetivoReal = resolverTienda(tiendaObjetivo, tiendas);

        // Construir vectores double[] nativos de 2*N dimensiones: [ventas_totales_p_i, rotacion_p_i]
        Map<String, double[]> vectores = new LinkedHashMap<>();
        int dim = productos.size() * 2;
        for (String t : tiendas) {
            double[] vec = new double[dim];
            Map<String, DatosTiendaProducto> mapaProd = tabla.getOrDefault(t, Map.of());
            for (int i = 0; i < productos.size(); i++) {
                DatosTiendaProducto d = mapaProd.get(productos.get(i));
                if (d != null) {
                    vec[i] = d.getVentasTotales();
                    vec[productos.size() + i] = d.getCantidadVendida() * 1000.0;
                }
            }
            vectores.put(t, vec);
        }

        double[] vecObjetivo = vectores.get(objetivoReal);
        String mejorVecino = objetivoReal;
        double mejorCoseno = -1.0;
        double mejorPearson = 0.0;
        List<Map<String, Object>> matriz = new ArrayList<>();

        for (String candidata : tiendas) {
            double[] vecCand = vectores.get(candidata);
            double cos = calcularSimilitudCoseno(vecObjetivo, vecCand);
            double pear = calcularCorrelacionPearson(vecObjetivo, vecCand);

            Map<String, Object> fila = new LinkedHashMap<>();
            fila.put("tienda", candidata);
            fila.put("es_objetivo", candidata.equals(objetivoReal));
            fila.put("similitud_coseno", Math.round(cos * 10000.0) / 10000.0);
            fila.put("similitud_pct", Math.round(cos * 1000.0) / 10.0);
            fila.put("correlacion_pearson", Math.round(pear * 10000.0) / 10000.0);
            matriz.add(fila);

            if (!candidata.equals(objetivoReal) && cos > mejorCoseno) {
                mejorCoseno = cos;
                mejorPearson = pear;
                mejorVecino = candidata;
            }
        }

        // Generar recomendaciones colaborativas comparando producto por producto contra la tienda más similar
        Map<String, String> insights = new LinkedHashMap<>();
        Map<String, DatosTiendaProducto> datosObj = tabla.getOrDefault(objetivoReal, Map.of());
        Map<String, DatosTiendaProducto> datosVec = tabla.getOrDefault(mejorVecino, Map.of());
        double simPct = Math.round(Math.max(0.0, mejorCoseno) * 1000.0) / 10.0;

        for (String prod : productos) {
            DatosTiendaProducto dObj = datosObj.get(prod);
            DatosTiendaProducto dVec = datosVec.get(prod);
            if (dObj == null || dVec == null) continue;

            double diffVentasPct = dObj.getVentasTotales() > 0
                    ? ((dVec.getVentasTotales() - dObj.getVentasTotales()) / dObj.getVentasTotales()) * 100.0
                    : 0.0;
            double diffPrecio = dVec.getPrecioUnitario() - dObj.getPrecioUnitario();

            if (diffVentasPct > 0.5) {
                String ajuste = diffPrecio < -0.05
                        ? String.format("precio competitivo S/ %.2f (%.2f menos)", dVec.getPrecioUnitario(), Math.abs(diffPrecio))
                        : String.format("mayor rotación (%.1f und./trx a S/ %.2f)", dVec.getCantidadVendida(), dVec.getPrecioUnitario());
                insights.put(prod, String.format(
                        "Replicar de %s (similitud %.1f%%): logró +%.1f%% más ventas con %s.",
                        mejorVecino, simPct, diffVentasPct, ajuste));
            } else {
                insights.put(prod, String.format(
                        "Líder frente a %s (similitud %.1f%%): mantener ventaja operativa y compartir práctica.",
                        mejorVecino, simPct));
            }
        }

        return new ResultadoColaborativo(
                objetivoReal,
                mejorVecino,
                Math.round(Math.max(0.0, mejorCoseno) * 10000.0) / 10000.0,
                Math.round(mejorPearson * 10000.0) / 10000.0,
                matriz,
                insights
        );
    }

    /**
     * Similitud del Coseno en arreglos double[] nativos: (A · B) / (||A|| * ||B||).
     */
    public double calcularSimilitudCoseno(double[] a, double[] b) {
        if (a == null || b == null || a.length != b.length || a.length == 0) return 0.0;
        double dot = 0.0, normA = 0.0, normB = 0.0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        double denom = Math.sqrt(normA) * Math.sqrt(normB);
        return denom > 1e-9 ? dot / denom : 0.0;
    }

    /**
     * Correlación de Pearson en arreglos double[] nativos.
     */
    public double calcularCorrelacionPearson(double[] a, double[] b) {
        if (a == null || b == null || a.length != b.length || a.length < 2) return 0.0;
        double meanA = 0.0, meanB = 0.0;
        for (int i = 0; i < a.length; i++) {
            meanA += a[i];
            meanB += b[i];
        }
        meanA /= a.length;
        meanB /= b.length;

        double num = 0.0, denA = 0.0, denB = 0.0;
        for (int i = 0; i < a.length; i++) {
            double da = a[i] - meanA;
            double db = b[i] - meanB;
            num += da * db;
            denA += da * da;
            denB += db * db;
        }
        double denom = Math.sqrt(denA * denB);
        return denom > 1e-9 ? num / denom : 0.0;
    }

    private String resolverTienda(String solicitada, List<String> disponibles) {
        if (solicitada != null && !solicitada.isBlank()) {
            for (String t : disponibles) {
                if (t.equalsIgnoreCase(solicitada.trim())) return t;
            }
            for (String t : disponibles) {
                if (t.toLowerCase().contains(solicitada.trim().toLowerCase())) return t;
            }
        }
        return disponibles.get(0);
    }
}
