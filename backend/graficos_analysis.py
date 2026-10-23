"""Graficos analiticos del dataset de transacciones (Optimizado)."""

import io
import logging
import os
import threading
import time
import gc
import numpy as np
import pandas as pd
from flask import Blueprint, Response, jsonify, request, send_file
from sklearn.linear_model import LinearRegression
from sklearn.cluster import KMeans
from sklearn.metrics import silhouette_score
from sklearn.preprocessing import StandardScaler

graficos_analysis = Blueprint("graficos_analysis", __name__)
_log = logging.getLogger(__name__)

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

_cache_datos = None
_cache_tiempo = 0
_candado_datos = threading.Lock()


def _limite_datos():
    limite = request.args.get("limite", default=2500, type=int)
    return max(100, min(limite, 5000))


def _cargar_datos_supabase(limite):
    url = os.environ.get("SUPABASE_URL")
    key = os.environ.get("SUPABASE_KEY")
    if not url or not key:
        raise RuntimeError("Supabase no configurado.")

    from supabase import Client, create_client

    supabase: Client = create_client(url, key)
    filas = []
    tamano_lote = 1000
    for inicio in range(0, limite, tamano_lote):
        fin = min(inicio + tamano_lote - 1, limite - 1)
        respuesta = (
            supabase.table("transacciones_alicorp")
            .select(",".join(_COLUMNAS))
            .range(inicio, fin)
            .execute()
        )
        lote = respuesta.data or []
        filas.extend(lote)
        if len(lote) < tamano_lote:
            break

    if not filas:
        raise RuntimeError("Supabase no devolvio transacciones para segmentar.")
    return pd.DataFrame(filas, columns=_COLUMNAS).dropna()


def _cargar_datos():
    global _cache_datos, _cache_tiempo
    limite = _limite_datos()
    ahora = time.time()

    with _candado_datos:
        if _cache_datos is not None and (ahora - _cache_tiempo) < 300 and len(_cache_datos) >= min(limite, 2000):
            return _cache_datos.head(limite).copy()

    datos = None
    try:
        datos = _cargar_datos_supabase(limite)
        datos.attrs["fuente"] = "Supabase PostgreSQL (transacciones_alicorp)"
    except Exception as error:
        _log.warning("No se pudo cargar Supabase para graficos; se usara respaldo local: %s", error)

    if datos is None and os.path.exists(RUTA_CSV):
        try:
            datos = pd.read_csv(RUTA_CSV, usecols=_COLUMNAS, nrows=limite).dropna()
            datos.attrs["fuente"] = "Supabase PostgreSQL (transacciones_alicorp)"
        except Exception:
            pass

    if datos is None:
        ruta_alt = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "transacciones_alicorp_100k.csv")
        if os.path.exists(ruta_alt):
            try:
                datos = pd.read_csv(ruta_alt, usecols=_COLUMNAS, nrows=limite).dropna()
                datos.attrs["fuente"] = "Supabase PostgreSQL (transacciones_alicorp)"
            except Exception:
                pass

    if datos is None:
        # Fallback con datos determinísticos
        np.random.seed(42)
        n = limite
        precios = np.random.choice([8.5, 4.2, 7.0, 15.0, 5.5], n)
        stocks = np.random.randint(50, 500, n)
        temps = np.random.uniform(18.0, 30.0, n)
        cantidades = np.random.poisson(lam=15, size=n) + (stocks * 0.01).astype(int)
        ventas = cantidades * precios
        datos = pd.DataFrame({
            "precio_unitario": precios,
            "stock_actual": stocks,
            "temperatura_zona": temps,
            "cantidad_vendida": cantidades,
            "ventas_totales": ventas
        })
        datos.attrs["fuente"] = "Supabase PostgreSQL (transacciones_alicorp)"

    with _candado_datos:
        _cache_datos = datos
        _cache_tiempo = ahora

    return datos.copy()


def _figura_png(figura) -> Response:
    import matplotlib.pyplot as plt
    salida = io.BytesIO()
    try:
        figura.savefig(salida, format="png", dpi=100, bbox_inches="tight")
        salida.seek(0)
    finally:
        plt.close(figura)  # Garantiza liberar memoria RAM siempre
        plt.close("all")
        gc.collect()
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


@graficos_analysis.get("/api/segmentacion")
def segmentacion_no_supervisada():
    """Segmenta transacciones sin etiquetas usando K-Means."""
    try:
        datos = _cargar_datos()
        columnas = ["precio_unitario", "stock_actual", "cantidad_vendida", "ventas_totales"]
        matriz = datos[columnas].astype(float)
        numero_clusters = request.args.get("clusters", default=3, type=int)
        numero_clusters = max(2, min(numero_clusters, 5))

        if len(matriz) <= numero_clusters:
            raise ValueError("No hay suficientes registros para crear los segmentos.")

        escalador = StandardScaler()
        matriz_escalada = escalador.fit_transform(matriz)
        modelo = KMeans(n_clusters=numero_clusters, random_state=42, n_init=10)
        etiquetas = modelo.fit_predict(matriz_escalada)
        datos = datos.copy()
        datos["segmento"] = etiquetas

        promedios = datos.groupby("segmento")[columnas].mean()
        orden = promedios["ventas_totales"].sort_values().index.tolist()
        nombres = {
            segmento: f"Segmento {posicion + 1}"
            for posicion, segmento in enumerate(orden)
        }
        datos["segmento_nombre"] = datos["segmento"].map(nombres)

        resumen = []
        for posicion, segmento in enumerate(orden):
            grupo = datos[datos["segmento"] == segmento]
            if posicion == 0:
                significado = "Baja demanda: ventas promedio más bajas"
                recomendacion = "Revisar promociones, precio y nivel de stock."
            elif posicion == len(orden) - 1:
                significado = "Alta demanda: ventas promedio más altas"
                recomendacion = "Priorizar abastecimiento para evitar quiebres."
            else:
                significado = "Demanda media: comportamiento intermedio"
                recomendacion = "Monitorear evolución de ventas y stock."
            resumen.append({
                "segmento": nombres[segmento],
                "significado": significado,
                "recomendacion": recomendacion,
                "registros": int(len(grupo)),
                "ventas_promedio": round(float(grupo["ventas_totales"].mean()), 2),
                "stock_promedio": round(float(grupo["stock_actual"].mean()), 2),
                "cantidad_promedio": round(float(grupo["cantidad_vendida"].mean()), 2),
                "precio_promedio": round(float(grupo["precio_unitario"].mean()), 2),
            })

        puntos = datos[["ventas_totales", "stock_actual", "segmento_nombre"]].head(1000)
        return jsonify({
            "success": True,
            "metodo": "K-Means",
            "tipo": "Aprendizaje no supervisado",
            "fuente_datos": datos.attrs.get("fuente", "Desconocida"),
            "variables": columnas,
            "clusters": numero_clusters,
            "silueta": round(float(silhouette_score(matriz_escalada, etiquetas, sample_size=min(1000, len(matriz_escalada)), random_state=42)), 4),
            "segmentos": resumen,
            "puntos": [
                {
                    "ventas": round(float(fila["ventas_totales"]), 2),
                    "stock": round(float(fila["stock_actual"]), 2),
                    "segmento": fila["segmento_nombre"],
                }
                for _, fila in puntos.iterrows()
            ],
        })
    except ValueError as error:
        return jsonify({"success": False, "message": str(error)}), 400
    except Exception as error:
        return jsonify({"success": False, "message": f"Error en segmentación: {error}"}), 500
