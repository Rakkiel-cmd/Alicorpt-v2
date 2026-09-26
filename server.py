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
    usuario = data.get('usuario')
    password = data.get('password')

    # Usamos Variables de Entorno por seguridad. Si no existen, usa valores por defecto.
    # En Render, deberás configurar ADMIN_USER y ADMIN_PASS en Environment Variables.
    USUARIO_CORRECTO = os.environ.get("ADMIN_USER", "admin")
    PASSWORD_CORRECTO = os.environ.get("ADMIN_PASS", "alicorp123")

    if usuario == USUARIO_CORRECTO and password == PASSWORD_CORRECTO:
        return jsonify({"success": True, "message": "Acceso concedido.", "redirect": "dashboard.html"})
    else:
        return jsonify({"success": False, "message": "Usuario o contraseña incorrectos."})

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
