-- ==============================================================================
-- PROYECTO: ALICORP INTELLIGENCE v2
-- TABLA: transacciones_alicorp (100,000 registros para el Dashboard BI)
-- ==============================================================================
-- INSTRUCCIONES:
-- 1. Entra a tu proyecto en Supabase: https://supabase.com/dashboard/project/tpgrjvnmozfbpualnlsf
-- 2. Ve a "SQL Editor" en el menú de la izquierda.
-- 3. Crea una "New query", pega este contenido completo y haz clic en "RUN".
-- ==============================================================================

-- 1. Crear la tabla principal
CREATE TABLE IF NOT EXISTS public.transacciones_alicorp (
    id_transaccion BIGINT PRIMARY KEY,
    fecha DATE NOT NULL,
    producto VARCHAR(150) NOT NULL,
    tienda VARCHAR(150) NOT NULL,
    precio_unitario NUMERIC(10,2) NOT NULL,
    stock_actual INT NOT NULL,
    temperatura_zona NUMERIC(6,2),
    cantidad_vendida INT NOT NULL,
    ventas_totales NUMERIC(12,2) NOT NULL
);

-- 2. Habilitar Row Level Security (RLS) y permitir acceso total
ALTER TABLE public.transacciones_alicorp ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "Acceso total transacciones_alicorp" ON public.transacciones_alicorp;

CREATE POLICY "Acceso total transacciones_alicorp"
ON public.transacciones_alicorp
FOR ALL
TO anon, authenticated, service_role
USING (true)
WITH CHECK (true);

-- 3. Crear índices para consultas ultra rápidas en el Dashboard
CREATE INDEX IF NOT EXISTS idx_transacciones_fecha ON public.transacciones_alicorp(fecha);
CREATE INDEX IF NOT EXISTS idx_transacciones_producto ON public.transacciones_alicorp(producto);
CREATE INDEX IF NOT EXISTS idx_transacciones_tienda ON public.transacciones_alicorp(tienda);

-- 4. Vistas agregadas para que el Dashboard consulte Supabase en < 50 ms con 0 RAM en Render
DROP VIEW IF EXISTS public.vista_resumen_dashboard CASCADE;
DROP VIEW IF EXISTS public.vista_totales_dashboard CASCADE;
DROP VIEW IF EXISTS public.vista_stock_dashboard CASCADE;
DROP VIEW IF EXISTS public.vista_historico_productos_alicorp CASCADE;

CREATE VIEW public.vista_resumen_dashboard AS
SELECT 
    producto,
    tienda,
    SUBSTRING(CAST(fecha AS TEXT) FROM 1 FOR 7) AS mes,
    COUNT(*) AS total_transacciones,
    COALESCE(SUM(cantidad_vendida), 0) AS unidades_totales,
    ROUND(COALESCE(SUM(ventas_totales), 0)::NUMERIC, 2) AS ventas_totales,
    ROUND(COALESCE(AVG(precio_unitario), 0)::NUMERIC, 2) AS precio_promedio,
    ROUND(COALESCE(AVG(stock_actual), 0)::NUMERIC, 1) AS stock_promedio
FROM public.transacciones_alicorp
GROUP BY producto, tienda, SUBSTRING(CAST(fecha AS TEXT) FROM 1 FOR 7);

CREATE VIEW public.vista_totales_dashboard AS
SELECT 
    COUNT(*) AS total_registros,
    ROUND(COALESCE(SUM(ventas_totales), 0)::NUMERIC, 2) AS ventas_totales,
    ROUND(COALESCE(AVG(ventas_totales), 0)::NUMERIC, 2) AS media_ventas,
    ROUND(COALESCE(stddev_samp(ventas_totales), 0)::NUMERIC, 2) AS desviacion_ventas,
    ROUND(COALESCE(var_samp(ventas_totales), 0)::NUMERIC, 2) AS varianza_ventas
FROM public.transacciones_alicorp;

CREATE VIEW public.vista_stock_dashboard AS
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

CREATE VIEW public.vista_historico_productos_alicorp AS
SELECT
    producto,
    COUNT(*) AS transacciones,
    COALESCE(SUM(cantidad_vendida), 0) AS unidades_historicas,
    ROUND(COALESCE(SUM(ventas_totales), 0)::NUMERIC, 2) AS ventas_historicas_soles,
    ROUND(COALESCE(AVG(precio_unitario), 0)::NUMERIC, 2) AS precio_promedio,
    ROUND(COALESCE(AVG(stock_actual), 0)::NUMERIC, 1) AS stock_promedio,
    ROUND((COALESCE(AVG(precio_unitario), 0) * 0.64)::NUMERIC, 2) AS costo_unitario
FROM public.transacciones_alicorp
GROUP BY producto
ORDER BY ventas_historicas_soles DESC;

DROP FUNCTION IF EXISTS public.estadisticas_dashboard(NUMERIC, NUMERIC);

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

GRANT SELECT ON public.vista_resumen_dashboard TO anon, authenticated, service_role;
GRANT SELECT ON public.vista_totales_dashboard TO anon, authenticated, service_role;
GRANT SELECT ON public.vista_stock_dashboard TO anon, authenticated, service_role;
GRANT SELECT ON public.vista_historico_productos_alicorp TO anon, authenticated, service_role;
GRANT EXECUTE ON FUNCTION public.estadisticas_dashboard(NUMERIC, NUMERIC) TO anon, authenticated, service_role;

NOTIFY pgrst, 'reload schema';
