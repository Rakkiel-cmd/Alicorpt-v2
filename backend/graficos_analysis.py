"""Graficos analiticos del dataset de transacciones (Optimizado)."""

import io
import os
from functools import lru_cache

import numpy as np
import pandas as pd
from flask import Blueprint, Response, jsonify, request, send_file
from sklearn.linear_model import LinearRegression

graficos_analysis = Blueprint("graficos_analysis", __name__)

RUTA_CSV = os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
    "data",
    "transacciones_alicorp_100k.csv",
)

_COLUMNAS = [
    "precio_unitario",
    "stock_actual",
    "temperatura_zona",
    "cantidad_vendida",
    "ventas_totales",
]


@lru_cache(maxsize=4)
def _cargar_datos_cached(limite):
    """Carga interna cacheada para evitar leer el disco constantemente."""
    rutas = [
        RUTA_CSV,
        os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "transacciones_alicorp_100k.csv")
    ]
    
    for ruta in rutas:
        if os.path.exists(ruta):
            try:
                return pd.read_csv(ruta, usecols=_COLUMNAS, nrows=limite).dropna()
            except Exception:
                continue

    # Fallback determinístico
    np.random.seed(42)
    n = limite
    precios = np.random.choice([8.5, 4.2, 7.0, 15.0, 5.5], n)
    stocks = np.random.randint(50, 500, n)
    temps = np.random.uniform(18.0, 30.0, n)
    cantidades = np.random.poisson(lam=15, size=n) + (stocks * 0.01).astype(int)
    ventas = cantidades * precios
    return pd.DataFrame({
        "precio_unitario": precios,
        "stock_actual": stocks,
        "temperatura_zona": temps,
        "cantidad_vendida": cantidades,
        "ventas_totales": ventas
    })


def _cargar_datos():
    limite = request.args.get("limite", default=15000, type=int)
    limite = max(100, min(limite, 15000))
    return _cargar_datos_cached(limite)


def _figura_png(figura) -> Response:
    import matplotlib.pyplot as plt
    salida = io.BytesIO()
    try:
        figura.savefig(salida, format="png", dpi=120, bbox_inches="tight")
        salida.seek(0)
    finally:
        plt.close(figura)  # Garantiza liberar memoria RAM siempre
    return send_file(salida, mimetype="image/png")


def _matplotlib():
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    return plt


def crear_recta_regresion(datos):
    """Crea ventas totales frente a cantidad vendida con recta ajustada."""
    plt = _matplotlib()
    x = datos[["cantidad_vendida"]]
    y = datos["ventas_totales"]
    
    modelo = LinearRegression().fit(x, y)
    predicciones = modelo.predict(x)

    figura, eje = plt.subplots(figsize=(9, 5))
    eje.scatter(x["cantidad_vendida"], y, s=10, alpha=0.25, label="Transacciones")
    
    orden = np.argsort(x["cantidad_vendida"].to_numpy())
    eje.plot(
        x["cantidad_vendida"].to_numpy()[orden],
        predicciones[orden],
        color="#d62828",
        linewidth=2,
        label="Recta de regresion",
    )
    eje.set_title("Recta de regresion: cantidad vendida y ventas totales")
    eje.set_xlabel("Cantidad vendida")
    eje.set_ylabel("Ventas totales")
    eje.legend()
    eje.grid(alpha=0.2)
    figura.tight_layout()
    return figura


def crear_heatmap_correlacion(datos):
    """Crea un mapa de calor de correlacion."""
    plt = _matplotlib()
    correlaciones = datos.corr(numeric_only=True)

    figura, eje = plt.subplots(figsize=(8, 6))
    imagen = eje.imshow(correlaciones, cmap="RdYlBu_r", vmin=-1, vmax=1)
    etiquetas = list(correlaciones.columns)
    
    eje.set_xticks(range(len(etiquetas)), etiquetas, rotation=45, ha="right")
    eje.set_yticks(range(len(etiquetas)), etiquetas)
    
    for fila in range(len(etiquetas)):
        for columna in range(len(etiquetas)):
            eje.text(
                columna,
                fila,
                f"{correlaciones.iloc[fila, columna]:.2f}",
                ha="center",
                va="center",
                fontsize=8,
            )
            
    figura.colorbar(imagen, ax=eje, label="Coeficiente de correlacion")
    eje.set_title("Mapa de calor de correlaciones")
    figura.tight_layout()
    return figura


def crear_campana_probabilidades(datos):
    """Crea la distribucion de ventas y una curva normal de referencia."""
    plt = _matplotlib()
    valores = datos["ventas_totales"].to_numpy(dtype=float)
    media = float(np.mean(valores))
    desviacion = float(np.std(valores, ddof=1))
    desviacion = desviacion if desviacion > 0 else 1.0
    
    eje_x = np.linspace(valores.min(), valores.max(), 300)
    densidad = np.exp(-0.5 * ((eje_x - media) / desviacion) ** 2) / (desviacion * np.sqrt(2 * np.pi))

    figura, eje = plt.subplots(figsize=(9, 5))
    eje.hist(
        valores,
        bins=30,
        density=True,
        alpha=0.55,
        color="#457b9d",
        label="Ventas observadas",
    )
    eje.plot(eje_x, densidad, color="#d62828", linewidth=2, label="Campana normal")
    eje.axvline(media, color="#1d3557", linestyle="--", label=f"Media: {media:.2f}")
    eje.set_title("Campana de probabilidades de ventas totales")
    eje.set_xlabel("Ventas totales")
    eje.set_ylabel("Densidad de probabilidad")
    eje.legend()
    eje.grid(alpha=0.2)
    figura.tight_layout()
    return figura


def _error_grafico(error):
    return jsonify({"success": False, "message": f"Error generando grafico: {error}"}), 500


@graficos_analysis.get("/api/graficos/regresion")
def grafico_regresion():
    try:
        return _figura_png(crear_recta_regresion(_cargar_datos()))
    except Exception as error:
        return _error_grafico(error)


@graficos_analysis.get("/api/graficos/correlacion")
def grafico_correlacion():
    try:
        return _figura_png(crear_heatmap_correlacion(_cargar_datos()))
    except Exception as error:
        return _error_grafico(error)


@graficos_analysis.get("/api/graficos/probabilidades")
def grafico_probabilidades():
    try:
        return _figura_png(crear_campana_probabilidades(_cargar_datos()))
    except Exception as error:
        return _error_grafico(error)
