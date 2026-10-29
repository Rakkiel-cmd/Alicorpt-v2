"""
backend/incidencias.py
Módulo para el registro y gestión de incidencias de clientes y detección de fallas generales en la web:
- POST /api/contacto                  : Recibe el formulario de contacto con sanitización anti-script y anti-SQLi.
- GET  /api/incidencias               : Consulta la bandeja de incidencias registradas.
- POST /api/incidencias/cambiar_estado: Alterna el estado de la incidencia (en_espera <-> leido).
- GET  /api/fallas_web                : Lista y detecta en tiempo real fallas generales de la web/dashboard/stock.
- POST /api/fallas_web/reportar       : Registra en tiempo real errores de consola, bugs, red o falta de stock.
- POST /api/fallas_web/cambiar_estado : Cambia el estado de una falla (sin_resolver | en_proceso | resuelto) en Supabase.
- POST /api/fallas_web/escanear       : Ejecuta un diagnóstico activo de inventario, servicios y base de datos.
"""

import hashlib
import html
import json
import os
import re
import threading
import time
from datetime import datetime, timezone, timedelta
from flask import Blueprint, jsonify, request

incidencias_bp = Blueprint("incidencias", __name__)

DIR_PROYECTO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DIR_DATA = os.path.join(DIR_PROYECTO, "data")
RUTA_FALLBACK_JSON = os.path.join(DIR_DATA, "incidencias_contacto.json")
RUTA_FALLAS_WEB_JSON = os.path.join(DIR_DATA, "fallas_web_sistema.json")
_candado_archivo = threading.Lock()
_candado_fallas = threading.Lock()

ZONA_PERU = timezone(timedelta(hours=-5))


def _ahora_peru_iso():
    return datetime.now(ZONA_PERU).isoformat()


def _obtener_cliente_supabase():
    """Retorna el cliente de Supabase reutilizando el singleton del proyecto si está disponible."""
    try:
        from backend.datos_supabase import obtener_cliente_supabase
        sb = obtener_cliente_supabase()
        if sb:
            return sb
    except Exception:
        pass
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
    texto = valor.replace("\x00", "").strip()
    texto = PATRON_SCRIPTS.sub("", texto)
    texto = texto[:max_longitud]
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
# ALMACENAMIENTO PERSISTENTE RESILIENTE DE INCIDENCIAS (SUPABASE + LOCAL)
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
# ENDPOINTS REST API: INCIDENCIAS DE CLIENTES
# ==============================================================================

@incidencias_bp.route("/api/contacto", methods=["POST"])
def registrar_contacto():
    """Recibe y almacena una nueva incidencia/contacto de un cliente."""
    try:
        datos = request.get_json(silent=True) or request.form or {}
        nombre_raw = datos.get("nombre") or datos.get("nombre_cliente") or ""
        correo_raw = datos.get("correo") or datos.get("email") or ""
        mensaje_raw = datos.get("mensaje") or ""

        validar_datos_contacto(nombre_raw.strip(), correo_raw.strip(), mensaje_raw.strip())

        nombre = sanitizar_cadena(nombre_raw, 120)
        correo = sanitizar_cadena(correo_raw, 120)
        mensaje = sanitizar_cadena(mensaje_raw, 2000)

        ahora_iso = _ahora_peru_iso()
        nuevo_registro = {
            "nombre_cliente": nombre,
            "correo": correo,
            "mensaje": mensaje,
            "estado": "en_espera",
            "fecha_registro": ahora_iso
        }

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

        if not guardado_en_supabase:
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

    items = _leer_incidencias_locales()
    if not items:
        items = [
            {
                "id": 1,
                "nombre_cliente": "Carlos Mendoza Soto",
                "correo": "cmendoza@distribuidoracentral.pe",
                "mensaje": "Presentamos demoras con el abastecimiento de Aceite Primor 1L en nuestra sede de Los Olivos. Solicitamos información sobre los lotes de reposición para esta semana.",
                "estado": "en_espera",
                "fecha_registro": _ahora_peru_iso()
            },
            {
                "id": 2,
                "nombre_cliente": "Valeria Ramos Benítez",
                "correo": "vramos.bodega@gmail.com",
                "mensaje": "Excelente atención con el pedido de Fideos Don Vittorio. Quisiéramos consultar si habilitarán promociones de compra por mayor para Harina Blanca Flor.",
                "estado": "leido",
                "fecha_registro": _ahora_peru_iso()
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
                id_val = int(incidencia_id) if str(incidencia_id).isdigit() else incidencia_id
                res = sb.table("incidencias_contacto").update({"estado": nuevo_estado}).eq("id", id_val).execute()
                if res.data:
                    actualizado_en_supabase = True
            except Exception as e:
                print("[INCIDENCIAS] Supabase update warning:", e)

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


# ==============================================================================
# SISTEMA EN TIEMPO REAL: DETECCIÓN DE FALLAS GENERALES EN LA WEB (SUPABASE)
# ==============================================================================

ESTADOS_FALLA_VALIDOS = ("sin_resolver", "en_proceso", "resuelto")
GRAVEDADES_VALIDAS = ("Crítica", "Alta", "Media", "Baja")


def _generar_codigo_unico(tipo_falla: str, titulo_error: str, codigo_explicito: str = "") -> str:
    if codigo_explicito and str(codigo_explicito).strip():
        limpio = re.sub(r"[^a-zA-Z0-9_\-:.]", "-", str(codigo_explicito).strip())
        return limpio[:170]
    semilla = f"{tipo_falla.strip().lower()}|{titulo_error.strip().lower()[:140]}"
    digest = hashlib.md5(semilla.encode("utf-8")).hexdigest()[:14]
    prefijo = re.sub(r"[^a-z0-9]+", "-", tipo_falla.strip().lower()).strip("-")[:24] or "falla"
    return f"{prefijo}-{digest}"


def _leer_fallas_locales():
    with _candado_fallas:
        if not os.path.exists(RUTA_FALLAS_WEB_JSON):
            return []
        try:
            with open(RUTA_FALLAS_WEB_JSON, "r", encoding="utf-8") as f:
                data = json.load(f)
                return data if isinstance(data, list) else []
        except Exception:
            return []


def _escribir_fallas_locales(lista):
    with _candado_fallas:
        os.makedirs(DIR_DATA, exist_ok=True)
        with open(RUTA_FALLAS_WEB_JSON, "w", encoding="utf-8") as f:
            json.dump(lista, f, ensure_ascii=False, indent=2)


def registrar_o_actualizar_falla(
    titulo_error: str,
    tipo_falla: str = "Error Web Consola",
    gravedad: str = "Media",
    detalle_tecnico: str = "",
    origen: str = "Sistema Web",
    codigo_unico: str = "",
    incrementar_ocurrencia: bool = True
):
    """
    Registra o actualiza una falla detectada en tiempo real tanto en Supabase
    (tabla fallas_web_sistema) como en el espejo local resiliente, preservando
    siempre el estado ('sin_resolver', 'en_proceso', 'resuelto') elegido por el usuario.
    """
    titulo = html.unescape(sanitizar_cadena(str(titulo_error or "Falla detectada en el sistema"), 300))
    detalle = html.unescape(sanitizar_cadena(str(detalle_tecnico or ""), 600))
    tipo = html.unescape(sanitizar_cadena(str(tipo_falla or "Error Web Consola"), 80))
    orig = html.unescape(sanitizar_cadena(str(origen or "Sistema Web"), 100))
    grav = gravedad if gravedad in GRAVEDADES_VALIDAS else "Media"
    codigo = _generar_codigo_unico(tipo, titulo, codigo_unico)
    ahora = _ahora_peru_iso()

    guardado_supabase = False
    registro_final = None

    # 1. Intentar sincronizar con Supabase (tabla fallas_web_sistema)
    sb = _obtener_cliente_supabase()
    if sb:
        try:
            existente = sb.table("fallas_web_sistema").select("*").eq("codigo_unico", codigo).execute()
            if existente.data and len(existente.data) > 0:
                fila = existente.data[0]
                nuevas_ocurrencias = int(fila.get("ocurrencias") or 1) + (1 if incrementar_ocurrencia else 0)
                payload_upd = {
                    "ultima_deteccion": ahora,
                    "ocurrencias": nuevas_ocurrencias
                }
                if detalle and not fila.get("detalle_tecnico"):
                    payload_upd["detalle_tecnico"] = detalle
                if incrementar_ocurrencia:
                    res_upd = sb.table("fallas_web_sistema").update(payload_upd).eq("id", fila["id"]).execute()
                    registro_final = res_upd.data[0] if res_upd.data else {**fila, **payload_upd}
                else:
                    registro_final = fila
                guardado_supabase = True
            else:
                nuevo_sb = {
                    "codigo_unico": codigo,
                    "titulo_error": titulo,
                    "detalle_tecnico": detalle,
                    "tipo_falla": tipo,
                    "gravedad": grav,
                    "origen": orig,
                    "estado": "sin_resolver",
                    "ocurrencias": 1,
                    "actualizado_por": "detector_automatico",
                    "fecha_deteccion": ahora,
                    "ultima_deteccion": ahora
                }
                res_ins = sb.table("fallas_web_sistema").insert(nuevo_sb).execute()
                if res_ins.data:
                    registro_final = res_ins.data[0]
                    guardado_supabase = True
        except Exception as e:
            # Si la tabla fallas_web_sistema aún no ha sido creada en Supabase, opera con el espejo local
            pass

    # 2. Sincronizar también en el almacenamiento local resiliente
    locales = _leer_fallas_locales()
    encontrado_local = None
    for item in locales:
        if item.get("codigo_unico") == codigo:
            encontrado_local = item
            break

    if encontrado_local:
        if incrementar_ocurrencia:
            encontrado_local["ocurrencias"] = int(encontrado_local.get("ocurrencias") or 1) + 1
            encontrado_local["ultima_deteccion"] = ahora
        if detalle:
            encontrado_local["detalle_tecnico"] = detalle
        if registro_final and registro_final.get("estado"):
            encontrado_local["estado"] = registro_final["estado"]
            encontrado_local["id"] = registro_final.get("id", encontrado_local.get("id"))
        elif not registro_final:
            registro_final = encontrado_local
    else:
        nuevo_id = (registro_final.get("id") if registro_final else None) or (int(time.time() * 1000) + len(locales))
        nuevo_item = {
            "id": nuevo_id,
            "codigo_unico": codigo,
            "titulo_error": titulo,
            "detalle_tecnico": detalle,
            "tipo_falla": tipo,
            "gravedad": grav,
            "origen": orig,
            "estado": (registro_final.get("estado") if registro_final else None) or "sin_resolver",
            "ocurrencias": (registro_final.get("ocurrencias") if registro_final else None) or 1,
            "actualizado_por": "detector_automatico",
            "fecha_deteccion": ahora,
            "ultima_deteccion": ahora
        }
        locales.insert(0, nuevo_item)
        if not registro_final:
            registro_final = nuevo_item

    _escribir_fallas_locales(locales)
    return registro_final, guardado_supabase


def ejecutar_escaneo_tiempo_real():
    """
    Analiza en tiempo real el estado real de la plataforma:
    1. Catálogo de productos e inventario (Falta de productos / Stock 0 y Escasez crítica de stock).
    2. Riesgo de quiebre de stock en tiendas según transacciones reales de Supabase (resumen_100k_supabase.json).
    3. Estado de servicios y configuración de plataforma (SMTP correo, latencia/RAM, incidencias críticas).
    Registra automáticamente los hallazgos sin sobrescribir el estado ('sin_resolver', 'en_proceso', 'resuelto')
    que el analista o administrador ya haya asignado.
    """
    hallazgos = []

    # A. Escanear catálogo de productos real (Falta de productos y Escasez de inventario)
    try:
        from backend.carrito_service import _estado as estado_tienda, _cargar_catalogo
        if estado_tienda.get("productos") is None:
            _cargar_catalogo()
        productos = estado_tienda.get("productos") or []

        agotados = [p for p in productos if int(p.get("stock", 0)) <= 0]
        escasez_critica = [p for p in productos if 0 < int(p.get("stock", 0)) <= 180]

        if agotados:
            ejemplos = ", ".join(f"{p.get('nombre')} ({p.get('sku')})" for p in agotados[:3])
            hallazgos.append({
                "codigo_unico": "inventario-falta-productos-stock-cero",
                "titulo_error": f"Falta de productos: {len(agotados)} SKU(s) agotados (0 unidades disponibles) en catálogo web",
                "detalle_tecnico": f"Productos sin stock en tienda en línea: {ejemplos}. Requiere reposición inmediata de lote.",
                "tipo_falla": "Falta de Productos",
                "gravedad": "Crítica",
                "origen": "Catálogo / Tienda en Línea"
            })

        if escasez_critica:
            # Ordenar por menor stock para mostrar los más críticos
            escasez_critica.sort(key=lambda x: int(x.get("stock", 0)))
            top_escasez = escasez_critica[:4]
            listado = ", ".join(
                f"{p.get('marca', '')} {p.get('nombre', '')} [{p.get('sku')}: {p.get('stock')} und.]"
                for p in top_escasez
            )
            hallazgos.append({
                "codigo_unico": "inventario-escasez-stock-minimo",
                "titulo_error": f"Escasez de inventario: {len(escasez_critica)} productos por debajo del stock de seguridad mayorista (<= 180 und.)",
                "detalle_tecnico": f"SKUs con menor inventario actual: {listado}",
                "tipo_falla": "Escasez de Inventario",
                "gravedad": "Alta",
                "origen": "Almacén / Control de Stock"
            })

        # Si la tabla productos en Supabase no está cargada y usa catálogo local
        if estado_tienda.get("fuente") == "catalogo_local":
            detalle_sb = estado_tienda.get("detalle_supabase") or "Respaldo local activo"
            hallazgos.append({
                "codigo_unico": "db-catalogo-respaldo-local",
                "titulo_error": "Sincronización de catálogo: operando con catálogo local de respaldo (7,000+ SKUs)",
                "detalle_tecnico": f"Estado tabla 'productos' en Supabase: {detalle_sb}.",
                "tipo_falla": "Alerta Base de Datos",
                "gravedad": "Baja",
                "origen": "Motor de Catálogo Supabase"
            })
    except Exception as e:
        hallazgos.append({
            "codigo_unico": "bug-escaneo-catalogo",
            "titulo_error": f"Excepción al verificar inventario de tienda: {str(e)[:120]}",
            "detalle_tecnico": "Origen: backend/carrito_service.py durante diagnóstico en tiempo real.",
            "tipo_falla": "Bug de Sistema",
            "gravedad": "Alta",
            "origen": "Backend Python"
        })

    # B. Escanear riesgo de quiebre de stock por sede/tienda en las 100k transacciones de Supabase
    try:
        ruta_resumen = os.path.join(DIR_DATA, "resumen_100k_supabase.json")
        if os.path.exists(ruta_resumen):
            with open(ruta_resumen, "r", encoding="utf-8") as f:
                resumen_100k = json.load(f)
            quiebres = (resumen_100k.get("estadisticas") or {}).get("quiebre_por_tienda") or []
            if isinstance(quiebres, list) and quiebres:
                quiebres_ord = sorted(quiebres, key=lambda x: float(x.get("prob_quiebre_real", 0)), reverse=True)
                peor = quiebres_ord[0]
                prob_real = float(peor.get("prob_quiebre_real", 0)) * 100
                if prob_real >= 10.0:
                    nombre_tienda = peor.get("tienda", "Sede Principal")
                    stock_med = float(peor.get("stock_medio", 0))
                    codigo_t = re.sub(r"[^a-z0-9]+", "-", nombre_tienda.lower()).strip("-")
                    hallazgos.append({
                        "codigo_unico": f"escasez-sede-{codigo_t}",
                        "titulo_error": f"Riesgo estadístico de quiebre de stock en {nombre_tienda} ({prob_real:.1f}% de probabilidad real)",
                        "detalle_tecnico": f"Calculado sobre 100,000 transacciones en Supabase · Stock medio en sede: {stock_med:.1f} und.",
                        "tipo_falla": "Escasez de Inventario",
                        "gravedad": "Media",
                        "origen": f"Analítica BI · {nombre_tienda}"
                    })
    except Exception:
        pass

    # C. Escanear reportes de clientes en espera que alertan sobre fallas técnicas o desabastecimiento
    try:
        sb = _obtener_cliente_supabase()
        incidencias_activas = []
        if sb:
            try:
                r_inc = sb.table("incidencias_contacto").select("*").eq("estado", "en_espera").execute()
                incidencias_activas = r_inc.data or []
            except Exception:
                incidencias_activas = _leer_incidencias_locales()
        else:
            incidencias_activas = _leer_incidencias_locales()

        for inc in incidencias_activas:
            if inc.get("estado") != "en_espera":
                continue
            msg_lower = str(inc.get("mensaje") or "").lower()
            autor = str(inc.get("nombre_cliente") or "Cliente")
            inc_id = inc.get("id")
            if any(k in msg_lower for k in ("ram", "dominio", "domino", "lento", "caido", "error", "bug", "502", "500", "404")):
                hallazgos.append({
                    "codigo_unico": f"incidencia-infraestructura-{inc_id}",
                    "titulo_error": f"Alerta de rendimiento / infraestructura reportada por usuario ({autor})",
                    "detalle_tecnico": f"Mensaje en bandeja #{inc_id}: \"{str(inc.get('mensaje'))[:140]}\" · Límite de memoria en instancia web Render (512 MB RAM).",
                    "tipo_falla": "Bug / Rendimiento Web",
                    "gravedad": "Alta",
                    "origen": "Reporte de Cliente / Servidor"
                })
            elif any(k in msg_lower for k in ("abastecimiento", "demora", "stock", "agotado", "falta", "escasez", "lote")):
                hallazgos.append({
                    "codigo_unico": f"incidencia-stock-cliente-{inc_id}",
                    "titulo_error": f"Alerta de falta de producto / demora de lote reportada por {autor}",
                    "detalle_tecnico": f"Incidencia #{inc_id}: \"{str(inc.get('mensaje'))[:140]}\"",
                    "tipo_falla": "Falta de Productos",
                    "gravedad": "Alta",
                    "origen": "Portal de Clientes"
                })
    except Exception:
        pass

    # D. Escanear estado del servicio de notificaciones SMTP y consola web
    try:
        from backend.correo_pedido import correo_configurado
        if not correo_configurado():
            hallazgos.append({
                "codigo_unico": "config-smtp-correo-pedidos",
                "titulo_error": "Servicio SMTP de correo para pedidos en modo simulación (variables SMTP no definidas)",
                "detalle_tecnico": "Los comprobantes de compra se muestran en pantalla, pero el envío por correo requiere configurar SMTP_USER y SMTP_PASS.",
                "tipo_falla": "Alerta de Configuración",
                "gravedad": "Baja",
                "origen": "Módulo Correo Pedidos"
            })
    except Exception:
        pass

    # Registrar/sincronizar todos los hallazgos sin incrementar ocurrencias artificialmente en cada GET
    for h in hallazgos:
        registrar_o_actualizar_falla(
            titulo_error=h["titulo_error"],
            tipo_falla=h["tipo_falla"],
            gravedad=h["gravedad"],
            detalle_tecnico=h["detalle_tecnico"],
            origen=h["origen"],
            codigo_unico=h["codigo_unico"],
            incrementar_ocurrencia=False
        )


def _obtener_todas_las_fallas():
    """Obtiene todas las fallas registradas desde Supabase (o respaldo local) ordenadas por relevancia."""
    ejecutar_escaneo_tiempo_real()

    sb = _obtener_cliente_supabase()
    if sb:
        try:
            res = sb.table("fallas_web_sistema").select("*").order("ultima_deteccion", desc=True).limit(100).execute()
            if res.data is not None:
                # Si la tabla en Supabase estaba recién creada y vacía, volcar las locales iniciales
                if len(res.data) == 0:
                    locales = _leer_fallas_locales()
                    for loc in locales:
                        try:
                            copia = {k: v for k, v in loc.items() if k != "id"}
                            sb.table("fallas_web_sistema").upsert(copia, on_conflict="codigo_unico").execute()
                        except Exception:
                            pass
                    res2 = sb.table("fallas_web_sistema").select("*").order("ultima_deteccion", desc=True).limit(100).execute()
                    if res2.data:
                        return res2.data, "Supabase PostgreSQL"
                else:
                    _escribir_fallas_locales(res.data)
                    return res.data, "Supabase PostgreSQL"
        except Exception as e:
            print("[FALLAS_WEB] Usando sincronización resiliente (ejecuta database/crear_tabla_fallas_web.sql para tabla dedicada):", e)

    locales = _leer_fallas_locales()
    return locales, "Supabase / Respaldo Activo"


@incidencias_bp.route("/api/fallas_web", methods=["GET"])
def listar_fallas_web():
    """Devuelve las fallas detectadas en tiempo real para la tabla inferior de Incidencias Cliente."""
    try:
        fallas, fuente = _obtener_todas_las_fallas()

        # Ordenar: primero sin_resolver, luego en_proceso, luego resuelto; y por gravedad
        orden_estado = {"sin_resolver": 0, "en_proceso": 1, "resuelto": 2}
        orden_gravedad = {"Crítica": 0, "Alta": 1, "Media": 2, "Baja": 3}
        fallas_ordenadas = sorted(
            fallas,
            key=lambda x: (
                orden_estado.get(x.get("estado", "sin_resolver"), 0),
                orden_gravedad.get(x.get("gravedad", "Media"), 2),
                str(x.get("ultima_deteccion") or "")
            )
        )

        resumen = {
            "total": len(fallas_ordenadas),
            "sin_resolver": sum(1 for f in fallas_ordenadas if f.get("estado") == "sin_resolver"),
            "en_proceso": sum(1 for f in fallas_ordenadas if f.get("estado") == "en_proceso"),
            "resuelto": sum(1 for f in fallas_ordenadas if f.get("estado") == "resuelto"),
        }

        return jsonify({
            "success": True,
            "fuente": fuente,
            "resumen": resumen,
            "fallas": fallas_ordenadas
        })
    except Exception as e:
        import traceback
        traceback.print_exc()
        return jsonify({"success": False, "message": str(e), "fallas": []}), 500


@incidencias_bp.route("/api/fallas_web/reportar", methods=["POST"])
def reportar_falla_web():
    """
    Endpoint invocado automáticamente en tiempo real cuando el navegador (web pública o dashboard)
    detecta un error de consola JS, fallo de red/API, recurso 404, bug o falta de stock en el carrito.
    """
    try:
        datos = request.get_json(silent=True) or {}
        titulo = (datos.get("titulo_error") or datos.get("mensaje") or "").strip()
        if not titulo:
            return jsonify({"success": False, "message": "Falta descripción del error."}), 400

        tipo_falla = (datos.get("tipo_falla") or "Error Web Consola").strip()
        gravedad = (datos.get("gravedad") or "Media").strip()
        detalle = (datos.get("detalle_tecnico") or "").strip()
        origen = (datos.get("origen") or "Navegador / Consola Web").strip()
        codigo = (datos.get("codigo_unico") or "").strip()

        registro, en_sb = registrar_o_actualizar_falla(
            titulo_error=titulo,
            tipo_falla=tipo_falla,
            gravedad=gravedad,
            detalle_tecnico=detalle,
            origen=origen,
            codigo_unico=codigo,
            incrementar_ocurrencia=True
        )

        return jsonify({
            "success": True,
            "falla": registro,
            "guardado_en": "supabase" if en_sb else "local_resiliente"
        })
    except Exception as e:
        return jsonify({"success": False, "message": str(e)}), 500


@incidencias_bp.route("/api/fallas_web/cambiar_estado", methods=["POST"])
def cambiar_estado_falla_web():
    """
    Actualiza interactivamente el estado de una falla ('sin_resolver', 'en_proceso', 'resuelto')
    y lo persiste en Supabase (tabla fallas_web_sistema) y en el respaldo local.
    """
    try:
        datos = request.get_json(silent=True) or {}
        falla_id = datos.get("id")
        codigo_unico = (datos.get("codigo_unico") or "").strip()
        nuevo_estado = (datos.get("estado") or "").strip()
        usuario = sanitizar_cadena(str(datos.get("usuario") or "analista"), 100)

        if nuevo_estado not in ESTADOS_FALLA_VALIDOS:
            return jsonify({
                "success": False,
                "message": "Estado no válido. Use 'sin_resolver', 'en_proceso' o 'resuelto'."
            }), 400

        if not falla_id and not codigo_unico:
            return jsonify({"success": False, "message": "Se requiere 'id' o 'codigo_unico' de la falla."}), 400

        ahora = _ahora_peru_iso()
        actualizado_sb = False
        sb = _obtener_cliente_supabase()

        if sb:
            try:
                upd_payload = {
                    "estado": nuevo_estado,
                    "actualizado_por": usuario,
                    "ultima_deteccion": ahora
                }
                if codigo_unico:
                    res = sb.table("fallas_web_sistema").update(upd_payload).eq("codigo_unico", codigo_unico).execute()
                    if res.data:
                        actualizado_sb = True
                if not actualizado_sb and falla_id is not None:
                    id_val = int(falla_id) if str(falla_id).isdigit() else falla_id
                    res = sb.table("fallas_web_sistema").update(upd_payload).eq("id", id_val).execute()
                    if res.data:
                        actualizado_sb = True
            except Exception as e:
                print("[FALLAS_WEB] Warning al actualizar estado en Supabase:", e)

        # Actualizar también en el almacenamiento local resiliente
        locales = _leer_fallas_locales()
        encontrado = False
        for it in locales:
            if (codigo_unico and it.get("codigo_unico") == codigo_unico) or (falla_id is not None and str(it.get("id")) == str(falla_id)):
                it["estado"] = nuevo_estado
                it["actualizado_por"] = usuario
                it["ultima_deteccion"] = ahora
                encontrado = True
                break
        if encontrado:
            _escribir_fallas_locales(locales)

        return jsonify({
            "success": True,
            "id": falla_id,
            "codigo_unico": codigo_unico,
            "nuevo_estado": nuevo_estado,
            "actualizado_por": usuario,
            "actualizado_en": "supabase" if actualizado_sb else "local"
        })
    except Exception as e:
        import traceback
        traceback.print_exc()
        return jsonify({"success": False, "message": str(e)}), 500


@incidencias_bp.route("/api/fallas_web/escanear", methods=["POST"])
def escanear_fallas_ahora():
    """Ejecuta un escaneo completo bajo demanda y devuelve la lista actualizada."""
    return listar_fallas_web()
