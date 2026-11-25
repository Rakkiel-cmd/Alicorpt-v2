package pe.com.alicorp.bi.modulos.estrategia_comercial;

import smile.base.cart.SplitRule;
import smile.classification.DecisionTree;
import smile.data.DataFrame;
import smile.data.Tuple;
import smile.data.formula.Formula;
import smile.data.vector.DoubleVector;
import smile.data.vector.IntVector;
import smile.validation.Bag;
import smile.validation.CrossValidation;
import smile.validation.metric.ConfusionMatrix;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Servicio de Árbol de Decisión + Validación Cruzada (K-Fold Cross-Validation) usando Smile ML 3.1.1.
 * Entrena un DecisionTree con (precio_unitario, stock_actual, temperatura_zona, cantidad_vendida),
 * clasifica en las 4 estrategias comerciales, ejecuta K-Fold Cross-Validation (k=5)
 * y expone Accuracy, Precision, Recall, F1-Score, Matriz de Confusión y comparativa vs reglas estáticas.
 */
public class EstrategiaDecisionService {

    public static final String[] CLASES = {
            "Reabastecimiento Urgente",
            "Impulso en Góndola / Promoción",
            "Liquidación por Sobrestock",
            "Mantener Estrategia"
    };

    private final RecomendadorSupabaseRepository repository;
    private final ColaborativoTiendasService colaborativoService;

    private volatile DecisionTree modeloArbol;
    private volatile NodoEstrategia arbolRecursivo;
    private volatile List<DatosTiendaProducto> resumenCache = List.of();
    private volatile Map<String, Object> metricasValidacionCache = Map.of();
    private volatile long ultimaCargaMs = 0L;
    private static final long TTL_MS = 5 * 60 * 1000L;

    public EstrategiaDecisionService() {
        this.repository = new RecomendadorSupabaseRepository();
        this.colaborativoService = new ColaborativoTiendasService();
    }

    /**
     * Carga datos agregados desde Supabase, calibra el árbol recursivo, entrena Smile DecisionTree
     * y ejecuta K-Fold Stratified Cross-Validation (k=5).
     */
    public synchronized void asegurarModeloEntrenado(boolean forzar) {
        long ahora = System.currentTimeMillis();
        if (!forzar && modeloArbol != null && !metricasValidacionCache.isEmpty() && (ahora - ultimaCargaMs) < TTL_MS) {
            return;
        }

        List<DatosTiendaProducto> agregadosMensuales = repository.cargarAgregadosMensuales();
        List<DatosTiendaProducto> resumenTiendas = repository.cargarResumenPorTiendaProducto();

        if (agregadosMensuales.isEmpty()) {
            this.resumenCache = resumenTiendas;
            return;
        }

        // 1. Calibrar umbrales adaptativos a partir de los percentiles reales de Supabase
        double[] stocks = agregadosMensuales.stream().mapToDouble(DatosTiendaProducto::getStockActual).sorted().toArray();
        double[] demandas = agregadosMensuales.stream().mapToDouble(DatosTiendaProducto::getCantidadVendida).sorted().toArray();
        double[] precios = agregadosMensuales.stream().mapToDouble(DatosTiendaProducto::getPrecioUnitario).sorted().toArray();
        double[] temps = agregadosMensuales.stream().mapToDouble(DatosTiendaProducto::getTemperaturaZona).sorted().toArray();

        double p30Stock = percentil(stocks, 0.30);
        double p70Stock = percentil(stocks, 0.70);
        double p50Demanda = percentil(demandas, 0.50);
        double p65Precio = percentil(precios, 0.65);
        double p65Temp = percentil(temps, 0.65);

        this.arbolRecursivo = NodoEstrategia.construirArbolEstrategia(
                p30Stock, p70Stock, p50Demanda, p65Precio, p65Temp
        );

        // 2. Construir dataset etiquetado (X: 4 features, y: clase 0..3)
        int n = agregadosMensuales.size();
        double[] colPrecio = new double[n];
        double[] colStock = new double[n];
        double[] colTemp = new double[n];
        double[] colCant = new double[n];
        int[] yReal = new int[n];

        for (int i = 0; i < n; i++) {
            DatosTiendaProducto d = agregadosMensuales.get(i);
            colPrecio[i] = d.getPrecioUnitario();
            colStock[i] = d.getStockActual();
            colTemp[i] = d.getTemperaturaZona();
            colCant[i] = d.getCantidadVendida();
            yReal[i] = asignarClaseEstrategia(d, p30Stock, p70Stock, p50Demanda, p65Precio, p65Temp);
        }

        DataFrame dfCompleto = DataFrame.of(
                DoubleVector.of("precio_unitario", colPrecio),
                DoubleVector.of("stock_actual", colStock),
                DoubleVector.of("temperatura_zona", colTemp),
                DoubleVector.of("cantidad_vendida", colCant),
                IntVector.of("estrategia", yReal)
        );
        Formula formula = Formula.lhs("estrategia");

        // 3. Entrenar Árbol de Decisión completo con Smile ML (CART / GINI)
        this.modeloArbol = DecisionTree.fit(formula, dfCompleto, SplitRule.GINI, 8, 20, 2);

        // 4. K-Fold Cross-Validation estratificado (k=5) con Smile ML
        int kFolds = Math.min(5, Math.max(2, n / 10));
        Bag[] folds = CrossValidation.stratify(yReal, kFolds);
        int[] yPredCV = new int[n];
        Arrays.fill(yPredCV, 3);
        List<Map<String, Object>> detalleFolds = new ArrayList<>();

        for (int f = 0; f < folds.length; f++) {
            int[] idxTrain = folds[f].samples;
            int[] idxTest = folds[f].oob;
            if (idxTrain.length == 0 || idxTest.length == 0) continue;

            DataFrame dfTrain = dfCompleto.of(idxTrain);
            DataFrame dfTest = dfCompleto.of(idxTest);
            DecisionTree arbolFold = DecisionTree.fit(formula, dfTrain, SplitRule.GINI, 8, 20, 2);

            int aciertosFold = 0;
            for (int j = 0; j < idxTest.length; j++) {
                int pred = arbolFold.predict(dfTest.get(j));
                yPredCV[idxTest[j]] = pred;
                if (pred == yReal[idxTest[j]]) aciertosFold++;
            }
            double accFold = Math.round((aciertosFold * 10000.0) / idxTest.length) / 100.0;
            detalleFolds.add(Map.of(
                    "fold", f + 1,
                    "muestras_prueba", idxTest.length,
                    "accuracy_pct", accFold
            ));
        }

        // 5. Calcular métricas reales del Árbol de Decisión (CV) vs Reglas Estáticas Básicas
        MetricasClasificacion metML = calcularMetricas(yReal, yPredCV, CLASES.length);

        int[] yPredEstatico = new int[n];
        for (int i = 0; i < n; i++) {
            // Baseline de reglas estáticas básicas fijas (solo umbral fijo de stock)
            if (colStock[i] < 150.0) yPredEstatico[i] = 0;
            else if (colStock[i] > 400.0) yPredEstatico[i] = 2;
            else yPredEstatico[i] = 3;
        }
        MetricasClasificacion metBaseline = calcularMetricas(yReal, yPredEstatico, CLASES.length);

        Map<String, Object> validacion = new LinkedHashMap<>();
        validacion.put("success", true);
        validacion.put("algoritmo", "Smile ML 3.1.1 DecisionTree (CART · Gini)");
        validacion.put("metodo_validacion", kFolds + "-Fold Stratified Cross-Validation");
        validacion.put("k_folds", kFolds);
        validacion.put("total_muestras_agregadas", n);
        validacion.put("fuente_datos", "Supabase PostgreSQL (GROUP BY tienda, producto, mes sobre TRANSACCIONES_ALICORP)");
        validacion.put("accuracy", metML.accuracy);
        validacion.put("precision", metML.precision);
        validacion.put("recall", metML.recall);
        validacion.put("f1_score", metML.f1Score);
        validacion.put("clases", List.of(CLASES));
        validacion.put("matriz_confusion", convertirMatriz(metML.confusion.matrix));
        validacion.put("detalle_folds", detalleFolds);
        validacion.put("comparativa_reglas_estaticas", Map.of(
                "baseline_nombre", "Reglas Estáticas Fijas (stock < 150 / > 400)",
                "baseline_accuracy", metBaseline.accuracy,
                "baseline_f1_score", metBaseline.f1Score,
                "modelo_ml_accuracy", metML.accuracy,
                "modelo_ml_f1_score", metML.f1Score,
                "mejora_accuracy_pts", Math.round((metML.accuracy - metBaseline.accuracy) * 100.0) / 100.0,
                "mejora_f1_pts", Math.round((metML.f1Score - metBaseline.f1Score) * 100.0) / 100.0
        ));

        this.resumenCache = resumenTiendas;
        this.metricasValidacionCache = validacion;
        this.ultimaCargaMs = ahora;
    }

    /**
     * Genera las estrategias comerciales y de reabastecimiento para la tienda seleccionada,
     * combinando Filtrado Colaborativo + Predicción del Árbol Smile ML + Evaluación Recursiva.
     */
    public Map<String, Object> obtenerEstrategiasPorTienda(String tiendaSolicitada) {
        asegurarModeloEntrenado(false);

        ColaborativoTiendasService.ResultadoColaborativo colab =
                colaborativoService.analizarSimilitudTiendas(tiendaSolicitada, resumenCache);
        String tiendaReal = colab.getTiendaObjetivo();

        List<Map<String, Object>> recomendaciones = new ArrayList<>();
        List<Map<String, Object>> rutasGlobales = new ArrayList<>();

        for (DatosTiendaProducto d : resumenCache) {
            if (!d.getTienda().equalsIgnoreCase(tiendaReal)) continue;

            // 1. Evaluación estrictamente recursiva en NodoEstrategia
            NodoEstrategia.ResultadoEvaluacion evalRec =
                    NodoEstrategia.evaluarRutaRecomendacion(arbolRecursivo, d);

            // 2. Predicción del Árbol de Decisión Smile ML entrenado
            String predSmile = evalRec.getRecomendacion();
            if (modeloArbol != null) {
                Tuple fila = DataFrame.of(
                        DoubleVector.of("precio_unitario", new double[]{d.getPrecioUnitario()}),
                        DoubleVector.of("stock_actual", new double[]{d.getStockActual()}),
                        DoubleVector.of("temperatura_zona", new double[]{d.getTemperaturaZona()}),
                        DoubleVector.of("cantidad_vendida", new double[]{d.getCantidadVendida()}),
                        IntVector.of("estrategia", new int[]{0})
                ).get(0);
                int idxClase = modeloArbol.predict(fila);
                if (idxClase >= 0 && idxClase < CLASES.length) {
                    predSmile = CLASES[idxClase];
                }
            }

            String insightColab = colab.getInsightsPorProducto().getOrDefault(
                    d.getProducto(), "Sin referencia colaborativa adicional.");

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("producto", d.getProducto());
            item.put("precio_unitario", d.getPrecioUnitario());
            item.put("stock_actual", d.getStockActual());
            item.put("temperatura_zona", d.getTemperaturaZona());
            item.put("cantidad_vendida", d.getCantidadVendida());
            item.put("ventas_totales", d.getVentasTotales());
            item.put("transacciones", d.getTransacciones());
            item.put("recomendacion_ml", predSmile);
            item.put("recomendacion_recursiva", evalRec.getRecomendacion());
            item.put("prioridad", evalRec.getPrioridad());
            item.put("accion_sugerida", evalRec.getAccionSugerida());
            item.put("insight_colaborativo", insightColab);
            item.put("profundidad_arbol", evalRec.getProfundidad());
            item.put("ruta_explicacion", evalRec.getRutaExplicacion());
            recomendaciones.add(item);

            rutasGlobales.add(Map.of(
                    "producto", d.getProducto(),
                    "decision", evalRec.getRecomendacion(),
                    "prioridad", evalRec.getPrioridad(),
                    "pasos", evalRec.getRutaExplicacion()
            ));
        }

        // Ordenar por prioridad: ALTA -> MEDIA -> BAJA
        recomendaciones.sort((a, b) -> Integer.compare(
                pesoPrioridad(String.valueOf(a.get("prioridad"))),
                pesoPrioridad(String.valueOf(b.get("prioridad")))
        ));

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("success", true);
        resp.put("tienda", tiendaReal);
        resp.put("tienda_similar", colab.getTiendaSimilar());
        resp.put("similitud_coseno", colab.getSimilitudCoseno());
        resp.put("correlacion_pearson", colab.getCorrelacionPearson());
        resp.put("matriz_similitud", colab.getMatrizSimilitud());
        resp.put("recomendaciones", recomendaciones);
        resp.put("ruta_decision", rutasGlobales);
        return resp;
    }

    public Map<String, Object> obtenerValidacionModelo() {
        asegurarModeloEntrenado(false);
        return metricasValidacionCache;
    }

    private int asignarClaseEstrategia(
            DatosTiendaProducto d,
            double p30Stock,
            double p70Stock,
            double p50Demanda,
            double p65Precio,
            double p65Temp) {
        if (d.getStockActual() <= p30Stock && d.getCantidadVendida() > p50Demanda * 0.90) {
            return 0; // Reabastecimiento Urgente
        }
        if (d.getStockActual() > p70Stock && d.getCantidadVendida() <= p50Demanda) {
            return 2; // Liquidación por Sobrestock
        }
        if (d.getPrecioUnitario() > p65Precio || d.getTemperaturaZona() > p65Temp || d.getStockActual() <= p30Stock) {
            return 1; // Impulso en Góndola / Promoción
        }
        return 3; // Mantener Estrategia
    }

    private static int pesoPrioridad(String p) {
        return switch (p) {
            case "ALTA" -> 1;
            case "MEDIA" -> 2;
            default -> 3;
        };
    }

    private static double percentil(double[] ordenados, double p) {
        if (ordenados == null || ordenados.length == 0) return 0.0;
        int idx = Math.min(ordenados.length - 1, Math.max(0, (int) Math.floor(p * (ordenados.length - 1))));
        return ordenados[idx];
    }

    private static class MetricasClasificacion {
        final double accuracy;
        final double precision;
        final double recall;
        final double f1Score;
        final ConfusionMatrix confusion;

        MetricasClasificacion(double accuracy, double precision, double recall, double f1Score, ConfusionMatrix confusion) {
            this.accuracy = accuracy;
            this.precision = precision;
            this.recall = recall;
            this.f1Score = f1Score;
            this.confusion = confusion;
        }
    }

    private MetricasClasificacion calcularMetricas(int[] yReal, int[] yPred, int numClases) {
        ConfusionMatrix cm = ConfusionMatrix.of(yReal, yPred);
        int[][] raw = cm.matrix;
        int[][] m = new int[numClases][numClases];
        for (int i = 0; i < Math.min(numClases, raw.length); i++) {
            System.arraycopy(raw[i], 0, m[i], 0, Math.min(numClases, raw[i].length));
        }

        int total = yReal.length;
        int correctos = 0;
        for (int i = 0; i < total; i++) {
            if (yReal[i] == yPred[i]) correctos++;
        }
        double acc = total > 0 ? (correctos * 100.0) / total : 0.0;

        double sumPrec = 0.0, sumRec = 0.0, sumF1 = 0.0;
        int clasesActivas = 0;
        for (int c = 0; c < numClases; c++) {
            int tp = m[c][c];
            int fp = 0, fn = 0;
            for (int k = 0; k < numClases; k++) {
                if (k != c) {
                    fp += m[k][c];
                    fn += m[c][k];
                }
            }
            if (tp + fn == 0 && tp + fp == 0) continue;
            clasesActivas++;
            double prec = (tp + fp) > 0 ? (double) tp / (tp + fp) : 0.0;
            double rec = (tp + fn) > 0 ? (double) tp / (tp + fn) : 0.0;
            double f1 = (prec + rec) > 0 ? (2.0 * prec * rec) / (prec + rec) : 0.0;
            sumPrec += prec;
            sumRec += rec;
            sumF1 += f1;
        }

        double macroPrec = clasesActivas > 0 ? (sumPrec / clasesActivas) * 100.0 : 0.0;
        double macroRec = clasesActivas > 0 ? (sumRec / clasesActivas) * 100.0 : 0.0;
        double macroF1 = clasesActivas > 0 ? (sumF1 / clasesActivas) * 100.0 : 0.0;

        return new MetricasClasificacion(
                Math.round(acc * 100.0) / 100.0,
                Math.round(macroPrec * 100.0) / 100.0,
                Math.round(macroRec * 100.0) / 100.0,
                Math.round(macroF1 * 100.0) / 100.0,
                new ConfusionMatrix(m)
        );
    }

    private List<List<Integer>> convertirMatriz(int[][] mat) {
        List<List<Integer>> out = new ArrayList<>();
        for (int[] fila : mat) {
            List<Integer> row = new ArrayList<>();
            for (int v : fila) row.add(v);
            out.add(row);
        }
        return out;
    }
}
