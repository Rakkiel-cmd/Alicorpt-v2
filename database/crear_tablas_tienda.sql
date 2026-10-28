-- ==============================================================================
-- PROYECTO: ALICORP INTELLIGENCE v2
-- TABLAS DE LA TIENDA: productos, compras y detalle_compra
-- ==============================================================================
-- INSTRUCCIONES:
-- 1. Entra a tu proyecto en Supabase: https://supabase.com/dashboard/project/tpgrjvnmozfbpualnlsf
-- 2. Ve a "SQL Editor" en el menú lateral izquierdo.
-- 3. Abre una "New query", pega este contenido y presiona "RUN" (o Ctrl+Enter).
-- 4. Luego ve a "Table Editor" > tabla "productos" > "Insert" > "Import data from CSV"
--    y sube el archivo productos_alicorp_catalogo.csv (más de 7 000 productos reales).
--    Las columnas del CSV tienen EXACTAMENTE los mismos nombres que esta tabla.
-- ==============================================================================

-- 1. Catálogo de productos (se carga desde el CSV)
CREATE TABLE IF NOT EXISTS public.productos (
    sku VARCHAR(20) PRIMARY KEY,
    nombre VARCHAR(200) NOT NULL,
    marca VARCHAR(80) NOT NULL,
    categoria VARCHAR(80) NOT NULL,
    subcategoria VARCHAR(120) NOT NULL,
    presentacion VARCHAR(80) NOT NULL,
    precio NUMERIC(10,2) NOT NULL CHECK (precio >= 0),
    stock INT NOT NULL DEFAULT 0 CHECK (stock >= 0),
    imagen VARCHAR(20),
    foto VARCHAR(120),
    descripcion TEXT,
    etiquetas TEXT
);

-- Si la tabla ya existía de una versión anterior, esto agrega la columna de la foto y vacía los productos viejos
ALTER TABLE public.productos ADD COLUMN IF NOT EXISTS foto VARCHAR(120);
TRUNCATE TABLE public.productos;

CREATE INDEX IF NOT EXISTS idx_productos_categoria ON public.productos(categoria);
CREATE INDEX IF NOT EXISTS idx_productos_marca ON public.productos(marca);
CREATE INDEX IF NOT EXISTS idx_productos_precio ON public.productos(precio);

-- 2. Compras realizadas desde el carrito (una fila por compra)
CREATE TABLE IF NOT EXISTS public.compras (
    id BIGSERIAL PRIMARY KEY,
    cliente_nombre VARCHAR(200) NOT NULL,
    cliente_correo VARCHAR(200) NOT NULL,
    cliente_telefono VARCHAR(40),
    direccion_entrega VARCHAR(300),
    total NUMERIC(12,2) NOT NULL,
    estado VARCHAR(40) NOT NULL DEFAULT 'pendiente',
    fecha_compra TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_compras_fecha ON public.compras(fecha_compra DESC);

-- 3. Detalle de cada compra (una fila por producto comprado)
CREATE TABLE IF NOT EXISTS public.detalle_compra (
    id BIGSERIAL PRIMARY KEY,
    compra_id BIGINT NOT NULL REFERENCES public.compras(id) ON DELETE CASCADE,
    sku VARCHAR(20) NOT NULL,
    producto VARCHAR(200) NOT NULL,
    cantidad INT NOT NULL CHECK (cantidad > 0),
    precio_unitario NUMERIC(10,2) NOT NULL,
    subtotal NUMERIC(12,2) NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_detalle_compra ON public.detalle_compra(compra_id);

-- 4. Row Level Security (mismo criterio que las demás tablas del proyecto)
ALTER TABLE public.productos ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.compras ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.detalle_compra ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "Acceso total productos" ON public.productos;
CREATE POLICY "Acceso total productos" ON public.productos
FOR ALL TO anon, authenticated, service_role USING (true) WITH CHECK (true);

DROP POLICY IF EXISTS "Acceso total compras" ON public.compras;
CREATE POLICY "Acceso total compras" ON public.compras
FOR ALL TO anon, authenticated, service_role USING (true) WITH CHECK (true);

DROP POLICY IF EXISTS "Acceso total detalle_compra" ON public.detalle_compra;
CREATE POLICY "Acceso total detalle_compra" ON public.detalle_compra
FOR ALL TO anon, authenticated, service_role USING (true) WITH CHECK (true);

-- 5. Vista lista para el dashboard: una fila por compra con sus productos resumidos
CREATE OR REPLACE VIEW public.vista_compras_resumen AS
SELECT
    c.id,
    c.fecha_compra,
    c.cliente_nombre,
    c.cliente_correo,
    c.total,
    c.estado,
    COUNT(d.id) AS lineas,
    COALESCE(SUM(d.cantidad), 0) AS unidades
FROM public.compras c
LEFT JOIN public.detalle_compra d ON d.compra_id = c.id
GROUP BY c.id;

-- Verificación
SELECT 'Tablas productos, compras y detalle_compra creadas con éxito' AS resultado;
