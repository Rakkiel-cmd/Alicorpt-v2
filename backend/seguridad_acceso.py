"""Funciones compartidas de autorización y llave de acceso."""
import os
import json
import random
import re
import hmac
from datetime import datetime

RUTA_BASE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DATA_DIR = os.path.join(RUTA_BASE, 'data')
ARCHIVO_CLAVE_LOCAL = os.path.join(DATA_DIR, 'seguridad_clave.json')
CLAVE_POR_DEFECTO = "784920"
SUPERADMINS_POR_DEFECTO = ["LPerez", "manuelito"]


def _obtener_supabase_client():
    try:
        from backend.datos_supabase import obtener_cliente_supabase
        client = obtener_cliente_supabase()
        if client:
            return client
    except Exception:
        pass
    url, key = os.environ.get('SUPABASE_URL'), os.environ.get('SUPABASE_KEY')
    if not url or not key:
        return None
    try:
        from supabase import create_client
        return create_client(url, key)
    except Exception:
        return None


def _asegurar_archivo_local():
    os.makedirs(DATA_DIR, exist_ok=True)
    if not os.path.exists(ARCHIVO_CLAVE_LOCAL):
        datos = {'clave_acceso_personal': CLAVE_POR_DEFECTO, 'superadmin_usuarios': [],
                 'updated_at': datetime.utcnow().isoformat(), 'actualizado_por': 'sistema_inicial'}
        with open(ARCHIVO_CLAVE_LOCAL, 'w', encoding='utf-8') as f:
            json.dump(datos, f, indent=2)
        return datos
    try:
        with open(ARCHIVO_CLAVE_LOCAL, 'r', encoding='utf-8') as f:
            return json.load(f)
    except Exception:
        return {'clave_acceso_personal': CLAVE_POR_DEFECTO, 'superadmin_usuarios': []}


def _guardar_archivo_local(clave, actualizado_por='superadmin'):
    os.makedirs(DATA_DIR, exist_ok=True)
    datos = {'clave_acceso_personal': str(clave).strip(), 'superadmin_usuarios': [],
             'updated_at': datetime.utcnow().isoformat(), 'actualizado_por': actualizado_por}
    with open(ARCHIVO_CLAVE_LOCAL, 'w', encoding='utf-8') as f:
        json.dump(datos, f, indent=2)


def obtener_clave_acceso():
    """La llave persistida en Supabase es la fuente principal para permitir su cambio aprobado."""
    client = _obtener_supabase_client()
    if client:
        try:
            res = client.table('configuracion_seguridad').select('valor').eq('clave', 'clave_acceso_personal').limit(1).execute()
            if res.data and res.data[0].get('valor') is not None:
                clave = str(res.data[0]['valor']).strip()
                _guardar_archivo_local(clave, 'sincronizacion_db')
                return clave
        except Exception:
            # Si la consulta falla, no se usa un dato local antiguo por encima de una variable explícita.
            pass
    env_key = os.environ.get('LLAVE_ACCESO_PERSONAL')
    if env_key and env_key.strip():
        return env_key.strip()
    return str(_asegurar_archivo_local().get('clave_acceso_personal', CLAVE_POR_DEFECTO)).strip()


def validar_clave_acceso(clave_candidata):
    if clave_candidata is None:
        return False
    return hmac.compare_digest(str(clave_candidata).strip(), obtener_clave_acceso())


def obtener_perfil_usuario(usuario):
    if not usuario:
        return None
    client = _obtener_supabase_client()
    if not client:
        return None
    try:
        res = client.table('administradores').select('usuario, rol, estado').ilike('usuario', str(usuario).strip()).limit(1).execute()
        if res.data:
            fila = res.data[0]
            return {'usuario': fila.get('usuario'), 'rol': str(fila.get('rol') or 'analista').lower(),
                    'estado': str(fila.get('estado') or 'activo').lower()}
    except Exception:
        return None
    return None


def esta_suspendido(usuario):
    """Falla de forma segura: cuenta inexistente o estado no verificable no autentica."""
    perfil = obtener_perfil_usuario(usuario)
    return not perfil or perfil.get('estado') != 'activo'


def es_superadmin(usuario):
    """Solo la columna rol de un registro activo concede privilegios."""
    perfil = obtener_perfil_usuario(usuario)
    return bool(perfil and perfil['estado'] == 'activo' and perfil['rol'] == 'superadmin')


def verificar_password(usuario, password_candidata):
    """Verifica la contraseña existente en administradores con comparación constante.
    En este proyecto se conserva el formato actual de almacenamiento, sin introducir nuevas librerías.
    Si la consulta o el dato no se pueden verificar, devuelve False.
    """
    if not usuario or password_candidata is None:
        return False
    client = _obtener_supabase_client()
    if not client:
        return False
    try:
        res = client.table('administradores').select('password').ilike('usuario', str(usuario).strip()).limit(1).execute()
        if not res.data:
            return False
        real = res.data[0].get('password')
        if not isinstance(real, str) or not isinstance(password_candidata, str):
            return False
        return hmac.compare_digest(real, password_candidata)
    except Exception:
        return False


def modificar_clave_acceso(usuario_solicitante, password_confirmacion, nueva_clave):
    """Compatibilidad: la llave ya no se modifica aquí; la ruta antigua crea una solicitud."""
    return {'success': False, 'message': 'El cambio de llave requiere una solicitud y la aprobación de otro Super Admin.'}


def guardar_nueva_clave_aprobada(nueva_clave, aprobado_por):
    if not re.fullmatch(r'\d{4,12}', str(nueva_clave or '')):
        return {'success': False, 'message': 'La llave debe contener entre 4 y 12 dígitos.'}
    client = _obtener_supabase_client()
    if not client:
        return {'success': False, 'message': 'No se pudo verificar Supabase; la llave no fue modificada.'}
    try:
        client.table('configuracion_seguridad').upsert({
            'clave': 'clave_acceso_personal', 'valor': str(nueva_clave),
            'actualizado_por': aprobado_por, 'updated_at': datetime.utcnow().isoformat()
        }, on_conflict='clave').execute()
        try:
            _guardar_archivo_local(str(nueva_clave), aprobado_por)
        except OSError:
            # Supabase es la fuente persistente; un fallo de caché local no revierte la escritura confirmada.
            pass
        return {'success': True, 'message': 'Llave actualizada tras doble aprobación.'}
    except Exception as exc:
        return {'success': False, 'message': f'No se pudo guardar la llave: {exc}'}


def generar_clave_aleatoria(longitud=6):
    import secrets
    return ''.join(str(secrets.randbelow(10)) for _ in range(longitud))
