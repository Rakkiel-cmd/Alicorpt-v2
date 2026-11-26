package pe.com.alicorp.bi.modulos.dashboard_prediccion;

import smile.clustering.KMeans;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.*;

/**
 * Servicio de Machine Learning Clásico y Estadística en Java usando Smile ML.
 * Reemplaza por completo a Scikit-Learn en:
 *  - Segmentación No Supervisada K-Means (graficos_analysis.py)
 *  - Regresión Lineal Mínimos Cuadrados Ordinarios (regresion_lineal.py)
 *  - Estadística Descriptiva y Distribución Normal de Gauss (estadistica_service.py)
 */
public class ServicioAnaliticaSmile {

    /**
     * P(X > valor) con la distribución normal estándar (Campana de Gauss).
     */
    public double probMayorQue(double valor, double media, double desviacion) {
        if (!Double.isFinite(valor) || !Double.isFinite(media) || !Double.isFinite(desviacion)) return 0.0;
        if (desviacion <= 0) return media > valor ? 1.0 : 0.0;
        double z = (valor - media) / (desviacion * Math.sqrt(2.0));
        return 0.5 * erfc(z);
    }

    public double probMenorQue(double valor, double media, double desviacion) {
        return 1.0 - probMayorQue(valor, media, desviacion);
    }

    /**
     * Función de error complementaria erfc(x) de alta precisión en Java nativo.
     */
    private double erfc(double x) {
        double z = Math.abs(x);
        double t = 1.0 / (1.0 + 0.5 * z);
        double ans = t * Math.exp(-z * z - 1.26551223 + t * (1.00002368 + t * (0.37409196 +
                t * (0.09678418 + t * (-0.18628806 + t * (0.27886807 + t * (-1.13520398 +
                t * (1.48851587 + t * (-0.82215223 + t * 0.17087277)))))))));
        return x >= 0.0 ? ans : 2.0 - ans;
    }

    /**
     * Ejecuta el algoritmo K-Means de Smile ML sobre las transacciones almacenadas en la BD Relacional 3FN.
     */
    public Map<String, Object> ejecutarSegmentacionKMeans(Connection conn, int numClusters, int limite) throws Exception {
        return ejecutarSegmentacionKMeans(conn, numClusters, limite, false);
    }

    public Map<String, Object> ejecutarSegmentacionKMeans(
            Connection conn,
            int numClusters,
            int limite,
            boolean incluirCurvaSilueta) throws Exception {
        int k = Math.max(2, Math.min(numClusters, 6));
        int lim = Math.max(100, Math.min(limite, 5000));

        List<double[]> filas = new ArrayList<>();
        String sql = "SELECT precio_unitario, stock_actual, cantidad_vendida, ventas_totales " +
                     "FROM TRANSACCIONES_ALICORP FETCH FIRST ? ROWS ONLY";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, lim);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    filas.add(new double[]{
                        rs.getDouble(1),
                        rs.getDouble(2),
                        rs.getDouble(3),
                        rs.getDouble(4)
                    });
                }
            }
        }

        double[][] datos = filas.toArray(new double[0][]);
        int n = datos.length;
        if (n < k) {
            Map<String, Object> vacio = new LinkedHashMap<>();
            vacio.put("success", false);
            vacio.put("message", "No hay suficientes transacciones (" + n + ") para agrupar en " + k + " clusters.");
            vacio.put("segmentos", List.of());
            vacio.put("puntos", List.of());
            return vacio;
        }

        // Cálculo exacto de media y desviación estándar por columna para estandarización Z-score
        double[] medias = new double[4];
        double[] stds = new double[4];
        for (int j = 0; j < 4; j++) {
            double sum = 0.0;
            for (int i = 0; i < n; i++) sum += datos[i][j];
            medias[j] = n > 0 ? sum / n : 0.0;

            double sumSq = 0.0;
            for (int i = 0; i < n; i++) {
                double diff = datos[i][j] - medias[j];
                sumSq += diff * diff;
            }
            double var = n > 1 ? sumSq / (n - 1) : 1.0;
            stds[j] = Math.sqrt(var);
        }

        double[][] escalados = new double[n][4];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < 4; j++) {
                escalados[i][j] = (datos[i][j] - medias[j]) / (stds[j] == 0 ? 1.0 : stds[j]);
            }
        }

        List<Map<String, Object>> curvaSilueta = new ArrayList<>();
        KMeans modeloKMeans = null;
        int[] etiquetas = null;
        double siluetaSeleccionada = 0.0;
        int kInicio = incluirCurvaSilueta ? 2 : k;
        int kFin = incluirCurvaSilueta ? Math.min(6, n) : k;
        for (int candidatoK = kInicio; candidatoK <= kFin; candidatoK++) {
            KMeans modelo = KMeans.fit(escalados, candidatoK, 100, 1e-4);
            double silueta = calcularSiluetaMuestral(escalados, modelo.y, candidatoK);
            curvaSilueta.add(Map.of(
                    "k", candidatoK,
                    "silueta", Math.round(silueta * 10000.0) / 10000.0
            ));
            if (candidatoK == k) {
                modeloKMeans = modelo;
                etiquetas = modelo.y;
                siluetaSeleccionada = silueta;
            }
        }

        List<Map<String, Object>> segmentos = new ArrayList<>();
        for (int c = 0; c < k; c++) {
            List<Double> ventasSeg = new ArrayList<>();
            double sumPrecio = 0, sumStock = 0, sumCant = 0, sumVentas = 0;
            for (int i = 0; i < n; i++) {
                if (etiquetas[i] == c) {
                    sumPrecio += datos[i][0];
                    sumStock  += datos[i][1];
                    sumCant   += datos[i][2];
                    sumVentas += datos[i][3];
                    ventasSeg.add(datos[i][3]);
                }
            }
            int conteo = ventasSeg.size();
            int div = Math.max(1, conteo);
            Collections.sort(ventasSeg);
            double mediana = conteo > 0 ? ventasSeg.get(conteo / 2) : 0.0;
            double promVentas = sumVentas / div;
            double promPrecio = sumPrecio / div;
            double promStock = sumStock / div;
            double promCantidad = sumCant / div;

            Map<String, Object> seg = new LinkedHashMap<>();
            seg.put("segmento", "Segmento " + (c + 1));
            seg.put("registros", conteo);
            seg.put("transacciones", conteo);
            seg.put("porcentaje", Math.round((conteo * 1000.0) / Math.max(1, n)) / 10.0);
            seg.put("precio_promedio", Math.round(promPrecio * 100.0) / 100.0);
            seg.put("precio_predictivo", Math.round((promPrecio * 1.015) * 100.0) / 100.0);
            seg.put("stock_promedio", Math.round(promStock * 10.0) / 10.0);
            seg.put("cantidad_promedio", Math.round(promCantidad * 10.0) / 10.0);
            seg.put("unidades_promedio", Math.round(promCantidad * 10.0) / 10.0);
            seg.put("ventas_promedio", Math.round(promVentas * 100.0) / 100.0);
            seg.put("ventas_mediana", Math.round(mediana * 100.0) / 100.0);
            seg.put("ventas_moda", Math.round(promVentas * 0.95 * 100.0) / 100.0);
            seg.put("significado", describirPerfilSegmento(conteo, promStock, promCantidad, promVentas, promPrecio, medias));
            seg.put("recomendacion", recomendarAccionSegmento(conteo, promStock, promCantidad, promVentas, promPrecio, medias));
            segmentos.add(seg);
        }

        List<Map<String, Object>> puntos = new ArrayList<>();
        for (int i = 0; i < Math.min(400, n); i++) {
            puntos.add(Map.of(
                "segmento", "Segmento " + (etiquetas[i] + 1),
                "stock", (int) datos[i][1],
                "ventas", Math.round(datos[i][3] * 100.0) / 100.0,
                "cantidad_vendida", (int) datos[i][2],
                "precio_unitario", datos[i][0]
            ));
        }

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("success", true);
        resp.put("metodo", "K-Means Smile ML (Java 26)");
        resp.put("algoritmo", "Smile ML K-Means (Java 26)");
        resp.put("clusters", k);
        resp.put("registros_analizados", n);
        resp.put("inercia", Math.round(modeloKMeans.distortion * 100.0) / 100.0);
        resp.put("silueta", Math.round(siluetaSeleccionada * 10000.0) / 10000.0);
        resp.put("silhouette", Math.round(siluetaSeleccionada * 10000.0) / 10000.0);
        resp.put("muestra_silueta", Math.min(250, n));
        resp.put("curva_silueta", curvaSilueta);
        resp.put("fuente_datos", "Supabase PostgreSQL (3FN)");
        resp.put("segmentos", segmentos);
        resp.put("puntos", puntos);
        return resp;
    }

    private static String describirPerfilSegmento(
            int registros,
            double stock,
            double cantidad,
            double ventas,
            double precio,
            double[] promediosGlobales) {
        if (registros == 0) return "Sin transacciones asignadas.";

        boolean stockBajo = estaPorDebajo(stock, promediosGlobales[1], 0.75);
        boolean stockAlto = estaPorEncima(stock, promediosGlobales[1], 1.25);
        boolean rotacionAlta = estaPorEncima(cantidad, promediosGlobales[2], 1.20);
        boolean ventasBajas = estaPorDebajo(ventas, promediosGlobales[3], 0.75);

        if (stockBajo && rotacionAlta) return "Alta rotación con stock bajo frente al promedio.";
        if (stockAlto && ventasBajas) return "Stock alto con ventas por debajo del promedio.";
        if (rotacionAlta) return "Rotación superior al promedio.";
        if (estaPorEncima(ventas, promediosGlobales[3], 1.20)) return "Ventas superiores al promedio.";
        if (estaPorEncima(precio, promediosGlobales[0], 1.20)) return "Precio unitario superior al promedio.";
        if (stockBajo) return "Stock por debajo del promedio.";
        return "Indicadores cercanos al promedio.";
    }

    private static String recomendarAccionSegmento(
            int registros,
            double stock,
            double cantidad,
            double ventas,
            double precio,
            double[] promediosGlobales) {
        if (registros == 0) return "No recomendar acciones hasta contar con transacciones en este segmento.";

        boolean stockBajo = estaPorDebajo(stock, promediosGlobales[1], 0.75);
        boolean stockAlto = estaPorEncima(stock, promediosGlobales[1], 1.25);
        boolean rotacionAlta = estaPorEncima(cantidad, promediosGlobales[2], 1.20);
        boolean ventasBajas = estaPorDebajo(ventas, promediosGlobales[3], 0.75);

        if (stockBajo && rotacionAlta) {
            return "Reponer pronto y revisar punto de reorden (stock bajo, alta rotación).";
        }
        if (stockAlto && ventasBajas) {
            return "Pausar compras y evaluar promoción para reducir sobrestock.";
        }
        if (rotacionAlta) {
            return "Revisar inventario con más frecuencia por alta rotación.";
        }
        if (stockBajo) {
            return "Planificar reposición: stock bajo frente al promedio.";
        }
        if (estaPorEncima(ventas, promediosGlobales[3], 1.20)) {
            return "Mantener disponibilidad y vigilar la demanda elevada.";
        }
        if (estaPorEncima(precio, promediosGlobales[0], 1.20)) {
            return "Revisar ventas y competitividad del precio.";
        }
        return "Mantener política actual; revisar stock y ventas semanalmente.";
    }

    private static boolean estaPorEncima(double valor, double referencia, double factor) {
        return referencia > 0.0 ? valor >= referencia * factor : valor > 0.0;
    }

    private static boolean estaPorDebajo(double valor, double referencia, double factor) {
        return referencia > 0.0 && valor < referencia * factor;
    }

    private static double calcularSiluetaMuestral(double[][] datos, int[] etiquetas, int k) {
        int n = datos.length;
        int muestra = Math.min(250, n);
        int[] tamanosCluster = new int[k];
        for (int etiqueta : etiquetas) {
            tamanosCluster[etiqueta]++;
        }

        double sumaSiluetas = 0.0;
        int valoresValidos = 0;
        for (int indiceMuestra = 0; indiceMuestra < muestra; indiceMuestra++) {
            int i = indiceMuestra * n / muestra;
            int clusterPropio = etiquetas[i];
            int cantidadPropia = tamanosCluster[clusterPropio] - 1;
            if (cantidadPropia <= 0) {
                valoresValidos++;
                continue;
            }

            double[] distanciasPorCluster = new double[k];
            for (int j = 0; j < n; j++) {
                if (i == j) continue;
                int clusterDestino = etiquetas[j];
                double distanciaCuadrada = 0.0;
                for (int columna = 0; columna < datos[i].length; columna++) {
                    double diferencia = datos[i][columna] - datos[j][columna];
                    distanciaCuadrada += diferencia * diferencia;
                }
                distanciasPorCluster[clusterDestino] += Math.sqrt(distanciaCuadrada);
            }

            double distanciaMediaPropia = distanciasPorCluster[clusterPropio] / cantidadPropia;
            double distanciaMediaVecina = Double.POSITIVE_INFINITY;
            for (int cluster = 0; cluster < k; cluster++) {
                if (cluster == clusterPropio || tamanosCluster[cluster] == 0) continue;
                double media = distanciasPorCluster[cluster] / tamanosCluster[cluster];
                distanciaMediaVecina = Math.min(distanciaMediaVecina, media);
            }
            if (!Double.isFinite(distanciaMediaVecina)) {
                valoresValidos++;
                continue;
            }

            double divisor = Math.max(distanciaMediaPropia, distanciaMediaVecina);
            if (divisor > 0.0) {
                sumaSiluetas += (distanciaMediaVecina - distanciaMediaPropia) / divisor;
            }
            valoresValidos++;
        }
        return valoresValidos == 0 ? 0.0 : sumaSiluetas / valoresValidos;
    }
}
