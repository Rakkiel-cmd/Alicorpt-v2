package pe.com.alicorp.bi.modulos.chatbot_asistente;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static pe.com.alicorp.bi.modulos.chatbot_asistente.MotorIntenciones.normalizar;
import static pe.com.alicorp.bi.modulos.chatbot_asistente.MotorIntenciones.tiene;

/**
 * Explica conceptos y pantallas del dashboard. Las CIFRAS siempre vienen de lo que el analista ve en
 * pantalla (el navegador las envía); aquí solo se interpretan, nunca se inventan.
 */
public final class ExplicadorDashboard {

    private ExplicadorDashboard() {}

    private record Concepto(String[] claves, String[] etiquetasEnPantalla, String explicacion) {}

    private static final Concepto[] GLOSARIO = {
            new Concepto(new String[]{"r2", "coeficiente de determinacion", "precision del modelo"}, new String[]{"r2", "precision"},
                    "El **R²** indica qué parte de la variación de las ventas logra explicar el modelo. Va de 0 a 100 %: cerca de 100 % el modelo se ajusta muy bien a los datos históricos; cerca de 0 % casi no explica nada."),
            new Concepto(new String[]{"rmse", "mse", "mae", "error cuadratico", "error medio", "margen de error"}, new String[]{"mse", "rmse", "mae", "error"},
                    "**MSE / RMSE / MAE** miden el error promedio del modelo al predecir. Mientras más bajos, mejor. El RMSE está en las mismas unidades que la venta (soles), por eso es el más fácil de interpretar; el MSE es ese error elevado al cuadrado y castiga más los errores grandes."),
            new Concepto(new String[]{"media", "promedio"}, new String[]{"media", "promedio"},
                    "La **media** es el promedio de todas las ventas: suma de los valores entre la cantidad de registros. Se ve afectada por valores extremos."),
            new Concepto(new String[]{"mediana"}, new String[]{"mediana"},
                    "La **mediana** es el valor del medio cuando se ordenan las ventas. Si es muy distinta de la media, hay valores extremos que jalan el promedio."),
            new Concepto(new String[]{"desviacion", "desviacion estandar", "varianza"}, new String[]{"desviacion", "varianza"},
                    "La **desviación estándar** indica cuánto se alejan las ventas de su promedio. Una desviación alta significa ventas muy dispersas; una baja, ventas parecidas entre sí."),
            new Concepto(new String[]{"correlacion"}, new String[]{"correlacion"},
                    "La **correlación** mide qué tan relacionadas están dos variables, de -1 a 1. Cerca de 1: suben juntas; cerca de -1: una sube y la otra baja; cerca de 0: no se relacionan. Correlación no implica causa."),
            new Concepto(new String[]{"outlier*", "atipico*", "valores extremos", "anomalia*"}, new String[]{"outlier", "atipico", "anomalia"},
                    "Un **valor atípico (outlier)** es un dato muy alejado del resto, por ejemplo una venta excepcionalmente alta o baja. Conviene revisarlo: puede ser un error de registro o un evento real."),
            new Concepto(new String[]{"kmeans", "k means", "cluster*", "segmento*", "segmentacion", "centroide*"}, new String[]{"cluster", "segmento", "k-means", "kmeans"},
                    "**K-Means** agrupa los datos en K segmentos (clusters) de comportamiento parecido, sin que nadie los etiquete antes. Cada grupo tiene un centroide (su punto promedio). Sirve para identificar tipos de producto o de cliente y tratarlos distinto."),
            new Concepto(new String[]{"keras", "red neuronal", "redes neuronales", "neurona*", "dl4j", "deep learning"}, new String[]{"neuronal", "keras", "red"},
                    "La **red neuronal** es un modelo con capas de neuronas que aprende relaciones no lineales entre las variables y la venta. Suele capturar patrones más complejos que la regresión lineal, a costa de ser más difícil de explicar."),
            new Concepto(new String[]{"epoca*", "epocas", "entrenamiento"}, new String[]{"epoca"},
                    "Una **época** es una pasada completa de la red neuronal por todos los datos de entrenamiento. Más épocas permiten aprender más, pero demasiadas pueden causar sobreajuste."),
            new Concepto(new String[]{"sobreajuste", "overfitting"}, new String[]{},
                    "El **sobreajuste** ocurre cuando el modelo memoriza los datos históricos y falla con datos nuevos. Se detecta si el ajuste en entrenamiento es muy bueno pero el error en datos de prueba es mucho mayor."),
            new Concepto(new String[]{"regresion", "pronostico", "prediccion", "regresion lineal"}, new String[]{"prediccion", "pronostico"},
                    "La **regresión** busca una fórmula que relacione variables (como precio, stock o temperatura) con la venta. El pronóstico es la venta que esa fórmula estima para las condiciones indicadas; es una estimación, no una garantía."),
            new Concepto(new String[]{"random forest", "bosque aleatorio", "arbol*", "importancia de variables", "variables importantes"}, new String[]{"importancia"},
                    "La **importancia de variables** muestra cuánto pesa cada dato (precio, stock, temperatura, etc.) en las decisiones del modelo. Las de mayor porcentaje son las que más influyen en el resultado."),
            new Concepto(new String[]{"percentil*", "cuartil*", "rango intercuartilico"}, new String[]{"percentil", "cuartil"},
                    "Un **percentil** indica el valor bajo el cual queda cierto porcentaje de los datos. El percentil 90, por ejemplo, deja por debajo al 90 % de las ventas. Los cuartiles dividen los datos en cuatro partes iguales."),
    };

    private static final Map<String, String> VISTAS = new LinkedHashMap<>();
    static {
        VISTAS.put("inicio", "Es el **Resumen Ejecutivo**: indicadores generales del negocio y accesos a cada módulo.");
        VISTAS.put("productos", "Es el **Portafolio y Stock**: el catálogo de productos con su inventario y precios.");
        VISTAS.put("compras", "Son las **Órdenes Web**: los pedidos hechos por clientes en la tienda virtual y su estado.");
        VISTAS.put("prediccion", "Es el **Pronóstico Lineal**: una regresión que estima la venta promedio proyectada a partir del historial. El R² dice qué tan bien se ajusta el modelo y el error (MSE) cuánto se equivoca en promedio.");
        VISTAS.put("keras", "Es el **simulador con red neuronal**: estima la venta para las condiciones que ingreses y muestra la precisión del modelo entrenado.");
        VISTAS.put("segmentacion", "Es la **segmentación K-Means**: agrupa productos o ventas en segmentos de comportamiento parecido.");
        VISTAS.put("reporte", "Es el **reporte / cierre**: consolida los resultados del análisis para revisarlos o exportarlos.");
        VISTAS.put("estadistica", "Es la **estadística descriptiva y de riesgo**: media, dispersión y valores atípicos de las ventas.");
        VISTAS.put("estrategia", "Es la **estrategia comercial**: recomendaciones derivadas de los modelos.");
        VISTAS.put("incidencias", "Es el módulo de **Incidencias**: mensajes de clientes y fallas del sistema con su estado. Aquí marcas los tickets como resueltos.");
        VISTAS.put("administracion", "Es la **Administración** del equipo: usuarios, roles y actividad.");
        VISTAS.put("seguridad", "Es el módulo de **Seguridad**: llave de acceso, auditoría y solicitudes de cambio.");
    }

    public static Optional<String> explicarConcepto(String norm, Map<String, String> datos) {
        for (Concepto c : GLOSARIO) {
            if (tiene(norm, c.claves())) {
                // Solo si el analista pregunta por el concepto (no por tickets)
                if (!tiene(norm, "que es", "que significa", "significa", "explica*", "para que sirve", "como se interpreta", "que quiere decir", "no entiendo", "que son", "definicion")
                        && norm.split(" ").length > 3) continue;
                StringBuilder r = new StringBuilder(c.explicacion());
                String valor = valorEnPantalla(datos, c.etiquetasEnPantalla());
                if (valor != null) {
                    r.append("\n\nEn tu pantalla ahora aparece: **").append(valor).append("**.");
                    String interp = c.claves()[0].equals("r2") ? interpretarR2(valor) : null;
                    if (interp != null) r.append(" ").append(interp);
                }
                return Optional.of(r.toString());
            }
        }
        return Optional.empty();
    }

    public static String explicarPantalla(String vista, Map<String, String> datos) {
        StringBuilder r = new StringBuilder();
        String desc = VISTAS.get(vista == null ? "" : vista);
        r.append(desc != null ? desc : "Esta es una pantalla del dashboard analítico.");
        if (datos != null && !datos.isEmpty()) {
            r.append("\n\nLo que veo en pantalla:");
            int n = 0;
            for (Map.Entry<String, String> e : datos.entrySet()) {
                if (e.getValue() == null || e.getValue().isBlank() || e.getValue().contains("--")) continue;
                r.append("\n• ").append(e.getKey()).append(": ").append(e.getValue());
                if (++n >= 8) break;
            }
            if (n == 0) r.append("\nAún no hay cifras cargadas. Si hay un botón de ejecutar, púlsalo y vuelve a preguntarme.");
            String r2 = valorEnPantalla(datos, new String[]{"r2", "precision"});
            if (r2 != null && interpretarR2(r2) != null) r.append("\n\n").append(interpretarR2(r2));
        } else {
            r.append("\n\nNo veo cifras en esta pantalla todavía.");
        }
        r.append("\n\nPuedes preguntarme por cualquier término (R², MSE, desviación, clusters…).");
        return r.toString();
    }

    private static String valorEnPantalla(Map<String, String> datos, String[] etiquetas) {
        if (datos == null || etiquetas.length == 0) return null;
        for (Map.Entry<String, String> e : datos.entrySet()) {
            String k = normalizar(e.getKey());
            for (String et : etiquetas) {
                if (k.contains(et) && e.getValue() != null && !e.getValue().isBlank() && !e.getValue().contains("--")) {
                    return e.getKey() + " = " + e.getValue();
                }
            }
        }
        return null;
    }

    private static String interpretarR2(String texto) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(-?\\d+(?:[.,]\\d+)?)\\s*(%?)").matcher(texto.substring(Math.max(0, texto.indexOf('=') + 1)));
        if (!m.find()) return null;
        double v = Double.parseDouble(m.group(1).replace(',', '.'));
        if (!m.group(2).isEmpty() || v > 1.0) v = v / 100.0;
        if (v >= 0.8) return "Es un ajuste alto: el modelo explica la gran mayoría de la variación de las ventas.";
        if (v >= 0.6) return "Es un ajuste aceptable: explica buena parte de la variación, pero queda una parte que el modelo no captura.";
        if (v >= 0.4) return "Es un ajuste moderado: úsalo como referencia y no como valor exacto.";
        return "Es un ajuste bajo: el modelo explica poco; conviene tomar sus predicciones con cautela.";
    }
}
