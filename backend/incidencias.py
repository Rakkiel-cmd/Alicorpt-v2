"""
backend/incidencias.py
Módulo para el registro y gestión de incidencias y quejas de clientes.
- POST /api/contacto                  : Recibe el formulario de contacto con sanitización anti-script y anti-SQLi.
- GET  /api/incidencias               : Consulta la bandeja de incidencias registradas.
- POST /api/incidencias/cambiar_estado: Alterna el estado de la incidencia (en_espera <-> leido).
"""

import html
import json
import os
import re
import threading
from datetime import datetime, timezone
from flask import Blueprint, jsonify, request

incidencias_bp = Blueprint("incidencias", __name__)

DIR_PROYECTO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DIR_DATA = os.path.join(DIR_PROYECTO, "data")
RUTA_FALLBACK_JSON = os.path.join(DIR_DATA, "incidencias_contacto.json")
_candado_archivo = threading.Lock()


def _obtener_cliente_supabase():
    """Retorna el cliente de Supabase si las credenciales están configuradas."""
    url = os.environ.get("SUPABASE_URL")
    key = os.environ.get("SUPABASE_KEY")
    if not url or not key:
        return None
    try:
        from supabase import Client, create_client
        return create_client(url, key)
    except Exception as e:
        print("[INCIDENCIAS] Error al instanciar Supabase:", e)
        return None


# ==============================================================================
# SEGURIDAD Y SANITIZACIÓN (ANTI-XSS, ANTI-SCRIPT, ANTI-SQL INJECTION)
# ==============================================================================

PATRON_CORREO = re.compile(r"^[a-zA-Z0-9_.+-]+@[a-zA-Z0-9-]+\.[a-zA-Z0-9-.]+$")
PATRON_SQL_INJECTION = re.compile(
    r"(union\s+select|insert\s+into|delete\s+from|drop\s+table|update\s+\w+\s+set|exec\s*\(|--|/\*|\*/|;\s*$)",
    re.IGNORECASE
)
PATRON_SCRIPTS = re.compile(
    r"(<\s*script[^>]*>.*?<\s*/\s*script\s*>|<\s*iframe[^>]*>|<\s*style[^>]*>|javascript\s*:|onerror\s*=|onload\s*=|onclick\s*=|eval\s*\(|document\.cookie)",
    re.IGNORECASE | re.DOTALL
)


def sanitizar_cadena(valor: str, max_longitud: int = 500) -> str:
    """Elimina scripts maliciosos, bytes nulos y codifica entidades HTML."""
    if not isinstance(valor, str):
        return ""
    # 1. Quitar bytes nulos y espacios extremos
    texto = valor.replace("\x00", "").strip()
    # 2. Remover patrones de script maliciosos y payloads embebidos
    texto = PATRON_SCRIPTS.sub("", texto)
    # 3. Truncar a la longitud máxima permitida
    texto = texto[:max_longitud]
    # 4. Codificar caracteres HTML peligrosos (< > " ' &)
    texto_seguro = html.escape(texto, quote=True)
    return texto_seguro.strip()


def validar_datos_contacto(nombre: str, correo: str, mensaje: str):
    """Valida los campos obligatorios y levanta excepciones si hay violaciones de seguridad."""
    if not nombre or len(nombre) < 2 or len(nombre) > 120:
        raise ValueError("El nombre completo debe tener entre 2 y 120 caracteres.")

    if PATRON_SQL_INJECTION.search(nombre):
        raise ValueError("El nombre ingresado contiene caracteres o sintaxis no permitida.")

    if not correo or not PATRON_CORREO.match(correo) or len(correo) > 120:
        raise ValueError("Por favor ingresa un correo electrónico válido.")

    if PATRON_SQL_INJECTION.search(correo):
        raise ValueError("El correo contiene caracteres no permitidos.")

    if not mensaje or len(mensaje) < 5 or len(mensaje) > 2000:
        raise ValueError("El mensaje debe contener entre 5 y 2000 caracteres.")


# ==============================================================================
# ALMACENAMIENTO PERSISTENTE RESILIENTE (SUPABASE + LOCAL FALLBACK)
# ==============================================================================

def _leer_incidencias_locales():
    with _candado_archivo:
        if not os.path.exists(RUTA_FALLBACK_JSON):
            return []
        try:
            with open(RUTA_FALLBACK_JSON, "r", encoding="utf-8") as f:
                return json.load(f)
        except Exception:
            return []


def _guardar_incidencia_local(item):
    with _candado_archivo:
        os.makedirs(DIR_DATA, exist_ok=True)
        items = []
        if os.path.exists(RUTA_FALLBACK_JSON):
            try:
                with open(RUTA_FALLBACK_JSON, "r", encoding="utf-8") as f:
                    items = json.load(f)
            except Exception:
                items = []
        items.insert(0, item)
        with open(RUTA_FALLBACK_JSON, "w", encoding="utf-8") as f:
            json.dump(items, f, ensure_ascii=False, indent=2)


def _actualizar_estado_local(incidencia_id, nuevo_estado):
    with _candado_archivo:
        if not os.path.exists(RUTA_FALLBACK_JSON):
            return False
        try:
            with open(RUTA_FALLBACK_JSON, "r", encoding="utf-8") as f:
                items = json.load(f)
            encontrado = False
            for it in items:
                if str(it.get("id")) == str(incidencia_id):
                    it["estado"] = nuevo_estado
                    encontrado = True
                    break
            if encontrado:
                with open(RUTA_FALLBACK_JSON, "w", encoding="utf-8") as f:
                    json.dump(items, f, ensure_ascii=False, indent=2)
            return encontrado
        except Exception:
            return False


# ==============================================================================
# ENDPOINTS REST API
# ==============================================================================

@incidencias_bp.route("/api/contacto", methods=["POST"])
def registrar_contacto():
    """Recibe y almacena una nueva incidencia/contacto de un cliente."""
    try:
        datos = request.get_json(silent=True) or request.form or {}
        nombre_raw = datos.get("nombre") or datos.get("nombre_cliente") or ""
        correo_raw = datos.get("correo") or datos.get("email") or ""
        mensaje_raw = datos.get("mensaje") or ""

        # Validación estricta
        validar_datos_contacto(nombre_raw.strip(), correo_raw.strip(), mensaje_raw.strip())

        # Sanitización de contenido
        nombre = sanitizar_cadena(nombre_raw, 120)
        correo = sanitizar_cadena(correo_raw, 120)
        mensaje = sanitizar_cadena(mensaje_raw, 2000)

        ahora_iso = datetime.now(timezone.utc).isoformat()
        nuevo_registro = {
            "nombre_cliente": nombre,
            "correo": correo,
            "mensaje": mensaje,
            "estado": "en_espera",
            "fecha_registro": ahora_iso
        }

        # 1. Intentar insertar en Supabase
        guardado_en_supabase = False
        sb = _obtener_cliente_supabase()
        if sb:
            try:
                res = sb.table("incidencias_contacto").insert(nuevo_registro).execute()
                if res.data:
                    guardado_en_supabase = True
                    nuevo_registro["id"] = res.data[0].get("id")
            except Exception as e:
                print("[INCIDENCIAS] Supabase insert warning (se usará respaldo local):", e)

        # 2. Si no se guardó en Supabase o falló la tabla, guardar en archivo local
        if not guardado_en_supabase:
            import time
            nuevo_registro["id"] = int(time.time() * 1000)
            _guardar_incidencia_local(nuevo_registro)

        return jsonify({
            "success": True,
            "message": "¡Gracias por contactarnos! Tu incidencia ha sido registrada con éxito en nuestro sistema de atención corporativa.",
            "guardado_en": "supabase" if guardado_en_supabase else "local_resiliente"
        })

    except ValueError as ve:
        return jsonify({"success": False, "message": str(ve)}), 400
    except Exception as e:
        import traceback
        traceback.print_exc()
        return jsonify({"success": False, "message": f"Error interno al procesar el mensaje: {str(e)}"}), 500


@incidencias_bp.route("/api/incidencias", methods=["GET"])
def listar_incidencias():
    """Retorna la lista de incidencias registradas para visualización en el dashboard."""
    sb = _obtener_cliente_supabase()
    if sb:
        try:
            res = sb.table("incidencias_contacto").select("*").order("fecha_registro", desc=True).execute()
            if res.data is not None:
                return jsonify({
                    "success": True,
                    "total": len(res.data),
                    "incidencias": res.data,
                    "fuente": "Supabase PostgreSQL"
                })
        except Exception as e:
            print("[INCIDENCIAS] Supabase query warning (usando respaldo local):", e)

    # Fallback local
    items = _leer_incidencias_locales()
    # Si la lista está vacía, incluir incidencias corporativas iniciales de ejemplo
    if not items:
        items = [
            {
                "id": 1,
                "nombre_cliente": "Carlos Mendoza Soto",
                "correo": "cmendoza@distribuidoracentral.pe",
                "mensaje": "Presentamos demoras con el abastecimiento de Aceite Primor 1L en nuestra sede de Los Olivos. Solicitamos información sobre los lotes de reposición para esta semana.",
                "estado": "en_espera",
                "fecha_registro": datetime.now(timezone.utc).isoformat()
            },
            {
                "id": 2,
                "nombre_cliente": "Valeria Ramos Benítez",
                "correo": "vramos.bodega@gmail.com",
                "mensaje": "Excelente atención con el pedido de Fideos Don Vittorio. Quisiéramos consultar si habilitarán promociones de compra por mayor para Harina Blanca Flor.",
                "estado": "leido",
                "fecha_registro": datetime.now(timezone.utc).isoformat()
            }
        ]
        with _candado_archivo:
            os.makedirs(DIR_DATA, exist_ok=True)
            with open(RUTA_FALLBACK_JSON, "w", encoding="utf-8") as f:
                json.dump(items, f, ensure_ascii=False, indent=2)

    return jsonify({
        "success": True,
        "total": len(items),
        "incidencias": items,
        "fuente": "Almacén local resiliente"
    })


@incidencias_bp.route("/api/incidencias/cambiar_estado", methods=["POST"])
def cambiar_estado():
    """Actualiza el estado de una incidencia entre 'en_espera' y 'leido'."""
    try:
        datos = request.get_json(silent=True) or {}
        incidencia_id = datos.get("id")
        nuevo_estado = datos.get("estado")

        if not incidencia_id:
            return jsonify({"success": False, "message": "ID de incidencia requerido."}), 400

        if nuevo_estado not in ("en_espera", "leido"):
            return jsonify({"success": False, "message": "Estado no válido. Use 'en_espera' o 'leido'."}), 400

        actualizado_en_supabase = False
        sb = _obtener_cliente_supabase()
        if sb:
            try:
                # Intentar convertir a int si es numérico
                id_val = int(incidencia_id) if str(incidencia_id).isdigit() else incidencia_id
                res = sb.table("incidencias_contacto").update({"estado": nuevo_estado}).eq("id", id_val).execute()
                if res.data:
                    actualizado_en_supabase = True
            except Exception as e:
                print("[INCIDENCIAS] Supabase update warning:", e)

        # Actualizar también respaldo local
        _actualizar_estado_local(incidencia_id, nuevo_estado)

        return jsonify({
            "success": True,
            "id": incidencia_id,
            "nuevo_estado": nuevo_estado,
            "actualizado_en": "supabase" if actualizado_en_supabase else "local"
        })

    except Exception as e:
        import traceback
        traceback.print_exc()
        return jsonify({"success": False, "message": str(e)}), 500
