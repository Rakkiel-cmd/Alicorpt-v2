"""Administración de colaboradores, auditoría y cambio de llave con doble aprobación."""
import json
import re
from functools import wraps
from threading import RLock
from datetime import datetime, timedelta, timezone
from flask import Blueprint, jsonify, request, session
from backend.datos_supabase import obtener_cliente_supabase
from backend.seguridad_acceso import es_superadmin, verificar_password, guardar_nueva_clave_aprobada

administracion_usuarios = Blueprint('administracion_usuarios', __name__)
_MUTACIONES_LOCK = RLock()

def _serializar_mutacion(func):
    """Serializa cambios sensibles en el proceso Flask (Procfile actual: un worker)."""
    @wraps(func)
    def _envolver(*args, **kwargs):
        with _MUTACIONES_LOCK:
            return func(*args, **kwargs)
    return _envolver


def _admin_requerido(_nombre_cliente=None):
    # Identidad autorizada: sesion Flask o parametro verificado contra Supabase
    nombre = str(session.get('usuario') or '').strip()
    if not nombre and _nombre_cliente:
        nombre = str(_nombre_cliente).strip()
    if not nombre:
        d = request.get_json(silent=True) or {}
        nombre = str(d.get('usuario') or request.args.get('usuario') or '').strip()
    if not nombre or not es_superadmin(nombre):
        return None, (jsonify({'success': False, 'message': 'Acceso restringido a Super Administradores activos.'}), 403)
    session['usuario'] = nombre
    return nombre, None

def _sincronizar_superadmins_config(db):
    try:
        res = db.table('administradores').select('usuario, rol, estado').execute()
        superadmins = [
            r['usuario'] for r in (res.data or [])
            if str(r.get('rol') or '').lower() == 'superadmin' and str(r.get('estado') or '').lower() == 'activo'
        ]
        db.table('configuracion_seguridad').upsert({
            'clave': 'superadmin_usuarios',
            'valor': json.dumps(superadmins),
            'actualizado_por': 'sistema_sync',
            'updated_at': datetime.now(timezone.utc).isoformat()
        }, on_conflict='clave').execute()
    except Exception as e:
        print('[SYNC_SUPERADMINS] Error:', e)


def _auditar(db, afectado, accion, actor, motivo=None):
    try:
        db.table('auditoria_usuarios').insert({'usuario_afectado': afectado, 'accion': accion,
            'realizado_por': actor, 'motivo': motivo}).execute()
    except Exception as e:
        print(f'[AUDITORIA] Aviso: no se pudo insertar {accion} ({e})')


def _buscar_usuario(db, usuario):
    res = db.table('administradores').select('usuario, rol, estado, email').eq('usuario', usuario).limit(1).execute()
    return res.data[0] if res.data else None


def _contar_superadmins_activos(db):
    res = db.table('administradores').select('usuario, rol, estado').execute()
    return sum(1 for r in (res.data or []) if str(r.get('rol') or 'analista').lower() == 'superadmin' and str(r.get('estado') or 'activo').lower() == 'activo')


def _solicitudes_pendientes(db):
    limite = (datetime.now(timezone.utc) - timedelta(hours=24)).isoformat()
    # Las solicitudes vencidas dejan de estar pendientes y no pueden aprobarse.
    vencidas = db.table('solicitudes_cambio_clave').select('id, solicitado_por').eq('estado', 'pendiente').lt('creado_en', limite).execute().data or []
    for solicitud in vencidas:
        actualizado = db.table('solicitudes_cambio_clave').update({'estado': 'rechazado', 'resuelto_en': datetime.now(timezone.utc).isoformat()}).eq('id', solicitud['id']).eq('estado', 'pendiente').select('id').execute()
        if actualizado.data:
            _auditar(db, 'clave_acceso_personal', 'cambio_clave', 'sistema', f"Solicitud {solicitud['id']} vencida; solicitada por {solicitud.get('solicitado_por', '')}")
    return db.table('solicitudes_cambio_clave').select('id, clave_propuesta, solicitado_por, aprobado_por, estado, creado_en, resuelto_en').eq('estado', 'pendiente').order('creado_en', desc=True).execute().data or []


@administracion_usuarios.post('/api/superadmin/ascender_rol')
@_serializar_mutacion
def ascender():
    d = request.get_json(silent=True) or {}
    actor, error = _admin_requerido()
    if error:
        return error
    objetivo = str(d.get('objetivo') or '').strip()
    if not objetivo:
        return jsonify({'success': False, 'message': 'Indica el colaborador.'}), 400
    if actor.casefold() == objetivo.casefold():
        return jsonify({'success': False, 'message': 'No puedes cambiar tu propio rol.'}), 400
    db = obtener_cliente_supabase()
    if not db:
        return jsonify({'success': False, 'message': 'Sin conexión a Supabase.'}), 503
    try:
        fila = _buscar_usuario(db, objetivo)
        if not fila:
            return jsonify({'success': False, 'message': 'Colaborador no encontrado.'}), 404
        if str(fila.get('rol') or 'analista').lower() == 'superadmin':
            return jsonify({'success': False, 'message': 'El colaborador ya es Super Admin.'}), 409
        if str(fila.get('estado') or 'activo').lower() != 'activo':
            return jsonify({'success': False, 'message': 'Reactiva la cuenta antes de ascenderla.'}), 400
        db.table('administradores').update({'rol': 'superadmin'}).eq('usuario', fila['usuario']).execute()
        _sincronizar_superadmins_config(db)
        # El trigger SQL sincroniza configuracion_seguridad.superadmin_usuarios.
        _auditar(db, fila['usuario'], 'ascenso_rol', actor, 'Ascenso a Super Admin')
        return jsonify({'success': True, 'message': f"@{fila['usuario']} ahora es Super Admin."})
    except Exception:
        return jsonify({'success': False, 'message': 'No se pudo ascender. Verifica que la migración SQL esté aplicada.'}), 500


@administracion_usuarios.post('/api/superadmin/degradar_rol')
@_serializar_mutacion
def degradar():
    d = request.get_json(silent=True) or {}
    actor, error = _admin_requerido()
    if error:
        return error
    objetivo = str(d.get('objetivo') or '').strip()
    motivo = str(d.get('motivo') or 'Degradación a Analista desde Administración').strip()
    if not objetivo:
        return jsonify({'success': False, 'message': 'Indica el colaborador.'}), 400
    if actor.casefold() == objetivo.casefold():
        return jsonify({'success': False, 'message': 'No puedes cambiar tu propio rol.'}), 400
    db = obtener_cliente_supabase()
    if not db:
        return jsonify({'success': False, 'message': 'Sin conexión a Supabase.'}), 503
    try:
        fila = _buscar_usuario(db, objetivo)
        if not fila:
            return jsonify({'success': False, 'message': 'Colaborador no encontrado.'}), 404
        if str(fila.get('rol') or 'analista').lower() != 'superadmin':
            return jsonify({'success': False, 'message': 'El colaborador ya tiene rol Analista.'}), 409
        if str(fila.get('estado') or 'activo').lower() == 'activo' and _contar_superadmins_activos(db) <= 1:
            return jsonify({'success': False, 'message': 'No puedes degradar al último Super Admin activo.'}), 400
        db.table('administradores').update({'rol': 'analista'}).eq('usuario', fila['usuario']).execute()
        _sincronizar_superadmins_config(db)
        # El trigger SQL elimina automáticamente al usuario de superadmin_usuarios.
        _auditar(db, fila['usuario'], 'degradacion_rol', actor, motivo or None)
        return jsonify({'success': True, 'message': f"@{fila['usuario']} ahora es Analista."})
    except Exception:
        return jsonify({'success': False, 'message': 'No se pudo cambiar el rol. Verifica la migración SQL.'}), 500


@administracion_usuarios.post('/api/superadmin/suspender')
@_serializar_mutacion
def suspender():
    d = request.get_json(silent=True) or {}; actor, error = _admin_requerido((d.get('usuario') or '').strip())
    if error: return error
    objetivo, motivo = (d.get('objetivo') or '').strip(), (d.get('motivo') or '').strip()
    if not objetivo: return jsonify({'success': False, 'message': 'Indica el colaborador.'}), 400
    if actor.lower() == objetivo.lower(): return jsonify({'success': False, 'message': 'No puedes suspender tu propia cuenta.'}), 400
    db = obtener_cliente_supabase()
    if not db:
        return jsonify({'success': False, 'message': 'Sin conexión a Supabase.'}), 503
    try:
        fila = _buscar_usuario(db, objetivo) if objetivo else None
        if not fila: return jsonify({'success': False, 'message': 'Colaborador no encontrado.'}), 404
        if fila.get('estado') == 'suspendido': return jsonify({'success': False, 'message': 'La cuenta ya está suspendida.'}), 400
        if fila.get('rol') == 'superadmin' and _contar_superadmins_activos(db) <= 1:
            return jsonify({'success': False, 'message': 'No puede quedar el sistema sin un Super Admin activo.'}), 400
        db.table('administradores').update({'estado': 'suspendido'}).eq('usuario', fila['usuario']).execute()
        _sincronizar_superadmins_config(db)
        _auditar(db, fila['usuario'], 'suspension', actor, motivo or None)
        return jsonify({'success': True, 'message': f"@{fila['usuario']} fue suspendido."})
    except Exception as exc: return jsonify({'success': False, 'message': 'No se pudo suspender.'}), 500


@administracion_usuarios.post('/api/superadmin/reactivar')
@_serializar_mutacion
def reactivar():
    d = request.get_json(silent=True) or {}; actor, error = _admin_requerido((d.get('usuario') or '').strip())
    if error: return error
    objetivo = (d.get('objetivo') or '').strip(); db = obtener_cliente_supabase()
    if not db:
        return jsonify({'success': False, 'message': 'Sin conexión a Supabase.'}), 503
    try:
        fila = _buscar_usuario(db, objetivo) if db and objetivo else None
        if not fila: return jsonify({'success': False, 'message': 'Colaborador no encontrado.'}), 404
        db.table('administradores').update({'estado': 'activo'}).eq('usuario', fila['usuario']).execute()
        _sincronizar_superadmins_config(db)
        _auditar(db, fila['usuario'], 'reactivacion', actor, 'Reactivación desde Administración')
        return jsonify({'success': True, 'message': f"@{fila['usuario']} fue reactivado."})
    except Exception as exc: return jsonify({'success': False, 'message': 'No se pudo reactivar.'}), 500


@administracion_usuarios.post('/api/superadmin/actualizar_email')
@_serializar_mutacion
def actualizar_email():
    d = request.get_json(silent=True) or {}; actor, error = _admin_requerido((d.get('usuario') or '').strip())
    if error: return error
    objetivo, email = (d.get('objetivo') or '').strip(), (d.get('email') or '').strip()
    if email and (len(email) > 200 or not re.fullmatch(r"[^\s@]+@[^\s@]+\.[^\s@]+", email)):
        return jsonify({'success': False, 'message': 'El correo no tiene un formato válido.'}), 400
    db = obtener_cliente_supabase()
    if not db:
        return jsonify({'success': False, 'message': 'Sin conexión a Supabase.'}), 503
    try:
        fila = _buscar_usuario(db, objetivo) if objetivo else None
        if not fila: return jsonify({'success': False, 'message': 'Colaborador no encontrado.'}), 404
        db.table('administradores').update({'email': email or None}).eq('usuario', fila['usuario']).execute()
        _auditar(db, fila['usuario'], 'actualizacion_email', actor, 'Correo actualizado' if email else 'Correo eliminado')
        return jsonify({'success': True, 'message': 'Correo actualizado.'})
    except Exception as exc: return jsonify({'success': False, 'message': 'No se pudo actualizar el correo.'}), 500


@administracion_usuarios.get('/api/superadmin/auditoria')
def auditoria():
    actor, error = _admin_requerido(request.args.get('usuario', '').strip())
    if error: return error
    db = obtener_cliente_supabase()
    try:
        res = db.table('auditoria_usuarios').select('id, usuario_afectado, accion, realizado_por, motivo, fecha').order('fecha', desc=True).limit(100).execute()
        return jsonify({'success': True, 'registros': res.data or []})
    except Exception as exc: return jsonify({'success': False, 'message': 'No se pudo consultar auditoría.'}), 500


@administracion_usuarios.post('/api/superadmin/clave/solicitar')
@_serializar_mutacion
def solicitar_clave():
    d = request.get_json(silent=True) or {}; actor, error = _admin_requerido((d.get('usuario') or '').strip())
    if error: return error
    clave, password = str(d.get('clave_propuesta') or d.get('nueva_clave') or '').strip(), d.get('password')
    if not re.fullmatch(r'\d{4,12}', clave): return jsonify({'success': False, 'message': 'La llave debe tener entre 4 y 12 dígitos.'}), 400
    if not verificar_password(actor, password): return jsonify({'success': False, 'message': 'No se pudo verificar la contraseña.'}), 401
    db = obtener_cliente_supabase()
    try:
        if _contar_superadmins_activos(db) < 2: return jsonify({'success': False, 'message': 'Se necesitan al menos dos Super Admin activos.'}), 400
        if _solicitudes_pendientes(db): return jsonify({'success': False, 'message': 'Ya existe una solicitud pendiente.'}), 409
        db.table('solicitudes_cambio_clave').insert({'clave_propuesta': clave, 'solicitado_por': actor, 'estado': 'pendiente'}).execute()
        return jsonify({'success': True, 'message': 'Solicitud creada. Debe aprobarla otro Super Admin.'})
    except Exception as exc:
        try:
            pendientes = db.table('solicitudes_cambio_clave').select('id').eq('estado', 'pendiente').limit(1).execute() if db else None
            if pendientes and pendientes.data:
                return jsonify({'success': False, 'message': 'Ya existe una solicitud pendiente.'}), 409
        except Exception:
            pass
        return jsonify({'success': False, 'message': 'No se pudo crear la solicitud.'}), 500


@administracion_usuarios.get('/api/superadmin/clave/solicitudes')
def listar_solicitudes():
    actor, error = _admin_requerido(request.args.get('usuario', '').strip())
    if error: return error
    db = obtener_cliente_supabase()
    if not db: return jsonify({'success': False, 'message': 'Sin conexión a Supabase.'}), 500
    try: return jsonify({'success': True, 'solicitudes': _solicitudes_pendientes(db)})
    except Exception as exc: return jsonify({'success': False, 'message': 'No se pudieron consultar solicitudes.'}), 500


@administracion_usuarios.post('/api/superadmin/clave/aprobar')
@_serializar_mutacion
def aprobar_clave():
    d = request.get_json(silent=True) or {}; actor, error = _admin_requerido((d.get('usuario') or '').strip())
    if error: return error
    password, sid = d.get('password'), d.get('id')
    if not verificar_password(actor, password): return jsonify({'success': False, 'message': 'No se pudo verificar la contraseña.'}), 401
    db = obtener_cliente_supabase()
    try:
        res = db.table('solicitudes_cambio_clave').select('*').eq('id', sid).eq('estado', 'pendiente').limit(1).execute()
        if not res.data: return jsonify({'success': False, 'message': 'Solicitud inexistente o ya resuelta.'}), 404
        s = res.data[0]
        creado = datetime.fromisoformat(str(s['creado_en']).replace('Z', '+00:00'))
        if creado.tzinfo is None: creado = creado.replace(tzinfo=timezone.utc)
        if datetime.now(timezone.utc) - creado > timedelta(hours=24):
            exp = db.table('solicitudes_cambio_clave').update({'estado': 'rechazado', 'resuelto_en': datetime.now(timezone.utc).isoformat()}).eq('id', sid).eq('estado', 'pendiente').select('id').execute()
            if exp.data:
                _auditar(db, 'clave_acceso_personal', 'cambio_clave', actor, f'Solicitud {sid} vencida al intentar aprobarse')
            return jsonify({'success': False, 'message': 'La solicitud venció a las 24 horas.'}), 400
        if str(s.get('solicitado_por', '')).lower() == actor.lower(): return jsonify({'success': False, 'message': 'Debe aprobarla otro Super Admin distinto.'}), 400
        # Reclamar la solicitud con UPDATE condicional: solo una petición concurrente puede ganar.
        ahora = datetime.now(timezone.utc).isoformat()
        limite_aprobacion = (datetime.now(timezone.utc) - timedelta(hours=24)).isoformat()
        reclamo = db.table('solicitudes_cambio_clave').update({
            'estado': 'aprobado', 'aprobado_por': actor, 'resuelto_en': ahora
        }).eq('id', sid).eq('estado', 'pendiente').gte('creado_en', limite_aprobacion).select('id').execute()
        if not reclamo.data:
            return jsonify({'success': False, 'message': 'La solicitud ya fue resuelta por otra operación.'}), 409
        resultado = guardar_nueva_clave_aprobada(s['clave_propuesta'], actor)
        if not resultado.get('success'):
            # La llave no cambió; liberar la solicitud solo si este aprobador conserva la reclamación.
            db.table('solicitudes_cambio_clave').update({
                'estado': 'pendiente', 'aprobado_por': None, 'resuelto_en': None
            }).eq('id', sid).eq('estado', 'aprobado').eq('aprobado_por', actor).execute()
            return jsonify(resultado), 500
        _auditar(db, 'clave_acceso_personal', 'cambio_clave', actor, f"Solicitud {sid} de {s.get('solicitado_por')} aprobada")
        return jsonify({'success': True, 'message': 'Solicitud aprobada y llave cambiada.'})
    except Exception as exc: return jsonify({'success': False, 'message': 'No se pudo aprobar la solicitud.'}), 500


@administracion_usuarios.post('/api/superadmin/clave/rechazar')
@_serializar_mutacion
def rechazar_clave():
    d = request.get_json(silent=True) or {}; actor, error = _admin_requerido((d.get('usuario') or '').strip())
    if error: return error
    db = obtener_cliente_supabase()
    try:
        res = db.table('solicitudes_cambio_clave').select('id, solicitado_por').eq('id', d.get('id')).eq('estado', 'pendiente').limit(1).execute()
        if not res.data: return jsonify({'success': False, 'message': 'Solicitud inexistente o ya resuelta.'}), 404
        s = res.data[0]
        if str(s.get('solicitado_por', '')).lower() == actor.lower(): return jsonify({'success': False, 'message': 'No puedes resolver tu propia solicitud.'}), 400
        actualizada = db.table('solicitudes_cambio_clave').update({'estado': 'rechazado', 'aprobado_por': actor, 'resuelto_en': datetime.now(timezone.utc).isoformat()}).eq('id', d.get('id')).eq('estado', 'pendiente').select('id').execute()
        if not actualizada.data:
            return jsonify({'success': False, 'message': 'La solicitud ya fue resuelta por otra operación.'}), 409
        _auditar(db, 'clave_acceso_personal', 'cambio_clave', actor, f"Solicitud {d.get('id')} rechazada; solicitada por {s.get('solicitado_por')}")
        return jsonify({'success': True, 'message': 'Solicitud rechazada.'})
    except Exception as exc: return jsonify({'success': False, 'message': 'No se pudo rechazar la solicitud.'}), 500
