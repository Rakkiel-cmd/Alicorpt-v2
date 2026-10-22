"""
Script de Migración de 100,000 registros a Supabase (PostgreSQL)
Proyecto: Alicorp Intelligence v2
"""
import os
import sys
import time
import math
import pandas as pd
from dotenv import load_dotenv

# Asegurar soporte UTF-8 en terminales Windows
if sys.platform == "win32":
    try:
        sys.stdout.reconfigure(encoding="utf-8")
    except Exception:
        pass

from supabase import create_client, Client

load_dotenv()

SUPABASE_URL = os.environ.get("SUPABASE_URL")
SUPABASE_KEY = os.environ.get("SUPABASE_KEY")
RUTA_PROYECTO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RUTA_CSV = os.path.join(RUTA_PROYECTO, "data", "transacciones_alicorp_100k.csv")
BATCH_SIZE = 2000  # Lote óptimo para Supabase REST API (entre 1000 y 2500)
MAX_RETRIES = 3

SQL_CREAR_TABLA = """
-- ============================================================
-- COPIA Y PEGA ESTE CODIGO EN EL SQL EDITOR DE SUPABASE:
-- (Supabase Dashboard > SQL Editor > New query > Run)
-- ============================================================

CREATE TABLE IF NOT EXISTS public.transacciones_alicorp (
    id_transaccion BIGINT PRIMARY KEY,
    fecha DATE NOT NULL,
    producto VARCHAR(150) NOT NULL,
    tienda VARCHAR(150) NOT NULL,
    precio_unitario NUMERIC(10,2) NOT NULL,
    stock_actual INT NOT NULL,
    temperatura_zona NUMERIC(6,2),
    cantidad_vendida INT NOT NULL,
    ventas_totales NUMERIC(12,2) NOT NULL
);

-- Politicas de seguridad para permitir lectura y escritura desde la aplicacion
ALTER TABLE public.transacciones_alicorp ENABLE ROW LEVEL SECURITY;

CREATE POLICY "Acceso total transacciones_alicorp"
ON public.transacciones_alicorp
FOR ALL
TO anon, authenticated, service_role
USING (true)
WITH CHECK (true);

-- Indices de alto rendimiento para acelerar las consultas del Dashboard
CREATE INDEX IF NOT EXISTS idx_transacciones_fecha ON public.transacciones_alicorp(fecha);
CREATE INDEX IF NOT EXISTS idx_transacciones_producto ON public.transacciones_alicorp(producto);
CREATE INDEX IF NOT EXISTS idx_transacciones_tienda ON public.transacciones_alicorp(tienda);
"""


def verificar_credenciales():
    if not SUPABASE_URL or not SUPABASE_KEY:
        print("\n[ERROR] Faltan credenciales en el archivo .env.")
        print("Asegurate de que SUPABASE_URL y SUPABASE_KEY esten configurados.\n")
        return False
    if not os.path.exists(RUTA_CSV):
        print(f"\n[ERROR] No se encontro el archivo CSV en: {RUTA_CSV}\n")
        return False
    return True


def verificar_tabla_existe(supabase: Client):
    try:
        supabase.table("transacciones_alicorp").select("id_transaccion").limit(1).execute()
        return True
    except Exception as e:
        err_msg = str(e).lower()
        if "schema cache" in err_msg or "not find the table" in err_msg or "pgrst205" in err_msg:
            return False
        print("Aviso al verificar tabla:", e)
        return False


def subir_lote_con_reintentos(supabase: Client, registros, num_lote, total_lotes):
    for intento in range(1, MAX_RETRIES + 1):
        try:
            supabase.table("transacciones_alicorp").upsert(registros).execute()
            return True
        except Exception as e:
            if intento < MAX_RETRIES:
                tiempo_espera = intento * 2
                print(f"   [!] Reintentando lote {num_lote}/{total_lotes} en {tiempo_espera}s por error: {e}")
                time.sleep(tiempo_espera)
            else:
                print(f"   [X] Fallo el lote {num_lote}/{total_lotes} tras {MAX_RETRIES} intentos: {e}")
                return False


def ejecutar_migracion():
    print("=" * 70)
    print(">> INICIANDO MIGRACION DE 100,000 REGISTROS A SUPABASE")
    print("=" * 70)

    if not verificar_credenciales():
        return

    print("[*] Conectando con Supabase...")
    supabase: Client = create_client(SUPABASE_URL, SUPABASE_KEY)

    print("[*] Verificando existencia de la tabla 'transacciones_alicorp'...")
    if not verificar_tabla_existe(supabase):
        print("\n" + "!" * 70)
        print("[!] LA TABLA 'transacciones_alicorp' AUN NO EXISTE EN SUPABASE.")
        print("!" * 70)
        print("\nPara crearla en 30 segundos, sigue estos pasos:")
        print("1. Abre tu navegador y entra a tu consola de Supabase:")
        print(f"   {SUPABASE_URL.replace('.co', '.co/project') if 'project' not in SUPABASE_URL else SUPABASE_URL}")
        print("2. En el menu lateral izquierdo, haz clic en 'SQL Editor'.")
        print("3. Haz clic en '+ New query', pega el siguiente bloque SQL y pulsa 'RUN':\n")
        print(SQL_CREAR_TABLA)
        print("=" * 70)
        print("En cuanto lo ejecutes en Supabase, vuelve a correr:")
        print("python migrar_100k_supabase.py\n")
        return

    # Contar si ya existen registros
    try:
        res_conteo = supabase.table("transacciones_alicorp").select("id_transaccion", count="exact").limit(1).execute()
        total_actual = res_conteo.count or 0
        if total_actual >= 100000:
            print(f"\n[OK] La base de datos ya cuenta con {total_actual:,} registros.")
            print("No se requiere migracion adicional.\n")
            return
        elif total_actual > 0:
            print(f"[*] La tabla ya contiene {total_actual:,} registros. Se sincronizaran los restantes...")
    except Exception as e:
        print("Aviso de conteo previo:", e)

    print(f"\n[*] Leyendo dataset local: {os.path.basename(RUTA_CSV)}...")
    t0 = time.time()
    df = pd.read_csv(RUTA_CSV)
    total_filas = len(df)
    print(f"[OK] Dataset cargado en memoria: {total_filas:,} filas y {len(df.columns)} columnas.")

    # Redondear campos numericos adecuadamente
    df["precio_unitario"] = df["precio_unitario"].round(2)
    df["temperatura_zona"] = df["temperatura_zona"].round(2)
    df["ventas_totales"] = df["ventas_totales"].round(2)

    total_lotes = math.ceil(total_filas / BATCH_SIZE)
    print(f"[*] Procesando en {total_lotes} lotes de {BATCH_SIZE:,} registros cada uno...\n")

    registros_insertados = 0
    t_inicio_subida = time.time()

    for i in range(total_lotes):
        inicio = i * BATCH_SIZE
        fin = min(inicio + BATCH_SIZE, total_filas)
        lote_df = df.iloc[inicio:fin]
        registros = lote_df.to_dict(orient="records")

        exito = subir_lote_con_reintentos(supabase, registros, i + 1, total_lotes)
        if not exito:
            print(f"\n[X] Migracion detenida por error en el lote {i + 1}.")
            return

        registros_insertados += len(registros)
        porcentaje = (registros_insertados / total_filas) * 100
        tiempo_transcurrido = time.time() - t_inicio_subida
        velocidad = registros_insertados / (tiempo_transcurrido if tiempo_transcurrido > 0 else 1)
        tiempo_restante = (total_filas - registros_insertados) / (velocidad if velocidad > 0 else 1)

        print(
            f"   > Lote {i + 1:02d}/{total_lotes:02d} | "
            f"{registros_insertados:7,d}/{total_filas:,d} ({porcentaje:5.1f}%) | "
            f"Velocidad: {int(velocidad):,d} reg/s | "
            f"Restante: {int(tiempo_restante)}s"
        )

    t_total = time.time() - t0
    print("\n" + "=" * 70)
    print(f"[EXITO] MIGRACION COMPLETADA CON EXITO EN {t_total:.1f} SEGUNDOS!")
    print("=" * 70)

    # Verificacion final
    try:
        final_conteo = supabase.table("transacciones_alicorp").select("id_transaccion", count="exact").limit(1).execute()
        print(f"[OK] Registros verificados en Supabase: {final_conteo.count:,} filas.")
    except Exception as e:
        print("Aviso en verificacion final:", e)
    print("=" * 70)


if __name__ == "__main__":
    ejecutar_migracion()
