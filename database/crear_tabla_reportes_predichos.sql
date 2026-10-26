-- ==============================================================================
-- TABLA DE REPORTES MENSUALES PREDICHOS CON RED NEURONAL (CACHE PERSISTENTE EN BD)
-- Corporación Alicorp S.A.A. - Plataforma BI & Sistema Web
-- ==============================================================================
-- Esta tabla implementa la capa de persistencia y reutilización (Cache-Aside)
-- para las predicciones mensuales generadas por la Red Neuronal (Keras DNN).
-- Evita reprocesamientos innecesarios: si una fecha futura ya fue calculada,
-- el sistema consulta directamente esta tabla en la base de datos.
-- ==============================================================================

CREATE TABLE IF NOT EXISTS public.reportes_mensuales_predichos (
    id SERIAL PRIMARY KEY,
    periodo VARCHAR(10) UNIQUE NOT NULL,                -- Formato 'AAAA-MM' (ej: '2026-08', '2027-03')
    anio INTEGER NOT NULL,                              -- Año proyectado (ej: 2026, 2027, 2028)
    mes INTEGER NOT NULL,                               -- Mes numérico (1 al 12)
    nombre_mes VARCHAR(50) NOT NULL,                    -- Nombre legible (ej: 'Agosto 2026')
    venta_total NUMERIC(14, 2) NOT NULL,                -- Suma de ventas proyectadas en soles
    margen_promedio NUMERIC(8, 2) NOT NULL,             -- Variación porcentual promedio de precios
    producto_riesgo VARCHAR(150),                       -- Producto con menor margen o mayor riesgo
    variacion_riesgo NUMERIC(8, 2),                     -- Variación % del producto con mayor riesgo
    datos_productos JSONB NOT NULL,                     -- Lista completa de productos (precios, variación, ventas, estado)
    modelo_motor VARCHAR(100) DEFAULT 'Red Neuronal Keras Sequential (DNN)', -- Motor de IA utilizado
    generado_por VARCHAR(100) DEFAULT 'sistema_predictivo', -- Usuario o identificador de sesión
    created_at TIMESTAMP WITH TIME ZONE DEFAULT timezone('utc'::text, now()) NOT NULL
);

-- Índice único por periodo para búsquedas ultra rápidas O(1)
CREATE UNIQUE INDEX IF NOT EXISTS idx_reportes_predichos_periodo 
ON public.reportes_mensuales_predichos (periodo);

-- Permisos de lectura, inserción y actualización
GRANT ALL ON TABLE public.reportes_mensuales_predichos TO postgres, anon, authenticated, service_role;
GRANT USAGE, SELECT ON SEQUENCE public.reportes_mensuales_predichos_id_seq TO postgres, anon, authenticated, service_role;
