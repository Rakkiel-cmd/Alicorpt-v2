# Administración: actividad del equipo, auditoría y PDF

Funciona sobre el backend Java (esquema 3FN). Solo Super Admin.

## Qué incluye (pestaña Administración)
- **Colaboradores:** tarjetas de resumen que filtran (Total, Super Admin, Analistas, Suspendidos, Conectados), buscador,
  estado con punto de color, última actividad y menú "Acciones" por fila. Lo bloqueado explica el motivo.
  Seguridad Super Admin queda como monitor en vivo de solo lectura.
- **Actividad del equipo** (panel desplegable): tiempo activo por colaborador, horas de mayor conexión,
  acciones por Super Admin, atención de tickets y proyección del próximo mes
  (regresión lineal sobre las últimas 12 semanas, con R² y confianza; con menos de 4 semanas dice "Datos insuficientes").
- **Auditoría única** con filtros (fecha, acción, colaborador, texto), paginación en la base de datos y PDF.
- **PDF** (jsPDF, se descarga solo al exportar): auditoría, historial de actividad de un colaborador y lista de colaboradores.
  No incluyen vectores biométricos, claves ni la llave de acceso.

## Endpoints nuevos
| Ruta | Para qué |
|---|---|
| `GET /api/superadmin/equipo?periodo=mes\|anio&ref=AAAA-MM\|AAAA` | Dashboard de actividad (caché 60 s) |
| `GET /api/superadmin/auditoria_filtrada?desde&hasta&accion&colaborador&q&pagina&por_pagina` | Auditoría filtrada y paginada |
| `GET /api/superadmin/usuarios` | Ahora agrega `ultima_actividad`, `ultimo_latido` (solo de hoy) y `suspension` |

## Registro de atención de tickets
- `POST /api/incidencias/cambiar_estado` y `POST /api/fallas_web/cambiar_estado` ahora guardan quién atendió y cuándo
  (columnas `id_admin_atendio`, `fecha_atencion`, `fecha_resolucion` y la tabla `HISTORIAL_ATENCION`).
- La migración **se aplica sola** la primera vez que se usa (`ServicioActividadEquipo.asegurarEsquemaAtencion`).
  Si el usuario de la base de datos no tiene permisos, ejecuta `database/atencion_tickets_3fn.sql` con un administrador.
- Desde ese día se acumula historial; lo anterior no se puede reconstruir.
- El motivo de una suspensión ahora se guarda en la auditoría, y los cambios de rol a Analista y de correo también quedan auditados.

## Archivos
- Nuevos: `application/equipo/AnalisisEquipo.java`, `application/equipo/ServicioActividadEquipo.java`,
  `database/atencion_tickets_3fn.sql`.
- Editados: `AlicorpBiApplication.java` (rutas), `TiendaAndSupportEndpointsRegistrar.java` (registro de atención),
  `ServicioSeguridadRelacional.java` (motivo de suspensión) y `frontend_corporativo/dashboard.html`.

## Rendimiento
Sin refresco automático del panel nuevo; consultas con columnas mínimas y rango de fechas; auditoría paginada en la base;
Administración se refresca cada 30 s en lugar de 5 s.
