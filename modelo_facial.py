"""
modelo_facial.py  -  Subproyecto 1 (Login con Reconocimiento Facial)
Integrante 2: Modelo de Reconocimiento Facial

Recibe el rostro ya recortado que devuelve Captura.capturar_rostro()
(array numpy BGR de 160x160) y decide si pertenece a un administrador
autorizado comparando embeddings de 128 dimensiones (face_recognition/dlib).

Uso desde app.py (Integrante 3):

    from Captura import capturar_rostro
    from modelo_facial import validar_rostro

    rostro = capturar_rostro(frame_bgr)      # Integrante 1
    if validar_rostro(rostro):               # Integrante 2 (este archivo)
        ...acceso concedido...

Contrato de la funcion principal:
    validar_rostro(rostro_capturado) -> bool
    - True  : el rostro coincide con algun rostro de rostros_autorizados/
    - False : impostor, rostro None, imagen invalida, carpeta vacia o
              cualquier error. NUNCA lanza excepciones.

Pruebas desde la terminal (en la carpeta del proyecto):
    python modelo_facial.py --registrar        # guarda tu rostro con la camara
    python modelo_facial.py --probar           # prueba en vivo con la camara
    python modelo_facial.py --validar foto.jpg # valida una foto del disco
    python modelo_facial.py --info             # muestra que rostros hay cargados

Dependencias (solo las necesita este archivo):
    pip install face_recognition opencv-python numpy
"""

import argparse
import logging
import os
import sys
import time

import cv2
import numpy as np

try:
    import face_recognition
except ImportError as error:  # mensaje claro en lugar de un traceback confuso
    raise ImportError(
        "Falta la libreria 'face_recognition'. Instalala con:\n"
        "    pip install face_recognition\n"
        "En Windows requiere dlib; si falla, instala CMake y las "
        "'Build Tools de Visual Studio' o usa: pip install dlib-bin"
    ) from error

# Funcion del Integrante 1. La guia la nombra 'captura.py' pero el archivo del
# repo es 'Captura.py'; se aceptan ambos para que funcione en Windows y Linux.
try:
    from Captura import capturar_rostro as _capturar_rostro
except ImportError:
    try:
        from captura import capturar_rostro as _capturar_rostro
    except ImportError:
        _capturar_rostro = None  # se usara la deteccion propia de dlib


# --------------------------------------------------------------------------
# CONFIGURACION
# --------------------------------------------------------------------------
CARPETA_AUTORIZADOS = os.path.join(
    os.path.dirname(os.path.abspath(__file__)), "rostros_autorizados"
)
EXTENSIONES_VALIDAS = (".jpg", ".jpeg", ".png", ".bmp", ".webp")

# Distancia euclidiana maxima entre embeddings para aceptar a alguien.
# face_recognition usa 0.6 por defecto; 0.5 es mas estricto (mejor para login).
# Bajalo (0.45) si acepta impostores; subelo (0.55) si te rechaza a ti.
UMBRAL_DISTANCIA = 0.5

_log = logging.getLogger("modelo_facial")

# Cache de encodings autorizados: se recalculan solo si la carpeta cambia.
_cache = {"firma": None, "autorizados": []}


# --------------------------------------------------------------------------
# UTILIDADES INTERNAS
# --------------------------------------------------------------------------
def _normalizar_bgr(imagen):
    """Devuelve la imagen como array uint8 BGR de 3 canales, o None si no sirve."""
    if imagen is None or not isinstance(imagen, np.ndarray) or imagen.size == 0:
        return None

    if imagen.dtype != np.uint8:
        # Acepta float 0-1 o 0-255 sin romperse.
        maximo = float(np.max(imagen))
        if maximo <= 1.0:
            imagen = imagen * 255.0
        imagen = np.clip(imagen, 0, 255).astype(np.uint8)

    if imagen.ndim == 2:  # escala de grises
        imagen = cv2.cvtColor(imagen, cv2.COLOR_GRAY2BGR)
    elif imagen.ndim == 3 and imagen.shape[2] == 4:  # con canal alfa
        imagen = cv2.cvtColor(imagen, cv2.COLOR_BGRA2BGR)
    elif imagen.ndim != 3 or imagen.shape[2] != 3:
        return None

    return np.ascontiguousarray(imagen)


def _encoding_de_rostro_recortado(rostro_bgr):
    """
    Embedding de 128 valores de una imagen que YA es un rostro recortado
    (salida de capturar_rostro). Como el recorte ocupa toda la imagen, se le
    indica a dlib la ubicacion del rostro (toda la imagen) porque su detector
    suele fallar sobre recortes muy ajustados.
    """
    rostro_bgr = _normalizar_bgr(rostro_bgr)
    if rostro_bgr is None:
        return None

    alto, ancho = rostro_bgr.shape[:2]
    rgb = cv2.cvtColor(rostro_bgr, cv2.COLOR_BGR2RGB)  # dlib espera RGB
    ubicacion = (0, ancho, alto, 0)  # (arriba, derecha, abajo, izquierda)

    encodings = face_recognition.face_encodings(
        rgb, known_face_locations=[ubicacion], num_jitters=1
    )
    return encodings[0] if encodings else None


def _encoding_de_foto_completa(foto_bgr):
    """
    Embedding a partir de una foto completa (cuando Captura no encuentra el
    rostro). Usa el detector de dlib y se queda con el rostro mas grande.
    """
    foto_bgr = _normalizar_bgr(foto_bgr)
    if foto_bgr is None:
        return None

    rgb = cv2.cvtColor(foto_bgr, cv2.COLOR_BGR2RGB)
    ubicaciones = face_recognition.face_locations(rgb)
    if not ubicaciones:
        return None

    mayor = max(ubicaciones, key=lambda u: (u[2] - u[0]) * (u[1] - u[3]))
    encodings = face_recognition.face_encodings(
        rgb, known_face_locations=[mayor], num_jitters=1
    )
    return encodings[0] if encodings else None


def _encoding_de_foto_autorizada(foto_bgr):
    """
    Procesa la foto del admin con el MISMO pipeline que el rostro en vivo
    (Captura -> recorte 160x160 -> embedding) para que ambos sean comparables.
    """
    if _capturar_rostro is not None:
        recorte = _capturar_rostro(foto_bgr)
        if recorte is not None:
            encoding = _encoding_de_rostro_recortado(recorte)
            if encoding is not None:
                return encoding
    return _encoding_de_foto_completa(foto_bgr)


def _archivos_autorizados():
    if not os.path.isdir(CARPETA_AUTORIZADOS):
        return []
    return sorted(
        nombre
        for nombre in os.listdir(CARPETA_AUTORIZADOS)
        if nombre.lower().endswith(EXTENSIONES_VALIDAS)
    )


def _firma_carpeta(archivos):
    """Huella de la carpeta (nombre, fecha, tamano) para detectar cambios."""
    firma = []
    for nombre in archivos:
        ruta = os.path.join(CARPETA_AUTORIZADOS, nombre)
        try:
            estado = os.stat(ruta)
            firma.append((nombre, estado.st_mtime_ns, estado.st_size))
        except OSError:
            continue
    return tuple(firma)


def _cargar_autorizados():
    """Descarga la lista de (usuario, encoding) directamente desde Supabase."""
    SUPABASE_URL = os.environ.get("SUPABASE_URL")
    SUPABASE_KEY = os.environ.get("SUPABASE_KEY")
    
    if not SUPABASE_URL or not SUPABASE_KEY:
        _log.error("❌ Faltan las variables de entorno SUPABASE_URL y SUPABASE_KEY.")
        return []

    try:
        from supabase import create_client, Client
        supabase: Client = create_client(SUPABASE_URL, SUPABASE_KEY)
        
        # Consultar la tabla de administradores
        response = supabase.table('administradores').select('usuario, face_encoding').execute()
        
        autorizados = []
        for fila in response.data:
            usuario = fila.get("usuario")
            firma_json = fila.get("face_encoding")
            
            if firma_json:
                # Convertir la lista de 128 números de vuelta a un array Numpy
                firma_np = np.array(firma_json)
                autorizados.append((usuario, firma_np))
                
        return autorizados
    except Exception as e:
        _log.error(f"Error conectando a Supabase: {e}")
        return []


# --------------------------------------------------------------------------
# API PUBLICA
# --------------------------------------------------------------------------
def recargar_autorizados():
    """Fuerza a releer rostros_autorizados/ (normalmente no hace falta)."""
    _cache["firma"] = None
    return len(_cargar_autorizados())


def validar_rostro_detalle(rostro_capturado):
    """
    Igual que validar_rostro pero devuelve un diccionario con el detalle,
    util para mostrar mensajes o depurar:

        {"autorizado": bool, "distancia": float | None,
         "coincide_con": str | None, "motivo": str}
    """
    resultado = {
        "autorizado": False,
        "distancia": None,
        "coincide_con": None,
        "motivo": "",
    }
    try:
        if rostro_capturado is None:
            resultado["motivo"] = "No se recibio ningun rostro."
            return resultado

        autorizados = _cargar_autorizados()
        if not autorizados:
            resultado["motivo"] = (
                "No hay rostros autorizados registrados en 'rostros_autorizados/'."
            )
            return resultado

        encoding = _encoding_de_rostro_recortado(rostro_capturado)
        if encoding is None:
            resultado["motivo"] = "No se pudo analizar el rostro capturado."
            return resultado

        conocidos = [enc for _, enc in autorizados]
        distancias = face_recognition.face_distance(conocidos, encoding)
        mejor = int(np.argmin(distancias))
        distancia = float(distancias[mejor])

        resultado["distancia"] = round(distancia, 4)
        resultado["coincide_con"] = autorizados[mejor][0]
        if distancia <= UMBRAL_DISTANCIA:
            resultado["autorizado"] = True
            resultado["motivo"] = "Rostro reconocido."
        else:
            resultado["coincide_con"] = None
            resultado["motivo"] = "Rostro no reconocido."
        return resultado

    except Exception as error:  # el login jamas debe caerse por esto
        _log.exception("Error inesperado al validar el rostro")
        resultado["autorizado"] = False
        resultado["motivo"] = f"Error al validar el rostro: {error}"
        return resultado


def validar_rostro(rostro_capturado):
    """
    Funcion que usa el Integrante 3.

    rostro_capturado : array numpy BGR (salida de Captura.capturar_rostro)
    return           : True si es un administrador autorizado, False si no.
    """
    return bool(validar_rostro_detalle(rostro_capturado)["autorizado"])


def registrar_rostro(frame_bgr, nombre=None):
    """
    Guarda un frame de camara en rostros_autorizados/ como nuevo rostro del
    administrador. Solo lo guarda si se detecta un rostro utilizable.
    Devuelve la ruta guardada o None. (Guarda el frame completo, no el recorte,
    para que se procese igual que el rostro en vivo.)
    """
    frame_bgr = _normalizar_bgr(frame_bgr)
    if frame_bgr is None:
        return None
    if _encoding_de_foto_autorizada(frame_bgr) is None:
        return None

    os.makedirs(CARPETA_AUTORIZADOS, exist_ok=True)
    if not nombre:
        nombre = f"admin_{time.strftime('%Y%m%d_%H%M%S')}"
    ruta = os.path.join(CARPETA_AUTORIZADOS, f"{nombre}.jpg")
    cv2.imwrite(ruta, frame_bgr)
    return ruta


# --------------------------------------------------------------------------
# HERRAMIENTAS DE PRUEBA (no afectan a app.py)
# --------------------------------------------------------------------------
def _abrir_camara():
    cap = cv2.VideoCapture(0, cv2.CAP_DSHOW)
    if not cap.isOpened():
        print("No se pudo acceder a la camara. Verifica permisos o el indice (0).")
        return None
    return cap


def _modo_registrar():
    cap = _abrir_camara()
    if cap is None:
        return
    print("Mira a la camara. 's' = guardar foto, 'q' = salir.")
    print("Guarda 3 a 5 fotos con distinta luz/angulo para mejor precision.")
    guardadas = 0
    while True:
        ok, frame = cap.read()
        if not ok:
            break
        vista = frame.copy()
        cv2.putText(vista, f"Guardadas: {guardadas}  (s=guardar, q=salir)",
                    (10, 25), cv2.FONT_HERSHEY_SIMPLEX, 0.6, (0, 255, 0), 2)
        cv2.imshow("Registrar rostro autorizado", vista)
        tecla = cv2.waitKey(1) & 0xFF
        if tecla == ord("s"):
            ruta = registrar_rostro(frame)
            if ruta:
                guardadas += 1
                print(f"Guardada: {ruta}")
                time.sleep(0.4)
            else:
                print("No se detecto un rostro claro. Acercate y mejora la luz.")
        elif tecla == ord("q"):
            break
    cap.release()
    cv2.destroyAllWindows()
    print(f"Listo. {guardadas} foto(s) nueva(s) en {CARPETA_AUTORIZADOS}")


def _modo_probar():
    if _capturar_rostro is None:
        print("No se pudo importar Captura.py. Ejecuta esto desde la carpeta del proyecto.")
        return
    cap = _abrir_camara()
    if cap is None:
        return
    print("Prueba en vivo. Presiona 'q' para salir.")
    while True:
        ok, frame = cap.read()
        if not ok:
            break
        rostro = _capturar_rostro(frame)
        if rostro is None:
            texto, color = "Sin rostro", (0, 165, 255)
        else:
            detalle = validar_rostro_detalle(rostro)
            dist = detalle["distancia"]
            if detalle["autorizado"]:
                texto, color = f"AUTORIZADO  d={dist}", (0, 200, 0)
            else:
                extra = f"  d={dist}" if dist is not None else ""
                texto, color = f"DENEGADO{extra}", (0, 0, 255)
        cv2.putText(frame, texto, (10, 30), cv2.FONT_HERSHEY_SIMPLEX, 0.8, color, 2)
        cv2.imshow("Prueba - validar_rostro (q para salir)", frame)
        if cv2.waitKey(1) & 0xFF == ord("q"):
            break
    cap.release()
    cv2.destroyAllWindows()


def _modo_validar_foto(ruta):
    foto = cv2.imread(ruta)
    if foto is None:
        print(f"No se pudo leer la imagen: {ruta}")
        return
    if _capturar_rostro is None:
        print("No se pudo importar Captura.py. Ejecuta esto desde la carpeta del proyecto.")
        return
    rostro = _capturar_rostro(foto)
    if rostro is None:
        print("Captura.py no detecto ningun rostro en la foto.")
        return
    print(validar_rostro_detalle(rostro))


def _modo_info():
    print(f"Carpeta      : {CARPETA_AUTORIZADOS}")
    print(f"Umbral       : {UMBRAL_DISTANCIA}")
    print(f"Captura.py   : {'OK' if _capturar_rostro else 'NO encontrado'}")
    archivos = _archivos_autorizados()
    validos = [n for n, _ in _cargar_autorizados()]
    print(f"Imagenes     : {len(archivos)} encontradas, {len(validos)} con rostro valido")
    for nombre in archivos:
        print(f"   {'OK ' if nombre in validos else 'X  '} {nombre}")
    if not archivos:
        print("Aun no hay rostros. Ejecuta: python modelo_facial.py --registrar")


if __name__ == "__main__":
    logging.basicConfig(level=logging.INFO, format="%(levelname)s: %(message)s")
    parser = argparse.ArgumentParser(description="Modelo de reconocimiento facial")
    grupo = parser.add_mutually_exclusive_group()
    grupo.add_argument("--registrar", action="store_true",
                       help="guardar tu rostro con la camara")
    grupo.add_argument("--probar", action="store_true",
                       help="probar validar_rostro en vivo con la camara")
    grupo.add_argument("--validar", metavar="FOTO",
                       help="validar una foto guardada en disco")
    grupo.add_argument("--info", action="store_true",
                       help="mostrar los rostros autorizados cargados")
    args = parser.parse_args()

    if args.registrar:
        _modo_registrar()
    elif args.probar:
        _modo_probar()
    elif args.validar:
        _modo_validar_foto(args.validar)
    else:
        _modo_info()
    sys.exit(0)

