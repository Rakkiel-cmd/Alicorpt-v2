"""Reporte mensual de ventas y proyeccion lineal por producto."""

import os

import pandas as pd
from flask import Blueprint, jsonify, request
from sklearn.linear_model import LinearRegression
from sklearn.metrics import r2_score

regresion_lineal = Blueprint("regresion_lineal", __name__)


def _cargar_mes(mes):
    mes_str = str(mes or "").strip()
    if not mes_str:
        mes_str = "2024-06"
    try:
        periodo = pd.Period(mes_str, freq="M")
        if pd.isna(periodo):
            raise ValueError("El mes debe tener el formato AAAA-MM.")
        inicio = periodo.start_time.date().isoformat()
        fin = (periodo + 1).start_time.date().isoformat()
    except (TypeError, ValueError, AttributeError):
        raise ValueError("El mes debe tener el formato AAAA-MM (por ejemplo, 2024-06).")

    try:
        from backend.datos_supabase import obtener_cliente_supabase
        supabase = obtener_cliente_supabase()
        if supabase:
            response = (
                supabase.table("transacciones_alicorp")
                .select("fecha, producto, precio_unitario, ventas_totales")
                .gte("fecha", inicio)
                .lt("fecha", fin)
                .execute()
            )
            if response.data:
                return pd.DataFrame(response.data)
    except Exception:
        pass

    return pd.DataFrame(columns=["fecha", "producto", "precio_unitario", "ventas_totales"])


def _proyectar_producto(grupo):
    grupo = grupo.copy()
    grupo["fecha"] = pd.to_datetime(grupo["fecha"])
    diario = grupo.groupby("fecha", as_index=False)["precio_unitario"].mean()
    precio_actual = float(grupo["precio_unitario"].mean())

    if len(diario) >= 2:
        x = (diario["fecha"] - diario["fecha"].min()).dt.days.to_numpy().reshape(-1, 1)
        y = diario["precio_unitario"].to_numpy()
        modelo = LinearRegression().fit(x, y)
        siguiente_dia = [[int(x.max()) + 1]]
        precio_futuro = float(modelo.predict(siguiente_dia)[0])
        return precio_actual, max(0.01, precio_futuro), float(modelo.coef_[0])

    return precio_actual, precio_actual, 0.0


@regresion_lineal.route("/api/reporte_mensual", methods=["GET"])
def reporte_mensual():
    mes = request.args.get("mes", "")
    try:
        datos = _cargar_mes(mes)
        if datos.empty:
            return jsonify({"success": True, "productos": [], "regresion": None})

        productos = []
        valores_reales = []
        valores_estimados = []
        for producto, grupo in datos.groupby("producto"):
            actual, futuro, pendiente = _proyectar_producto(grupo)
            productos.append({
                "producto": producto,
                "precio_actual": round(actual, 2),
                "precio_futuro": round(futuro, 2),
                "ventas_mes": round(float(grupo["ventas_totales"].sum()), 2),
            })
            valores_reales.append(actual)
            valores_estimados.append(futuro)

        regresion = None
        if len(valores_reales) >= 2:
            modelo = LinearRegression().fit(
                [[valor] for valor in valores_reales], valores_estimados
            )
            predichos = modelo.predict([[valor] for valor in valores_reales])
            regresion = {
                "m": round(float(modelo.coef_[0]), 4),
                "b": round(float(modelo.intercept_), 4),
                "r2": round(float(r2_score(valores_estimados, predichos)), 4),
            }

        return jsonify({"success": True, "productos": productos, "regresion": regresion})
    except (RuntimeError, ValueError) as error:
        return jsonify({"success": False, "message": str(error)}), 400
    except Exception as error:
        return jsonify({"success": False, "message": f"Error generando reporte: {error}"}), 500
