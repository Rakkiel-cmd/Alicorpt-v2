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
    from backend.Captura import capturar_rostro
    from backend.modelo_facial import validar_rostro
except ImportError:
    capturar_rostro = None
    validar_rostro = None

app = Flask(__name__, static_folder='frontend_corporativo')

# Subproyecto 2: endpoints extra del dashboard de ventas (productos y simulador)
from backend.ventas_extra import ventas_extra
app.register_blueprint(ventas_extra)
from backend.estadistica_service import estadistica
app.register_blueprint(estadistica)
from backend.modelo_keras import modelo_keras
app.register_blueprint(modelo_keras)
from backend.regresion_lineal import regresion_lineal
app.register_blueprint(regresion_lineal)
from backend.graficos_analysis import graficos_analysis
app.register_blueprint(graficos_analysis)
from backend.incidencias import incidencias_bp
app.register_blueprint(incidencias_bp)
from backend.carrito_service import carrito_bp
app.register_blueprint(carrito_bp)
from backend.superadmin_panel import superadmin_panel
app.register_blueprint(superadmin_panel)
from backend.reporte_predictivo_service import reporte_predictivo_bp
app.register_blueprint(reporte_predictivo_bp)
from backend.seguridad_acceso import es_superadmin
from datetime import datetime, timezone, timedelta

# Zona horaria oficial Corporación Alicorp Perú (UTC-5, ciclo diario estricto de 00:00 a 24:00)
ZONA_HORARIA_PERU = timezone(timedelta(hours=-5))

def obtener_ahora_peru():
    """Retorna el datetime actual en la zona horaria de Perú (UTC-5)."""
    return datetime.now(ZONA_HORARIA_PERU)

def obtener_fecha_hoy_peru():
    """Retorna la fecha calendario de hoy en Perú (YYYY-MM-DD), reiniciando exactamente a las 00:00."""
    return obtener_ahora_peru().date().isoformat()

def obtener_iso_ahora_peru():
    """Retorna timestamp ISO 8601 con offset explícito -05:00."""
    return obtener_ahora_peru().isoformat()

def segundos_transcurridos_desde_medianoche():
    """Segundos transcurridos desde las 00:00:00 del día actual en Perú (máx 86400)."""
    ahora = obtener_ahora_peru()
    inicio_dia = ahora.replace(hour=0, minute=0, second=0, microsecond=0)
    return max(0, min(86400, int((ahora - inicio_dia).total_seconds())))

# Limpiar y forzar cabeceras de seguridad para evitar errores de Permissions-Policy ('join-ad-interest-group')
# y capturar automáticamente fallas HTTP 404/500 en tiempo real para el monitor de Incidencias Cliente
@app.after_request
def add_security_headers(response):
    response.headers['Permissions-Policy'] = "camera=(self), microphone=(), geolocation=()"
    try:
        status = response.status_code
        ruta = request.path or ""
        if not ruta.startswith("/api/fallas_web") and (status >= 500 or status == 404):
            import threading
            from backend.incidencias import registrar_o_actualizar_falla
            metodo = request.method
            tipo = "Bug / Error Servidor 500" if status >= 500 else "Error Web Consola (404)"
            grav = "Crítica" if status >= 500 else "Media"
            titulo = f"Respuesta HTTP {status} en ruta {metodo} {ruta}"
            detalle = f"Recurso o endpoint solicitado desde {request.remote_addr or 'cliente web'} devolvió estado {status}."
            codigo = f"http-{status}-{metodo.lower()}-{ruta.strip('/').replace('/', '-') or 'root'}"
            threading.Thread(
                target=registrar_o_actualizar_falla,
                kwargs={
                    "titulo_error": titulo,
                    "tipo_falla": tipo,
                    "gravedad": grav,
                    "detalle_tecnico": detalle,
                    "origen": "Servidor Flask / HTTP",
                    "codigo_unico": codigo,
                    "incrementar_ocurrencia": True
                },
                daemon=True
            ).start()
    except Exception:
        pass
    return response

# Ruta para servir la página principal
@app.route('/')
def index():
    return send_from_directory('frontend_corporativo', 'index.html')

# Ruta explícita para servir el favicon corporativo y evitar errores 404
@app.route('/favicon.ico')
def favicon():
    return send_from_directory('frontend_corporativo', 'favicon.ico', mimetype='image/vnd.microsoft.icon')

# Ruta para servir cualquier otro archivo estático (CSS, JS, JSON, HTML)
@app.route('/<path:path>')
def serve_static(path):
    return send_from_directory('frontend_corporativo', path)

# API Endpoint: Login Tradicional (Usuario y Contraseña)
@app.route('/api/login_password', methods=['POST'])
def login_password():
    data = request.json or {}
    usuario_input = data.get('usuario', '').strip()
    password_input = data.get('password', '')

    SUPABASE_URL = os.environ.get("SUPABASE_URL")
    SUPABASE_KEY = os.environ.get("SUPABASE_KEY")

    if not SUPABASE_URL or not SUPABASE_KEY:
        return jsonify({"success": False, "message": "Faltan credenciales de base de datos."}), 500

    try:
        from supabase import create_client, Client
        supabase: Client = create_client(SUPABASE_URL, SUPABASE_KEY)
        
        # Buscar el usuario en la base de datos (con soporte para nombre_completo)
        try:
            response = supabase.table('administradores').select('usuario, password, nombre_completo, face_encoding').eq('usuario', usuario_input).execute()
        except Exception:
            response = supabase.table('administradores').select('usuario, password, face_encoding').eq('usuario', usuario_input).execute()
        
        if len(response.data) > 0:
            fila = response.data[0]
            password_real = fila.get('password')
            if password_input == password_real:
                nombre_completo = fila.get('nombre_completo') or fila.get('usuario')
                face_encoding = fila.get('face_encoding')
                
                # Inicializar o registrar actividad para el día calendario actual (00:00 a 24:00 Perú)
                try:
                    hoy = obtener_fecha_hoy_peru()
                    now_iso = obtener_iso_ahora_peru()
                    act_check = supabase.table('actividad_usuarios').select('id').eq('usuario', usuario_input).eq('fecha', hoy).execute()
                    if not act_check.data:
                        supabase.table('actividad_usuarios').insert({
                            'usuario': usuario_input,
                            'fecha': hoy,
                            'tiempo_segundos': 0,
                            'primer_ingreso': now_iso,
                            'ultimo_latido': now_iso
                        }).execute()
                    else:
                        supabase.table('actividad_usuarios').update({
                            'ultimo_latido': now_iso
                        }).eq('id', act_check.data[0]['id']).execute()
                except Exception:
                    pass

                es_super = es_superadmin(fila.get('usuario'))
                return jsonify({
                    "success": True, 
                    "message": "Acceso concedido.", 
                    "usuario": fila.get('usuario'),
                    "nombre_completo": nombre_completo,
                    "face_encoding": face_encoding,
                    "es_superadmin": es_super,
                    "rol": "SuperAdmin" if es_super else "Analista",
                    "redirect": "dashboard.html"
                })
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

    data = request.json or {}
    imagen_base64 = data.get('image')

    if not imagen_base64:
        return jsonify({"success": False, "message": "No se recibió ninguna imagen."})

    # Decodificar la imagen Base64 a un array Numpy (formato BGR para OpenCV)
    try:
        header, encoded = imagen_base64.split(",", 1)
        decoded_bytes = base64.b64decode(encoded)
        np_arr = np.frombuffer(decoded_bytes, np.uint8)
        frame_bgr = cv2.imdecode(np_arr, cv2.IMREAD_COLOR)

        # Usar la lógica de captura
        rostro = capturar_rostro(frame_bgr)
        if rostro is not None:
            # Validar con la base de datos de Supabase en modelo_facial
            # IMPORTANTE: Usamos validar_rostro_detalle para obtener la distancia
            from backend.modelo_facial import validar_rostro_detalle
            detalle = validar_rostro_detalle(rostro)
            
            if detalle["autorizado"]:
                distancia = detalle["distancia"]
                porcentaje = round(max(0, min(100, 100 - (distancia * 20))), 2)
                usuario_reconocido = detalle.get("coincide_con")

                # Obtener perfil del usuario desde Supabase
                nombre_completo = usuario_reconocido
                face_encoding = None
                try:
                    SUPABASE_URL = os.environ.get("SUPABASE_URL")
                    SUPABASE_KEY = os.environ.get("SUPABASE_KEY")
                    from supabase import create_client, Client
                    supabase: Client = create_client(SUPABASE_URL, SUPABASE_KEY)
                    try:
                        u_res = supabase.table('administradores').select('usuario, nombre_completo, face_encoding').eq('usuario', usuario_reconocido).execute()
                    except Exception:
                        u_res = supabase.table('administradores').select('usuario, face_encoding').eq('usuario', usuario_reconocido).execute()
                    
                    if u_res.data:
                        nombre_completo = u_res.data[0].get('nombre_completo') or usuario_reconocido
                        face_encoding = u_res.data[0].get('face_encoding')
                    
                    # Inicializar actividad del día calendario actual (00:00 a 24:00 Perú)
                    hoy = obtener_fecha_hoy_peru()
                    now_iso = obtener_iso_ahora_peru()
                    act_check = supabase.table('actividad_usuarios').select('id').eq('usuario', usuario_reconocido).eq('fecha', hoy).execute()
                    if not act_check.data:
                        supabase.table('actividad_usuarios').insert({
                            'usuario': usuario_reconocido,
                            'fecha': hoy,
                            'tiempo_segundos': 0,
                            'primer_ingreso': now_iso,
                            'ultimo_latido': now_iso
                        }).execute()
                    else:
                        supabase.table('actividad_usuarios').update({
                            'ultimo_latido': now_iso
                        }).eq('id', act_check.data[0]['id']).execute()
                except Exception:
                    pass

                es_super = es_superadmin(usuario_reconocido)
                return jsonify({
                    "success": True, 
                    "message": f"¡Identidad confirmada! Bienvenido, {nombre_completo}.", 
                    "porcentaje": porcentaje,
                    "usuario": usuario_reconocido,
                    "nombre_completo": nombre_completo,
                    "face_encoding": face_encoding,
                    "es_superadmin": es_super,
                    "rol": "SuperAdmin" if es_super else "Analista",
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
        
    data = request.json or {}
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

    data = request.json or {}
    usuario = (data.get('usuario') or '').strip()
    nombre_completo = (data.get('nombre_completo') or '').strip() or usuario
    password = data.get('password')
    img_b64 = data.get('image')

    if not usuario or not password or not img_b64:
        return jsonify({"success": False, "message": "Datos incompletos. Llena usuario, nombre completo y contraseña."})

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

            insert_payload = {
                "usuario": usuario,
                "password": password,
                "face_encoding": firma,
                "nombre_completo": nombre_completo
            }
            try:
                supabase.table('administradores').insert(insert_payload).execute()
            except Exception as e_col:
                # Si la columna nombre_completo no existe todavía en Supabase, guardar sin ella
                if "nombre_completo" in str(e_col):
                    del insert_payload["nombre_completo"]
                    supabase.table('administradores').insert(insert_payload).execute()
                else:
                    raise e_col
            
            # Recargar la caché del modelo
            from backend.modelo_facial import recargar_autorizados
            recargar_autorizados()
            
            return jsonify({
                "success": True, 
                "message": f"Registro completado para {nombre_completo}.",
                "usuario": usuario,
                "nombre_completo": nombre_completo
            })
        else:
            return jsonify({"success": False, "message": "No se pudo extraer la firma biométrica."})
    except Exception as e:
        return jsonify({"success": False, "message": f"Error: {str(e)}"})

# Endpoint: Perfil de Usuario (Nombre, Usuario y Vector)
@app.route('/api/usuario_perfil', methods=['GET'])
def usuario_perfil():
    usuario = request.args.get('usuario')
    if not usuario:
        return jsonify({"success": False, "message": "Falta parámetro usuario."}), 400

    SUPABASE_URL = os.environ.get("SUPABASE_URL")
    SUPABASE_KEY = os.environ.get("SUPABASE_KEY")
    if not SUPABASE_URL or not SUPABASE_KEY:
        return jsonify({"success": False, "message": "Faltan credenciales de base de datos."}), 500

    try:
        from supabase import create_client, Client
        supabase: Client = create_client(SUPABASE_URL, SUPABASE_KEY)
        try:
            res = supabase.table('administradores').select('usuario, nombre_completo, face_encoding').eq('usuario', usuario).execute()
        except Exception:
            res = supabase.table('administradores').select('usuario, face_encoding').eq('usuario', usuario).execute()

        if res.data and len(res.data) > 0:
            fila = res.data[0]
            es_super = es_superadmin(fila.get("usuario"))
            return jsonify({
                "success": True,
                "usuario": fila.get("usuario"),
                "nombre_completo": fila.get("nombre_completo") or fila.get("usuario"),
                "face_encoding": fila.get("face_encoding"),
                "es_superadmin": es_super,
                "rol": "SuperAdmin" if es_super else "Analista"
            })
        return jsonify({"success": False, "message": "Usuario no encontrado."}), 404
    except Exception as e:
        return jsonify({"success": False, "message": str(e)}), 500

# Endpoint: Obtener tiempo de actividad acumulado hoy (ciclo diario 00:00 a 24:00 hora Perú)
@app.route('/api/actividad/hoy', methods=['GET'])
def actividad_hoy():
    usuario = request.args.get('usuario')
    if not usuario:
        return jsonify({"success": False, "message": "Falta parámetro usuario."}), 400

    hoy = obtener_fecha_hoy_peru()
    now_iso = obtener_iso_ahora_peru()
    max_seg_hoy = segundos_transcurridos_desde_medianoche()

    SUPABASE_URL = os.environ.get("SUPABASE_URL")
    SUPABASE_KEY = os.environ.get("SUPABASE_KEY")
    if not SUPABASE_URL or not SUPABASE_KEY:
        return jsonify({"success": True, "usuario": usuario, "fecha": hoy, "tiempo_segundos": 0, "offline": True})

    try:
        from backend.datos_supabase import obtener_cliente_supabase
        supabase = obtener_cliente_supabase()

        res = supabase.table('actividad_usuarios').select('*').eq('usuario', usuario).eq('fecha', hoy).execute()
        if res.data and len(res.data) > 0:
            fila = res.data[0]
            tiempo_db = max(0, int(fila.get("tiempo_segundos", 0) or 0))
            # Garantizar que el tiempo del día nunca supere los segundos transcurridos desde las 00:00
            if tiempo_db > max_seg_hoy + 15:
                tiempo_db = max_seg_hoy
                try:
                    supabase.table('actividad_usuarios').update({"tiempo_segundos": tiempo_db}).eq('id', fila['id']).execute()
                except Exception:
                    pass
            return jsonify({
                "success": True,
                "usuario": usuario,
                "fecha": fila.get("fecha") or hoy,
                "tiempo_segundos": tiempo_db,
                "primer_ingreso": fila.get("primer_ingreso"),
                "ultimo_latido": fila.get("ultimo_latido"),
                "segundos_desde_medianoche": max_seg_hoy
            })
        else:
            # Crear nueva fila para hoy iniciando en 0 segundos (conteo desde las 00:00 hasta las 24:00)
            nuevo = {
                "usuario": usuario,
                "fecha": hoy,
                "tiempo_segundos": 0,
                "primer_ingreso": now_iso,
                "ultimo_latido": now_iso
            }
            try:
                supabase.table('actividad_usuarios').insert(nuevo).execute()
            except Exception:
                pass
            return jsonify({
                "success": True,
                "usuario": usuario,
                "fecha": hoy,
                "tiempo_segundos": 0,
                "primer_ingreso": now_iso,
                "ultimo_latido": now_iso,
                "segundos_desde_medianoche": max_seg_hoy
            })
    except Exception as e:
        return jsonify({
            "success": True,
            "usuario": usuario,
            "fecha": hoy,
            "tiempo_segundos": 0,
            "offline": True,
            "error": str(e)
        })

# Endpoint: Latido en vivo para registrar tiempo de actividad en Supabase (reinicio estricto a las 00:00)
@app.route('/api/actividad/latido', methods=['POST'])
def actividad_latido():
    try:
        data = request.get_json(silent=True) or {}
        usuario = (data.get('usuario') or 'admin').strip()
        fecha_cliente = (data.get('fecha') or '').strip()
        cierre_dia = bool(data.get('cierre_dia', False))
        tiempo_total_hoy = data.get('tiempo_total_hoy')
        try:
            segundos_delta = max(0, min(60, int(data.get('segundos_delta', 15))))
        except (ValueError, TypeError):
            segundos_delta = 15

        hoy = obtener_fecha_hoy_peru()
        now_iso = obtener_iso_ahora_peru()
        max_seg_hoy = segundos_transcurridos_desde_medianoche()

        SUPABASE_URL = os.environ.get("SUPABASE_URL")
        SUPABASE_KEY = os.environ.get("SUPABASE_KEY")
        if not SUPABASE_URL or not SUPABASE_KEY:
            return jsonify({"success": True, "offline": True, "fecha": hoy})

        try:
            from backend.datos_supabase import obtener_cliente_supabase
            supabase = obtener_cliente_supabase()

            # CASO 1: El cliente reporta el cierre exacto a las 24:00 (00:00) del día que acaba de terminar
            if cierre_dia and fecha_cliente and fecha_cliente != hoy:
                res_ayer = supabase.table('actividad_usuarios').select('id, tiempo_segundos').eq('usuario', usuario).eq('fecha', fecha_cliente).execute()
                if res_ayer.data and len(res_ayer.data) > 0:
                    id_ayer = res_ayer.data[0]['id']
                    actual_ayer = int(res_ayer.data[0].get('tiempo_segundos', 0) or 0)
                    final_ayer = actual_ayer + segundos_delta
                    if tiempo_total_hoy is not None:
                        try:
                            final_ayer = max(final_ayer, int(tiempo_total_hoy))
                        except (ValueError, TypeError):
                            pass
                    final_ayer = min(86400, final_ayer)
                    supabase.table('actividad_usuarios').update({
                        "tiempo_segundos": final_ayer,
                        "ultimo_latido": f"{fecha_cliente}T23:59:59-05:00"
                    }).eq('id', id_ayer).execute()

                # Garantizar que la fila del nuevo día (hoy, desde las 00:00) inicie en 0 segundos
                res_hoy = supabase.table('actividad_usuarios').select('id, tiempo_segundos').eq('usuario', usuario).eq('fecha', hoy).execute()
                if not res_hoy.data:
                    supabase.table('actividad_usuarios').insert({
                        "usuario": usuario,
                        "fecha": hoy,
                        "tiempo_segundos": 0,
                        "primer_ingreso": f"{hoy}T00:00:00-05:00",
                        "ultimo_latido": now_iso
                    }).execute()
                return jsonify({
                    "success": True,
                    "fecha": hoy,
                    "tiempo_segundos": 0,
                    "reset_medianoche": True
                })

            # CASO 2: El cliente mandó un latido con fecha del día anterior (ej. pestaña que despertó tras cruzar las 00:00)
            # NUNCA arrastrar tiempo_total_hoy del día anterior al nuevo día.
            es_cambio_dia_no_sincronizado = bool(fecha_cliente and fecha_cliente != hoy)

            # Buscar si ya existe fila para el día calendario actual (hoy: 00:00 a 24:00)
            res = supabase.table('actividad_usuarios').select('id, tiempo_segundos').eq('usuario', usuario).eq('fecha', hoy).execute()
            if res.data and len(res.data) > 0:
                reg_id = res.data[0]['id']
                actual = max(0, int(res.data[0].get('tiempo_segundos', 0) or 0))
                nuevo_tiempo = actual + segundos_delta

                # Solo aceptar sincronización de tiempo_total_hoy si pertenece al mismo día hoy y no excede el tiempo desde las 00:00
                if not es_cambio_dia_no_sincronizado and tiempo_total_hoy is not None:
                    try:
                        t_cliente = int(tiempo_total_hoy)
                        if actual <= t_cliente <= actual + 45:
                            nuevo_tiempo = t_cliente
                    except (ValueError, TypeError):
                        pass

                # Tope físico: nunca puede superar los segundos transcurridos desde las 00:00 de hoy
                nuevo_tiempo = min(86400, min(nuevo_tiempo, max_seg_hoy + 15))

                supabase.table('actividad_usuarios').update({
                    "tiempo_segundos": nuevo_tiempo,
                    "ultimo_latido": now_iso
                }).eq('id', reg_id).execute()

                return jsonify({
                    "success": True,
                    "fecha": hoy,
                    "tiempo_segundos": nuevo_tiempo,
                    "reset_medianoche": es_cambio_dia_no_sincronizado
                })
            else:
                # Nuevo registro para hoy (empieza desde 0 + el delta del latido actual, jamás arrastrando el total de ayer)
                init_tiempo = min(segundos_delta, max_seg_hoy + 5)
                nuevo = {
                    "usuario": usuario,
                    "fecha": hoy,
                    "tiempo_segundos": init_tiempo,
                    "primer_ingreso": now_iso,
                    "ultimo_latido": now_iso
                }
                supabase.table('actividad_usuarios').insert(nuevo).execute()
                return jsonify({
                    "success": True,
                    "fecha": hoy,
                    "tiempo_segundos": init_tiempo,
                    "reset_medianoche": True
                })
        except Exception as e:
            return jsonify({"success": True, "offline": True, "error": str(e), "fecha": hoy})
    except Exception as e:
        return jsonify({"success": True, "offline": True, "error": str(e)})


def _normalizar_registro_historial_peru(fila):
    """
    Asegura que primer_ingreso y ultimo_latido tengan zona horaria de Perú (-05:00)
    y correspondan coherentemente al día 00:00 - 24:00 de la fila.
    """
    if not isinstance(fila, dict):
        return fila
    copia = dict(fila)
    fecha_str = str(copia.get("fecha") or "")
    tiempo_seg = max(0, int(copia.get("tiempo_segundos") or 0))

    def _a_dt_peru(val):
        if not val:
            return None
        try:
            s = str(val).strip().replace('Z', '+00:00')
            dt = datetime.fromisoformat(s)
            if dt.tzinfo is None:
                dt = dt.replace(tzinfo=timezone.utc)
            return dt.astimezone(ZONA_HORARIA_PERU)
        except Exception:
            return None

    dt_primer = _a_dt_peru(copia.get("primer_ingreso"))
    dt_ultimo = _a_dt_peru(copia.get("ultimo_latido"))

    if fecha_str:
        try:
            inicio_dia = datetime.fromisoformat(f"{fecha_str}T00:00:00-05:00")
            fin_dia = datetime.fromisoformat(f"{fecha_str}T23:59:59-05:00")
            if dt_ultimo:
                if dt_ultimo < inicio_dia:
                    dt_ultimo = min(fin_dia, inicio_dia + timedelta(seconds=tiempo_seg))
                elif dt_ultimo > fin_dia:
                    dt_ultimo = fin_dia
            if dt_primer:
                # Si por el antiguo corte UTC (7pm) el primer_ingreso quedó en el día anterior o después del último latido
                if dt_primer < inicio_dia or (dt_ultimo and dt_primer > dt_ultimo):
                    if dt_ultimo:
                        dt_primer = max(inicio_dia, dt_ultimo - timedelta(seconds=max(tiempo_seg, 60)))
                    else:
                        dt_primer = inicio_dia
        except Exception:
            pass

    if dt_primer:
        copia["primer_ingreso"] = dt_primer.isoformat()
    if dt_ultimo:
        copia["ultimo_latido"] = dt_ultimo.isoformat()
    return copia


@app.route('/api/actividad/historial', methods=['GET'])
def actividad_historial():
    usuario = request.args.get('usuario')
    solicitante = request.args.get('solicitante', '').strip()
    if not usuario:
        return jsonify({"success": False, "message": "Falta parámetro usuario."}), 400

    # Si se consulta el historial de otro usuario, se exige rol SuperAdmin
    if solicitante and solicitante.lower() != usuario.lower():
        if not es_superadmin(solicitante):
            return jsonify({
                "success": False,
                "message": "Permiso denegado. Solo un Super Administrador puede ver el historial de otros usuarios."
            }), 403

    hoy = obtener_fecha_hoy_peru()
    SUPABASE_URL = os.environ.get("SUPABASE_URL")
    SUPABASE_KEY = os.environ.get("SUPABASE_KEY")
    if not SUPABASE_URL or not SUPABASE_KEY:
        return jsonify({"success": True, "historial": [], "fecha_hoy": hoy})

    try:
        from backend.datos_supabase import obtener_cliente_supabase
        supabase = obtener_cliente_supabase()
        res = supabase.table('actividad_usuarios').select('*').eq('usuario', usuario).order('fecha', desc=True).limit(30).execute()
        filas = [_normalizar_registro_historial_peru(f) for f in (res.data or [])]
        return jsonify({"success": True, "historial": filas, "fecha_hoy": hoy})
    except Exception as e:
        return jsonify({"success": True, "historial": [], "fecha_hoy": hoy, "error": str(e)})

# Subproyecto 2: API Endpoint para predecir ventas con Red Neuronal
@app.route('/api/predecir_ventas', methods=['POST'])
def predecir_ventas():
    try:
        ruta_json = os.path.join(os.path.dirname(os.path.abspath(__file__)), "data", "resumen_100k_supabase.json")
        stats = None
        if os.path.exists(ruta_json):
            try:
                import json
                with open(ruta_json, "r", encoding="utf-8") as f:
                    stats = json.load(f)["estadisticas"]
            except Exception:
                pass

        if stats is None:
            ruta_json_root = os.path.join(os.path.dirname(os.path.abspath(__file__)), "resumen_100k_supabase.json")
            if os.path.exists(ruta_json_root):
                try:
                    import json
                    with open(ruta_json_root, "r", encoding="utf-8") as f:
                        stats = json.load(f)["estadisticas"]
                except Exception:
                    pass

        if stats is None:
            from backend.datos_supabase import obtener_transacciones
            from backend.estadistica_service import calcular_estadisticas
            df = obtener_transacciones()
            stats = calcular_estadisticas(df)

        # Usar el modelo entrenado con los datos reales
        from backend.ventas_extra import _obtener_modelo
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

# ==============================================================================
# ENDPOINTS: SEGURIDAD Y LLAVE DE ACCESO PERSONAL (SUPER ADMIN)
# ==============================================================================
from backend.seguridad_acceso import (
    obtener_clave_acceso,
    validar_clave_acceso,
    es_superadmin,
    modificar_clave_acceso,
    generar_clave_aleatoria
)

@app.route('/api/seguridad/validar_key', methods=['POST'])
def api_validar_key():
    data = request.json or {}
    key_input = data.get('key', '')
    es_valida = validar_clave_acceso(key_input)
    if es_valida:
        return jsonify({
            "success": True,
            "desbloqueado": True,
            "message": "Acceso de personal autorizado desbloqueado correctamente."
        })
    else:
        return jsonify({
            "success": False,
            "desbloqueado": False,
            "message": "Clave de acceso incorrecta o no válida."
        }), 401

@app.route('/api/seguridad/info_clave', methods=['GET'])
def api_info_clave():
    usuario = request.args.get('usuario', '').strip()
    if not es_superadmin(usuario):
        return jsonify({
            "success": False,
            "message": "Acceso restringido. Solo el Super Administrador puede visualizar la llave secreta.",
            "es_superadmin": False,
            "rol": "Analista"
        }), 403

    clave_actual = obtener_clave_acceso()
    return jsonify({
        "success": True,
        "clave_actual": clave_actual,
        "es_superadmin": True,
        "rol": "SuperAdmin"
    })

@app.route('/api/seguridad/modificar_clave', methods=['POST'])
def api_modificar_clave():
    data = request.json or {}
    usuario = data.get('usuario', '').strip()
    password = data.get('password', '')
    nueva_clave = data.get('nueva_clave', '').strip()

    resultado = modificar_clave_acceso(usuario, password, nueva_clave)
    status_code = 200 if resultado.get('success') else 400
    return jsonify(resultado), status_code

@app.route('/api/seguridad/generar_clave_random', methods=['GET'])
def api_generar_clave_random():
    usuario = request.args.get('usuario', '').strip()
    if not es_superadmin(usuario):
        return jsonify({"success": False, "message": "No autorizado."}), 403
    return jsonify({
        "success": True,
        "clave_sugerida": generar_clave_aleatoria(6)
    })

if __name__ == '__main__':
    print("Iniciando servidor unificado (Web + IA)...")
    app.run(debug=True, port=5000)
