"""
regresion_lineal.py - Regresion Lineal Simple y Multiple (Integrante 2).

- GET  /api/regresion_simple?variable=stock_actual
       Ajusta y = m*x + b relacionando una variable (stock_actual, precio_unitario
       o temperatura_zona) contra ventas_totales. Devuelve m, b, R2 y una muestra
       de puntos reales + la recta, listos para graficar en el dashboard.

- GET  /api/regresion_multiple
       Ajusta una regresion lineal multiple con precio_unitario, stock_actual,
       temperatura_zona, mes y tienda contra ventas_totales. Devuelve el
       intercepto, el coeficiente de cada variable y el R2, para contrastar
       su precision frente al modelo simple (tal como pide el profesor).

- POST /api/regresion_predecir
       Usa el modelo multiple ya entrenado para predecir ventas con valores
       nuevos. Este es el indicador de Regresion Lineal que debe mostrarse
       como tarjeta/KPI en la web (punto 6 de las exigencias del profesor).
"""
import os
import threading

import pandas as pd
from flask import Blueprint, jsonify, request
from sklearn.linear_model import LinearRegression
from sklearn.metrics import r2_score

regresion_lineal = Blueprint('regresion_lineal', __name__)

from datos_supabase import obtener_transacciones

VARIABLES_SIMPLES_PERMITIDAS = ('stock_actual', 'precio_unitario', 'temperatura_zona')
COLUMNAS_MULTIPLE_NUM = ['precio_unitario', 'stock_actual', 'temperatura_zona', 'mes']
COLUMNAS_MULTIPLE_CAT = ['tienda']

_cache_simple = {}
_cache_multiple = None
_candado = threading.Lock()


def cargar_datos():
    # Carga los 100,000 registros originados en la base de datos Supabase
    columnas = ['fecha', 'tienda', 'precio_unitario', 'stock_actual',
                'temperatura_zona', 'ventas_totales']
    df = obtener_transacciones()[columnas].copy()
    df['mes'] = pd.to_datetime(df['fecha']).dt.month
    return df


# ---------------------------------------------------------------------------
# 1) Regresion lineal simple: y = m*x + b
# ---------------------------------------------------------------------------
def _entrenar_simple(variable):
    df = cargar_datos()
    X = df[[variable]].values
    y = df['ventas_totales'].values

    modelo = LinearRegression()
    modelo.fit(X, y)

    m = float(modelo.coef_[0])
    b = float(modelo.intercept_)
    r2 = float(modelo.score(X, y))

    # Muestra de 300 puntos reales para el grafico de dispersion (no se manda
    # el dataset completo de 100k filas al navegador).
    muestra = df.sample(n=min(300, len(df)), random_state=42)
    puntos = [{'x': round(float(fx), 2), 'y': round(float(fy), 2)}
              for fx, fy in zip(muestra[variable], muestra['ventas_totales'])]

    # Dos puntos para dibujar la recta ajustada de punta a punta del eje X.
    x_min, x_max = float(df[variable].min()), float(df[variable].max())
    recta = [
        {'x': round(x_min, 2), 'y': round(m * x_min + b, 2)},
        {'x': round(x_max, 2), 'y': round(m * x_max + b, 2)},
    ]

    return {
        'success': True,
        'variable': variable,
        'formula': f'ventas_totales = {m:.4f} * {variable} + ({b:.4f})',
        'm': round(m, 4),
        'b': round(b, 4),
        'r2': round(r2, 4),
        'n': int(len(df)),
        'puntos': puntos,
        'recta': recta,
    }


@regresion_lineal.route('/api/regresion_simple', methods=['GET'])
def api_regresion_simple():
    try:
        variable = request.args.get('variable', 'stock_actual')
        if variable not in VARIABLES_SIMPLES_PERMITIDAS:
            return jsonify({
                'success': False,
                'message': f"variable debe ser una de: {', '.join(VARIABLES_SIMPLES_PERMITIDAS)}",
            }), 400

        with _candado:
            if variable not in _cache_simple:
                _cache_simple[variable] = _entrenar_simple(variable)
        return jsonify(_cache_simple[variable])
    except Exception as e:
        import traceback
        traceback.print_exc()
        return jsonify({'success': False, 'message': str(e)}), 500


# ---------------------------------------------------------------------------
# 2) Regresion lineal multiple: y = b0 + b1*x1 + b2*x2 + ...
# ---------------------------------------------------------------------------
def _entrenar_multiple():
    df = cargar_datos()
    X = pd.get_dummies(df[COLUMNAS_MULTIPLE_NUM + COLUMNAS_MULTIPLE_CAT],
                        columns=COLUMNAS_MULTIPLE_CAT).astype(float)
    y = df['ventas_totales'].values
    columnas = list(X.columns)

    modelo = LinearRegression()
    modelo.fit(X, y)
    pred = modelo.predict(X)
    r2 = float(r2_score(y, pred))

    coeficientes = [{'variable': col, 'coeficiente': round(float(coef), 4)}
                     for col, coef in zip(columnas, modelo.coef_)]
    coeficientes.sort(key=lambda d: abs(d['coeficiente']), reverse=True)

    resultado = {
        'success': True,
        'intercepto': round(float(modelo.intercept_), 4),
        'r2': round(r2, 4),
        'n': int(len(df)),
        'variables': columnas,
        'coeficientes': coeficientes,
    }
    return resultado, modelo, columnas


def _obtener_modelo_multiple():
    global _cache_multiple
    with _candado:
        if _cache_multiple is None:
            _cache_multiple = _entrenar_multiple()
        return _cache_multiple


@regresion_lineal.route('/api/regresion_multiple', methods=['GET'])
def api_regresion_multiple():
    try:
        resultado, _modelo, _columnas = _obtener_modelo_multiple()
        return jsonify(resultado)
    except Exception as e:
        import traceback
        traceback.print_exc()
        return jsonify({'success': False, 'message': str(e)}), 500


# ---------------------------------------------------------------------------
# 3) Prediccion con el modelo multiple: el indicador para la tarjeta del dashboard
# ---------------------------------------------------------------------------
def _numero(datos, clave, minimo, maximo, por_defecto):
    valor = datos.get(clave, por_defecto)
    valor = float(valor) if valor is not None else por_defecto
    if not (minimo <= valor <= maximo):
        raise ValueError(f"{clave} debe estar entre {minimo} y {maximo}")
    return valor


@regresion_lineal.route('/api/regresion_predecir', methods=['POST'])
def api_regresion_predecir():
    try:
        datos = request.get_json(silent=True) or {}
        resultado, modelo, columnas = _obtener_modelo_multiple()

        precio = _numero(datos, 'precio_unitario', 1, 100, 10)
        stock = _numero(datos, 'stock_actual', 0, 1000, 200)
        temperatura = _numero(datos, 'temperatura_zona', 0, 45, 22)
        mes = _numero(datos, 'mes', 1, 12, 1)
        tienda = datos.get('tienda')

        fila = dict.fromkeys(columnas, 0.0)
        fila['precio_unitario'] = precio
        fila['stock_actual'] = stock
        fila['temperatura_zona'] = temperatura
        fila['mes'] = mes
        columna_tienda = f'tienda_{tienda}'
        if tienda is not None and columna_tienda in fila:
            fila[columna_tienda] = 1.0

        prediccion = modelo.predict(pd.DataFrame([fila], columns=columnas))[0]
        return jsonify({
            'success': True,
            'prediccion': round(max(float(prediccion), 0.0), 2),
            'r2_modelo': resultado['r2'],
        })
    except (TypeError, ValueError) as e:
        return jsonify({'success': False, 'message': f'Datos invalidos: {e}'}), 400
    except Exception as e:
        import traceback
        traceback.print_exc()
        return jsonify({'success': False, 'message': str(e)}), 500
