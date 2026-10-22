"""
entrenar_modelo_keras.py
Subproyecto 2 / Integrante 4: Deep Learning con TensorFlow y Keras.

Entrena una Red Neuronal Profunda (Sequential) usando los 100,000 registros
almacenados en Supabase (PostgreSQL).
Guarda:
  1. modelo_ventas_keras.keras  (Modelo nativo Keras 3)
  2. metadatos_keras.json       (Escaladores, columnas y metricas)
  3. pesos_keras.npz           (Pesos exportados para inferencia ultraligera en Render)
"""

import os
import sys
import json
import time
import numpy as np
import pandas as pd
from dotenv import load_dotenv

load_dotenv()

# Configurar UTF-8 en terminal de Windows
if sys.platform == "win32":
    try:
        sys.stdout.reconfigure(encoding="utf-8")
    except Exception:
        pass

# Desactivar logs ruidosos de TensorFlow
os.environ["TF_CPP_MIN_LOG_LEVEL"] = "2"
os.environ["TF_ENABLE_ONEDNN_OPTS"] = "0"

import keras
from keras import layers, regularizers
from sklearn.model_selection import train_test_split
from sklearn.metrics import mean_squared_error, mean_absolute_error, r2_score
from sklearn.preprocessing import StandardScaler


def cargar_datos_supabase():
    """Carga los 100,000 registros desde Supabase (o cache local si ya fue descargado)."""
    directorio_proyecto = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    directorio_datos = os.path.join(directorio_proyecto, "data")
    archivo_cache = os.path.join(directorio_datos, "transacciones_supabase_cache.parquet")
    if os.path.exists(archivo_cache):
        print(f"[*] Cargando 100,000 registros desde cache de Supabase: {archivo_cache}...")
        return pd.read_parquet(archivo_cache)

    print("[*] Conectando a Supabase para descargar los 100,000 registros...")
    from supabase import create_client
    from concurrent.futures import ThreadPoolExecutor

    sb_url = os.environ.get("SUPABASE_URL")
    sb_key = os.environ.get("SUPABASE_KEY")
    if not sb_url or not sb_key:
        raise ValueError("Faltan SUPABASE_URL o SUPABASE_KEY en .env")

    sb = create_client(sb_url, sb_key)

    def fetch_chunk(start):
        res = (
            sb.table("transacciones_alicorp")
            .select("fecha,producto,tienda,precio_unitario,stock_actual,temperatura_zona,cantidad_vendida,ventas_totales")
            .range(start, start + 999)
            .execute()
        )
        return res.data

    chunks = list(range(0, 100000, 1000))
    with ThreadPoolExecutor(max_workers=10) as ex:
        results = list(ex.map(fetch_chunk, chunks))

    all_rows = [row for chunk in results for row in chunk]
    df = pd.DataFrame(all_rows)
    os.makedirs(directorio_datos, exist_ok=True)
    df.to_parquet(archivo_cache, index=False)
    print(f"[OK] {len(df):,} registros descargados de Supabase y guardados en cache.")
    return df


def preparar_datos(df):
    """Realiza ingenieria de caracteristicas y division train/test."""
    print("[*] Preprocesando caracteristicas para la Red Neuronal Keras...")
    df = df.copy()

    # Extraer variables temporales
    f = pd.to_datetime(df["fecha"])
    df["mes"] = f.dt.month
    df["dia_semana"] = f.dt.dayofweek

    columnas_features = ["precio_unitario", "stock_actual", "temperatura_zona", "mes", "dia_semana", "producto", "tienda"]
    X_raw = df[columnas_features]
    y = df["ventas_totales"].values.astype(np.float32)

    # One-hot encoding de variables categoricas (producto y tienda)
    X = pd.get_dummies(X_raw, columns=["producto", "tienda"], dtype=float)
    feature_names = list(X.columns)

    # Division Train (80,000) y Test (20,000)
    X_train, X_test, y_train, y_test = train_test_split(X, y, test_size=0.2, random_state=42)

    # Escalado de caracteristicas
    scaler_X = StandardScaler()
    X_train_scaled = scaler_X.fit_transform(X_train).astype(np.float32)
    X_test_scaled = scaler_X.transform(X_test).astype(np.float32)

    # Escalado de target para acelerar convergencia
    y_mean = float(y_train.mean())
    y_std = float(y_train.std())
    y_train_scaled = ((y_train - y_mean) / y_std).astype(np.float32)

    return (
        X_train_scaled,
        X_test_scaled,
        y_train_scaled,
        y_test,
        y_mean,
        y_std,
        feature_names,
        scaler_X,
        sorted(df["producto"].unique().tolist()),
        sorted(df["tienda"].unique().tolist()),
    )


def construir_modelo_keras(input_dim):
    """Construye una Red Neuronal Profunda con la API Sequential de Keras."""
    model = keras.Sequential([
        layers.Input(shape=(input_dim,)),
        layers.Dense(64, activation="relu", kernel_regularizer=regularizers.l2(1e-4)),
        layers.BatchNormalization(),
        layers.Dropout(0.1),
        layers.Dense(32, activation="relu", kernel_regularizer=regularizers.l2(1e-4)),
        layers.Dense(16, activation="relu"),
        layers.Dense(1, activation="linear")
    ], name="Red_Neuronal_Alicorp_Ventas")

    model.compile(
        optimizer=keras.optimizers.Adam(learning_rate=0.002),
        loss="mse",
        metrics=["mae"]
    )
    return model


def entrenar():
    t_inicio = time.time()
    print("=" * 70)
    print("🧠 ENTRENAMIENTO DE RED NEURONAL PROFUNDA (TENSORFLOW & KERAS)")
    print("=" * 70)

    df = cargar_datos_supabase()
    total_registros = len(df)

    (
        X_train,
        X_test,
        y_train_scaled,
        y_test,
        y_mean,
        y_std,
        feature_names,
        scaler_X,
        lista_productos,
        lista_tiendas,
    ) = preparar_datos(df)

    print(f"[*] Registros de entrenamiento: {len(X_train):,}")
    print(f"[*] Registros de prueba:        {len(X_test):,}")
    print(f"[*] Dimension de entrada:       {X_train.shape[1]} variables")

    print("\n[*] Construyendo arquitectura Keras Sequential...")
    model = construir_modelo_keras(X_train.shape[1])
    model.summary()

    callbacks = [
        keras.callbacks.EarlyStopping(monitor="val_loss", patience=5, restore_best_weights=True),
        keras.callbacks.ReduceLROnPlateau(monitor="val_loss", factor=0.5, patience=2, min_lr=1e-5),
    ]

    print("\n[*] Entrenando la Red Neuronal durante hasta 25 epocas con EarlyStopping...")
    history = model.fit(
        X_train,
        y_train_scaled,
        validation_split=0.15,
        epochs=25,
        batch_size=128,
        callbacks=callbacks,
        verbose=1,
    )

    print("\n[*] Evaluando predicciones sobre el conjunto de prueba (20,000 registros)...")
    pred_scaled = model.predict(X_test, batch_size=256).flatten()
    pred_real = pred_scaled * y_std + y_mean
    pred_real = np.maximum(pred_real, 0.0)  # Ventas no pueden ser negativas

    r2 = float(r2_score(y_test, pred_real))
    mse = float(mean_squared_error(y_test, pred_real))
    mae = float(mean_absolute_error(y_test, pred_real))
    rmse = float(np.sqrt(mse))

    print("\n" + "=" * 70)
    print("📊 RESULTADOS DEL MODELO TENSORFLOW & KERAS:")
    print(f"   • Coeficiente de Determinacion (R²): {r2:.4f} ({r2*100:.2f}%)")
    print(f"   • Error Absoluto Medio (MAE):         S/. {mae:.2f}")
    print(f"   • Raiz del Error Cuadratico (RMSE):   S/. {rmse:.2f}")
    print(f"   • Error Cuadratico Medio (MSE):       {mse:.2f}")
    print("=" * 70)

    # 1. Guardar modelo nativo Keras
    directorio_modelos = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "models")
    os.makedirs(directorio_modelos, exist_ok=True)
    ruta_keras = os.path.join(directorio_modelos, "modelo_ventas_keras.keras")
    model.save(ruta_keras)
    print(f"[OK] Modelo Keras guardado en: {ruta_keras}")

    # 2. Guardar pesos en formato npz para inferencia ultra-eficiente en Render
    pesos = {}
    for i, w in enumerate(model.get_weights()):
        pesos[f"w_{i}"] = w
    ruta_pesos = os.path.join(directorio_modelos, "pesos_keras.npz")
    np.savez_compressed(ruta_pesos, **pesos)
    print(f"[OK] Pesos comprimidos guardados en: {ruta_pesos} (soporte Render 512MB RAM)")

    # 3. Guardar metadatos en JSON
    metadatos = {
        "framework": "TensorFlow & Keras",
        "keras_version": keras.__version__,
        "total_registros_entrenamiento": total_registros,
        "n_train": len(X_train),
        "n_test": len(X_test),
        "input_dim": int(X_train.shape[1]),
        "feature_names": feature_names,
        "scaler_mean": [float(m) for m in scaler_X.mean_],
        "scaler_scale": [float(s) for s in scaler_X.scale_],
        "target_mean": y_mean,
        "target_std": y_std,
        "metricas": {
            "r2": round(r2, 4),
            "mae": round(mae, 2),
            "rmse": round(rmse, 2),
            "mse": round(mse, 2),
        },
        "productos": lista_productos,
        "tiendas": lista_tiendas,
        "fecha_entrenamiento": time.strftime("%Y-%m-%d %H:%M:%S"),
    }
    ruta_metadatos = os.path.join(directorio_modelos, "metadatos_keras.json")
    with open(ruta_metadatos, "w", encoding="utf-8") as f:
        json.dump(metadatos, f, indent=2, ensure_ascii=False)
    print(f"[OK] Metadatos y escaladores guardados en: {ruta_metadatos}")

    t_total = time.time() - t_inicio
    print(f"\n🎉 ¡Proceso completado con exito en {t_total:.1f} segundos!")


if __name__ == "__main__":
    entrenar()
