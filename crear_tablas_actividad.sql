-- ==============================================================================
-- PROYECTO: ALICORP INTELLIGENCE v2
-- TABLA: actividad_usuarios y ampliación de administradores
-- ==============================================================================
-- INSTRUCCIONES:
-- 1. Entra a tu proyecto en Supabase: https://supabase.com/dashboard/project/tpgrjvnmozfbpualnlsf
-- 2. Ve a "SQL Editor" en el menú lateral izquierdo.
-- 3. Abre una "New query", pega este contenido completo y presiona "RUN" (o Ctrl+Enter).
-- ==============================================================================

-- 1. Agregar columna nombre_completo a la tabla administradores si aún no existe
ALTER TABLE public.administradores 
ADD COLUMN IF NOT EXISTS nombre_completo VARCHAR(255);

-- 2. Si ya existen administradores sin nombre completo, asignarles su usuario por defecto
UPDATE public.administradores 
SET nombre_completo = usuario 
WHERE nombre_completo IS NULL;

-- 3. Crear tabla de registro en vivo de actividad diaria de usuarios
CREATE TABLE IF NOT EXISTS public.actividad_usuarios (
    id BIGSERIAL PRIMARY KEY,
    usuario VARCHAR(150) NOT NULL,
    fecha DATE NOT NULL DEFAULT CURRENT_DATE,
    tiempo_segundos INT NOT NULL DEFAULT 0,
    primer_ingreso TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    ultimo_latido TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT unq_usuario_fecha UNIQUE (usuario, fecha)
);

-- 4. Crear índices para consultas ultra rápidas por usuario y fecha
CREATE INDEX IF NOT EXISTS idx_actividad_usuario_fecha 
ON public.actividad_usuarios(usuario, fecha);

CREATE INDEX IF NOT EXISTS idx_actividad_fecha 
ON public.actividad_usuarios(fecha DESC);

-- 5. Habilitar Row Level Security (RLS) con acceso completo
ALTER TABLE public.actividad_usuarios ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "Acceso total actividad_usuarios" ON public.actividad_usuarios;

CREATE POLICY "Acceso total actividad_usuarios"
ON public.actividad_usuarios
FOR ALL
TO anon, authenticated, service_role
USING (true)
WITH CHECK (true);

-- 6. Verificación de tablas
SELECT 'Tabla actividad_usuarios lista y columna nombre_completo creada con éxito' AS resultado;
