import os
import cv2
import json
import numpy as np
from supabase import create_client, Client

# Importamos la lógica de captura para reciclar código
try:
    from Captura import capturar_rostro
    import face_recognition
except ImportError:
    print("Falta instalar OpenCV o face_recognition")
    exit(1)

# Conexión a Supabase
SUPABASE_URL = os.environ.get("SUPABASE_URL")
SUPABASE_KEY = os.environ.get("SUPABASE_KEY")

if not SUPABASE_URL or not SUPABASE_KEY:
    print("=========================================================")
    print("⚠️ ERROR: No has configurado las llaves de Supabase. ⚠️")
    print("Antes de ejecutar este script, debes configurar tus")
    print("variables de entorno SUPABASE_URL y SUPABASE_KEY.")
    print("En Windows, ejecuta en PowerShell:")
    print('$env:SUPABASE_URL="tu_url"')
    print('$env:SUPABASE_KEY="tu_key"')
    print("=========================================================")
    exit(1)

supabase: Client = create_client(SUPABASE_URL, SUPABASE_KEY)

def registrar_nuevo_admin():
    print("=== Registro de Administrador en la Nube (Supabase) ===")
    usuario = input("Ingresa un nombre de usuario: ")
    password = input("Ingresa una contraseña segura: ")
    
    print("\n¿Cómo deseas subir la foto?")
    print("1. Usar mi cámara web")
    print("2. Cargar una foto desde mi computadora")
    opcion = input("Elige una opción (1/2): ")

    if opcion == "2":
        nombre_foto = input("Ingresa el nombre del archivo (ejemplo: compañero.jpg): ")
        if not os.path.exists(nombre_foto):
            print(f"❌ No se encontró el archivo '{nombre_foto}'. Asegúrate de que esté en la misma carpeta.")
            return
            
        frame = cv2.imread(nombre_foto)
        if frame is None:
            print("❌ No se pudo leer la imagen. Archivo corrupto o formato no soportado.")
            return
            
        print("Procesando rostro de la imagen...")
        rostro_detectado = capturar_rostro(frame)
        procesar_y_guardar(rostro_detectado, usuario, password)

    else:
        print("\nInicializando cámara... Mira fijamente y presiona 's' para tomar la foto.")
        cap = cv2.VideoCapture(0)
        
        while True:
            ret, frame = cap.read()
            if not ret:
                print("No se pudo acceder a la cámara.")
                break
                
            cv2.imshow("Registro Facial - Presiona 's' para capturar", frame)
            
            if cv2.waitKey(1) & 0xFF == ord('s'):
                print("Procesando rostro...")
                rostro_detectado = capturar_rostro(frame)
                exito = procesar_y_guardar(rostro_detectado, usuario, password)
                if exito:
                    break
                else:
                    print("Intenta de nuevo presionando 's'.")

        cap.release()
        cv2.destroyAllWindows()

def procesar_y_guardar(rostro_detectado, usuario, password):
    if rostro_detectado is not None:
        # Intentar extraer la firma matemática (128 números)
        rgb_rostro = cv2.cvtColor(rostro_detectado, cv2.COLOR_BGR2RGB)
        encodings = face_recognition.face_encodings(rgb_rostro)
        
        if len(encodings) > 0:
            firma = encodings[0].tolist() # Convertir Numpy array a lista normal de Python
            print("✅ Firma biométrica extraída con éxito.")
            
            # Guardar en Supabase
            try:
                data, count = supabase.table('administradores').insert({
                    "usuario": usuario,
                    "password": password,
                    "face_encoding": firma
                }).execute()
                print(f"🎉 ¡Administrador '{usuario}' registrado exitosamente en la base de datos de Supabase!")
                return True
            except Exception as e:
                print("❌ Error al guardar en Supabase:", e)
                return False
        else:
            print("❌ No se pudo extraer la firma biométrica. Intenta acercarte más o mejorar la luz en la foto.")
            return False
    else:
        print("❌ No se detectó ningún rostro en la foto. Intenta con otra imagen.")
        return False

if __name__ == "__main__":
    registrar_nuevo_admin()
