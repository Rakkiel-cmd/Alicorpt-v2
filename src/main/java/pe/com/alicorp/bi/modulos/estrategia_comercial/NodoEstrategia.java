package pe.com.alicorp.bi.modulos.estrategia_comercial;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Estructura de nodos propia en Java puro para representar el árbol jerárquico
 * de reglas de estrategia comercial y reabastecimiento.
 * Implementa el método estrictamente recursivo evaluarRutaRecomendacion(...) que
 * recorre las ramas hasta una hoja y construye la ruta explicativa paso a paso.
 */
public class NodoEstrategia {

    public static class ResultadoEvaluacion {
        private final String recomendacion;
        private final String prioridad;
        private final String accionSugerida;
        private final List<String> rutaExplicacion;
        private final int profundidad;

        public ResultadoEvaluacion(
                String recomendacion,
                String prioridad,
                String accionSugerida,
                List<String> rutaExplicacion,
                int profundidad) {
            this.recomendacion = recomendacion;
            this.prioridad = prioridad;
            this.accionSugerida = accionSugerida;
            this.rutaExplicacion = Collections.unmodifiableList(rutaExplicacion);
            this.profundidad = profundidad;
        }

        public String getRecomendacion() { return recomendacion; }
        public String getPrioridad() { return prioridad; }
        public String getAccionSugerida() { return accionSugerida; }
        public List<String> getRutaExplicacion() { return rutaExplicacion; }
        public int getProfundidad() { return profundidad; }
    }

    private final String variable;          // "stock_actual", "cantidad_vendida", "precio_unitario", "temperatura_zona"
    private final double umbral;
    private final String etiquetaRegla;
    private final NodoEstrategia hijoIzquierdo; // Rama TRUE (valor <= umbral)
    private final NodoEstrategia hijoDerecho;   // Rama FALSE (valor > umbral)

    // Atributos exclusivos de nodos hoja
    private final String recomendacionFinal;
    private final String prioridad;
    private final String accionSugerida;

    /** Constructor para nodo de decisión interno */
    public NodoEstrategia(
            String variable,
            double umbral,
            String etiquetaRegla,
            NodoEstrategia hijoIzquierdo,
            NodoEstrategia hijoDerecho) {
        this.variable = variable;
        this.umbral = umbral;
        this.etiquetaRegla = etiquetaRegla;
        this.hijoIzquierdo = hijoIzquierdo;
        this.hijoDerecho = hijoDerecho;
        this.recomendacionFinal = null;
        this.prioridad = null;
        this.accionSugerida = null;
    }

    /** Constructor para nodo hoja (decisión final) */
    public NodoEstrategia(String recomendacionFinal, String prioridad, String accionSugerida) {
        this.variable = null;
        this.umbral = 0.0;
        this.etiquetaRegla = null;
        this.hijoIzquierdo = null;
        this.hijoDerecho = null;
        this.recomendacionFinal = recomendacionFinal;
        this.prioridad = prioridad;
        this.accionSugerida = accionSugerida;
    }

    public boolean esHoja() {
        return recomendacionFinal != null;
    }

    /**
     * Construye el árbol jerárquico calibrado con los percentiles reales del catálogo Alicorp.
     */
    public static NodoEstrategia construirArbolEstrategia(
            double umbralStockBajo,
            double umbralStockAlto,
            double umbralDemandaAlta,
            double umbralPrecioAlto,
            double umbralTempAlta) {

        NodoEstrategia hojaReabastecimiento = new NodoEstrategia(
                "Reabastecimiento Urgente",
                "ALTA",
                "Emitir orden de reposición prioritaria en 24h para evitar quiebre de stock."
        );
        NodoEstrategia hojaPromocion = new NodoEstrategia(
                "Impulso en Góndola / Promoción",
                "MEDIA",
                "Activar exhibición preferente, pack promocional o ajuste táctico de precio."
        );
        NodoEstrategia hojaLiquidacion = new NodoEstrategia(
                "Liquidación por Sobrestock",
                "ALTA",
                "Aplicar descuento escalonado o traslado de inventario hacia tienda con mayor rotación."
        );
        NodoEstrategia hojaMantener = new NodoEstrategia(
                "Mantener Estrategia",
                "BAJA",
                "Continuar con política actual de reposición automática y monitoreo semanal."
        );

        // Subárbol izquierdo: stock_actual <= umbralStockBajo
        NodoEstrategia ramaStockBajo = new NodoEstrategia(
                "cantidad_vendida",
                umbralDemandaAlta * 0.90,
                "Evaluación de rotación con stock restringido",
                hojaPromocion,        // stock bajo + rotación moderada -> impulsar rotación controlada
                hojaReabastecimiento  // stock bajo + alta rotación -> Reabastecimiento Urgente
        );

        // Subárbol sobrestock: stock_actual > umbralStockAlto
        NodoEstrategia ramaSobrestock = new NodoEstrategia(
                "cantidad_vendida",
                umbralDemandaAlta,
                "Evaluación de salida frente a sobrestock",
                hojaLiquidacion,      // stock alto + baja demanda -> Liquidación por Sobrestock
                hojaMantener          // stock alto + alta demanda -> Mantener Estrategia
        );

        // Subárbol intermedio: stock saludable -> evaluar precio y temperatura/demanda
        NodoEstrategia ramaTemp = new NodoEstrategia(
                "temperatura_zona",
                umbralTempAlta,
                "Sensibilidad estacional / temperatura de zona",
                hojaMantener,
                hojaPromocion
        );

        NodoEstrategia ramaPrecio = new NodoEstrategia(
                "precio_unitario",
                umbralPrecioAlto,
                "Elasticidad de precio en canal",
                ramaTemp,
                hojaPromocion
        );

        NodoEstrategia ramaStockMedioOAlto = new NodoEstrategia(
                "stock_actual",
                umbralStockAlto,
                "Control de sobrestock en almacén",
                ramaPrecio,
                ramaSobrestock
        );

        // Nodo raíz: verificación inicial de riesgo de quiebre de stock
        return new NodoEstrategia(
                "stock_actual",
                umbralStockBajo,
                "Nivel crítico de stock actual",
                ramaStockBajo,
                ramaStockMedioOAlto
        );
    }

    /**
     * Método estrictamente recursivo que evalúa las reglas nodo por nodo hasta llegar
     * a una hoja, acumulando la ruta explicativa para el analista de BI.
     */
    public static ResultadoEvaluacion evaluarRutaRecomendacion(NodoEstrategia nodo, DatosTiendaProducto datos) {
        List<String> ruta = new ArrayList<>();
        return evaluarRecursivo(nodo, datos, ruta, 0);
    }

    private static ResultadoEvaluacion evaluarRecursivo(
            NodoEstrategia nodo,
            DatosTiendaProducto datos,
            List<String> ruta,
            int profundidad) {

        if (nodo == null) {
            ruta.add("Hoja por defecto -> Mantener Estrategia");
            return new ResultadoEvaluacion("Mantener Estrategia", "BAJA", "Monitorear indicadores.", ruta, profundidad);
        }

        // Caso base de la recursión: nodo hoja
        if (nodo.esHoja()) {
            ruta.add("Decisión Final [" + nodo.prioridad + "]: " + nodo.recomendacionFinal);
            return new ResultadoEvaluacion(
                    nodo.recomendacionFinal,
                    nodo.prioridad,
                    nodo.accionSugerida,
                    ruta,
                    profundidad
            );
        }

        double valorActual = extraerVariable(nodo.variable, datos);
        boolean vaIzquierda = valorActual <= nodo.umbral;
        String comparacion = vaIzquierda
                ? String.format("%s (%.2f) <= %.2f [SÍ]", nodo.variable, valorActual, nodo.umbral)
                : String.format("%s (%.2f) > %.2f [NO]", nodo.variable, valorActual, nodo.umbral);

        ruta.add("Nivel " + (profundidad + 1) + " · " + nodo.etiquetaRegla + ": " + comparacion);

        // Llamada estrictamente recursiva hacia la rama correspondiente
        NodoEstrategia siguiente = vaIzquierda ? nodo.hijoIzquierdo : nodo.hijoDerecho;
        return evaluarRecursivo(siguiente, datos, ruta, profundidad + 1);
    }

    private static double extraerVariable(String variable, DatosTiendaProducto d) {
        if (variable == null) return 0.0;
        return switch (variable) {
            case "stock_actual" -> d.getStockActual();
            case "cantidad_vendida" -> d.getCantidadVendida();
            case "precio_unitario" -> d.getPrecioUnitario();
            case "temperatura_zona" -> d.getTemperaturaZona();
            default -> 0.0;
        };
    }
}
