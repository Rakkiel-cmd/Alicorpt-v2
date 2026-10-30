"""
administracion_usuarios.py - Fase 2: Roles y Suspension (panel de Administracion).

- POST /api/superadmin/ascender_rol    { usuario, objetivo }
      Asciende a un colaborador de 'analista' a 'superadmin'. Nunca desciende
      (una vez superadmin, no se puede bajar desde aqui, solo suspender).

- POST /api/superadmin/suspender       { usuario, objetivo, motivo }
      Suspende a un colaborador (independiente de su rol). No permite
      autosuspenderse, y no permite dejar el sistema sin ningun superadmin
      activo (la misma regla que usan GitHub/Google: nunca te quedas sin
      administradores).

- POST /api/superadmin/reactivar       { usuario, objetivo }
      Reactiva a un colaborador suspendido.

- GET  /api/superadmin/auditoria       ?usuario=<quien_pregunta>
      Lista las acciones administrativas registradas (ascensos, suspensiones,
      reactivaciones), para mostrarse en la pestaña Incidencias > Colaboradores.

Todas las acciones de escritura exigen que 'usuario' (quien pregunta) sea
Super Administrador y quedan registradas en auditoria_usuarios con quien la
hizo, a quien afecto y el motivo (si se dio uno).
"""
from flask import Blueprint, jsonify, request

from backend.datos_supabase import obtener_cliente_supabase
from backend.seguridad_acceso import es_superadmin, obtener_perfil_usuario

administracion_usuarios = Blueprint('administracion_usuarios', __name__)


def _validar_superadmin():
    usuario = request.args.get('usuario') or (request.get_json(silent=True) or {}).get('usuario')
    if not es_superadmin(usuario):
        return False, (jsonify({
            'success': False,
            'message': 'Permiso denegado. Esta accion es exclusiva de Super Administradores.',
        }), 403)
    return True, usuario


def _registrar_auditoria(supabase, usuario_afectado, accion, realizado_por, motivo=None):
    try:
        supabase.table('auditoria_usuarios').insert({
            'usuario_afectado': usuario_afectado,
            'accion': accion,
            'realizado_por': realizado_por,
            'motivo': motivo,
        }).execute()
    except Exception as e:
        # La auditoria no debe tumbar la accion principal si falla al escribirse;
        # solo se deja constancia en el log del servidor.
        print(f"[Administracion] No se pudo registrar auditoria: {e}")


def _contar_superadmins_activos(supabase):
    res = supabase.table('administradores').select('usuario').eq('rol', 'superadmin').eq('estado', 'activo').execute()
    return len(res.data or [])


@administracion_usuarios.route('/api/superadmin/ascender_rol', methods=['POST'])
def ascender_rol():
    ok, resultado = _validar_superadmin()
    if not ok:
        return resultado
    solicitante = resultado

    datos = request.get_json(silent=True) or {}
    objetivo = datos.get('objetivo')
    if not objetivo:
        return jsonify({'success': False, 'message': "Falta el parametro 'objetivo'."}), 400

    try:
        supabase = obtener_cliente_supabase()
        perfil = obtener_perfil_usuario(objetivo)
        if perfil is None:
            return jsonify({'success': False, 'message': 'Usuario no encontrado.'}), 404
        if perfil['rol'] == 'superadmin':
            return jsonify({'success': False, 'message': 'Ese colaborador ya es Super Administrador.'}), 400

        supabase.table('administradores').update({'rol': 'superadmin'}).eq('usuario', objetivo).execute()
        _registrar_auditoria(supabase, objetivo, 'ascenso_rol', solicitante,
                              motivo='Ascendido de Analista a Super Administrador')

        return jsonify({'success': True, 'message': f'{objetivo} ahora es Super Administrador.'})
    except Exception as e:
        import traceback
        traceback.print_exc()
        return jsonify({'success': False, 'message': str(e)}), 500


@administracion_usuarios.route('/api/superadmin/suspender', methods=['POST'])
def suspender_usuario():
    ok, resultado = _validar_superadmin()
    if not ok:
        return resultado
    solicitante = resultado

    datos = request.get_json(silent=True) or {}
    objetivo = datos.get('objetivo')
    motivo = (datos.get('motivo') or '').strip() or None
    if not objetivo:
        return jsonify({'success': False, 'message': "Falta el parametro 'objetivo'."}), 400

    if str(objetivo).strip() == str(solicitante).strip():
        return jsonify({'success': False, 'message': 'No puedes suspenderte a ti mismo.'}), 400

    try:
        supabase = obtener_cliente_supabase()
        perfil = obtener_perfil_usuario(objetivo)
        if perfil is None:
            return jsonify({'success': False, 'message': 'Usuario no encontrado.'}), 404
        if perfil['estado'] == 'suspendido':
            return jsonify({'success': False, 'message': 'Ese colaborador ya esta suspendido.'}), 400

        # Regla de seguridad: nunca dejar el sistema sin NINGUN superadmin activo.
        if perfil['rol'] == 'superadmin' and _contar_superadmins_activos(supabase) <= 1:
            return jsonify({
                'success': False,
                'message': 'No se puede suspender: es el unico Super Administrador activo. '
                           'Asciende a otro colaborador antes de suspender a este.',
            }), 400

        supabase.table('administradores').update({'estado': 'suspendido'}).eq('usuario', objetivo).execute()
        _registrar_auditoria(supabase, objetivo, 'suspension', solicitante, motivo=motivo)

        return jsonify({'success': True, 'message': f'{objetivo} fue suspendido.'})
    except Exception as e:
        import traceback
        traceback.print_exc()
        return jsonify({'success': False, 'message': str(e)}), 500


@administracion_usuarios.route('/api/superadmin/reactivar', methods=['POST'])
def reactivar_usuario():
    ok, resultado = _validar_superadmin()
    if not ok:
        return resultado
    solicitante = resultado

    datos = request.get_json(silent=True) or {}
    objetivo = datos.get('objetivo')
    if not objetivo:
        return jsonify({'success': False, 'message': "Falta el parametro 'objetivo'."}), 400

    try:
        supabase = obtener_cliente_supabase()
        perfil = obtener_perfil_usuario(objetivo)
        if perfil is None:
            return jsonify({'success': False, 'message': 'Usuario no encontrado.'}), 404
        if perfil['estado'] == 'activo':
            return jsonify({'success': False, 'message': 'Ese colaborador ya esta activo.'}), 400

        supabase.table('administradores').update({'estado': 'activo'}).eq('usuario', objetivo).execute()
        _registrar_auditoria(supabase, objetivo, 'reactivacion', solicitante)

        return jsonify({'success': True, 'message': f'{objetivo} fue reactivado.'})
    except Exception as e:
        import traceback
        traceback.print_exc()
        return jsonify({'success': False, 'message': str(e)}), 500


@administracion_usuarios.route('/api/superadmin/auditoria', methods=['GET'])
def listar_auditoria():
    ok, resultado = _validar_superadmin()
    if not ok:
        return resultado

    try:
        supabase = obtener_cliente_supabase()
        res = supabase.table('auditoria_usuarios').select('*').order('fecha', desc=True).limit(200).execute()
        return jsonify({'success': True, 'eventos': res.data or []})
    except Exception as e:
        import traceback
        traceback.print_exc()
        return jsonify({'success': False, 'message': str(e)}), 500
