package pe.com.alicorp.bi.modulos.estrategia_comercial;

/**
 * Registro agregado por tienda, producto (y opcionalmente mes) extraído desde
 * TRANSACCIONES_ALICORP en Supabase PostgreSQL mediante consultas GROUP BY.
 */
public class DatosTiendaProducto {

    private final String tienda;
    private final String producto;
    private final String periodoMes;
    private final double precioUnitario;
    private final double stockActual;
    private final double temperaturaZona;
    private final double cantidadVendida;
    private final double ventasTotales;
    private final long transacciones;

    public DatosTiendaProducto(
            String tienda,
            String producto,
            String periodoMes,
            double precioUnitario,
            double stockActual,
            double temperaturaZona,
            double cantidadVendida,
            double ventasTotales,
            long transacciones) {
        this.tienda = tienda != null ? tienda : "";
        this.producto = producto != null ? producto : "";
        this.periodoMes = periodoMes != null ? periodoMes : "GLOBAL";
        this.precioUnitario = precioUnitario;
        this.stockActual = stockActual;
        this.temperaturaZona = temperaturaZona;
        this.cantidadVendida = cantidadVendida;
        this.ventasTotales = ventasTotales;
        this.transacciones = transacciones;
    }

    public String getTienda() { return tienda; }
    public String getProducto() { return producto; }
    public String getPeriodoMes() { return periodoMes; }
    public double getPrecioUnitario() { return precioUnitario; }
    public double getStockActual() { return stockActual; }
    public double getTemperaturaZona() { return temperaturaZona; }
    public double getCantidadVendida() { return cantidadVendida; }
    public double getVentasTotales() { return ventasTotales; }
    public long getTransacciones() { return transacciones; }
}
