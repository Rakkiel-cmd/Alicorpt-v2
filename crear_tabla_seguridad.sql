-- ==============================================================================
-- TABLA DE CONFIGURACIÓN Y SEGURIDAD (LLAVE SECRETA DE ACCESO PERSONAL)
-- Corporación Alicorp S.A.A. - Plataforma BI & Sistema Web
-- ==============================================================================

CREATE TABLE IF NOT EXISTS public.configuracion_seguridad (
    id SERIAL PRIMARY KEY,
    clave VARCHAR(100) UNIQUE NOT NULL,
    valor TEXT NOT NULL,
    descripcion TEXT,
    actualizado_por VARCHAR(100),
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT timezone('utc'::text, now()) NOT NULL
);

-- Insertar llave secreta inicial de acceso para empleados (ejemplo: 784920)
INSERT INTO public.configuracion_seguridad (clave, valor, descripcion, actualizado_por)
VALUES (
    'clave_acceso_personal',
    '784920',
    'Serie de dígitos requerida en la URL (?key=...) para habilitar el botón de Personal Autorizado a empleados.',
    'sistema_inicial'
)
ON CONFLICT (clave) DO NOTHING;

-- Insertar lista de usuarios superadministradores con permiso exclusivo para ver/cambiar la llave
INSERT INTO public.configuracion_seguridad (clave, valor, descripcion, actualizado_por)
VALUES (
    'superadmin_usuarios',
    '["123456789", "admin", "aaron2502"]',
    'Usuarios autorizados para modificar la clave de acceso de personal.',
    'sistema_inicial'
)
ON CONFLICT (clave) DO NOTHING;

-- Habilitar permisos de lectura y actualización
GRANT ALL ON TABLE public.configuracion_seguridad TO postgres, anon, authenticated, service_role;
GRANT USAGE, SELECT ON SEQUENCE public.configuracion_seguridad_id_seq TO postgres, anon, authenticated, service_role;
