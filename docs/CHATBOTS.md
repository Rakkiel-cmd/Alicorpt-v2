# Chatbots Alicorp (no son agentes: responden y consultan, no ejecutan acciones)

| Chatbot | Dónde aparece | Quién | Diseño |
|---|---|---|---|
| Cliente | `tienda.html` | Público | Colorido (rojo→naranja), botón "¿Te ayudo?" |
| Analista | `dashboard.html` | Rol analista | Limpio, sigue modo claro/oscuro del dashboard |
| Administrador | `dashboard.html` | Superadmin activo | "Centro de mando" oscuro/cian |

## Cómo funciona
1. **Motor por reglas (siempre activo, gratis):** `MotorIntenciones` normaliza el texto y detecta la intención; los servicios consultan la base y responden con texto + botones (chips, enlaces, acciones).
2. **IA gratuita opcional (apagada por defecto):** `AsistenteIAOpcional` solo se usa si el motor no entiende. Se activa con `CHATBOT_IA_API_KEY` en `.env` (ver `.env.example`). Límite por usuario/día, caché, pausa de 60 s si el servicio falla; si falla, todo sigue con reglas.

## Backend (`modulos/chatbot_asistente/`)
- `ChatbotController` — `POST /api/chatbot/{cliente|contexto|analista|admin}`, `GET /api/chatbot/analista/pulso`
- `ChatbotClienteService`, `ChatbotAnalistaService`, `ChatbotAdminService`, `ExplicadorDashboard`, `RespuestaChat`

## Frontend (`frontend_corporativo/chatbot/`)
- `chatbot_base.js` (motor de interfaz + voz), `chatbot_cliente.js`, `chatbot_dashboard.js` (elige analista/admin por rol, avisos emergentes), `chatbot.css` (3 temas)

## Detalles útiles
- Voz (cliente): 🔊 junto a cada respuesta lee solo esa; el 🔊 de la cabecera lee todas las nuevas; ⏹ detiene. Usa la voz del navegador (Web Speech API).
- Analista: aviso emergente cada 3/5/10 min (botón de reloj; 0 = apagado) que se desvanece a los 8 s; aviso inmediato si entra un ticket nuevo; 🔔 activa avisos de escritorio cuando la pestaña está en segundo plano.
- "Explícame esta pantalla": el navegador envía las cifras visibles (`.metric-card`, `.hero-banner`); el servidor solo las interpreta, no inventa valores.
- El historial de "resueltos por analista" cuenta desde que existe `HISTORIAL_ATENCION`.
- El chatbot de admin nunca muestra claves, vectores biométricos ni contraseñas.
- Quejas/sugerencias: enlazan a `/contacto_soporte/contacto.html?tipo=queja|sugerencia|duda` (el formulario prellena el asunto).
