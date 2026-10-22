"""
Estadistica descriptiva y probabilidades (Integrante 3).

- GET /api/estadistica?meta=200&umbral_stock=100
  Devuelve media, mediana, varianza muestral, desviacion estandar y probabilidades.
"""
import math
import os

import pandas as pd
from flask import Blueprint, jsonify, request

estadistica = Blueprint('estadistica', __name__)

from datos_supabase import obtener_transacciones


def cargar_datos():
    # Carga los 100,000 registros originados en la base de datos Supabase
    df = obtener_transacciones()
    return df[['tienda', 'stock_actual', 'ventas_totales']]


def prob_mayor_que(valor, media, desviacion):
    """P(X > valor) con la distribucion normal (campana de Gauss)."""
    if desviacion == 0:
        return 1.0 if media > valor else 0.0
    z = (valor - media) / desviacion
    return 0.5 * math.erfc(z / math.sqrt(2))


def prob_menor_que(valor, media, desviacion):
    """P(X < valor) con la distribucion normal."""
    return 1 - prob_mayor_que(valor, media, desviacion)


def calcular_estadisticas(df, meta=200.0, umbral_stock=100):
    ventas = df['ventas_totales']
    media = float(ventas.mean())
    desviacion = float(ventas.std(ddof=1))      # desviacion muestral
    varianza = float(ventas.var(ddof=1))        # varianza muestral

    resultado = {
        'success': True,
        'n': int(len(ventas)),
        'media': round(media, 2),
        'mediana': round(float(ventas.median()), 2),
        'varianza': round(varianza, 2),
        'desviacion_estandar': round(desviacion, 2),
        'meta': meta,
        # Probabilidad con la normal vs. el porcentaje real de los datos
        'prob_venta_mayor_meta': round(prob_mayor_que(meta, media, desviacion), 4),
        'prob_venta_mayor_meta_real': round(float((ventas > meta).mean()), 4),
        'umbral_stock': umbral_stock,
        'quiebre_por_tienda': [],
    }

    # Probabilidad de quiebre de inventario: stock por debajo del umbral
    for tienda, g in df.groupby('tienda'):
        m = float(g['stock_actual'].mean())
        d = float(g['stock_actual'].std(ddof=1))
        resultado['quiebre_por_tienda'].append({
            'tienda': tienda,
            'stock_medio': round(m, 1),
            'prob_quiebre': round(prob_menor_que(umbral_stock, m, d), 4),
            'prob_quiebre_real': round(float((g['stock_actual'] < umbral_stock).mean()), 4),
        })
    return resultado


@estadistica.route('/api/estadistica', methods=['GET'])
def api_estadistica():
    try:
        ruta_json = os.path.join(os.path.dirname(os.path.abspath(__file__)), "resumen_100k_supabase.json")
        if os.path.exists(ruta_json):
            try:
                import json
                with open(ruta_json, "r", encoding="utf-8") as f:
                    d = json.load(f)
                    return jsonify(d["estadisticas"])
            except Exception:
                pass

        meta = float(request.args.get('meta', 200))
        umbral = float(request.args.get('umbral_stock', 100))
        return jsonify(calcular_estadisticas(cargar_datos(), meta, umbral))
    except Exception as e:
        return jsonify({'success': False, 'message': str(e)}), 500
