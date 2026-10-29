-- ampliar_administracion.sql
-- Fase 1: base de datos para el panel de Administracion del Super Admin.
-- Seguro de correr varias veces (todo usa IF NOT EXISTS), no borra nada
-- existente. Pensado para ejecutarse una sola vez en el SQL Editor de Supabase.

-- ---------------------------------------------------------------------
-- 1) Roles, estado y correo por colaborador
-- ---------------------------------------------------------------------
ALTER TABLE public.administradores
    ADD COLUMN IF NOT EXISTS rol VARCHAR(20) NOT NULL DEFAULT 'analista';

ALTER TABLE public.administradores
    ADD COLUMN IF NOT EXISTS estado VARCHAR(20) NOT NULL DEFAULT 'activo';

ALTER TABLE public.administradores
    ADD COLUMN IF NOT EXISTS email VARCHAR(200);

-- Solo 'analista' o 'superadmin' (plataforma de colaboradores, sin rol 'usuario').
ALTER TABLE public.administradores
    DROP CONSTRAINT IF EXISTS administradores_rol_check;
ALTER TABLE public.administradores
    ADD CONSTRAINT administradores_rol_check CHECK (rol IN ('analista', 'superadmin'));

ALTER TABLE public.administradores
    DROP CONSTRAINT IF EXISTS administradores_estado_check;
ALTER TABLE public.administradores
    ADD CONSTRAINT administradores_estado_check CHECK (estado IN ('activo', 'suspendido'));

-- Migra la lista vieja de superadmins (guardada como JSON en configuracion_seguridad)
-- hacia la columna real 'rol', para que de ahora en adelante el rol viva por
-- persona y se pueda ascender a alguien sin editar esa lista a mano.
UPDATE public.administradores a
SET rol = 'superadmin'
WHERE a.usuario IN (
    SELECT jsonb_array_elements_text(cs.valor::jsonb)
    FROM public.configuracion_seguridad cs
    WHERE cs.clave = 'superadmin_usuarios'
);

-- ---------------------------------------------------------------------
-- 2) Auditoria de acciones administrativas (vive dentro de la pestaña
--    "Incidencias", separada de las quejas de clientes).
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS public.auditoria_usuarios (
    id BIGSERIAL PRIMARY KEY,
    usuario_afectado VARCHAR(100) NOT NULL,
    accion VARCHAR(40) NOT NULL,          -- 'suspension' | 'reactivacion' | 'ascenso_rol'
    realizado_por VARCHAR(100) NOT NULL,
    motivo TEXT,
    fecha TIMESTAMPTZ NOT NULL DEFAULT now()
);
ALTER TABLE public.auditoria_usuarios
    DROP CONSTRAINT IF EXISTS auditoria_usuarios_accion_check;
ALTER TABLE public.auditoria_usuarios
    ADD CONSTRAINT auditoria_usuarios_accion_check
    CHECK (accion IN ('suspension', 'reactivacion', 'ascenso_rol'));

CREATE INDEX IF NOT EXISTS idx_auditoria_usuario_afectado
    ON public.auditoria_usuarios (usuario_afectado);

-- ---------------------------------------------------------------------
-- 3) Doble aprobacion para cambiar la llave manualmente (Four Eyes Principle)
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS public.solicitudes_cambio_clave (
    id BIGSERIAL PRIMARY KEY,
    clave_propuesta VARCHAR(12) NOT NULL,
    solicitado_por VARCHAR(100) NOT NULL,
    aprobado_por VARCHAR(100),
    estado VARCHAR(20) NOT NULL DEFAULT 'pendiente',   -- 'pendiente' | 'aprobado' | 'rechazado'
    creado_en TIMESTAMPTZ NOT NULL DEFAULT now(),
    resuelto_en TIMESTAMPTZ
);
ALTER TABLE public.solicitudes_cambio_clave
    DROP CONSTRAINT IF EXISTS solicitudes_cambio_clave_estado_check;
ALTER TABLE public.solicitudes_cambio_clave
    ADD CONSTRAINT solicitudes_cambio_clave_estado_check
    CHECK (estado IN ('pendiente', 'aprobado', 'rechazado'));

-- ---------------------------------------------------------------------
-- 4) Eventos de actividad CON SIGNIFICADO (nunca clics crudos)
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS public.eventos_actividad (
    id BIGSERIAL PRIMARY KEY,
    usuario VARCHAR(100) NOT NULL,
    tipo_evento VARCHAR(80) NOT NULL,   -- 'cambio_pestana' | 'ejecutar_prediccion' | etc.
    detalle TEXT,
    fecha TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_eventos_actividad_usuario_fecha
    ON public.eventos_actividad (usuario, fecha DESC);

-- ---------------------------------------------------------------------
-- 5) Rotacion automatica de la llave de acceso
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS public.rotacion_clave_config (
    id SMALLINT PRIMARY KEY DEFAULT 1,
    activa BOOLEAN NOT NULL DEFAULT false,
    intervalo_horas INT NOT NULL DEFAULT 24,
    fecha_inicio TIMESTAMPTZ,
    fecha_fin TIMESTAMPTZ,              -- NULL = indefinido
    proxima_rotacion TIMESTAMPTZ,
    activada_por_1 VARCHAR(100),        -- doble aprobacion para ACTIVAR la rotacion
    activada_por_2 VARCHAR(100),
    CONSTRAINT rotacion_clave_config_un_solo_registro CHECK (id = 1)
);
ALTER TABLE public.rotacion_clave_config
    DROP CONSTRAINT IF EXISTS rotacion_clave_config_intervalo_check;
ALTER TABLE public.rotacion_clave_config
    ADD CONSTRAINT rotacion_clave_config_intervalo_check CHECK (intervalo_horas > 0);

INSERT INTO public.rotacion_clave_config (id, activa, intervalo_horas)
VALUES (1, false, 24)
ON CONFLICT (id) DO NOTHING;
