"""
superadmin_panel.py - Tablero del Super Admin: estado en vivo de usuarios.

- GET /api/superadmin/usuarios?usuario=<quien_pregunta>
      Lista todos los administradores con su estado (Conectado/Desconectado),
      tiempo acumulado de hoy y si tienen biometria registrada. A proposito
      NO envia el vector de 128D de nadie aqui; solo manda un booleano
      'tiene_biometria', para no transferir datos de mas al navegador cada
      vez que se refresca la tabla.

- GET /api/superadmin/vector?usuario=<quien_pregunta>&objetivo=<usuario_a_ver>
      Devuelve el vector de 128D de UN solo usuario puntual. Se pide bajo
      demanda, solo cuando el Super Admin hace clic en "Ver vector" de esa
      persona en la tabla.

Ambos endpoints exigen que 'usuario' (quien pregunta) sea Super Administrador,
reutilizando la misma validacion que ya usa el resto del sistema de seguridad
(backend/seguridad_acceso.py), para no duplicar esa logica.
"""
from datetime import date, datetime, timezone, timedelta

from flask import Blueprint, jsonify, request

from backend.datos_supabase import obtener_cliente_supabase
from backend.seguridad_acceso import es_superadmin

superadmin_panel = Blueprint('superadmin_panel', __name__)

SEGUNDOS_PARA_DESCONECTADO = 60
ZONA_HORARIA_PERU = timezone(timedelta(hours=-5))


def _segundos_desde(iso_timestamp):
    """Segundos transcurridos desde un timestamp ISO (UTC) hasta ahora. None si no hay dato."""
    if not iso_timestamp:
        return None
    try:
        momento = datetime.fromisoformat(str(iso_timestamp).replace('Z', '+00:00'))
    except ValueError:
        return None
    if momento.tzinfo is None:
        momento = momento.replace(tzinfo=timezone.utc)
    return (datetime.now(timezone.utc) - momento).total_seconds()


def _validar_superadmin():
    """Verifica que 'usuario' sea Super Admin. Devuelve (ok, usuario) o (False, respuesta_error)."""
    usuario = request.args.get('usuario')
    if not es_superadmin(usuario):
        return False, (jsonify({
            'success': False,
            'message': 'Permiso denegado. Este panel es exclusivo de Super Administradores.',
        }), 403)
    return True, usuario


@superadmin_panel.route('/api/superadmin/usuarios', methods=['GET'])
def listar_usuarios_con_estado():
    ok, resultado = _validar_superadmin()
    if not ok:
        return resultado

    try:
        supabase = obtener_cliente_supabase()
        hoy = datetime.now(ZONA_HORARIA_PERU).date().isoformat()

        res_admins = supabase.table('administradores') \
            .select('usuario, nombre_completo, face_encoding').execute()
        administradores = res_admins.data or []

        res_actividad = supabase.table('actividad_usuarios') \
            .select('usuario, tiempo_segundos, primer_ingreso, ultimo_latido') \
            .eq('fecha', hoy).execute()
        actividad_por_usuario = {fila['usuario']: fila for fila in (res_actividad.data or [])}

        lista = []
        for admin in administradores:
            nombre_usuario = admin.get('usuario')
            fila_actividad = actividad_por_usuario.get(nombre_usuario)

            segundos_inactivo = _segundos_desde(fila_actividad['ultimo_latido']) if fila_actividad else None
            conectado = segundos_inactivo is not None and segundos_inactivo <= SEGUNDOS_PARA_DESCONECTADO

            vector = admin.get('face_encoding')
            tiene_biometria = isinstance(vector, list) and len(vector) == 128
            # OJO: 'vector' nunca se agrega al diccionario de abajo. Solo viaja
            # el booleano 'tiene_biometria'; el vector completo se pide aparte
            # en /api/superadmin/vector, uno a la vez.

            es_super = es_superadmin(nombre_usuario)

            lista.append({
                'usuario': nombre_usuario,
                'nombre_completo': admin.get('nombre_completo') or nombre_usuario,
                'conectado': conectado,
                'tiempo_segundos_hoy': int(fila_actividad['tiempo_segundos']) if fila_actividad else 0,
                'primer_ingreso_hoy': fila_actividad.get('primer_ingreso') if fila_actividad else None,
                'ultimo_latido': fila_actividad.get('ultimo_latido') if fila_actividad else None,
                'tiene_biometria': tiene_biometria,
                'es_superadmin': es_super,
                'rol': 'SuperAdmin' if es_super else 'Analista',
            })

        # Conectados primero, y dentro de cada grupo, orden alfabetico por nombre.
        lista.sort(key=lambda u: (not u['conectado'], (u['nombre_completo'] or '').lower()))

        return jsonify({
            'success': True,
            'total': len(lista),
            'conectados': sum(1 for u in lista if u['conectado']),
            'usuarios': lista,
        })
    except Exception as e:
        import traceback
        traceback.print_exc()
        return jsonify({'success': False, 'message': str(e)}), 500


@superadmin_panel.route('/api/superadmin/vector', methods=['GET'])
def obtener_vector_usuario():
    ok, resultado = _validar_superadmin()
    if not ok:
        return resultado

    objetivo = request.args.get('objetivo')
    if not objetivo:
        return jsonify({'success': False, 'message': "Falta el parametro 'objetivo'."}), 400

    try:
        supabase = obtener_cliente_supabase()
        res = supabase.table('administradores') \
            .select('usuario, nombre_completo, face_encoding') \
            .eq('usuario', objetivo).execute()

        if not res.data:
            return jsonify({'success': False, 'message': 'Usuario no encontrado.'}), 404

        fila = res.data[0]
        vector = fila.get('face_encoding')
        if not isinstance(vector, list) or len(vector) != 128:
            return jsonify({'success': False, 'message': 'Este usuario no tiene biometria registrada.'}), 404

        return jsonify({
            'success': True,
            'usuario': fila.get('usuario'),
            'nombre_completo': fila.get('nombre_completo') or fila.get('usuario'),
            'face_encoding': vector,
        })
    except Exception as e:
        import traceback
        traceback.print_exc()
        return jsonify({'success': False, 'message': str(e)}), 500
