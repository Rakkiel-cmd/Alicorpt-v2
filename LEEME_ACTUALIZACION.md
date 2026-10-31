# Alicorpt-v2 — actualización de Administración y Super Admin

## Archivos incluidos

- `server.py`
- `backend/seguridad_acceso.py`
- `backend/administracion_usuarios.py`
- `backend/superadmin_panel.py`
- `frontend_corporativo/dashboard.html`
- `database/ampliar_administracion.sql`

## Cambios

1. La sesión Flask es la identidad usada para autorizar Administración y Seguridad Super Admin; no se confía en el usuario enviado por JSON o query string.
2. La interfaz oculta las pestañas administrativas a Analistas y `mostrarVista()` impide abrirlas por hash o navegación directa. El arranque espera la validación de perfil antes de seleccionar la vista inicial.
3. Los endpoints del panel, vectores, auditoría, gestión de colaboradores y solicitudes de llave verifican que la sesión pertenezca a un Super Admin activo.
4. Se incorpora `POST /api/superadmin/degradar_rol` y el botón **Bajar a Analista** en Administración.
5. Se impide cambiar el propio rol, suspenderse a sí mismo y degradar/suspender al último Super Admin activo.
6. El SQL sincroniza automáticamente `configuracion_seguridad.superadmin_usuarios` con las cuentas `administradores` que tengan `rol = superadmin` y `estado = activo`. La sincronización se ejecuta en la misma transacción del cambio de rol/estado mediante triggers.
7. Se agrega `degradacion_rol` a las acciones permitidas de `auditoria_usuarios`.
8. Al suspender un Super Admin se retira de la lista heredada; al reactivarlo se incorpora otra vez si conserva el rol Super Admin.

## Instalación

1. Haz una copia de seguridad de la base de datos y de los archivos actuales.
2. Ejecuta `database/ampliar_administracion.sql` completo en Supabase SQL Editor.
3. Reemplaza los archivos del proyecto respetando exactamente las rutas indicadas arriba.
4. Reinicia o vuelve a desplegar Flask.
5. Cierra sesión y vuelve a entrar para renovar la sesión y comprobar el rol actualizado.

## Pruebas que deben hacerse en el entorno conectado

- Entrar como Analista: no debe aparecer Administración ni Seguridad Super Admin; abrir `#administracion` manualmente debe redirigir a Inicio.
- Como Analista, llamar a `/api/superadmin/usuarios` y `/api/superadmin/auditoria`: debe responder 403.
- Como Super Admin, ascender un Analista y comprobar que aparece en `configuracion_seguridad.superadmin_usuarios`.
- Bajar ese usuario a Analista y comprobar que desaparece de la lista y se registra `degradacion_rol` en auditoría.
- Suspender un Super Admin y comprobar que desaparece de la lista; reactivarlo y comprobar que vuelve.
- Intentar degradar o suspender al último Super Admin activo: debe rechazarse.

## Nota sobre Cuyi

La autorización actual se determina desde `administradores.rol` y `administradores.estado`. La lista `configuracion_seguridad.superadmin_usuarios` queda como lista sincronizada para compatibilidad con módulos anteriores. El código no contiene a Cuyi como Super Admin predeterminado. Para saber por qué aparece en el entorno real, consulta su fila en `administradores` (rol/estado) y compárala con el valor de `configuracion_seguridad` antes y después de ejecutar la migración.

## Validación realizada aquí

- Compilación sintáctica de los cuatro archivos Python con `py_compile`.
- Comprobación sintáctica de los scripts JavaScript embebidos de `dashboard.html` con `node --check`.
- No se pudo ejecutar una prueba de integración contra Supabase desde este entorno; el SQL debe ejecutarse y probarse en el proyecto conectado.
