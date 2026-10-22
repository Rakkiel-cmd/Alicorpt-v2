from flask import Flask, send_from_directory, request, jsonify
import cv2
import numpy as np
import base64
import os
from dotenv import load_dotenv

# Cargar variables de entorno desde el archivo .env si existe
load_dotenv()

# Importamos la lógica de IA que hicieron tus compañeros
try:
    from Captura import capturar_rostro
    from modelo_facial import validar_rostro
except ImportError:
    capturar_rostro = None
    validar_rostro = None

app = Flask(__name__, static_folder='frontend_corporativo')

# Subproyecto 2: endpoints extra del dashboard de ventas (productos y simulador)
from ventas_extra import ventas_extra
app.register_blueprint(ventas_extra)
from estadistica_service import estadistica
app.register_blueprint(estadistica)
from modelo_keras import modelo_keras
app.register_blueprint(modelo_keras)
from regresion_lineal import regresion_lineal
app.register_blueprint(regresion_lineal)

# Limpiar y forzar cabeceras de seguridad para evitar errores de Permissions-Policy ('join-ad-interest-group')
@app.after_request
def add_security_headers(response):
    response.headers['Permissions-Policy'] = "camera=(self), microphone=(), geolocation=()"
    return response

# Ruta para servir la página principal
@app.route('/')
def index():
    return send_from_directory('frontend_corporativo', 'index.html')

# Ruta para servir cualquier otro archivo estático (CSS, JS, JSON, HTML)
@app.route('/<path:path>')
def serve_static(path):
    return send_from_directory('frontend_corporativo', path)

# API Endpoint: Login Tradicional (Usuario y Contraseña)
@app.route('/api/login_password', methods=['POST'])
def login_password():
    data = request.json
    usuario_input = data.get('usuario')
    password_input = data.get('password')

    SUPABASE_URL = os.environ.get("SUPABASE_URL")
    SUPABASE_KEY = os.environ.get("SUPABASE_KEY")

    if not SUPABASE_URL or not SUPABASE_KEY:
        return jsonify({"success": False, "message": "Faltan credenciales de base de datos."}), 500

    try:
        from supabase import create_client, Client
        supabase: Client = create_client(SUPABASE_URL, SUPABASE_KEY)
        
        # Buscar el usuario en la base de datos
        response = supabase.table('administradores').select('password').eq('usuario', usuario_input).execute()
        
        if len(response.data) > 0:
            password_real = response.data[0]['password']
            if password_input == password_real:
                return jsonify({"success": True, "message": "Acceso concedido.", "redirect": "dashboard.html"})
            else:
                return jsonify({"success": False, "message": "Contraseña incorrecta."})
        else:
            return jsonify({"success": False, "message": "El usuario no existe."})
            
    except Exception as e:
        return jsonify({"success": False, "message": f"Error de base de datos: {str(e)}"}), 500

# API Endpoint: Aquí es donde JavaScript enviará la foto de la cámara
@app.route('/api/login_facial', methods=['POST'])
def login_facial():
    if not validar_rostro or not capturar_rostro:
        return jsonify({"success": False, "message": "El modelo facial no está disponible."})

    data = request.json
    imagen_base64 = data.get('image')

    if not imagen_base64:
        return jsonify({"success": False, "message": "No se recibió ninguna imagen."})

    # Decodificar la imagen Base64 a un array Numpy (formato BGR para OpenCV)
    try:
        header, encoded = imagen_base64.split(",", 1)
        decoded_bytes = base64.b64decode(encoded)
        np_arr = np.frombuffer(decoded_bytes, np.uint8)
        frame_bgr = cv2.imdecode(np_arr, cv2.IMREAD_COLOR)

        # Usar la lógica de los compañeros
        rostro = capturar_rostro(frame_bgr)
        if rostro is not None:
            # Validar con la base de datos de Supabase en modelo_facial
            # IMPORTANTE: Usamos validar_rostro_detalle para obtener la distancia
            from modelo_facial import validar_rostro_detalle
            detalle = validar_rostro_detalle(rostro)
            
            if detalle["autorizado"]:
                distancia = detalle["distancia"]
                # Ajuste de escala para que una distancia aceptable (ej. 0.45) se mapee a >90%
                # Matemática de presentación: 100 - (distancia * 20)
                porcentaje = round(max(0, min(100, 100 - (distancia * 20))), 2)
                return jsonify({
                    "success": True, 
                    "message": "¡Identidad confirmada!", 
                    "porcentaje": porcentaje,
                    "redirect": "dashboard.html"
                })
            else:
                return jsonify({"success": False, "message": "Rostro no autorizado."})
        else:
            return jsonify({"success": False, "message": "No se detectó ningún rostro claro."})

    except Exception as e:
        return jsonify({"success": False, "message": f"Error procesando imagen: {str(e)}"})

# Subproyecto 1: Registro Web - Verificar calidad
@app.route('/api/verificar_rostro', methods=['POST'])
def verificar_rostro():
    if not capturar_rostro:
        return jsonify({"success": False, "message": "La IA está apagada. Activa el entorno virtual (venv)."})
        
    data = request.json
    img_b64 = data.get('image')
    if not img_b64:
        return jsonify({"success": False, "message": "Falta imagen."})
    
    try:
        header, encoded = img_b64.split(",", 1)
        decoded_bytes = base64.b64decode(encoded)
        np_arr = np.frombuffer(decoded_bytes, np.uint8)
        frame_bgr = cv2.imdecode(np_arr, cv2.IMREAD_COLOR)

        rostro = capturar_rostro(frame_bgr)
        if rostro is not None:
            return jsonify({"success": True, "message": "Rostro detectado correctamente. Calidad óptima."})
        else:
            return jsonify({"success": False, "message": "No se detectó rostro o hay mala iluminación."})
    except Exception as e:
        return jsonify({"success": False, "message": str(e)})

# Subproyecto 1: Registro Web - Guardar en Supabase
@app.route('/api/registrar_admin_web', methods=['POST'])
def registrar_admin_web():
    if not capturar_rostro:
        return jsonify({"success": False, "message": "La IA está apagada. Activa el entorno virtual (venv)."})

    data = request.json
    usuario = data.get('usuario')
    password = data.get('password')
    img_b64 = data.get('image')

    if not usuario or not password or not img_b64:
        return jsonify({"success": False, "message": "Datos incompletos."})

    try:
        header, encoded = img_b64.split(",", 1)
        decoded_bytes = base64.b64decode(encoded)
        np_arr = np.frombuffer(decoded_bytes, np.uint8)
        frame_bgr = cv2.imdecode(np_arr, cv2.IMREAD_COLOR)

        rostro = capturar_rostro(frame_bgr)
        if rostro is None:
            return jsonify({"success": False, "message": "Rostro no detectado en la validación final."})
        
        # Extraer encodings
        rgb_rostro = cv2.cvtColor(rostro, cv2.COLOR_BGR2RGB)
        import face_recognition
        encodings = face_recognition.face_encodings(rgb_rostro)
        
        if len(encodings) > 0:
            firma = encodings[0].tolist()
            
            SUPABASE_URL = os.environ.get("SUPABASE_URL")
            SUPABASE_KEY = os.environ.get("SUPABASE_KEY")
            from supabase import create_client, Client
            supabase: Client = create_client(SUPABASE_URL, SUPABASE_KEY)
            
            # Verificar si usuario ya existe
            exist = supabase.table('administradores').select('usuario').eq('usuario', usuario).execute()
            if len(exist.data) > 0:
                return jsonify({"success": False, "message": "El nombre de usuario ya está en uso."})

            supabase.table('administradores').insert({
                "usuario": usuario,
                "password": password,
                "face_encoding": firma
            }).execute()
            
            # Recargar la caché del modelo
            from modelo_facial import recargar_autorizados
            recargar_autorizados()
            
            return jsonify({"success": True, "message": "Registro completado."})
        else:
            return jsonify({"success": False, "message": "No se pudo extraer la firma biométrica."})
    except Exception as e:
        return jsonify({"success": False, "message": f"Error: {str(e)}"})

# Subproyecto 2: API Endpoint para predecir ventas con Red Neuronal
@app.route('/api/predecir_ventas', methods=['POST'])
def predecir_ventas():
    try:
        ruta_json = os.path.join(os.path.dirname(os.path.abspath(__file__)), "resumen_100k_supabase.json")
        stats = None
        if os.path.exists(ruta_json):
            try:
                import json
                with open(ruta_json, "r", encoding="utf-8") as f:
                    stats = json.load(f)["estadisticas"]
            except Exception:
                pass

        if stats is None:
            from datos_supabase import obtener_transacciones
            from estadistica_service import calcular_estadisticas
            df = obtener_transacciones()
            stats = calcular_estadisticas(df)

        # Usar el modelo entrenado con los datos reales
        from ventas_extra import _obtener_modelo
        modelo_info = _obtener_modelo()
        y_test = modelo_info['y_test']
        X_test = modelo_info['X_test']
        modelo = modelo_info['modelo']

        from sklearn.metrics import mean_squared_error
        y_pred = modelo.predict(X_test)
        mse = float(round(mean_squared_error(y_test, y_pred), 2))
        r2 = float(round(modelo_info['r2'], 4))
        prediccion_resultado = float(round(float(y_pred.mean()), 2))

        return jsonify({
            "success": True,
            "total_registros": int(stats.get("n", 100000)),
            "media_ventas": stats['media'],
            "mediana_ventas": stats['mediana'],
            "desviacion_ventas": stats['desviacion_estandar'],
            "varianza": stats['varianza'],
            "prob_venta_mayor_meta": stats['prob_venta_mayor_meta'],
            "mse": mse,
            "r2": r2,
            "prediccion_resultado": prediccion_resultado
        })
    except Exception as e:
        import traceback
        traceback.print_exc()
        return jsonify({"success": False, "message": str(e)})

if __name__ == '__main__':
    print("Iniciando servidor unificado (Web + IA)...")
    app.run(debug=True, port=5000)
