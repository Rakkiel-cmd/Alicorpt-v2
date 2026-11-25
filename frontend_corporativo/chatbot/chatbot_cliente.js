/* Chatbot de clientes: tienda virtual, portal institucional y contacto. */
(function () {
    'use strict';
    if (window.__achCliente) return; window.__achCliente = true;
    function montar() {
        AlicorpChat.crear({
            rol: 'cliente',
            endpoint: '/api/chatbot/cliente',
            titulo: 'Asistente Alicorp',
            subtitulo: 'Te ayudo con productos y pedidos',
            avatar: '🛒',
            lanzadorTexto: '¿Te ayudo?',
            placeholder: 'Escribe un producto o tu duda…',
            voz: true,
            bienvenida: '¡Hola! 👋 Soy el asistente de la tienda. Puedo decirte si hay stock de un producto, su precio, llevarte a él, ayudarte con tu pedido o enviarte al formulario de quejas y sugerencias. Toca una opción o escríbeme.\n\nPuedes pulsar 🔊 en cualquier respuesta para escucharla, o activar la lectura automática arriba.',
            chips: ['¿Hay aceite en stock?', 'Ver productos recomendados', '¿Cómo compro?', 'Consultar mi pedido', 'Tengo una queja', 'Quiero dar una sugerencia'],
            recordarAbierto: false
        });
    }
    if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', montar); else montar();
})();
