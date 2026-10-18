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

-- 4. (Opcional pero recomendado): Vista agregada para que el Dashboard no consuma memoria
CREATE OR REPLACE VIEW public.vista_resumen_dashboard AS
SELECT 
    producto,
    tienda,
    TO_CHAR(fecha, 'YYYY-MM') AS mes,
    COUNT(*) AS total_transacciones,
    SUM(cantidad_vendida) AS unidades_totales,
    SUM(ventas_totales) AS ventas_totales,
    ROUND(AVG(precio_unitario), 2) AS precio_promedio,
    ROUND(AVG(stock_actual), 1) AS stock_promedio
FROM public.transacciones_alicorp
GROUP BY producto, tienda, TO_CHAR(fecha, 'YYYY-MM');
