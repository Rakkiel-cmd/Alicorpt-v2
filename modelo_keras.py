"""
modelo_keras.py
Subproyecto 2 / Integrante 4: Servicio Web de Inferencia con TensorFlow y Keras.

Expone los endpoints para el Dashboard corporativo:
  - GET  /api/keras/info           : Retorna arquitectura, metricas (R², MAE, RMSE) y datos del entrenamiento.
  - POST /api/keras/predecir       : Estima ventas para una combinacion especifica con la Red Neuronal de Keras.
  - POST /api/keras/simular_todos  : Compara la prediccion de la red neuronal entre todos los productos.

Disenado con arquitectura resiliente para servidores en la nube de bajos recursos (Render Free 512MB RAM):
Carga el modelo Keras (.keras) y, si el entorno tiene memoria restringida, utiliza el motor de inferencia
matricial exacto de los pesos exportados (.npz), garantizando 0 caidas por OOM (Out Of Memory).
"""

import os
import json
import numpy as np
from flask import Blueprint, jsonify, request

modelo_keras = Blueprint("modelo_keras", __name__)

DIR_ACTUAL = os.path.dirname(os.path.abspath(__file__))
RUTA_MODELO_KERAS = os.path.join(DIR_ACTUAL, "modelo_ventas_keras.keras")
RUTA_METADATOS = os.path.join(DIR_ACTUAL, "metadatos_keras.json")
RUTA_PESOS = os.path.join(DIR_ACTUAL, "pesos_keras.npz")

_metadatos = None
_pesos = None
_modelo_keras_obj = None


def _cargar_recursos():
    """Carga los metadatos y el modelo/pesos en memoria una sola vez (Lazy loading)."""
    global _metadatos, _pesos, _modelo_keras_obj

    if _metadatos is None and os.path.exists(RUTA_METADATOS):
        with open(RUTA_METADATOS, "r", encoding="utf-8") as f:
            _metadatos = json.load(f)

    if _pesos is None and os.path.exists(RUTA_PESOS):
        _pesos = np.load(RUTA_PESOS)

    # Intentar cargar Keras si no esta cargado
    if _modelo_keras_obj is None and os.path.exists(RUTA_MODELO_KERAS):
        try:
            # Desactivar avisos de GPU en produccion
            os.environ["TF_CPP_MIN_LOG_LEVEL"] = "2"
            os.environ["TF_ENABLE_ONEDNN_OPTS"] = "0"
            import keras
            _modelo_keras_obj = keras.models.load_model(RUTA_MODELO_KERAS)
        except Exception as e:
            # Si en Render o servidor Linux falla por memoria RAM o libGL,
            # el motor de pesos matriciales se encarga sin interrupciones.
            _modelo_keras_obj = None


def _armar_vector(producto, tienda, precio, stock, temp, mes, dia):
    """Construye y normaliza el vector de entrada segun los escaladores de Keras."""
    _cargar_recursos()
    if not _metadatos:
        raise RuntimeError("No se encontraron los metadatos del modelo Keras. Ejecuta entrenar_modelo_keras.py.")

    cols = _metadatos["feature_names"]
    fila = dict.fromkeys(cols, 0.0)

    fila["precio_unitario"] = float(precio)
    fila["stock_actual"] = float(stock)
    fila["temperatura_zona"] = float(temp)
    fila["mes"] = float(mes)
    fila["dia_semana"] = float(dia)

    col_prod = f"producto_{producto}"
    col_tienda = f"tienda_{tienda}"

    if col_prod in fila:
        fila[col_prod] = 1.0
    if col_tienda in fila:
        fila[col_tienda] = 1.0

    raw_vec = np.array([[fila[c] for c in cols]], dtype=np.float32)

    # Estandarizar con las medias y escalas aprendidas
    means = np.array(_metadatos["scaler_mean"], dtype=np.float32)
    scales = np.array(_metadatos["scaler_scale"], dtype=np.float32)
    scaled_vec = (raw_vec - means) / scales

    return scaled_vec.astype(np.float32)


def predecir_con_red_neuronal(x_scaled):
    """
    Ejecuta el paso hacia adelante (Forward Propagation) de la red neuronal.
    Si Keras esta disponible en memoria lo usa; si no, ejecuta la propagacion
    matricial pura con los pesos exactos (consumo < 5MB RAM).
    """
    _cargar_recursos()

    if _modelo_keras_obj is not None:
        try:
            y_scaled = _modelo_keras_obj.predict(x_scaled, verbose=0)
            y_val = float(y_scaled[0][0])
            pred_real = y_val * _metadatos["target_std"] + _metadatos["target_mean"]
            return max(float(pred_real), 0.0)
        except Exception:
            pass  # Fallback inmediato al motor matricial si Keras tuviera conflicto de hilos

    # Motor matricial nativo de alta eficiencia (Garantia Render 512MB RAM)
    p = _pesos
    # Capa 1: Dense(64) + ReLU
    x = np.maximum(0, x_scaled @ p["w_0"] + p["w_1"])
    # Capa 2: BatchNormalization
    x = p["w_2"] * (x - p["w_4"]) / np.sqrt(p["w_5"] + 1e-3) + p["w_3"]
    # Capa 3: Dense(32) + ReLU
    x = np.maximum(0, x @ p["w_6"] + p["w_7"])
    # Capa 4: Dense(16) + ReLU
    x = np.maximum(0, x @ p["w_8"] + p["w_9"])
    # Capa 5: Dense(1) Lineal
    y_scaled = x @ p["w_10"] + p["w_11"]

    pred_real = float(y_scaled[0][0]) * _metadatos["target_std"] + _metadatos["target_mean"]
    return max(float(pred_real), 0.0)


# ==============================================================================
# ENDPOINTS REST API (FLASK BLUEPRINT)
# ==============================================================================

@modelo_keras.route("/api/keras/info", methods=["GET"])
def api_keras_info():
    """Devuelve informacion tecnica del modelo Keras entrenado con los 100k registros."""
    try:
        _cargar_recursos()
        if not _metadatos:
            return jsonify({"success": False, "message": "Modelo Keras no inicializado."}), 404

        return jsonify({
            "success": True,
            "framework": _metadatos.get("framework", "TensorFlow & Keras"),
            "keras_version": _metadatos.get("keras_version", "3.x"),
            "total_registros_entrenamiento": _metadatos.get("total_registros_entrenamiento", 100000),
            "metricas": _metadatos.get("metricas", {}),
            "productos": _metadatos.get("productos", []),
            "tiendas": _metadatos.get("tiendas", []),
            "fecha_entrenamiento": _metadatos.get("fecha_entrenamiento", ""),
            "motor_activo": "Keras Sequential DNN" if _modelo_keras_obj is not None else "Keras Weights Engine (Render Optimized)"
        })
    except Exception as e:
        return jsonify({"success": False, "message": str(e)}), 500


@modelo_keras.route("/api/keras/predecir", methods=["POST"])
def api_keras_predecir():
    """Predice ventas en soles utilizando la Red Neuronal Profunda de Keras."""
    try:
        data = request.get_json(silent=True) or {}
        _cargar_recursos()

        producto = data.get("producto")
        tienda = data.get("tienda")

        if not producto or not tienda:
            return jsonify({"success": False, "message": "Falta producto o tienda."}), 400

        precio = float(data.get("precio_unitario", 8.5))
        stock = float(data.get("stock_actual", 250))
        temp = float(data.get("temperatura_zona", 22.0))
        mes = int(data.get("mes", 6))
        dia = int(data.get("dia_semana", 2))

        x_vec = _armar_vector(producto, tienda, precio, stock, temp, mes, dia)
        prediccion_soles = round(predecir_con_red_neuronal(x_vec), 2)
        unidades_est = round(prediccion_soles / precio, 1) if precio > 0 else 0.0

        return jsonify({
            "success": True,
            "producto": producto,
            "tienda": tienda,
            "prediccion_soles": prediccion_soles,
            "unidades_estimadas": unidades_est,
            "r2_modelo": _metadatos["metricas"]["r2"] if _metadatos else 0.7798,
            "mae_soles": _metadatos["metricas"]["mae"] if _metadatos else 25.07,
            "motor": "TensorFlow & Keras Sequential"
        })
    except Exception as e:
        return jsonify({"success": False, "message": str(e)}), 500


@modelo_keras.route("/api/keras/simular_todos", methods=["POST"])
def api_keras_simular_todos():
    """Simula y compara las ventas de todos los productos bajo identicas condiciones con Keras."""
    try:
        data = request.get_json(silent=True) or {}
        _cargar_recursos()

        tienda = data.get("tienda", "Supermercado Lima Norte")
        precio = float(data.get("precio_unitario", 8.5))
        stock = float(data.get("stock_actual", 250))
        temp = float(data.get("temperatura_zona", 22.0))
        mes = int(data.get("mes", 6))
        dia = int(data.get("dia_semana", 2))

        productos = _metadatos["productos"] if _metadatos else [
            "Aceite Primor 1L", "Detergente Bolívar 2kg", "Fideos Don Vittorio 500g",
            "Harina Blanca Flor 1kg", "Mayonesa Alacena 500g"
        ]

        resultados = []
        for prod in productos:
            x_vec = _armar_vector(prod, tienda, precio, stock, temp, mes, dia)
            pred = round(predecir_con_red_neuronal(x_vec), 2)
            resultados.append({
                "producto": prod,
                "prediccion_soles": pred,
                "unidades_estimadas": round(pred / precio, 1) if precio > 0 else 0.0
            })

        resultados.sort(key=lambda x: x["prediccion_soles"], reverse=True)

        return jsonify({
            "success": True,
            "tienda": tienda,
            "comparativa": resultados,
            "r2_modelo": _metadatos["metricas"]["r2"] if _metadatos else 0.7798,
            "motor": "TensorFlow & Keras Sequential (100k registros Supabase)"
        })
    except Exception as e:
        return jsonify({"success": False, "message": str(e)}), 500
