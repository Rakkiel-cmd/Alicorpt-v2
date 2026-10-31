-- Alicorpt-v2: administración básica y doble aprobación de llave.
-- Idempotente: se puede ejecutar varias veces.
-- IMPORTANTE: NO se habilita RLS; el backend utiliza la clave anon configurada actualmente.
-- Si las tablas ya existen con columnas incompatibles y se confirmó que están vacías,
-- se pueden descomentar manualmente estas líneas antes de ejecutar:
-- DROP TABLE IF EXISTS public.solicitudes_cambio_clave;
-- DROP TABLE IF EXISTS public.auditoria_usuarios;

ALTER TABLE public.administradores ADD COLUMN IF NOT EXISTS rol VARCHAR(20) NOT NULL DEFAULT 'analista';
ALTER TABLE public.administradores ADD COLUMN IF NOT EXISTS estado VARCHAR(20) NOT NULL DEFAULT 'activo';
ALTER TABLE public.administradores ADD COLUMN IF NOT EXISTS email VARCHAR(200);
-- Normaliza columnas preexistentes si ya existían con NULL o valores antiguos.
UPDATE public.administradores SET rol = 'analista' WHERE rol IS NULL OR rol NOT IN ('analista', 'superadmin');
UPDATE public.administradores SET estado = 'activo' WHERE estado IS NULL OR estado NOT IN ('activo', 'suspendido');
ALTER TABLE public.administradores ALTER COLUMN rol SET DEFAULT 'analista';
ALTER TABLE public.administradores ALTER COLUMN rol SET NOT NULL;
ALTER TABLE public.administradores ALTER COLUMN estado SET DEFAULT 'activo';
ALTER TABLE public.administradores ALTER COLUMN estado SET NOT NULL;

ALTER TABLE public.administradores DROP CONSTRAINT IF EXISTS chk_administradores_rol;
ALTER TABLE public.administradores ADD CONSTRAINT chk_administradores_rol CHECK (rol IN ('analista', 'superadmin'));
ALTER TABLE public.administradores DROP CONSTRAINT IF EXISTS chk_administradores_estado;
ALTER TABLE public.administradores ADD CONSTRAINT chk_administradores_estado CHECK (estado IN ('activo', 'suspendido'));

-- Migración desde la lista anterior de superadministradores. No se asignan superadmins
-- predeterminados que no existan como colaboradores en administradores.
DO $$
BEGIN
    IF to_regclass('public.configuracion_seguridad') IS NOT NULL THEN
        BEGIN
            UPDATE public.administradores a SET rol = 'superadmin'
            WHERE lower(a.usuario) IN (
                SELECT lower(jsonb_array_elements_text(valor::jsonb))
                FROM public.configuracion_seguridad
                WHERE clave = 'superadmin_usuarios' AND valor::text LIKE '[%]'
            );
        EXCEPTION WHEN others THEN
            RAISE NOTICE 'No se pudo migrar superadmin_usuarios; revisar el formato del valor manualmente.';
        END;
    ELSE
        RAISE NOTICE 'No existe configuracion_seguridad; no se asignaron superadmins por defecto.';
    END IF;
END $$;

CREATE TABLE IF NOT EXISTS public.auditoria_usuarios (
    id BIGSERIAL PRIMARY KEY,
    usuario_afectado VARCHAR(100) NOT NULL,
    accion VARCHAR(40) NOT NULL CHECK (accion IN ('suspension', 'reactivacion', 'ascenso_rol', 'cambio_clave', 'actualizacion_email')),
    realizado_por VARCHAR(100) NOT NULL,
    motivo TEXT,
    fecha TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
ALTER TABLE public.auditoria_usuarios DROP CONSTRAINT IF EXISTS auditoria_usuarios_accion_check;
ALTER TABLE public.auditoria_usuarios ADD CONSTRAINT auditoria_usuarios_accion_check
    CHECK (accion IN ('suspension', 'reactivacion', 'ascenso_rol', 'degradacion_rol', 'cambio_clave', 'actualizacion_email'));
CREATE INDEX IF NOT EXISTS idx_auditoria_usuarios_afectado ON public.auditoria_usuarios (usuario_afectado, fecha DESC);

CREATE TABLE IF NOT EXISTS public.solicitudes_cambio_clave (
    id BIGSERIAL PRIMARY KEY,
    clave_propuesta VARCHAR(12) NOT NULL,
    solicitado_por VARCHAR(100) NOT NULL,
    aprobado_por VARCHAR(100),
    estado VARCHAR(20) NOT NULL DEFAULT 'pendiente' CHECK (estado IN ('pendiente', 'aprobado', 'rechazado')),
    creado_en TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    resuelto_en TIMESTAMPTZ,
    CONSTRAINT chk_solicitud_clave_aprobador_distinto CHECK (aprobado_por IS NULL OR lower(aprobado_por) <> lower(solicitado_por)),
    CONSTRAINT chk_solicitud_clave_formato CHECK (clave_propuesta ~ '^[0-9]{4,12}$')
);
ALTER TABLE public.solicitudes_cambio_clave DROP CONSTRAINT IF EXISTS solicitudes_cambio_clave_estado_check;
ALTER TABLE public.solicitudes_cambio_clave ADD CONSTRAINT solicitudes_cambio_clave_estado_check CHECK (estado IN ('pendiente', 'aprobado', 'rechazado'));
ALTER TABLE public.solicitudes_cambio_clave DROP CONSTRAINT IF EXISTS chk_solicitud_clave_aprobador_distinto;
ALTER TABLE public.solicitudes_cambio_clave ADD CONSTRAINT chk_solicitud_clave_aprobador_distinto
    CHECK (aprobado_por IS NULL OR lower(aprobado_por) <> lower(solicitado_por));
ALTER TABLE public.solicitudes_cambio_clave DROP CONSTRAINT IF EXISTS chk_solicitud_clave_formato;
ALTER TABLE public.solicitudes_cambio_clave ADD CONSTRAINT chk_solicitud_clave_formato CHECK (clave_propuesta ~ '^[0-9]{4,12}$');
CREATE INDEX IF NOT EXISTS idx_solicitudes_cambio_estado ON public.solicitudes_cambio_clave (estado, creado_en DESC);

-- Este script no activa RLS ni crea políticas. Revisar permisos existentes de forma separada.
-- Normaliza posibles pendientes duplicadas antiguas antes del índice único.
WITH pendientes AS (
    SELECT id, ROW_NUMBER() OVER (ORDER BY creado_en DESC, id DESC) AS rn
    FROM public.solicitudes_cambio_clave WHERE estado = 'pendiente'
)
UPDATE public.solicitudes_cambio_clave s
SET estado = 'rechazado', resuelto_en = NOW()
FROM pendientes p WHERE s.id = p.id AND p.rn > 1;
-- Evita solicitudes pendientes simultáneas incluso ante dos peticiones concurrentes.
CREATE UNIQUE INDEX IF NOT EXISTS uq_solicitud_clave_pendiente_unica
    ON public.solicitudes_cambio_clave ((estado)) WHERE estado = 'pendiente';


-- Fuente de verdad: administradores. Esta lista heredada se mantiene sincronizada
-- automáticamente para los módulos antiguos que todavía consultan superadmin_usuarios.
-- Solo se incluyen cuentas con rol superadmin y estado activo.
CREATE OR REPLACE FUNCTION public.reconstruir_lista_superadmin_usuarios()
RETURNS VOID
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_lista JSONB;
    v_tipo_valor TEXT;
BEGIN
    IF to_regclass('public.configuracion_seguridad') IS NULL THEN
        RETURN;
    END IF;

    SELECT COALESCE(jsonb_agg(a.usuario ORDER BY lower(a.usuario)), '[]'::jsonb)
      INTO v_lista
      FROM public.administradores a
     WHERE lower(COALESCE(a.rol, 'analista')) = 'superadmin'
       AND lower(COALESCE(a.estado, 'activo')) = 'activo';

    SELECT data_type INTO v_tipo_valor
      FROM information_schema.columns
     WHERE table_schema = 'public'
       AND table_name = 'configuracion_seguridad'
       AND column_name = 'valor';

    IF v_tipo_valor IS NULL THEN
        RAISE EXCEPTION 'No existe configuracion_seguridad.valor; no se puede sincronizar Super Admin.';
    ELSIF v_tipo_valor IN ('json', 'jsonb') THEN
        EXECUTE 'INSERT INTO public.configuracion_seguridad (clave, valor) VALUES ($1, $2) ON CONFLICT (clave) DO UPDATE SET valor = EXCLUDED.valor'
            USING 'superadmin_usuarios', v_lista;
    ELSE
        EXECUTE 'INSERT INTO public.configuracion_seguridad (clave, valor) VALUES ($1, $2) ON CONFLICT (clave) DO UPDATE SET valor = EXCLUDED.valor'
            USING 'superadmin_usuarios', v_lista::text;
    END IF;
END;
$$;

REVOKE ALL ON FUNCTION public.reconstruir_lista_superadmin_usuarios() FROM PUBLIC;

CREATE OR REPLACE FUNCTION public.sincronizar_superadmin_usuarios()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
BEGIN
    PERFORM public.reconstruir_lista_superadmin_usuarios();
    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS trg_sincronizar_superadmin_usuarios ON public.administradores;
DROP TRIGGER IF EXISTS trg_sincronizar_superadmin_usuarios_update ON public.administradores;
CREATE TRIGGER trg_sincronizar_superadmin_usuarios
AFTER INSERT OR DELETE ON public.administradores
FOR EACH ROW EXECUTE FUNCTION public.sincronizar_superadmin_usuarios();
CREATE TRIGGER trg_sincronizar_superadmin_usuarios_update
AFTER UPDATE OF rol, estado ON public.administradores
FOR EACH ROW EXECUTE FUNCTION public.sincronizar_superadmin_usuarios();

REVOKE ALL ON FUNCTION public.sincronizar_superadmin_usuarios() FROM PUBLIC;

-- Sincronización inicial: reconstruye la lista a partir de los roles y estados ya migrados.
SELECT public.reconstruir_lista_superadmin_usuarios();
