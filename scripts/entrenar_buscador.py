"""
scripts/entrenar_buscador.py
Entrena la red neuronal del buscador UNA vez y guarda el resultado en data/buscador_modelo.npz.
El servidor (Render) solo lee ese archivo; así no gasta memoria ni tiempo entrenando.

Cuándo ejecutarlo: solo si cambias el catálogo (data/catalogo_real.json o los productos de Supabase).
Uso (desde la carpeta del proyecto, con el venv activado):
    python scripts/entrenar_buscador.py
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from backend.buscador_inteligente import BuscadorInteligente, RUTA_MODELO  # noqa: E402
from backend.catalogo_productos import generar_catalogo  # noqa: E402

if __name__ == "__main__":
    productos = generar_catalogo()
    if os.path.exists(RUTA_MODELO):
        os.remove(RUTA_MODELO)          # fuerza a entrenar de verdad
    buscador = BuscadorInteligente(productos)
    buscador.guardar_modelo()
    print(f"Listo: {len(productos)} productos. Modelo guardado en {RUTA_MODELO} ({os.path.getsize(RUTA_MODELO) / 1024:.0f} KB)")
