package pe.com.alicorp.bi.modulos.tienda_buscador_ia;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Modelo de dominio inmutable/enriquecido para un producto del catálogo Alicorp
 * combinado con sus métricas históricas agregadas desde Supabase (TRANSACCIONES_ALICORP).
 */
public class ProductoCatalogoIA {

    private final int idProducto;
    private final String sku;
    private final String nombre;
    private final String marca;
    private final String presentacion;
    private final String categoria;
    private final double precioUnitario;
    private final int stockActual;
    private final double stockPromedioHistorico;
    private final double temperaturaPromedio;
    private final long volumenHistoricoUnidades;
    private final double ventasTotalesHistoricas;
    private final long totalTransacciones;
    private final Map<String, Double> afinidadPorTienda;

    public ProductoCatalogoIA(
            int idProducto,
            String sku,
            String nombre,
            String marca,
            String presentacion,
            String categoria,
            double precioUnitario,
            int stockActual,
            double stockPromedioHistorico,
            double temperaturaPromedio,
            long volumenHistoricoUnidades,
            double ventasTotalesHistoricas,
            long totalTransacciones,
            Map<String, Double> afinidadPorTienda) {
        this.idProducto = idProducto;
        this.sku = sku != null ? sku : "";
        this.nombre = nombre != null ? nombre : "";
        this.marca = marca != null ? marca : "Alicorp";
        this.presentacion = presentacion != null ? presentacion : "Unidad";
        this.categoria = categoria != null ? categoria : "General";
        this.precioUnitario = precioUnitario;
        this.stockActual = Math.max(0, stockActual);
        this.stockPromedioHistorico = Math.max(0.0, stockPromedioHistorico);
        this.temperaturaPromedio = temperaturaPromedio;
        this.volumenHistoricoUnidades = Math.max(0L, volumenHistoricoUnidades);
        this.ventasTotalesHistoricas = Math.max(0.0, ventasTotalesHistoricas);
        this.totalTransacciones = Math.max(0L, totalTransacciones);
        this.afinidadPorTienda = afinidadPorTienda != null
                ? Collections.unmodifiableMap(new HashMap<>(afinidadPorTienda))
                : Collections.emptyMap();
    }

    public int getIdProducto() { return idProducto; }
    public String getSku() { return sku; }
    public String getNombre() { return nombre; }
    public String getMarca() { return marca; }
    public String getPresentacion() { return presentacion; }
    public String getCategoria() { return categoria; }
    public double getPrecioUnitario() { return precioUnitario; }
    public int getStockActual() { return stockActual; }
    public double getStockPromedioHistorico() { return stockPromedioHistorico; }
    public double getTemperaturaPromedio() { return temperaturaPromedio; }
    public long getVolumenHistoricoUnidades() { return volumenHistoricoUnidades; }
    public double getVentasTotalesHistoricas() { return ventasTotalesHistoricas; }
    public long getTotalTransacciones() { return totalTransacciones; }
    public Map<String, Double> getAfinidadPorTienda() { return afinidadPorTienda; }

    /**
     * Retorna la afinidad normalizada [0.0, 1.0] de este producto para una tienda específica.
     */
    public double getAfinidadParaTienda(String tienda) {
        if (tienda == null || tienda.isBlank()) {
            return totalTransacciones > 0 ? 0.5 : 0.25;
        }
        String clave = tienda.trim().toLowerCase();
        for (Map.Entry<String, Double> entry : afinidadPorTienda.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(clave)) {
                return entry.getValue();
            }
        }
        return totalTransacciones > 0 ? 0.20 : 0.10;
    }
}
