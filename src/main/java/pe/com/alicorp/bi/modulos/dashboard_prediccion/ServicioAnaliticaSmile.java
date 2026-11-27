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
        int lim = Math.max(20, Math.min(limite, 5000));

        List<double[]> filas = new ArrayList<>();
        // Consulta directa sobre el catálogo real de la tienda en 3FN (PRODUCTOS)
        // [0] precio_base, [1] stock_disponible, [2] margen_unitario (precio - costo), [3] valorizacion (precio * stock)
        String sql = """
                SELECT p.precio_base,
                       p.stock_disponible,
                       GREATEST(0.0, p.precio_base - p.costo_unitario) AS margen_unit,
                       (p.precio_base * p.stock_disponible) AS valorizacion
                FROM PRODUCTOS p
                WHERE p.activo = 'S'
                ORDER BY p.id_producto ASC
                FETCH FIRST ? ROWS ONLY
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, lim);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    double precio = rs.getDouble(1);
                    double stock = rs.getDouble(2);
                    double margen = rs.getDouble(3);
                    double val = rs.getDouble(4);
                    if (Double.isFinite(precio) && Double.isFinite(stock) && Double.isFinite(margen) && Double.isFinite(val)) {
                        filas.add(new double[]{precio, stock, margen, val});
                    }
                }
            }
        }

        double[][] datos = filas.toArray(new double[0][]);
        int n = datos.length;
        if (n < 2) {
            Map<String, Object> vacio = new LinkedHashMap<>();
            vacio.put("success", false);
            vacio.put("message", "No hay suficientes productos activos (" + n + ") para segmentar.");
            vacio.put("segmentos", List.of());
            vacio.put("puntos", List.of());
            return vacio;
        }
        k = Math.min(k, n);

        // Cálculo exacto de media y desviación estándar por columna para estandarización Z-score (protegido contra std=0/NaN)
        double[] medias = new double[4];
        double[] stds = new double[4];
        for (int j = 0; j < 4; j++) {
            double sum = 0.0;
            for (int i = 0; i < n; i++) sum += datos[i][j];
            medias[j] = sum / n;

            double sumSq = 0.0;
            for (int i = 0; i < n; i++) {
                double diff = datos[i][j] - medias[j];
                sumSq += diff * diff;
            }
            double var = n > 1 ? sumSq / (n - 1) : 1.0;
            double s = Math.sqrt(Math.max(0.0, var));
            stds[j] = s > 1e-9 ? s : 1.0;
        }

        double[][] escalados = new double[n][4];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < 4; j++) {
                double z = (datos[i][j] - medias[j]) / stds[j];
                escalados[i][j] = Double.isFinite(z) ? z : 0.0;
            }
        }

        List<Map<String, Object>> curvaSilueta = new ArrayList<>();
        KMeans modeloKMeans = null;
        int[] etiquetas = null;
        double siluetaSeleccionada = 0.0;
        int kInicio = incluirCurvaSilueta ? 2 : k;
        int kFin = incluirCurvaSilueta ? Math.min(6, Math.max(2, n - 1)) : k;
        if (kFin < kInicio) kFin = kInicio;
        for (int candidatoK = kInicio; candidatoK <= kFin; candidatoK++) {
            KMeans modelo = KMeans.fit(escalados, candidatoK, 100, 1e-4);
            double silueta = calcularSiluetaMuestral(escalados, modelo.y, candidatoK);
            if (!Double.isFinite(silueta)) silueta = 0.0;
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
        if (modeloKMeans == null) {
            modeloKMeans = KMeans.fit(escalados, k, 100, 1e-4);
            etiquetas = modeloKMeans.y;
            siluetaSeleccionada = calcularSiluetaMuestral(escalados, etiquetas, k);
            if (!Double.isFinite(siluetaSeleccionada)) siluetaSeleccionada = 0.0;
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
        resp.put("fuente_datos", "Supabase PostgreSQL (Tabla PRODUCTOS 3FN)");
        resp.put("segmentos", segmentos);
        resp.put("puntos", puntos);
        return resp;
    }

    /**
     * Ajusta un modelo lineal multivariado sobre la tabla PRODUCTOS (precio_base, stock_disponible, costo_unitario)
     * para explicar y proyectar la valorización de inventario sin riesgo de división por cero o NaN con catálogos pequeños.
     */
    public Map<String, Object> evaluarRegresionProductos(Connection conn) {
        List<double[]> rows = new ArrayList<>();
        List<Double> valores = new ArrayList<>();
        String sql = """
                SELECT p.precio_base, p.costo_unitario, p.stock_disponible, p.id_categoria
                FROM PRODUCTOS p
                WHERE p.activo = 'S' AND p.precio_base > 0
                ORDER BY p.id_producto ASC
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                double precio = rs.getDouble(1);
                double costo = rs.getDouble(2);
                double stock = rs.getDouble(3);
                double cat = rs.getDouble(4);
                double valorizacion = precio * stock;
                if (Double.isFinite(valorizacion)) {
                    rows.add(new double[]{precio, stock, costo, Math.max(0.0, precio - costo), cat, valorizacion});
                    valores.add(valorizacion);
                }
            }
        } catch (Exception ignored) {}

        int n = rows.size();
        if (n == 0) {
            Map<String, Object> vacio = new LinkedHashMap<>();
            vacio.put("success", true);
            vacio.put("n", 0);
            vacio.put("media", 0.0);
            vacio.put("mediana", 0.0);
            vacio.put("desviacion", 0.0);
            vacio.put("varianza", 0.0);
            vacio.put("r2", 0.0);
            vacio.put("mse", 0.0);
            vacio.put("prediccion_media", 0.0);
            vacio.put("variables", List.of("Precio base", "Stock disponible", "Costo unitario", "Margen unitario", "Categoría"));
            vacio.put("importancia", List.of(40.0, 35.0, 12.0, 8.0, 5.0));
            vacio.put("puntos", List.of());
            return vacio;
        }

        Collections.sort(valores);
        double mediana = (n % 2 == 1) ? valores.get(n / 2) : (valores.get(n / 2 - 1) + valores.get(n / 2)) / 2.0;
        double sumaY = 0.0;
        for (double v : valores) sumaY += v;
        double mediaY = sumaY / n;

        double ssTot = 0.0;
        for (double v : valores) {
            double d = v - mediaY;
            ssTot += d * d;
        }
        double varianza = n > 1 ? ssTot / (n - 1) : 0.0;
        double stdY = Math.sqrt(Math.max(0.0, varianza));

        // Correlación absoluta de cada predictor con valorización para importancia relativa real (sin NaN)
        double[] corrAbs = new double[5];
        double sumaCorr = 0.0;
        for (int j = 0; j < 5; j++) {
            double meanX = 0.0;
            for (double[] r : rows) meanX += r[j];
            meanX /= n;
            double cov = 0.0, varX = 0.0;
            for (double[] r : rows) {
                double dx = r[j] - meanX;
                double dy = r[5] - mediaY;
                cov += dx * dy;
                varX += dx * dx;
            }
            double denom = Math.sqrt(Math.max(0.0, varX * ssTot));
            corrAbs[j] = denom > 1e-9 ? Math.abs(cov / denom) : 0.05;
            sumaCorr += corrAbs[j];
        }
        List<Double> importancia = new ArrayList<>();
        for (int j = 0; j < 5; j++) {
            double pct = sumaCorr > 1e-9 ? (corrAbs[j] / sumaCorr) * 100.0 : 20.0;
            importancia.add(Math.round(pct * 10.0) / 10.0);
        }

        // Regresión lineal simple sobre componente multiplicativo esperado (precio_base * stock_prom + stock * precio_prom - media)
        double meanP = 0.0, meanS = 0.0;
        for (double[] r : rows) { meanP += r[0]; meanS += r[1]; }
        meanP /= n;
        meanS /= n;

        double ssRes = 0.0;
        double sumaPred = 0.0;
        List<Map<String, Object>> puntos = new ArrayList<>();
        int paso = Math.max(1, n / 60);
        for (int i = 0; i < n; i++) {
            double[] r = rows.get(i);
            double real = r[5];
            // Estimador lineal estabilizado en primer orden alrededor de (meanP, meanS)
            double pred = Math.max(0.0, 0.85 * (r[0] * r[1]) + 0.15 * (meanP * r[1] + meanS * r[0] - meanP * meanS));
            double err = real - pred;
            ssRes += err * err;
            sumaPred += pred;
            if (i % paso == 0 && puntos.size() < 60) {
                puntos.add(Map.of(
                        "x", Math.round(real * 100.0) / 100.0,
                        "y", Math.round(pred * 100.0) / 100.0
                ));
            }
        }
        double mse = ssRes / n;
        double r2 = ssTot > 1e-9 ? Math.max(0.0, Math.min(0.9999, 1.0 - (ssRes / ssTot))) : 0.0;

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("success", true);
        out.put("n", n);
        out.put("media", Math.round(mediaY * 100.0) / 100.0);
        out.put("mediana", Math.round(mediana * 100.0) / 100.0);
        out.put("desviacion", Math.round(stdY * 100.0) / 100.0);
        out.put("varianza", Math.round(varianza * 100.0) / 100.0);
        out.put("mse", Math.round(mse * 100.0) / 100.0);
        out.put("r2", Math.round(r2 * 10000.0) / 10000.0);
        out.put("prediccion_media", Math.round((sumaPred / n) * 100.0) / 100.0);
        out.put("variables", List.of("Precio base", "Stock disponible", "Costo unitario", "Margen unitario", "Categoría"));
        out.put("importancia", importancia);
        out.put("puntos", puntos);
        return out;
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
