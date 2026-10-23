-- ==============================================================================
-- PROYECTO: ALICORP INTELLIGENCE v2
-- TABLA: incidencias_contacto (Mensajes, quejas y consultas de clientes)
-- ==============================================================================
-- INSTRUCCIONES:
-- 1. Entra a tu proyecto en Supabase: https://supabase.com/dashboard/project/tpgrjvnmozfbpualnlsf
-- 2. Ve a "SQL Editor" en el menú lateral izquierdo.
-- 3. Abre una "New query", pega este contenido y presiona "RUN" (o Ctrl+Enter).
-- ==============================================================================

CREATE TABLE IF NOT EXISTS public.incidencias_contacto (
    id BIGSERIAL PRIMARY KEY,
    nombre_cliente VARCHAR(200) NOT NULL,
    correo VARCHAR(200) NOT NULL,
    mensaje TEXT NOT NULL,
    estado VARCHAR(50) NOT NULL DEFAULT 'en_espera', -- 'en_espera' o 'leido'
    fecha_registro TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Índices para búsqueda rápida
CREATE INDEX IF NOT EXISTS idx_incidencias_fecha 
ON public.incidencias_contacto(fecha_registro DESC);

CREATE INDEX IF NOT EXISTS idx_incidencias_estado 
ON public.incidencias_contacto(estado);

-- Habilitar Row Level Security (RLS) con acceso completo
ALTER TABLE public.incidencias_contacto ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "Acceso total incidencias_contacto" ON public.incidencias_contacto;

CREATE POLICY "Acceso total incidencias_contacto"
ON public.incidencias_contacto
FOR ALL
TO anon, authenticated, service_role
USING (true)
WITH CHECK (true);

-- Verificación
SELECT 'Tabla incidencias_contacto creada con éxito en Supabase' AS resultado;
