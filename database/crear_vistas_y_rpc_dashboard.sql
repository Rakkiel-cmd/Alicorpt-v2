-- ==============================================================================
-- PROYECTO: ALICORP INTELLIGENCE v2
-- SCRIPT SQL: VISTAS Y FUNCIONES RPC DE ALTO RENDIMIENTO (< 50 ms, 0 RAM en Render)
-- ==============================================================================
-- INSTRUCCIONES PARA SUPABASE:
-- 1. Abre tu proyecto en Supabase -> "SQL Editor".
-- 2. Crea una "New query", pega todo este script y presiona "RUN".
--
-- ¿Qué hace este script?
-- Crea las vistas agregadas y la función RPC directamente sobre los 100,000
-- registros de `public.transacciones_alicorp` para que PostgreSQL haga todo el
-- cálculo pesado en milisegundos y devuelva el resultado listo por API sin usar
-- archivos .json/.csv ni saturar los 512 MB de RAM de Render.
-- ==============================================================================

-- 1. Índices de aceleración sobre transacciones_alicorp
CREATE INDEX IF NOT EXISTS idx_transacciones_fecha ON public.transacciones_alicorp(fecha);
CREATE INDEX IF NOT EXISTS idx_transacciones_producto ON public.transacciones_alicorp(producto);
CREATE INDEX IF NOT EXISTS idx_transacciones_tienda ON public.transacciones_alicorp(tienda);
CREATE INDEX IF NOT EXISTS idx_transacciones_stock ON public.transacciones_alicorp(stock_actual);

-- 2. Vista agregada por producto, tienda y mes (Inicio y Pestaña Productos)
CREATE OR REPLACE VIEW public.vista_resumen_dashboard AS
SELECT 
    producto,
    tienda,
    SUBSTRING(CAST(fecha AS TEXT) FROM 1 FOR 7) AS mes,
    COUNT(*)::INT AS total_transacciones,
    COALESCE(SUM(cantidad_vendida), 0)::BIGINT AS unidades_totales,
    ROUND(COALESCE(SUM(ventas_totales), 0)::NUMERIC, 2) AS ventas_totales,
    ROUND(COALESCE(AVG(precio_unitario), 0)::NUMERIC, 2) AS precio_promedio,
    ROUND(COALESCE(AVG(stock_actual), 0)::NUMERIC, 1) AS stock_promedio
FROM public.transacciones_alicorp
GROUP BY producto, tienda, SUBSTRING(CAST(fecha AS TEXT) FROM 1 FOR 7);

-- 3. Vista de totales globales corporativos (Tarjetas superiores de Inicio y Keras)
CREATE OR REPLACE VIEW public.vista_totales_dashboard AS
SELECT 
    COUNT(*)::INT AS total_registros,
    ROUND(COALESCE(SUM(ventas_totales), 0)::NUMERIC, 2) AS ventas_totales,
    ROUND(COALESCE(AVG(ventas_totales), 0)::NUMERIC, 2) AS media_ventas,
    ROUND(COALESCE(stddev_samp(ventas_totales), 0)::NUMERIC, 2) AS desviacion_ventas,
    ROUND(COALESCE(var_samp(ventas_totales), 0)::NUMERIC, 2) AS varianza_ventas
FROM public.transacciones_alicorp;

-- 4. Vista de relación Nivel de Stock vs. Unidades y Ventas Promedio
CREATE OR REPLACE VIEW public.vista_stock_dashboard AS
SELECT
    CASE
        WHEN stock_actual < 150 THEN '50-149'
        WHEN stock_actual < 250 THEN '150-249'
        WHEN stock_actual < 350 THEN '250-349'
        WHEN stock_actual < 450 THEN '350-449'
        ELSE '450-500'
    END AS nivel_stock,
    ROUND(COALESCE(AVG(cantidad_vendida), 0)::NUMERIC, 2) AS unidades_promedio,
    ROUND(COALESCE(AVG(ventas_totales), 0)::NUMERIC, 2) AS ventas_promedio
FROM public.transacciones_alicorp
GROUP BY 1
ORDER BY 1;

-- 5. Vista de histórico por producto de Alicorp (para Simulación Competitiva en Keras)
CREATE OR REPLACE VIEW public.vista_historico_productos_alicorp AS
SELECT
    producto,
    COUNT(*)::INT AS transacciones,
    COALESCE(SUM(cantidad_vendida), 0)::BIGINT AS unidades_historicas,
    ROUND(COALESCE(SUM(ventas_totales), 0)::NUMERIC, 2) AS ventas_historicas_soles,
    ROUND(COALESCE(AVG(precio_unitario), 0)::NUMERIC, 2) AS precio_promedio,
    ROUND(COALESCE(AVG(stock_actual), 0)::NUMERIC, 1) AS stock_promedio,
    ROUND((COALESCE(AVG(precio_unitario), 0) * 0.64)::NUMERIC, 2) AS costo_unitario
FROM public.transacciones_alicorp
GROUP BY producto
ORDER BY ventas_historicas_soles DESC;

-- 6. Función RPC para Estadística Descriptiva, Campana de Gauss y Quiebre de Stock
CREATE OR REPLACE FUNCTION public.estadisticas_dashboard(
    meta_venta NUMERIC DEFAULT 200.0,
    umbral_inventario NUMERIC DEFAULT 100.0
)
RETURNS JSONB
LANGUAGE SQL
STABLE
AS $$
SELECT jsonb_build_object(
    'general', (
        SELECT jsonb_build_object(
            'n', COUNT(*)::INT,
            'media', COALESCE(AVG(ventas_totales), 0),
            'mediana', COALESCE(percentile_cont(0.5) WITHIN GROUP (ORDER BY ventas_totales), 0),
            'varianza', COALESCE(var_samp(ventas_totales), 0),
            'desviacion', COALESCE(stddev_samp(ventas_totales), 0),
            'mayores_meta', COUNT(*) FILTER (WHERE ventas_totales > meta_venta)::INT
        )
        FROM public.transacciones_alicorp
    ),
    'tiendas', COALESCE((
        SELECT jsonb_agg(jsonb_build_object(
            'tienda', tienda,
            'n', cantidad::INT,
            'stock_medio', stock_medio,
            'stock_desviacion', stock_desviacion,
            'debajo_umbral', debajo_umbral::INT
        ) ORDER BY tienda)
        FROM (
            SELECT tienda,
                   COUNT(*) AS cantidad,
                   COALESCE(AVG(stock_actual), 0) AS stock_medio,
                   COALESCE(stddev_samp(stock_actual), 1) AS stock_desviacion,
                   COUNT(*) FILTER (WHERE stock_actual < umbral_inventario) AS debajo_umbral
            FROM public.transacciones_alicorp
            GROUP BY tienda
        ) por_tienda
    ), '[]'::jsonb)
);
$$;

-- 7. Otorgar permisos de lectura y ejecución a la API REST de Supabase (PostgREST)
GRANT SELECT ON public.vista_resumen_dashboard TO anon, authenticated, service_role;
GRANT SELECT ON public.vista_totales_dashboard TO anon, authenticated, service_role;
GRANT SELECT ON public.vista_stock_dashboard TO anon, authenticated, service_role;
GRANT SELECT ON public.vista_historico_productos_alicorp TO anon, authenticated, service_role;
GRANT EXECUTE ON FUNCTION public.estadisticas_dashboard(NUMERIC, NUMERIC) TO anon, authenticated, service_role;

-- 8. Recargar caché de esquema de PostgREST inmediatamente
NOTIFY pgrst, 'reload schema';

SELECT 'Vistas y función RPC (vista_resumen_dashboard, vista_totales_dashboard, vista_stock_dashboard, vista_historico_productos_alicorp, estadisticas_dashboard) creadas y publicadas correctamente' AS resultado;
