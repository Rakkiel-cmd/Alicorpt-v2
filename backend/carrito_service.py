"""
backend/carrito_service.py
Tienda en línea y carrito de compras de Alicorp Intelligence.

Endpoints:
- GET  /api/tienda/estado       : de dónde vienen los datos y si el buscador ya está listo.
- GET  /api/tienda/categorias   : categorías con cantidad de productos.
- GET  /api/tienda/productos    : lista con filtros (categoría, marca, orden, página).
- GET  /api/tienda/buscar?q=    : búsqueda inteligente (TF-IDF + red neuronal).
- GET  /api/tienda/destacados   : productos para la portada.
- POST /api/tienda/comprar      : guarda la compra (tablas compras y detalle_compra).
- GET  /api/tienda/compras      : historial de compras (para el dashboard).

Los productos se leen de la tabla "productos" de Supabase (consultas SQL).
Si la tabla aún está vacía o no hay conexión, se usa el mismo catálogo generado por
backend/catalogo_productos.py, así la web nunca se queda sin productos.
"""
import json
import os
import re
import threading
from datetime import datetime, timezone

from flask import Blueprint, jsonify, request

from backend.catalogo_productos import generar_catalogo
from backend.incidencias import PATRON_CORREO, PATRON_SQL_INJECTION, sanitizar_cadena

carrito_bp = Blueprint("carrito", __name__)

DIR_PROYECTO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DIR_DATA = os.path.join(DIR_PROYECTO, "data")
RUTA_COMPRAS_JSON = os.path.join(DIR_DATA, "compras_tienda.json")
_candado_archivo = threading.Lock()

MAX_UNIDADES_POR_LINEA = 100000     # compras grandes permitidas (mayoristas)
MAX_LINEAS = 200

_estado = {"productos": None, "fuente": None, "buscador": None, "cargando": False}
_candado_carga = threading.Lock()


# ==============================================================================
# CONEXIÓN A SUPABASE
# ==============================================================================
def _cliente_supabase():
    url = os.environ.get("SUPABASE_URL")
    key = os.environ.get("SUPABASE_KEY")
    if not url or not key:
        return None
    try:
        from supabase import create_client
        return create_client(url, key)
    except Exception as e:
        print("[TIENDA] No se pudo crear el cliente de Supabase:", e)
        return None


def _leer_productos_supabase():
    """Lee TODOS los productos de la tabla (de 1000 en 1000). Devuelve [] si no hay datos."""
    cliente = _cliente_supabase()
    if cliente is None:
        return []
    productos, inicio = [], 0
    try:
        while True:
            lote = (cliente.table("productos").select("*").order("sku").range(inicio, inicio + 999).execute().data) or []
            productos.extend(lote)
            if len(lote) < 1000:
                break
            inicio += 1000
    except Exception as e:
        print("[TIENDA] No se pudo leer la tabla productos:", e)
        return []
    for p in productos:
        p["precio"] = float(p["precio"])
        p["stock"] = int(p["stock"])
        p["etiquetas"] = p.get("etiquetas") or ""
        p["descripcion"] = p.get("descripcion") or ""
        p["imagen"] = p.get("imagen") or "🛒"
        p["foto"] = p.get("foto") or ""
    return productos


# ==============================================================================
# CATÁLOGO EN MEMORIA + BUSCADOR
# ==============================================================================
def _entrenar_buscador(buscador):
    try:
        buscador.entrenar()
        print("[TIENDA] Red neuronal del buscador entrenada.")
    except Exception as e:
        print("[TIENDA] No se pudo entrenar la red neuronal:", e)


def _cargar_catalogo():
    """Carga el catálogo una sola vez (Supabase primero, catálogo generado como respaldo)."""
    with _candado_carga:
        if _estado["productos"] is not None:
            return
        productos = _leer_productos_supabase()
        fuente = "supabase"
        if not productos:
            productos = generar_catalogo()
            fuente = "catalogo_local"
        _estado["productos"] = productos
        _estado["fuente"] = fuente
        try:
            from backend.buscador_inteligente import BuscadorInteligente
            buscador = BuscadorInteligente(productos)
            _estado["buscador"] = buscador
            # La red neuronal se entrena en segundo plano; mientras tanto el buscador ya funciona con TF-IDF
            threading.Thread(target=_entrenar_buscador, args=(buscador,), daemon=True).start()
        except Exception as e:
            print("[TIENDA] No se pudo crear el buscador inteligente:", e)
            _estado["buscador"] = None


def _productos():
    if _estado["productos"] is None:
        _cargar_catalogo()
    return _estado["productos"]


def _publico(p):
    """Campos que se envían a la web."""
    return {
        "sku": p["sku"], "nombre": p["nombre"], "marca": p["marca"], "categoria": p["categoria"],
        "subcategoria": p["subcategoria"], "presentacion": p["presentacion"], "precio": p["precio"],
        "stock": p["stock"], "imagen": p["imagen"], "foto": p.get("foto", ""), "descripcion": p.get("descripcion", ""),
    }


def _entero(valor, defecto, minimo, maximo):
    try:
        return max(minimo, min(maximo, int(valor)))
    except (TypeError, ValueError):
        return defecto


# ==============================================================================
# CONSULTAS
# ==============================================================================
@carrito_bp.route("/api/tienda/estado", methods=["GET"])
def tienda_estado():
    productos = _productos()
    buscador = _estado["buscador"]
    return jsonify({
        "ok": True,
        "fuente": _estado["fuente"],
        "total_productos": len(productos),
        "buscador_listo": bool(buscador and buscador.entrenada),
        "motor": "Red neuronal MLP + TF-IDF" if buscador else "Búsqueda simple",
    })


@carrito_bp.route("/api/tienda/categorias", methods=["GET"])
def tienda_categorias():
    cuenta = {}
    for p in _productos():
        cuenta[p["categoria"]] = cuenta.get(p["categoria"], 0) + 1
    emoji = {}
    for p in _productos():
        emoji.setdefault(p["categoria"], p["imagen"])
    categorias = [{"categoria": c, "emoji": emoji.get(c, "🛒"), "cantidad": n} for c, n in cuenta.items()]
    categorias.sort(key=lambda x: x["categoria"])
    return jsonify({"ok": True, "categorias": categorias})


@carrito_bp.route("/api/tienda/productos", methods=["GET"])
def tienda_productos():
    productos = _productos()
    categoria = request.args.get("categoria", "").strip()
    marca = request.args.get("marca", "").strip()
    orden = request.args.get("orden", "").strip()
    pagina = _entero(request.args.get("pagina"), 1, 1, 100000)
    por_pagina = _entero(request.args.get("por_pagina"), 24, 1, 60)

    lista = productos
    if categoria:
        lista = [p for p in lista if p["categoria"] == categoria]
    if marca:
        lista = [p for p in lista if p["marca"] == marca]
    if orden == "precio_asc":
        lista = sorted(lista, key=lambda p: p["precio"])
    elif orden == "precio_desc":
        lista = sorted(lista, key=lambda p: -p["precio"])
    elif orden == "nombre":
        lista = sorted(lista, key=lambda p: p["nombre"])
    total = len(lista)
    inicio = (pagina - 1) * por_pagina
    marcas = sorted({p["marca"] for p in (productos if not categoria else [q for q in productos if q["categoria"] == categoria])})
    return jsonify({
        "ok": True, "total": total, "pagina": pagina, "por_pagina": por_pagina,
        "paginas": max(1, -(-total // por_pagina)),
        "marcas": marcas,
        "items": [_publico(p) for p in lista[inicio:inicio + por_pagina]],
    })


@carrito_bp.route("/api/tienda/buscar", methods=["GET"])
def tienda_buscar():
    consulta = request.args.get("q", "").strip()[:120]
    limite = _entero(request.args.get("limite"), 24, 1, 60)
    if not consulta:
        return jsonify({"ok": True, "items": [], "total": 0, "motor": "vacio", "intencion": None})
    if PATRON_SQL_INJECTION.search(consulta):
        return jsonify({"ok": False, "error": "La búsqueda contiene caracteres no permitidos."}), 400
    productos = _productos()
    buscador = _estado["buscador"]
    if buscador is not None:
        try:
            r = buscador.buscar(consulta, limite)
            return jsonify({"ok": True, "consulta": consulta, "total": r["total"], "motor": r["motor"],
                            "intencion": r["intencion"], "items": [_publico(p) for p in r["items"]]})
        except Exception as e:
            print("[TIENDA] Falló el buscador inteligente, se usa búsqueda simple:", e)
    palabras = consulta.lower().split()
    items = [p for p in productos if all(w in f"{p['nombre']} {p['marca']} {p['categoria']}".lower() for w in palabras)]
    return jsonify({"ok": True, "consulta": consulta, "total": len(items[:limite]), "motor": "Búsqueda simple",
                    "intencion": None, "items": [_publico(p) for p in items[:limite]]})


@carrito_bp.route("/api/tienda/destacados", methods=["GET"])
def tienda_destacados():
    """Un producto por categoría (el primero en stock) para mostrar en la portada."""
    vistos, items = set(), []
    for p in _productos():
        if p["categoria"] not in vistos and p["stock"] > 0 and p.get("foto") and 2 <= p["precio"] <= 60:
            vistos.add(p["categoria"])
            items.append(_publico(p))
    return jsonify({"ok": True, "items": items[:12]})


# ==============================================================================
# COMPRAS
# ==============================================================================
def _guardar_compra_local(compra):
    with _candado_archivo:
        os.makedirs(DIR_DATA, exist_ok=True)
        compras = []
        if os.path.exists(RUTA_COMPRAS_JSON):
            try:
                with open(RUTA_COMPRAS_JSON, "r", encoding="utf-8") as f:
                    compras = json.load(f)
            except Exception:
                compras = []
        compras.insert(0, compra)
        with open(RUTA_COMPRAS_JSON, "w", encoding="utf-8") as f:
            json.dump(compras, f, ensure_ascii=False, indent=2)


def _leer_compras_locales():
    with _candado_archivo:
        if not os.path.exists(RUTA_COMPRAS_JSON):
            return []
        try:
            with open(RUTA_COMPRAS_JSON, "r", encoding="utf-8") as f:
                return json.load(f)
        except Exception:
            return []


@carrito_bp.route("/api/tienda/comprar", methods=["POST"])
def tienda_comprar():
    datos = request.get_json(silent=True) or {}
    nombre = sanitizar_cadena(str(datos.get("nombre", "")), 120)
    correo = str(datos.get("correo", "")).strip()[:120]
    telefono = sanitizar_cadena(str(datos.get("telefono", "")), 40)
    direccion = sanitizar_cadena(str(datos.get("direccion", "")), 300)
    lineas = datos.get("items")

    if len(nombre) < 2:
        return jsonify({"ok": False, "error": "Escribe tu nombre completo."}), 400
    if not PATRON_CORREO.match(correo) or PATRON_SQL_INJECTION.search(correo):
        return jsonify({"ok": False, "error": "Escribe un correo electrónico válido."}), 400
    if not isinstance(lineas, list) or not lineas:
        return jsonify({"ok": False, "error": "Tu carrito está vacío."}), 400
    if len(lineas) > MAX_LINEAS:
        return jsonify({"ok": False, "error": f"Máximo {MAX_LINEAS} productos distintos por compra."}), 400

    por_sku = {p["sku"]: p for p in _productos()}
    detalle, total = [], 0.0
    for linea in lineas:
        sku = str((linea or {}).get("sku", "")).strip()
        producto = por_sku.get(sku)
        if producto is None:
            return jsonify({"ok": False, "error": f"El producto {sku} ya no existe."}), 400
        try:
            cantidad = int(linea.get("cantidad"))
        except (TypeError, ValueError):
            return jsonify({"ok": False, "error": "Cantidad inválida."}), 400
        if cantidad < 1 or cantidad > MAX_UNIDADES_POR_LINEA:
            return jsonify({"ok": False, "error": f"La cantidad de {producto['nombre']} debe estar entre 1 y {MAX_UNIDADES_POR_LINEA}."}), 400
        if producto["stock"] < cantidad:
            return jsonify({"ok": False, "error": f"Stock insuficiente de {producto['nombre']} (disponible: {producto['stock']})."}), 400
        subtotal = round(producto["precio"] * cantidad, 2)      # el precio SIEMPRE se toma del servidor
        total += subtotal
        detalle.append({"sku": sku, "producto": producto["nombre"], "cantidad": cantidad,
                        "precio_unitario": producto["precio"], "subtotal": subtotal})
    total = round(total, 2)
    ahora = datetime.now(timezone.utc).isoformat()

    cliente = _cliente_supabase()
    if cliente is not None:
        try:
            fila = cliente.table("compras").insert({
                "cliente_nombre": nombre, "cliente_correo": correo, "cliente_telefono": telefono,
                "direccion_entrega": direccion, "total": total, "estado": "pendiente",
            }).execute().data[0]
            compra_id = fila["id"]
            cliente.table("detalle_compra").insert([dict(d, compra_id=compra_id) for d in detalle]).execute()
            # Descuenta el stock (mejor esfuerzo: si falla, la compra ya quedó guardada)
            for d in detalle:
                try:
                    por_sku[d["sku"]]["stock"] -= d["cantidad"]
                    cliente.table("productos").update({"stock": por_sku[d["sku"]]["stock"]}).eq("sku", d["sku"]).execute()
                except Exception:
                    pass
            return jsonify({"ok": True, "compra_id": compra_id, "total": total, "guardado_en": "supabase", "lineas": len(detalle)})
        except Exception as e:
            print("[TIENDA] No se pudo guardar en Supabase, se guarda localmente:", e)

    compra_id = f"L{int(datetime.now().timestamp())}"
    _guardar_compra_local({"id": compra_id, "fecha_compra": ahora, "cliente_nombre": nombre, "cliente_correo": correo,
                           "cliente_telefono": telefono, "direccion_entrega": direccion, "total": total,
                           "estado": "pendiente", "items": detalle})
    return jsonify({"ok": True, "compra_id": compra_id, "total": total, "guardado_en": "archivo_local", "lineas": len(detalle)})


@carrito_bp.route("/api/tienda/compras", methods=["GET"])
def tienda_compras():
    """Historial de compras con el detalle de cada una (lo consume el dashboard)."""
    limite = _entero(request.args.get("limite"), 50, 1, 500)
    cliente = _cliente_supabase()
    if cliente is not None:
        try:
            compras = cliente.table("compras").select("*").order("fecha_compra", desc=True).limit(limite).execute().data or []
            if compras:
                ids = [c["id"] for c in compras]
                detalle = cliente.table("detalle_compra").select("*").in_("compra_id", ids).execute().data or []
                for c in compras:
                    c["items"] = [d for d in detalle if d["compra_id"] == c["id"]]
            return jsonify({"ok": True, "fuente": "supabase", "total": len(compras), "compras": compras})
        except Exception as e:
            print("[TIENDA] No se pudo leer compras de Supabase:", e)
    locales = _leer_compras_locales()[:limite]
    return jsonify({"ok": True, "fuente": "archivo_local", "total": len(locales), "compras": locales})


# Precalienta el catálogo y entrena la red neuronal apenas arranca el servidor (así la primera búsqueda ya es inteligente)
if os.environ.get("TIENDA_PRECALENTAR", "1") == "1":
    threading.Thread(target=_cargar_catalogo, daemon=True).start()
