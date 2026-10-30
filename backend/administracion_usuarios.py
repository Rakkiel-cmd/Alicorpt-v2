# ==============================================================================
# ADMINISTRACIÓN DE USUARIOS (Solo Super Admin)
# ==============================================================================
# Permite a un Super Administrador:
#   - Ascender un Analista a Super Admin (no reversible desde el panel).
#   - Suspender un colaborador con motivo obligatorio.
#   - Reactivar un colaborador suspendido.
#   - Consultar el historial de auditoría de estas acciones.
# Reglas de protección:
#   - Nadie puede modificarse a sí mismo.
#   - Siempre debe quedar al menos 1 Super Admin activo en el sistema.
# ==============================================================================

import json
from flask import Blueprint, jsonify, request
from backend.datos_supabase import obtener_cliente_supabase
from backend.seguridad_acceso import es_superadmin, SUPERADMINS_POR_DEFECTO

administracion_usuarios = Blueprint('administracion_usuarios', __name__)


def _contar_superadmins_activos(supabase):
    """Cuenta cuántos usuarios activos tienen rol 'superadmin'."""
    try:
        res = (
            supabase.table('administradores')
            .select('usuario, rol, estado')
            .execute()
        )
        activos = set()
        for r in (res.data or []):
            usr = (r.get('usuario') or '').strip().lower()
            est = (r.get('estado') or 'activo').strip().lower()
            rol = (r.get('rol') or 'analista').strip().lower()
            if est == 'activo' and (rol == 'superadmin' or es_superadmin(usr)):
                activos.add(usr)
        return max(len(activos), 1)
    except Exception:
        return len(SUPERADMINS_POR_DEFECTO)


def _registrar_auditoria(supabase, usuario_afectado, accion, realizado_por, motivo=None):
    """Guarda un registro en 'auditoria_usuarios' sin romper la operación si la tabla no existe aún."""
    try:
        supabase.table('auditoria_usuarios').insert({
            'usuario_afectado': usuario_afectado,
            'accion': accion,
            'realizado_por': realizado_por,
            'motivo': motivo,
        }).execute()
    except Exception as e:
        print(f"[AdministracionUsuarios] No se pudo registrar auditoría: {e}")


def _sincronizar_superadmin_config(supabase, objetivo):
    """Sincroniza el nuevo superadmin también en configuracion_seguridad para compatibilidad total."""
    try:
        res = supabase.table('configuracion_seguridad').select('valor').eq('clave', 'superadmin_usuarios').execute()
        lista = list(SUPERADMINS_POR_DEFECTO)
        if res.data and len(res.data) > 0:
            raw_val = res.data[0].get('valor')
            parsed = json.loads(raw_val) if isinstance(raw_val, str) else raw_val
            if isinstance(parsed, list):
                lista = parsed
        if objetivo.lower() not in [str(x).strip().lower() for x in lista]:
            lista.append(objetivo)
            supabase.table('configuracion_seguridad').upsert({
                'clave': 'superadmin_usuarios',
                'valor': json.dumps(lista),
                'actualizado_por': 'sistema_ascenso'
            }, on_conflict='clave').execute()
    except Exception:
        pass


@administracion_usuarios.route('/api/superadmin/ascender_rol', methods=['POST'])
def ascender_rol():
    """
    POST /api/superadmin/ascender_rol
    Body JSON: { "usuario": "<quien_ejecuta>", "objetivo": "<usuario_a_ascender>" }
    Pasa a un usuario de 'analista' a 'superadmin'.
    """
    datos = request.get_json(silent=True) or {}
    solicitante = (datos.get('usuario') or '').strip()
    objetivo = (datos.get('objetivo') or '').strip()

    if not es_superadmin(solicitante):
        return jsonify({'success': False, 'message': 'Acceso restringido a Super Administradores.'}), 403
    if not objetivo:
        return jsonify({'success': False, 'message': 'Falta indicar el usuario objetivo.'}), 400
    if solicitante.lower() == objetivo.lower():
        return jsonify({'success': False, 'message': 'No puedes cambiar tu propio rol.'}), 400

    supabase = obtener_cliente_supabase()
    if not supabase:
        return jsonify({'success': False, 'message': 'Sin conexión a Supabase.'}), 500

    try:
        actual = (
            supabase.table('administradores')
            .select('*')
            .ilike('usuario', objetivo)
            .limit(1)
            .execute()
        )
        if not actual.data:
            return jsonify({'success': False, 'message': 'El usuario objetivo no existe.'}), 404

        fila = actual.data[0]
        nombre_real = fila.get('usuario', objetivo)
        if (fila.get('estado') or 'activo').lower() == 'suspendido':
            return jsonify({'success': False, 'message': 'No se puede ascender a un usuario suspendido.'}), 400
        if (fila.get('rol') or 'analista').lower() == 'superadmin' or es_superadmin(nombre_real):
            return jsonify({'success': False, 'message': 'El usuario ya es Super Administrador.'}), 400

        try:
            supabase.table('administradores').update({'rol': 'superadmin'}).eq('usuario', nombre_real).execute()
        except Exception:
            pass
        _sincronizar_superadmin_config(supabase, nombre_real)
        _registrar_auditoria(supabase, nombre_real, 'ascenso_rol', solicitante, 'Ascenso a Super Administrador')
        return jsonify({'success': True, 'message': f'@{nombre_real} ahora es Super Administrador.'})
    except Exception as e:
        return jsonify({'success': False, 'message': f'Error al ascender rol: {e}'}), 500


@administracion_usuarios.route('/api/superadmin/suspender', methods=['POST'])
def suspender_usuario():
    """
    POST /api/superadmin/suspender
    Body JSON: { "usuario": "<quien_ejecuta>", "objetivo": "<usuario_a_suspender>", "motivo": "<texto>" }
    """
    datos = request.get_json(silent=True) or {}
    solicitante = (datos.get('usuario') or '').strip()
    objetivo = (datos.get('objetivo') or '').strip()
    motivo = (datos.get('motivo') or '').strip()

    if not es_superadmin(solicitante):
        return jsonify({'success': False, 'message': 'Acceso restringido a Super Administradores.'}), 403
    if not objetivo:
        return jsonify({'success': False, 'message': 'Falta indicar el usuario objetivo.'}), 400
    if not motivo or len(motivo) < 4:
        return jsonify({'success': False, 'message': 'Debes indicar un motivo claro (mínimo 4 caracteres).'}), 400
    if solicitante.lower() == objetivo.lower():
        return jsonify({'success': False, 'message': 'No puedes suspender tu propia cuenta.'}), 400

    supabase = obtener_cliente_supabase()
    if not supabase:
        return jsonify({'success': False, 'message': 'Sin conexión a Supabase.'}), 500

    try:
        actual = (
            supabase.table('administradores')
            .select('*')
            .ilike('usuario', objetivo)
            .limit(1)
            .execute()
        )
        if not actual.data:
            return jsonify({'success': False, 'message': 'El usuario objetivo no existe.'}), 404

        fila = actual.data[0]
        nombre_real = fila.get('usuario', objetivo)
        if (fila.get('estado') or 'activo').lower() == 'suspendido':
            return jsonify({'success': False, 'message': 'El usuario ya está suspendido.'}), 400

        # Regla: siempre debe quedar al menos 1 Super Admin activo
        if (fila.get('rol') or 'analista').lower() == 'superadmin' or es_superadmin(nombre_real):
            if _contar_superadmins_activos(supabase) <= 1:
                return jsonify({
                    'success': False,
                    'message': 'No se puede suspender al único Super Administrador activo.'
                }), 400

        supabase.table('administradores').update({'estado': 'suspendido'}).eq('usuario', nombre_real).execute()
        _registrar_auditoria(supabase, nombre_real, 'suspension', solicitante, motivo)
        return jsonify({'success': True, 'message': f'Usuario @{nombre_real} suspendido correctamente.'})
    except Exception as e:
        return jsonify({'success': False, 'message': f'Error al suspender usuario (ejecuta database/ampliar_administracion.sql en Supabase si falta la columna estado): {e}'}), 500


@administracion_usuarios.route('/api/superadmin/reactivar', methods=['POST'])
def reactivar_usuario():
    """
    POST /api/superadmin/reactivar
    Body JSON: { "usuario": "<quien_ejecuta>", "objetivo": "<usuario_a_reactivar>" }
    """
    datos = request.get_json(silent=True) or {}
    solicitante = (datos.get('usuario') or '').strip()
    objetivo = (datos.get('objetivo') or '').strip()

    if not es_superadmin(solicitante):
        return jsonify({'success': False, 'message': 'Acceso restringido a Super Administradores.'}), 403
    if not objetivo:
        return jsonify({'success': False, 'message': 'Falta indicar el usuario objetivo.'}), 400

    supabase = obtener_cliente_supabase()
    if not supabase:
        return jsonify({'success': False, 'message': 'Sin conexión a Supabase.'}), 500

    try:
        actual = (
            supabase.table('administradores')
            .select('*')
            .ilike('usuario', objetivo)
            .limit(1)
            .execute()
        )
        if not actual.data:
            return jsonify({'success': False, 'message': 'El usuario objetivo no existe.'}), 404

        fila = actual.data[0]
        nombre_real = fila.get('usuario', objetivo)
        supabase.table('administradores').update({'estado': 'activo'}).eq('usuario', nombre_real).execute()
        _registrar_auditoria(supabase, nombre_real, 'reactivacion', solicitante, 'Reactivación desde panel Super Admin')
        return jsonify({'success': True, 'message': f'Usuario @{nombre_real} reactivado.'})
    except Exception as e:
        return jsonify({'success': False, 'message': f'Error al reactivar usuario: {e}'}), 500


@administracion_usuarios.route('/api/superadmin/auditoria', methods=['GET'])
def listar_auditoria():
    """
    GET /api/superadmin/auditoria?usuario=<quien_pregunta>
    Devuelve los últimos 50 movimientos de suspensión, reactivación y ascenso.
    """
    solicitante = (request.args.get('usuario') or '').strip()
    if not es_superadmin(solicitante):
        return jsonify({'success': False, 'message': 'Acceso restringido a Super Administradores.'}), 403

    supabase = obtener_cliente_supabase()
    if not supabase:
        return jsonify({'success': False, 'message': 'Sin conexión a Supabase.'}), 500

    try:
        res = (
            supabase.table('auditoria_usuarios')
            .select('id, usuario_afectado, accion, realizado_por, motivo, fecha')
            .order('fecha', desc=True)
            .limit(50)
            .execute()
        )
        return jsonify({'success': True, 'registros': res.data or []})
    except Exception:
        return jsonify({'success': True, 'registros': []})
