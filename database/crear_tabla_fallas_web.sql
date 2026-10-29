-- ==============================================================================
-- PROYECTO: ALICORP INTELLIGENCE v2
-- TABLA: fallas_web_sistema (Detección de fallas generales en la web en tiempo real)
-- ==============================================================================
-- INSTRUCCIONES:
-- 1. Entra a tu proyecto en Supabase -> "SQL Editor".
-- 2. Abre una "New query", pega este contenido completo y presiona "RUN".
-- ==============================================================================

CREATE TABLE IF NOT EXISTS public.fallas_web_sistema (
    id BIGSERIAL PRIMARY KEY,
    codigo_unico VARCHAR(180) UNIQUE NOT NULL,
    titulo_error TEXT NOT NULL,
    detalle_tecnico TEXT DEFAULT '',
    tipo_falla VARCHAR(80) NOT NULL,
    gravedad VARCHAR(30) NOT NULL DEFAULT 'Media',
    origen VARCHAR(100) NOT NULL DEFAULT 'Sistema Web',
    estado VARCHAR(30) NOT NULL DEFAULT 'sin_resolver',
    ocurrencias INT NOT NULL DEFAULT 1,
    actualizado_por VARCHAR(120) DEFAULT 'sistema',
    fecha_deteccion TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    ultima_deteccion TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Índices para consultas rápidas por estado, tipo de falla y última detección
CREATE INDEX IF NOT EXISTS idx_fallas_web_estado 
ON public.fallas_web_sistema(estado);

CREATE INDEX IF NOT EXISTS idx_fallas_web_tipo 
ON public.fallas_web_sistema(tipo_falla);

CREATE INDEX IF NOT EXISTS idx_fallas_web_ultima_deteccion 
ON public.fallas_web_sistema(ultima_deteccion DESC);

-- Habilitar Row Level Security (RLS) con política de acceso para el backend/cliente
ALTER TABLE public.fallas_web_sistema ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "Acceso total fallas_web_sistema" ON public.fallas_web_sistema;

CREATE POLICY "Acceso total fallas_web_sistema"
ON public.fallas_web_sistema
FOR ALL
TO anon, authenticated, service_role
USING (true)
WITH CHECK (true);

SELECT 'Tabla fallas_web_sistema creada y configurada correctamente' AS resultado;

