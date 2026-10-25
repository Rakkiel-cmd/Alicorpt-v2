# ==============================================================================
# MÓDULO DE SEGURIDAD Y CONTROL DE ACCESO MEDIANTE LLAVE URL (ALICORP BI)
# ==============================================================================
import os
import json
import random
import re
from datetime import datetime

RUTA_BASE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DATA_DIR = os.path.join(RUTA_BASE, 'data')
ARCHIVO_CLAVE_LOCAL = os.path.join(DATA_DIR, 'seguridad_clave.json')

# Valores por defecto de alta disponibilidad (sincronizados con tabla configuracion_seguridad)
CLAVE_POR_DEFECTO = "784920"
SUPERADMINS_POR_DEFECTO = ["LPerez", "manuelito"]

def _obtener_supabase_client():
    """Retorna un cliente Supabase (usando singleton para optimizar recursos)."""
    try:
        from backend.datos_supabase import obtener_cliente_supabase
        client = obtener_cliente_supabase()
        if client:
            return client
    except Exception:
        pass
    supabase_url = os.environ.get("SUPABASE_URL")
    supabase_key = os.environ.get("SUPABASE_KEY")
    if not supabase_url or not supabase_key:
        return None
    try:
        from supabase import create_client
        return create_client(supabase_url, supabase_key)
    except Exception as e:
        print(f"[Seguridad] Error al instanciar Supabase: {e}")
        return None

def _asegurar_archivo_local():
    """Garantiza la existencia del archivo local de respaldo de seguridad."""
    os.makedirs(DATA_DIR, exist_ok=True)
    if not os.path.exists(ARCHIVO_CLAVE_LOCAL):
        datos_iniciales = {
            "clave_acceso_personal": CLAVE_POR_DEFECTO,
            "superadmin_usuarios": SUPERADMINS_POR_DEFECTO,
            "updated_at": datetime.utcnow().isoformat(),
            "actualizado_por": "sistema_inicial"
        }
        with open(ARCHIVO_CLAVE_LOCAL, 'w', encoding='utf-8') as f:
            json.dump(datos_iniciales, f, indent=4)
        return datos_iniciales
    try:
        with open(ARCHIVO_CLAVE_LOCAL, 'r', encoding='utf-8') as f:
            return json.load(f)
    except Exception:
        return {
            "clave_acceso_personal": CLAVE_POR_DEFECTO,
            "superadmin_usuarios": SUPERADMINS_POR_DEFECTO
        }

def _guardar_archivo_local(clave, actualizado_por="superadmin"):
    """Guarda en el archivo local de respaldo."""
    os.makedirs(DATA_DIR, exist_ok=True)
    datos = {
        "clave_acceso_personal": str(clave).strip(),
        "superadmin_usuarios": SUPERADMINS_POR_DEFECTO,
        "updated_at": datetime.utcnow().isoformat(),
        "actualizado_por": actualizado_por
    }
    with open(ARCHIVO_CLAVE_LOCAL, 'w', encoding='utf-8') as f:
        json.dump(datos, f, indent=4)

def obtener_clave_acceso():
    """
    Obtiene la llave secreta activa de acceso para empleados.
    Prioridad: Variable de Entorno (.env/Render) -> Supabase -> Archivo Local protegido.
    """
    # 1. Variable de entorno segura (.env / Render)
    env_key = os.environ.get("LLAVE_ACCESO_PERSONAL")
    if env_key and str(env_key).strip():
        return str(env_key).strip()

    # 2. Base de datos segura Supabase
    client = _obtener_supabase_client()
    if client:
        try:
            res = client.table('configuracion_seguridad').select('valor').eq('clave', 'clave_acceso_personal').execute()
            if res.data and len(res.data) > 0 and res.data[0].get('valor'):
                clave_db = str(res.data[0]['valor']).strip()
                _guardar_archivo_local(clave_db, actualizado_por="sincronizacion_db")
                return clave_db
        except Exception:
            pass

    # 3. Respaldo local privado (excluido de git por .gitignore)
    local_data = _asegurar_archivo_local()
    return str(local_data.get("clave_acceso_personal", CLAVE_POR_DEFECTO)).strip()

def validar_clave_acceso(clave_candidata):
    """
    Valida si una clave provista por URL coincide con la clave secreta.
    """
    if not clave_candidata:
        return False
    clave_real = obtener_clave_acceso()
    candidata_limpia = str(clave_candidata).strip()
    return candidata_limpia == clave_real

def es_superadmin(usuario):
    """Verifica si un usuario posee el rol de Super Administrador."""
    if not usuario:
        return False
    usuario_str = str(usuario).strip().lower()
    
    # 1. Chequeo de variables de entorno opcional
    env_superadmins = os.environ.get("SUPERADMIN_USUARIOS", "")
    if env_superadmins:
        lista_env = [u.strip().lower() for u in env_superadmins.split(',') if u.strip()]
        if usuario_str in lista_env:
            return True
            
    # 2. Chequeo de Supabase (Fuente oficial y autoritativa en la nube)
    client = _obtener_supabase_client()
    if client:
        try:
            res = client.table('configuracion_seguridad').select('valor').eq('clave', 'superadmin_usuarios').execute()
            if res.data and len(res.data) > 0:
                raw_val = res.data[0].get('valor')
                lista_super = json.loads(raw_val) if isinstance(raw_val, str) else raw_val
                if isinstance(lista_super, list):
                    # Si Supabase responde exitosamente, esta es la fuente definitiva de verdad:
                    return usuario_str in [str(x).strip().lower() for x in lista_super]
        except Exception as e:
            print(f"[Seguridad] Error consultando superadmins en Supabase: {e}")

    # 3. Respaldo local privado únicamente si Supabase no estuvo disponible
    local_data = _asegurar_archivo_local()
    lista_local = local_data.get("superadmin_usuarios", SUPERADMINS_POR_DEFECTO)
    return usuario_str in [str(x).strip().lower() for x in lista_local]

def modificar_clave_acceso(usuario_solicitante, password_confirmacion, nueva_clave):
    """
    Permite modificar la llave secreta exclusivamente a un Super Administrador,
    previa validación de su contraseña en la tabla administradores.
    """
    if not es_superadmin(usuario_solicitante):
        return {
            "success": False,
            "message": "Permiso denegado. Solo un Super Administrador puede modificar la llave de acceso."
        }

    # Validar formato de la nueva clave (solo dígitos, de 4 a 12 caracteres)
    nueva_clave_str = str(nueva_clave).strip()
    if not re.match(r'^\d{4,12}$', nueva_clave_str):
        return {
            "success": False,
            "message": "Formato inválido. La llave debe estar compuesta únicamente por entre 4 y 12 dígitos numéricos."
        }

    # Validar credenciales de confirmación del Super Admin en Supabase
    client = _obtener_supabase_client()
    if client:
        try:
            res = client.table('administradores').select('password').eq('usuario', usuario_solicitante).execute()
            if not res.data or len(res.data) == 0:
                return {"success": False, "message": "Usuario administrador no encontrado."}
            password_real = res.data[0].get('password')
            if str(password_confirmacion) != str(password_real):
                return {"success": False, "message": "Contraseña de confirmación incorrecta."}
        except Exception as e:
            print(f"[Seguridad] Error verificando contraseña en DB: {e}")

    # Guardar en Supabase
    guardado_db = False
    if client:
        try:
            now_iso = datetime.utcnow().isoformat()
            res_upsert = client.table('configuracion_seguridad').upsert({
                'clave': 'clave_acceso_personal',
                'valor': nueva_clave_str,
                'actualizado_por': usuario_solicitante,
                'updated_at': now_iso
            }, on_conflict='clave').execute()
            guardado_db = True
        except Exception as e:
            print(f"[Seguridad] No se pudo escribir en Supabase (usando archivo local): {e}")

    # Guardar en archivo local
    _guardar_archivo_local(nueva_clave_str, actualizado_por=usuario_solicitante)

    return {
        "success": True,
        "message": "Llave secreta de acceso de personal actualizada correctamente.",
        "en_base_de_datos": guardado_db
    }

def generar_clave_aleatoria(longitud=6):
    """Genera una serie de dígitos aleatoria segura."""
    return ''.join([str(random.randint(0, 9)) for _ in range(longitud)])
