-- ==============================================================================
-- PROYECTO: ALICORP INTELLIGENCE v2
-- TABLA: empresas_competencia_simuladas (Datos de empresas competidoras en Supabase)
-- PROPÓSITO: Comparar registros históricos de Alicorp vs. Empresas Competidoras
--            mediante consultas en Supabase e inferencia Deep Learning (Keras).
-- ==============================================================================
-- INSTRUCCIONES:
-- 1. Entra a tu proyecto en Supabase -> "SQL Editor".
-- 2. Abre una "New query", pega este contenido completo y presiona "RUN".
-- ==============================================================================

CREATE TABLE IF NOT EXISTS public.empresas_competencia_simuladas (
    id BIGSERIAL PRIMARY KEY,
    codigo_empresa VARCHAR(60) NOT NULL,
    nombre_empresa VARCHAR(140) NOT NULL,
    perfil_estrategia VARCHAR(120) NOT NULL,
    producto_alicorp VARCHAR(120) NOT NULL,
    marca_competidora VARCHAR(140) NOT NULL,
    precio_promedio NUMERIC(10, 2) NOT NULL,
    costo_unitario NUMERIC(10, 2) NOT NULL,
    stock_promedio INT NOT NULL,
    ventas_historicas_soles NUMERIC(14, 2) NOT NULL,
    ganancias_netas_historicas NUMERIC(14, 2) NOT NULL,
    perdida_estimada_competencia NUMERIC(14, 2) NOT NULL,
    cuota_mercado_pct NUMERIC(6, 2) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT unq_empresa_producto UNIQUE (codigo_empresa, producto_alicorp)
);

CREATE INDEX IF NOT EXISTS idx_comp_keras_empresa
ON public.empresas_competencia_simuladas(codigo_empresa);

CREATE INDEX IF NOT EXISTS idx_comp_keras_producto
ON public.empresas_competencia_simuladas(producto_alicorp);

ALTER TABLE public.empresas_competencia_simuladas ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "Acceso total empresas_competencia_simuladas" ON public.empresas_competencia_simuladas;

CREATE POLICY "Acceso total empresas_competencia_simuladas"
ON public.empresas_competencia_simuladas
FOR ALL
TO anon, authenticated, service_role
USING (true)
WITH CHECK (true);

-- Poblar registros históricos simulados de 4 empresas competidoras para los 5 productos clave
INSERT INTO public.empresas_competencia_simuladas (
    codigo_empresa, nombre_empresa, perfil_estrategia, producto_alicorp, marca_competidora,
    precio_promedio, costo_unitario, stock_promedio, ventas_historicas_soles,
    ganancias_netas_historicas, perdida_estimada_competencia, cuota_mercado_pct
) VALUES
    -- 1. Grupo Consumo Andino S.A.C. (Competidor directo masivo)
    ('ANDINO', 'Grupo Consumo Andino S.A.C.', 'Precios agresivos en canal mayorista', 'Aceite Primor 1L', 'Aceite Sol Andino 1L', 8.10, 5.65, 290, 2684500.00, 782100.00, 312400.00, 44.20),
    ('ANDINO', 'Grupo Consumo Andino S.A.C.', 'Precios agresivos en canal mayorista', 'Detergente Bolívar 2kg', 'Detergente LimpioMax 2kg', 9.20, 6.20, 260, 2410200.00, 715000.00, 248900.00, 41.80),
    ('ANDINO', 'Grupo Consumo Andino S.A.C.', 'Precios agresivos en canal mayorista', 'Fideos Don Vittorio 500g', 'Fideos Tradición Oro 500g', 4.30, 2.85, 310, 2590800.00, 812400.00, 289500.00, 43.50),
    ('ANDINO', 'Grupo Consumo Andino S.A.C.', 'Precios agresivos en canal mayorista', 'Harina Blanca Flor 1kg', 'Harina Trigo Real 1kg', 6.40, 4.30, 275, 2345600.00, 698300.00, 215000.00, 40.90),
    ('ANDINO', 'Grupo Consumo Andino S.A.C.', 'Precios agresivos en canal mayorista', 'Mayonesa Alacena 500g', 'Mayonesa Cremosa Sur 500g', 7.80, 5.10, 240, 2189400.00, 665200.00, 198400.00, 39.40),

    -- 2. Industrias del Pacífico Perú S.A. (Competidor enfocado en descuento y alto stock)
    ('PACIFICO', 'Industrias del Pacífico Perú S.A.', 'Alto volumen y promociones 2x1 en supermercados', 'Aceite Primor 1L', 'Aceite Pacífico Puro 1L', 7.85, 5.55, 330, 2815000.00, 795400.00, 364200.00, 46.50),
    ('PACIFICO', 'Industrias del Pacífico Perú S.A.', 'Alto volumen y promociones 2x1 en supermercados', 'Detergente Bolívar 2kg', 'Detergente BioPacífico 2kg', 8.95, 6.10, 320, 2640100.00, 758200.00, 328100.00, 45.10),
    ('PACIFICO', 'Industrias del Pacífico Perú S.A.', 'Alto volumen y promociones 2x1 en supermercados', 'Fideos Don Vittorio 500g', 'Pasta Molino Azul 500g', 4.15, 2.80, 280, 2390500.00, 701200.00, 224600.00, 41.20),
    ('PACIFICO', 'Industrias del Pacífico Perú S.A.', 'Alto volumen y promociones 2x1 en supermercados', 'Harina Blanca Flor 1kg', 'Harina Panadera Plus 1kg', 6.20, 4.25, 300, 2498000.00, 724900.00, 267300.00, 42.80),
    ('PACIFICO', 'Industrias del Pacífico Perú S.A.', 'Alto volumen y promociones 2x1 en supermercados', 'Mayonesa Alacena 500g', 'Mayonesa DelChef 500g', 7.60, 5.05, 265, 2295000.00, 682000.00, 231800.00, 40.70),

    -- 3. Alimentos & Marcas del Sur S.A.A. (Competidor regional en bodegas y mercados)
    ('SURFOODS', 'Alimentos & Marcas del Sur S.A.A.', 'Penetración intensiva en bodegas y mercados', 'Aceite Primor 1L', 'Aceite Doña Clara 1L', 8.30, 5.80, 245, 2320400.00, 689100.00, 205400.00, 38.90),
    ('SURFOODS', 'Alimentos & Marcas del Sur S.A.A.', 'Penetración intensiva en bodegas y mercados', 'Detergente Bolívar 2kg', 'Detergente RindeMás 2kg', 9.40, 6.35, 235, 2190800.00, 645300.00, 189200.00, 37.60),
    ('SURFOODS', 'Alimentos & Marcas del Sur S.A.A.', 'Penetración intensiva en bodegas y mercados', 'Fideos Don Vittorio 500g', 'Fideos Casa Nostra 500g', 4.45, 2.95, 295, 2510300.00, 764000.00, 269100.00, 42.60),
    ('SURFOODS', 'Alimentos & Marcas del Sur S.A.A.', 'Penetración intensiva en bodegas y mercados', 'Harina Blanca Flor 1kg', 'Harina Estrella del Sur 1kg', 6.55, 4.40, 285, 2425000.00, 738500.00, 244800.00, 41.90),
    ('SURFOODS', 'Alimentos & Marcas del Sur S.A.A.', 'Penetración intensiva en bodegas y mercados', 'Mayonesa Alacena 500g', 'Mayonesa Sabor Casero 500g', 8.00, 5.25, 255, 2260700.00, 691400.00, 212300.00, 40.10),

    -- 4. Consorcio Agroindustrial NovaFoods (Competidor premium en cadenas modernas)
    ('NOVAFOODS', 'Consorcio Agroindustrial NovaFoods', 'Posicionamiento premium y margen alto en hipermercados', 'Aceite Primor 1L', 'Aceite Golden Olive Blend 1L', 9.40, 6.30, 220, 2540900.00, 838500.00, 276400.00, 41.50),
    ('NOVAFOODS', 'Consorcio Agroindustrial NovaFoods', 'Posicionamiento premium y margen alto en hipermercados', 'Detergente Bolívar 2kg', 'Detergente UltraCare 2kg', 10.50, 6.90, 215, 2480300.00, 818400.00, 258900.00, 40.80),
    ('NOVAFOODS', 'Consorcio Agroindustrial NovaFoods', 'Posicionamiento premium y margen alto en hipermercados', 'Fideos Don Vittorio 500g', 'Pasta Artigiana 500g', 5.20, 3.30, 240, 2610400.00, 887500.00, 298700.00, 43.10),
    ('NOVAFOODS', 'Consorcio Agroindustrial NovaFoods', 'Posicionamiento premium y margen alto en hipermercados', 'Harina Blanca Flor 1kg', 'Harina Gourmet Repostera 1kg', 7.30, 4.75, 230, 2390100.00, 788700.00, 229500.00, 40.20),
    ('NOVAFOODS', 'Consorcio Agroindustrial NovaFoods', 'Posicionamiento premium y margen alto en hipermercados', 'Mayonesa Alacena 500g', 'Mayonesa Artesanal Nova 500g', 8.90, 5.65, 250, 2695200.00, 916300.00, 334100.00, 44.60)
ON CONFLICT (codigo_empresa, producto_alicorp) DO UPDATE SET
    nombre_empresa = EXCLUDED.nombre_empresa,
    perfil_estrategia = EXCLUDED.perfil_estrategia,
    marca_competidora = EXCLUDED.marca_competidora,
    precio_promedio = EXCLUDED.precio_promedio,
    costo_unitario = EXCLUDED.costo_unitario,
    stock_promedio = EXCLUDED.stock_promedio,
    ventas_historicas_soles = EXCLUDED.ventas_historicas_soles,
    ganancias_netas_historicas = EXCLUDED.ganancias_netas_historicas,
    perdida_estimada_competencia = EXCLUDED.perdida_estimada_competencia,
    cuota_mercado_pct = EXCLUDED.cuota_mercado_pct;

SELECT 'Tabla empresas_competencia_simuladas creada y poblada con éxito en Supabase' AS resultado;
