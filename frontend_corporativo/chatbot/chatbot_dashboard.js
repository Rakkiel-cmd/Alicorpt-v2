/* Chatbots del dashboard (después del login): decide por rol cuál mostrar.
   - Analista  → asistente de tickets + avisos emergentes periódicos + explicación del dashboard.
   - Superadmin → centro de mando con resúmenes del equipo (solo lectura). */
(function () {
    'use strict';
    if (window.__achDash) return; window.__achDash = true;

    const leer = (k) => { try { return localStorage.getItem(k); } catch (e) { return null; } };
    const guardar = (k, v) => { try { localStorage.setItem(k, v); } catch (e) { /* sin almacenamiento */ } };

    function usuarioActual() {
        try {
            const g = sessionStorage.getItem('usuario_activo') || localStorage.getItem('usuario_activo');
            if (g) { const p = JSON.parse(g); if (p && p.usuario) return p.usuario; }
        } catch (e) { /* JSON inválido */ }
        const u = leer('ultimo_usuario');
        if (u) return u;
        try { if (typeof sesionUsuario !== 'undefined' && sesionUsuario.usuario) return sesionUsuario.usuario; } catch (e) { /* aún no definido */ }
        return '';
    }

    function irAVista(valor) {
        try { if (typeof mostrarVista === 'function') { mostrarVista(valor); return true; } } catch (e) { /* ignorar */ }
        return false;
    }

    // ---------------------------------------------------------------- Cifras visibles (para "explícame esta pantalla")
    function capturarPantalla() {
        const v = document.querySelector('.vista.active');
        const datos = {};
        if (!v) return { vista: '', datos };
        const txt = (n) => (n && n.textContent ? n.textContent.replace(/\s+/g, ' ').trim() : '');
        v.querySelectorAll('.metric-card, .hero-banner').forEach((c) => {
            if (Object.keys(datos).length >= 25) return;
            const etiqueta = txt(c.querySelector('h4')) || txt(c.querySelector('span')) || txt(c.querySelector('small'));
            const valor = txt(c.querySelector('p')) || txt(c.querySelector('h2')) || txt(c.querySelector('strong'));
            if (etiqueta && valor && !(etiqueta in datos)) datos[etiqueta] = valor;
        });
        return { vista: (v.id || '').replace('vista-', ''), datos };
    }

    // ---------------------------------------------------------------- Notificaciones emergentes
    let contenedor = null;
    function toast(texto, titulo, alClick) {
        if (!contenedor) { contenedor = document.createElement('div'); contenedor.className = 'ach-toasts ach-analista'; document.body.appendChild(contenedor); }
        while (contenedor.children.length >= 2) contenedor.firstChild.remove();
        const t = document.createElement('div'); t.className = 'ach-toast'; t.setAttribute('role', 'status');
        t.innerHTML = '<div class="ach-t-ico">' + AlicorpChat.ICONOS.ticket + '</div><div><b>' + AlicorpChat.esc(titulo || 'Asistente de tickets') + '</b><span>' + AlicorpChat.esc(texto) + '</span></div>';
        if (alClick) t.addEventListener('click', () => { alClick(); t.remove(); });
        contenedor.appendChild(t);
        requestAnimationFrame(() => requestAnimationFrame(() => t.classList.add('in')));
        setTimeout(() => { t.classList.add('out'); setTimeout(() => t.remove(), 1300); }, 8000);   // se desvanece solo
    }

    function avisoEscritorio(titulo, texto) {
        if (!('Notification' in window) || Notification.permission !== 'granted') return false;
        try { const n = new Notification(titulo, { body: texto, tag: 'alicorp-tickets' }); setTimeout(() => n.close(), 8000); return true; } catch (e) { return false; }
    }

    // ---------------------------------------------------------------- Analista
    function montarAnalista(ctx, usuario) {
        let chat = null, previoPend = null, ultimoAviso = Date.now();
        let minutos = parseInt(leer('ach_aviso_min') || '5', 10); if (![0, 3, 5, 10].includes(minutos)) minutos = 5;

        chat = AlicorpChat.crear({
            rol: 'analista', endpoint: '/api/chatbot/analista', titulo: 'Asistente de tickets', subtitulo: 'Estado en tiempo real',
            icono: 'ticket', placeholder: 'Pregunta por tickets o resultados…', voz: false, recordarAbierto: true,
            bienvenida: 'Hola' + (ctx.nombre ? ', ' + String(ctx.nombre).split(' ')[0] : '') + '. Te informo cuántos tickets quedan, cuántos has resuelto y te explico los resultados del dashboard. También te aviso de tu avance cada ' + (minutos || 'cierto') + ' min.',
            chips: ctx.chips_analista || [],
            cuerpo: (mensaje) => { const p = capturarPantalla(); return { mensaje, usuario, vista: p.vista, datos: p.datos }; },
            onAccion: (accion, valor) => (accion === 'vista' ? irAVista(valor) : false),
            extraCabecera: (herr) => {
                const reloj = document.createElement('button'); reloj.type = 'button'; reloj.className = 'ach-hbtn';
                const campana = document.createElement('button'); campana.type = 'button'; campana.className = 'ach-hbtn'; campana.textContent = '🔔';
                const pintarReloj = () => { reloj.textContent = minutos ? minutos + 'm' : '⏸'; reloj.style.fontSize = '11px'; reloj.style.fontWeight = '800';
                    reloj.title = minutos ? 'Avance cada ' + minutos + ' min (clic para cambiar)' : 'Avisos periódicos desactivados (clic para activar)'; };
                const pintarCampana = () => { const ok = 'Notification' in window && Notification.permission === 'granted';
                    campana.classList.toggle('activo', ok); campana.title = ok ? 'Avisos de escritorio activados' : 'Activar avisos de escritorio cuando la pestaña esté en segundo plano'; };
                reloj.addEventListener('click', () => {
                    const ciclo = [3, 5, 10, 0]; minutos = ciclo[(ciclo.indexOf(minutos) + 1) % ciclo.length];
                    guardar('ach_aviso_min', String(minutos)); ultimoAviso = Date.now(); pintarReloj();
                    toast(minutos ? 'Te avisaré tu avance cada ' + minutos + ' minutos.' : 'Avisos periódicos desactivados.', 'Asistente de tickets');
                });
                campana.addEventListener('click', () => {
                    if (!('Notification' in window)) { toast('Tu navegador no admite avisos de escritorio.', 'Asistente de tickets'); return; }
                    if (Notification.permission === 'granted') { toast('Los avisos de escritorio ya están activados.', 'Asistente de tickets'); return; }
                    Notification.requestPermission().then(pintarCampana);
                });
                pintarReloj(); pintarCampana(); herr.appendChild(reloj); herr.appendChild(campana);
            }
        });

        async function latir() {
            let d;
            try { const r = await fetch('/api/chatbot/analista/pulso?usuario=' + encodeURIComponent(usuario)); if (!r.ok) return; d = await r.json(); } catch (e) { return; }
            if (!d || d.success === false) return;
            const oculto = document.hidden;
            const mostrar = (texto, titulo) => {
                ultimoAviso = Date.now();
                if (oculto) { avisoEscritorio(titulo, texto); } else { toast(texto, titulo, () => chat.abrir()); }
                chat.avisar();
            };
            if (previoPend !== null && d.pendientes > previoPend) {
                const n = d.pendientes - previoPend;
                mostrar((n === 1 ? 'Entró 1 ticket nuevo.' : 'Entraron ' + n + ' tickets nuevos.') + ' Pendientes: ' + d.pendientes + '.', 'Ticket nuevo');
            } else if (minutos > 0 && Date.now() - ultimoAviso >= minutos * 60000) {
                mostrar(d.texto, 'Tu avance');
            }
            previoPend = d.pendientes;
        }
        latir(); setInterval(latir, 40000);
    }

    // ---------------------------------------------------------------- Administrador
    function montarAdmin(ctx, usuario) {
        AlicorpChat.crear({
            rol: 'admin', endpoint: '/api/chatbot/admin', titulo: 'Centro de mando', subtitulo: 'Resumen del equipo',
            icono: 'radar', placeholder: 'Consulta: resumen, latidos, llaves…', voz: false, recordarAbierto: true,
            bienvenida: 'Centro de mando listo. Puedo resumirte el día, el último latido de cada analista, su avance, los cambios de llave y las alertas pendientes. Solo consulto y resumo; no ejecuto acciones.',
            chips: ctx.chips_admin || [],
            cuerpo: (mensaje) => ({ mensaje, usuario }),
            onAccion: (accion, valor) => (accion === 'vista' ? irAVista(valor) : false)
        });
    }

    async function iniciar() {
        const usuario = usuarioActual();
        let ctx;
        try {
            const r = await fetch('/api/chatbot/contexto', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ usuario }) });
            if (!r.ok) return; ctx = await r.json();
        } catch (e) { return; }
        if (!ctx || !ctx.success) return;
        const u = ctx.usuario || usuario;
        if (ctx.rol === 'admin') montarAdmin(ctx, u); else montarAnalista(ctx, u);
    }
    if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', () => setTimeout(iniciar, 1200));
    else setTimeout(iniciar, 1200);
})();
