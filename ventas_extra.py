"""
Endpoints extra del dashboard de ventas (Subproyecto 2: Prediccion de ventas).

- GET  /api/resumen_productos : ventas reales agrupadas por producto, tienda y mes.
- POST /api/simular           : simulador "que pasaria si..." con la red neuronal.

Esta en un archivo aparte para no mezclarse con el codigo del login en server.py.
"""
import os
import threading

import pandas as pd
from flask import Blueprint, jsonify, request

ventas_extra = Blueprint('ventas_extra', __name__)

RUTA_CSV = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'transacciones_alicorp_100k.csv')

_resumen_cache = None
_modelo_cache = None
_candado = threading.Lock()


# ---------------------------------------------------------------------------
# 1) Resumen real por producto / tienda / mes (se calcula una sola vez)
# ---------------------------------------------------------------------------
def _grupo_a_lista(df, columna):
    g = df.groupby(columna).agg(
        ventas=('ventas_totales', 'sum'),
        unidades=('cantidad_vendida', 'sum'),
        precio_promedio=('precio_unitario', 'mean'),
        stock_promedio=('stock_actual', 'mean'),
        transacciones=(columna, 'size'),
    ).sort_values('ventas', ascending=False)
    total = float(g['ventas'].sum())
    lista = []
    for nombre, fila in g.iterrows():
        lista.append({
            'nombre': nombre,
            'ventas': round(float(fila['ventas']), 2),
            'unidades': int(fila['unidades']),
            'precio_promedio': round(float(fila['precio_promedio']), 2),
            'stock_promedio': round(float(fila['stock_promedio']), 1),
            'transacciones': int(fila['transacciones']),
            'participacion': round(float(fila['ventas']) / total * 100, 1),
        })
    return lista


def _calcular_resumen():
    columnas = ['fecha', 'producto', 'tienda', 'precio_unitario', 'stock_actual',
                'cantidad_vendida', 'ventas_totales']
    df = pd.read_csv(RUTA_CSV, usecols=columnas)
    df['mes'] = pd.to_datetime(df['fecha']).dt.strftime('%Y-%m')
    por_mes = df.groupby('mes')['ventas_totales'].sum()
    return {
        'success': True,
        'total_registros': int(len(df)),
        'ventas_totales': round(float(df['ventas_totales'].sum()), 2),
        'productos': _grupo_a_lista(df, 'producto'),
        'tiendas': _grupo_a_lista(df, 'tienda'),
        'meses': list(por_mes.index),
        'ventas_por_mes': [round(float(v), 2) for v in por_mes.values],
    }


@ventas_extra.route('/api/resumen_productos', methods=['GET'])
def resumen_productos():
    global _resumen_cache
    try:
        if _resumen_cache is None:
            _resumen_cache = _calcular_resumen()
        return jsonify(_resumen_cache)
    except Exception as e:
        import traceback
        traceback.print_exc()
        return jsonify({'success': False, 'message': str(e)}), 500


# ---------------------------------------------------------------------------
# 2) Simulador: misma red neuronal que /api/predecir_ventas (se entrena una vez)
# ---------------------------------------------------------------------------
COLUMNAS_MODELO = ['precio_unitario', 'stock_actual', 'temperatura_zona',
                   'mes', 'dia_semana', 'producto', 'tienda']


def _obtener_modelo():
    global _modelo_cache
    with _candado:
        if _modelo_cache is not None:
            return _modelo_cache

        from sklearn.compose import TransformedTargetRegressor
        from sklearn.model_selection import train_test_split
        from sklearn.neural_network import MLPRegressor
        from sklearn.pipeline import make_pipeline
        from sklearn.preprocessing import StandardScaler

        # Solo 15,000 filas para no saturar el servidor gratuito de Render
        df = pd.read_csv(RUTA_CSV, nrows=15000)
        fecha = pd.to_datetime(df['fecha'])
        df['mes'] = fecha.dt.month
        df['dia_semana'] = fecha.dt.dayofweek

        X = pd.get_dummies(df[COLUMNAS_MODELO]).astype(float)
        y = df['ventas_totales']
        X_train, X_test, y_train, y_test = train_test_split(X, y, test_size=0.2, random_state=42)

        modelo = TransformedTargetRegressor(
            regressor=make_pipeline(
                StandardScaler(),
                MLPRegressor(hidden_layer_sizes=(32, 16), max_iter=300, early_stopping=True, random_state=42)),
            transformer=StandardScaler())
        modelo.fit(X_train, y_train)

        _modelo_cache = {
            'modelo': modelo,
            'columnas': list(X.columns),
            'r2': float(round(modelo.score(X_test, y_test), 4)),
            'productos': sorted(df['producto'].unique()),
            'tiendas': sorted(df['tienda'].unique()),
        }
        return _modelo_cache


def _fila(m, producto, tienda, precio, stock, temperatura, mes, dia_semana):
    fila = dict.fromkeys(m['columnas'], 0.0)
    fila.update({
        'precio_unitario': precio, 'stock_actual': stock, 'temperatura_zona': temperatura,
        'mes': mes, 'dia_semana': dia_semana,
    })
    fila['producto_' + producto] = 1.0
    fila['tienda_' + tienda] = 1.0
    return fila


def _numero(datos, clave, minimo, maximo):
    valor = float(datos.get(clave))
    if not (minimo <= valor <= maximo):
        raise ValueError(f"{clave} debe estar entre {minimo} y {maximo}")
    return valor


@ventas_extra.route('/api/simular', methods=['POST'])
def simular():
    try:
        datos = request.get_json(silent=True) or {}
        m = _obtener_modelo()

        producto = datos.get('producto')
        tienda = datos.get('tienda')
        if producto not in m['productos'] or tienda not in m['tiendas']:
            return jsonify({'success': False, 'message': 'Producto o tienda no valido.'}), 400

        precio = _numero(datos, 'precio_unitario', 1, 50)
        stock = _numero(datos, 'stock_actual', 0, 1000)
        temperatura = _numero(datos, 'temperatura_zona', 10, 40)
        mes = _numero(datos, 'mes', 1, 12)
        dia = _numero(datos, 'dia_semana', 0, 6)

        # Una fila por producto (misma tienda, precio, etc.) para comparar
        filas = [_fila(m, p, tienda, precio, stock, temperatura, mes, dia) for p in m['productos']]
        pred = m['modelo'].predict(pd.DataFrame(filas, columns=m['columnas']))
        por_producto = [{'producto': p, 'prediccion': round(max(float(v), 0.0), 2)}
                        for p, v in zip(m['productos'], pred)]
        elegido = next(x['prediccion'] for x in por_producto if x['producto'] == producto)

        return jsonify({
            'success': True,
            'prediccion': elegido,
            'unidades_estimadas': round(elegido / precio, 1),
            'por_producto': por_producto,
            'r2_modelo': m['r2'],
        })
    except (TypeError, ValueError) as e:
        return jsonify({'success': False, 'message': f'Datos invalidos: {e}'}), 400
    except Exception as e:
        import traceback
        traceback.print_exc()
        return jsonify({'success': False, 'message': str(e)}), 500
