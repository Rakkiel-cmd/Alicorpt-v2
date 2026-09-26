from flask import Flask, send_from_directory, request, jsonify
import cv2
import numpy as np
import base64

import os

# Importamos la lógica de IA que hicieron tus compañeros
try:
    from Captura import capturar_rostro
    from modelo_facial import validar_rostro
except ImportError:
    capturar_rostro = None
    validar_rostro = None

app = Flask(__name__, static_folder='frontend_corporativo')

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
    if not capturar_rostro or not validar_rostro:
        return jsonify({"success": False, "message": "Módulos de IA no disponibles."}), 500

    data = request.json
    if not data or 'image' not in data:
        return jsonify({"success": False, "message": "No se recibió imagen."}), 400

    try:
        # 1. Recibimos la imagen base64 de JavaScript y la limpiamos
        image_data = data['image'].split(',')[1]
        # 2. La convertimos a formato numpy array que entiende OpenCV
        nparr = np.frombuffer(base64.b64decode(image_data), np.uint8)
        frame_bgr = cv2.imdecode(nparr, cv2.IMREAD_COLOR)

        if frame_bgr is None:
            return jsonify({"success": False, "message": "Imagen inválida."}), 400

        # 3. Se la pasamos al código del Integrante 1 (Captura)
        rostro = capturar_rostro(frame_bgr)
        if rostro is not None:
            # 4. Se la pasamos al código del Integrante 2 (Validación)
            if validar_rostro(rostro):
                return jsonify({"success": True, "message": "Acceso concedido.", "redirect": "dashboard.html"})
            else:
                return jsonify({"success": False, "message": "Acceso denegado. Rostro no reconocido."})
        else:
            return jsonify({"success": False, "message": "No se detectó ningún rostro en la foto."})
            
    except Exception as e:
        return jsonify({"success": False, "message": f"Error del servidor: {str(e)}"}), 500

if __name__ == '__main__':
    print("Iniciando servidor unificado (Web + IA)...")
    app.run(debug=True, port=5000)
