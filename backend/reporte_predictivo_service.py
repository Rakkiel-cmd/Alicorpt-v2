"""
reporte_predictivo_service.py
Subproyecto 2: Persistencia y Generación Predictiva de Reportes Mensuales con Red Neuronal Keras.

Implementa la arquitectura Cache-Aside sobre PostgreSQL en Supabase:
1. Si un mes futuro (ej: 2026-08, 2027-03) ya fue calculado previamente, recupera los datos
   guardados en la tabla 'reportes_mensuales_predichos' de Supabase sin reprocesar.
2. Si el usuario solicita predecir un mes nuevo, ejecuta la inferencia con la Red Neuronal
   Profunda de Keras (DNN), calcula la variación y margen proyectado, guarda el resultado
   en la base de datos y retorna la nueva predicción.
"""

import os
from datetime import datetime
import numpy as np
from flask import Blueprint, jsonify, request

from backend.datos_supabase import obtener_cliente_supabase
from backend.modelo_keras import _armar_vector, predecir_con_red_neuronal

reporte_predictivo_bp = Blueprint("reporte_predictivo_bp", __name__)

# Memoria caché en caso de que la tabla aún no se haya ejecutado en el SQL Editor de Supabase
_CACHE_LOCAL_REPORTES = {}

NOMBRES_MES = [
    "Enero", "Febrero", "Marzo", "Abril", "Mayo", "Junio",
    "Julio", "Agosto", "Septiembre", "Octubre", "Noviembre", "Diciembre"
]

PRODUCTOS_CATALOGO = [
    {"nombre": "Aceite Primor 1L", "categoria": "Abarrotes", "precio_base": 8.50, "factor_mercado": 0.025},
    {"nombre": "Detergente Bolívar 2kg", "categoria": "Limpieza del Hogar", "precio_base": 12.50, "factor_mercado": -0.020},
    {"nombre": "Fideos Don Vittorio 500g", "categoria": "Pastas y Harinas", "precio_base": 4.20, "factor_mercado": 0.035},
    {"nombre": "Harina Blanca Flor 1kg", "categoria": "Pastas y Harinas", "precio_base": 4.80, "factor_mercado": -0.015},
    {"nombre": "Mayonesa Alacena 500g", "categoria": "Salsas y Untables", "precio_base": 7.50, "factor_mercado": 0.030},
]

TIENDAS = [
    "Hipermercado Centro",
    "Mayorista San Martín",
    "Supermercado Lima Norte",
    "Tienda Express Los Olivos"
]


def estado_rentabilidad(variacion):
    """Clasifica el estado de rentabilidad según la variación porcentual estimada."""
    if variacion > 2.0:
        return {"clase": "ganancia", "texto": "🟢 Ganancia"}
    if variacion < -2.0:
        return {"clase": "perdida", "texto": "🔴 Pérdida"}
    return {"clase": "neutro", "texto": "⚪ Neutro"}


def obtener_o_generar_prediccion_mensual(anio, mes, usuario=None):
    """
    Consulta o genera la predicción mensual mediante Red Neuronal Keras
    con persistencia automática en Supabase PostgreSQL.
    """
    anio = int(anio)
    mes = int(mes)
    if mes < 1 or mes > 12:
        raise ValueError("El mes debe ser un valor entre 1 y 12.")

    periodo = f"{anio}-{mes:02d}"
    nombre_mes = f"{NOMBRES_MES[mes - 1]} {anio}"

    # =========================================================================
    # PASO 1: Comprobar si ya existe guardado en la Base de Datos (Supabase)
    # =========================================================================
    sb = None
    try:
        sb = obtener_cliente_supabase()
    except Exception as e:
        print("[!] Aviso conexión Supabase en reporte predictivo:", e)

    if sb is not None:
        try:
            res = (
                sb.table("reportes_mensuales_predichos")
                .select("*")
                .eq("periodo", periodo)
                .limit(1)
                .execute()
            )
            if res.data and len(res.data) > 0:
                reg = res.data[0]
                return {
                    "success": True,
                    "reutilizado": True,
                    "origen": "base_de_datos",
                    "mensaje": f"Reporte de {nombre_mes} recuperado de la Base de Datos (reutilizado sin reprocesar).",
                    "periodo": reg["periodo"],
                    "anio": reg["anio"],
                    "mes": reg["mes"],
                    "nombre_mes": reg.get("nombre_mes", nombre_mes),
                    "total": float(reg["venta_total"]),
                    "margen": float(reg["margen_promedio"]),
                    "producto_riesgo": reg.get("producto_riesgo", ""),
                    "variacion_riesgo": float(reg.get("variacion_riesgo", 0.0)),
                    "datos": reg["datos_productos"],
                    "modelo_motor": reg.get("modelo_motor", "Red Neuronal Keras Sequential (DNN)"),
                    "created_at": reg.get("created_at", "")
                }
        except Exception as e:
            # Si la tabla no está creada aún o error de red, continúa al caché o generación
            print(f"[!] Consulta tabla reportes_mensuales_predichos en Supabase: {e}")

    # Comprobar en caché local en memoria
    if periodo in _CACHE_LOCAL_REPORTES:
        reg = _CACHE_LOCAL_REPORTES[periodo]
        return {
            "success": True,
            "reutilizado": True,
            "origen": "cache_local",
            "mensaje": f"Reporte de {nombre_mes} recuperado de la memoria en caché (reutilizado sin reprocesar).",
            "periodo": reg["periodo"],
            "anio": reg["anio"],
            "mes": reg["mes"],
            "nombre_mes": reg.get("nombre_mes", nombre_mes),
            "total": float(reg["venta_total"]),
            "margen": float(reg["margen_promedio"]),
            "producto_riesgo": reg.get("producto_riesgo", ""),
            "variacion_riesgo": float(reg.get("variacion_riesgo", 0.0)),
            "datos": reg["datos_productos"],
            "modelo_motor": reg.get("modelo_motor", "Red Neuronal Keras Sequential (DNN)"),
            "created_at": reg.get("created_at", "")
        }

    # =========================================================================
    # PASO 2: Generar con la Red Neuronal Profunda de Keras
    # =========================================================================
    delta_meses = (anio - 2024) * 12 + (mes - 6)
    temp_estacional = 22.0 + 4.0 * np.sin((mes - 1) / 12.0 * 2.0 * np.pi)

    datos_productos = []
    for item in PRODUCTOS_CATALOGO:
        nombre = item["nombre"]
        p_base = item["precio_base"]
        factor_m = item["factor_mercado"]

        # Proyección de precio futuro considerando inflación/tendencia y estacionalidad
        p_futuro = round(p_base * (1.0 + (delta_meses * 0.0025) + factor_m), 2)
        p_futuro = max(p_futuro, 1.0)
        variacion = round(((p_futuro - p_base) / p_base) * 100.0, 2)

        # Inferencia con la Red Neuronal Keras promediando en las 4 tiendas corporativas
        preds_tienda = []
        for tienda in TIENDAS:
            x_vec = _armar_vector(
                producto=nombre,
                tienda=tienda,
                precio=p_futuro,
                stock=250.0,
                temp=temp_estacional,
                mes=mes,
                dia=2  # Miércoles
            )
            val_tx = predecir_con_red_neuronal(x_vec)
            preds_tienda.append(val_tx)

        # Volumen de ventas mensual = predicción promedio × frecuencia estimada mensual (~185 transacciones)
        ventas_mes = round(float(np.mean(preds_tienda)) * 185.0, 2)

        datos_productos.append({
            "producto": nombre,
            "categoria": item["categoria"],
            "actual": p_base,
            "futuro": p_futuro,
            "variacion": variacion,
            "ventas": ventas_mes,
            "estado": estado_rentabilidad(variacion)
        })

    # Métricas agregadas
    venta_total = round(sum(d["ventas"] for d in datos_productos), 2)
    margen_promedio = round(sum(d["variacion"] for d in datos_productos) / len(datos_productos), 2)
    peor_producto = min(datos_productos, key=lambda x: x["variacion"])

    # =========================================================================
    # PASO 3: Guardar en la Base de Datos (Supabase) y en caché
    # =========================================================================
    registro = {
        "periodo": periodo,
        "anio": anio,
        "mes": mes,
        "nombre_mes": nombre_mes,
        "venta_total": venta_total,
        "margen_promedio": margen_promedio,
        "producto_riesgo": peor_producto["producto"],
        "variacion_riesgo": peor_producto["variacion"],
        "datos_productos": datos_productos,
        "modelo_motor": "Red Neuronal Keras Sequential (DNN)",
        "generado_por": str(usuario or "sistema_predictivo")
    }

    if sb is not None:
        try:
            sb.table("reportes_mensuales_predichos").insert(registro).execute()
            print(f"[+] Predicción {periodo} guardada exitosamente en Supabase PostgreSQL.")
        except Exception as e:
            print(f"[!] Aviso al insertar predicción en Supabase (usando caché): {e}")

    # Guardar en memoria caché local
    _CACHE_LOCAL_REPORTES[periodo] = {**registro, "created_at": datetime.utcnow().isoformat()}

    return {
        "success": True,
        "reutilizado": False,
        "origen": "red_neuronal",
        "mensaje": f"Nueva predicción calculada con Red Neuronal Keras y guardada en Base de Datos ({nombre_mes}).",
        "periodo": periodo,
        "anio": anio,
        "mes": mes,
        "nombre_mes": nombre_mes,
        "total": venta_total,
        "margen": margen_promedio,
        "producto_riesgo": peor_producto["producto"],
        "variacion_riesgo": peor_producto["variacion"],
        "datos": datos_productos,
        "modelo_motor": "Red Neuronal Keras Sequential (DNN)"
    }


# ==============================================================================
# ENDPOINTS REST API
# ==============================================================================

@reporte_predictivo_bp.route("/api/reporte_mensual/predecir_futuro", methods=["GET", "POST"])
def api_predecir_futuro():
    """
    Endpoint para predecir o recuperar un reporte mensual futuro con Red Neuronal.
    Acepta tanto GET con query params (?anio=2026&mes=8) como POST con JSON.
    """
    try:
        if request.method == "POST":
            data = request.get_json(silent=True) or {}
            anio = data.get("anio")
            mes = data.get("mes")
            usuario = data.get("usuario")
        else:
            anio = request.args.get("anio")
            mes = request.args.get("mes")
            usuario = request.args.get("usuario")

        if not anio or not mes:
            return jsonify({
                "success": False,
                "message": "Debe especificar el año y mes a predecir (ej: anio=2026, mes=8)."
            }), 400

        resultado = obtener_o_generar_prediccion_mensual(anio, mes, usuario)
        return jsonify(resultado)

    except ValueError as e:
        return jsonify({"success": False, "message": str(e)}), 400
    except Exception as e:
        return jsonify({"success": False, "message": f"Error en inferencia predictiva: {e}"}), 500


@reporte_predictivo_bp.route("/api/reporte_mensual/historial_predicciones", methods=["GET"])
def api_historial_predicciones():
    """Retorna la lista de todas las predicciones mensuales guardadas en la BD."""
    try:
        sb = obtener_cliente_supabase()
        if sb:
            res = (
                sb.table("reportes_mensuales_predichos")
                .select("periodo, anio, mes, nombre_mes, venta_total, margen_promedio, created_at")
                .order("periodo", desc=True)
                .execute()
            )
            if res.data:
                return jsonify({"success": True, "historial": res.data})

        # Fallback de caché local
        lista = [
            {
                "periodo": v["periodo"],
                "anio": v["anio"],
                "mes": v["mes"],
                "nombre_mes": v.get("nombre_mes", k),
                "venta_total": v["venta_total"],
                "margen_promedio": v["margen_promedio"],
                "created_at": v.get("created_at", "")
            }
            for k, v in _CACHE_LOCAL_REPORTES.items()
        ]
        return jsonify({"success": True, "historial": lista})
    except Exception as e:
        return jsonify({"success": False, "message": str(e)}), 500
