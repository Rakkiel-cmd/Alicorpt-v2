-- =====================================================================
-- SCRIPT DE AMPLIACIÓN: ADMINISTRACIÓN DE USUARIOS Y SEGURIDAD AVANZADA
-- =====================================================================
-- Copiar y pegar este script en el SQL Editor de Supabase y darle a "Run".
-- Es idempotente: se puede ejecutar varias veces sin romper datos existentes.
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. Ampliar la tabla 'administradores' con rol, estado y correo
-- ---------------------------------------------------------------------
ALTER TABLE public.administradores
    ADD COLUMN IF NOT EXISTS rol VARCHAR(20) NOT NULL DEFAULT 'analista';

ALTER TABLE public.administradores
    ADD COLUMN IF NOT EXISTS estado VARCHAR(20) NOT NULL DEFAULT 'activo';

ALTER TABLE public.administradores
    ADD COLUMN IF NOT EXISTS email VARCHAR(200);

-- Asegurar que solo existan los valores permitidos en 'rol' y 'estado'
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'chk_administradores_rol'
    ) THEN
        ALTER TABLE public.administradores
            ADD CONSTRAINT chk_administradores_rol
            CHECK (rol IN ('analista', 'superadmin'));
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'chk_administradores_estado'
    ) THEN
        ALTER TABLE public.administradores
            ADD CONSTRAINT chk_administradores_estado
            CHECK (estado IN ('activo', 'suspendido'));
    END IF;
END $$;

-- Migrar superadmins por defecto y los existentes en configuracion_seguridad
UPDATE public.administradores
SET rol = 'superadmin'
WHERE lower(usuario) IN ('lperez', 'manuelito');

UPDATE public.administradores
SET rol = 'superadmin'
WHERE lower(usuario) IN (
    SELECT lower(jsonb_array_elements_text(valor::jsonb))
    FROM public.configuracion_seguridad
    WHERE clave = 'superadmin_usuarios'
      AND valor LIKE '[%]'
);

-- ---------------------------------------------------------------------
-- 2. Tabla de auditoría de acciones sobre usuarios
--    (suspensión, reactivación, ascenso de rol)
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS public.auditoria_usuarios (
    id BIGSERIAL PRIMARY KEY,
    usuario_afectado VARCHAR(100) NOT NULL,
    accion VARCHAR(40) NOT NULL CHECK (accion IN ('suspension', 'reactivacion', 'ascenso_rol')),
    realizado_por VARCHAR(100) NOT NULL,
    motivo TEXT,
    fecha TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_auditoria_usuarios_afectado
    ON public.auditoria_usuarios (usuario_afectado, fecha DESC);

-- ---------------------------------------------------------------------
-- 3. Tabla de solicitudes de cambio de llave con aprobación doble
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS public.solicitudes_cambio_clave (
    id BIGSERIAL PRIMARY KEY,
    nueva_clave VARCHAR(20) NOT NULL,
    solicitante VARCHAR(100) NOT NULL,
    aprobador VARCHAR(100),
    estado VARCHAR(20) NOT NULL DEFAULT 'pendiente'
        CHECK (estado IN ('pendiente', 'aprobada', 'rechazada', 'expirada')),
    motivo_rechazo TEXT,
    creado_en TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    resuelto_en TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_solicitudes_cambio_estado
    ON public.solicitudes_cambio_clave (estado, creado_en DESC);

-- ---------------------------------------------------------------------
-- 4. Tabla de eventos de actividad (para alertas de actividad inusual)
--    Registra cada intento de inicio de sesión y cada acceso relevante.
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS public.eventos_actividad (
    id BIGSERIAL PRIMARY KEY,
    usuario VARCHAR(100) NOT NULL,
    tipo_evento VARCHAR(40) NOT NULL
        CHECK (tipo_evento IN ('login_exitoso', 'login_fallido', 'inicio_sesion_fuera_horario', 'cambio_clave', 'acceso_panel_superadmin')),
    detalle TEXT,
    ip VARCHAR(64),
    fecha TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_eventos_actividad_usuario_fecha
    ON public.eventos_actividad (usuario, fecha DESC);

CREATE INDEX IF NOT EXISTS idx_eventos_actividad_tipo_fecha
    ON public.eventos_actividad (tipo_evento, fecha DESC);

-- ---------------------------------------------------------------------
-- 5. Tabla de configuración de rotación de llave secreta
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS public.rotacion_clave_config (
    id INT PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    dias_rotacion INT NOT NULL DEFAULT 30 CHECK (dias_rotacion BETWEEN 1 AND 365),
    ultima_rotacion TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    rotacion_automatica BOOLEAN NOT NULL DEFAULT FALSE,
    actualizado_por VARCHAR(100)
);

INSERT INTO public.rotacion_clave_config (id, dias_rotacion, ultima_rotacion, rotacion_automatica)
VALUES (1, 30, NOW(), FALSE)
ON CONFLICT (id) DO NOTHING;

-- ---------------------------------------------------------------------
-- 6. Políticas RLS para lectura/escritura desde el backend
-- ---------------------------------------------------------------------
ALTER TABLE public.auditoria_usuarios ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.solicitudes_cambio_clave ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.eventos_actividad ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.rotacion_clave_config ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "Acceso total backend auditoria_usuarios" ON public.auditoria_usuarios;
CREATE POLICY "Acceso total backend auditoria_usuarios"
    ON public.auditoria_usuarios FOR ALL USING (true) WITH CHECK (true);

DROP POLICY IF EXISTS "Acceso total backend solicitudes_cambio_clave" ON public.solicitudes_cambio_clave;
CREATE POLICY "Acceso total backend solicitudes_cambio_clave"
    ON public.solicitudes_cambio_clave FOR ALL USING (true) WITH CHECK (true);

DROP POLICY IF EXISTS "Acceso total backend eventos_actividad" ON public.eventos_actividad;
CREATE POLICY "Acceso total backend eventos_actividad"
    ON public.eventos_actividad FOR ALL USING (true) WITH CHECK (true);

DROP POLICY IF EXISTS "Acceso total backend rotacion_clave_config" ON public.rotacion_clave_config;
CREATE POLICY "Acceso total backend rotacion_clave_config"
    ON public.rotacion_clave_config FOR ALL USING (true) WITH CHECK (true);
