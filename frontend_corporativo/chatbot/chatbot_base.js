/* Motor de interfaz compartido por los tres chatbots de Alicorp.
   Uso: AlicorpChat.crear({ rol, endpoint, titulo, subtitulo, avatar, lanzadorTexto, bienvenida, chips,
                            voz, placeholder, cuerpo(mensaje) , onAccion(accion, valor) , extraCabecera(cab) }) */
(function () {
    'use strict';

    const ICONOS = {
        chat: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M21 12a8 8 0 0 1-11.6 7.1L4 20l1-4.4A8 8 0 1 1 21 12Z"/></svg>',
        ticket: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M3 9V6a1 1 0 0 1 1-1h16a1 1 0 0 1 1 1v3a3 3 0 0 0 0 6v3a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1v-3a3 3 0 0 0 0-6Z"/><path d="M13 5v14" stroke-dasharray="2 3"/></svg>',
        radar: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="9"/><circle cx="12" cy="12" r="4.5"/><path d="M12 12 18 6"/></svg>'
    };

    const el = (tag, cls, html) => { const e = document.createElement(tag); if (cls) e.className = cls; if (html != null) e.innerHTML = html; return e; };
    const esc = (s) => String(s == null ? '' : s).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
    const formato = (t) => esc(t).replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>').replace(/\n/g, '<br>');
    const limpiarParaVoz = (t) => String(t || '').replace(/\*\*/g, '').replace(/[\u{1F000}-\u{1FAFF}\u{2600}-\u{27BF}\u{FE0F}]/gu, '').replace(/\n+/g, '. ').replace(/•/g, '').replace(/\s+/g, ' ').trim();
    const guardar = (k, v) => { try { localStorage.setItem(k, v); } catch (e) { /* almacenamiento bloqueado */ } };
    const leer = (k) => { try { return localStorage.getItem(k); } catch (e) { return null; } };

    // ---------------------------------------------------------------- Voz (solo cliente)
    const Voz = {
        ok: typeof window !== 'undefined' && 'speechSynthesis' in window && 'SpeechSynthesisUtterance' in window,
        voz: null,
        elegir() {
            if (!this.ok) return;
            const voces = speechSynthesis.getVoices();
            const pref = ['es-PE', 'es-419', 'es-MX', 'es-US', 'es-CO', 'es-AR', 'es-ES'];
            for (const l of pref) { const v = voces.find((x) => x.lang.replace('_', '-') === l); if (v) { this.voz = v; return; } }
            this.voz = voces.find((x) => x.lang.toLowerCase().startsWith('es')) || null;
        },
        hablar(texto, alTerminar) {
            if (!this.ok) return;
            speechSynthesis.cancel();
            const limpio = limpiarParaVoz(texto);
            if (!limpio) return;
            const u = new SpeechSynthesisUtterance(limpio);
            if (!this.voz) this.elegir();
            if (this.voz) { u.voice = this.voz; u.lang = this.voz.lang; } else { u.lang = 'es-PE'; }
            u.rate = 1; u.pitch = 1;
            u.onend = u.onerror = () => { if (alTerminar) alTerminar(); };
            speechSynthesis.speak(u);
        },
        parar() { if (this.ok) speechSynthesis.cancel(); }
    };
    if (Voz.ok) { Voz.elegir(); speechSynthesis.onvoiceschanged = () => Voz.elegir(); }

    // ---------------------------------------------------------------- Fábrica
    function crear(o) {
        const clave = 'ach_hist_' + o.rol;
        const raiz = el('div', 'ach ach-' + o.rol);
        raiz.innerHTML =
            '<button type="button" class="ach-lanzador" aria-label="Abrir ' + esc(o.titulo) + '">' +
                '<span class="ach-lanz-ico">' + (o.icono ? ICONOS[o.icono] : esc(o.avatar || '💬')) + '</span>' +
                '<span class="ach-lanz-txt">' + esc(o.lanzadorTexto || '') + '</span>' +
                '<span class="ach-punto" aria-hidden="true"></span>' +
            '</button>' +
            '<section class="ach-panel" role="dialog" aria-label="' + esc(o.titulo) + '">' +
                '<header class="ach-cab">' +
                    '<div class="ach-avatar">' + (o.icono ? ICONOS[o.icono] : esc(o.avatar || '💬')) + '</div>' +
                    '<div class="ach-tit"><strong>' + esc(o.titulo) + '</strong><small>' + esc(o.subtitulo || 'En línea') + '</small></div>' +
                    '<span class="ach-herr"></span>' +
                    '<button type="button" class="ach-hbtn ach-cerrar" aria-label="Cerrar chat" title="Cerrar">✕</button>' +
                '</header>' +
                '<div class="ach-msgs" aria-live="polite"></div>' +
                '<div class="ach-chips"></div>' +
                '<form class="ach-form" autocomplete="off">' +
                    '<input type="text" maxlength="300" placeholder="' + esc(o.placeholder || 'Escribe tu mensaje…') + '" aria-label="Mensaje">' +
                    '<button type="submit" aria-label="Enviar">➤</button>' +
                '</form>' +
            '</section>';
        document.body.appendChild(raiz);

        const $ = (s) => raiz.querySelector(s);
        const lanzador = $('.ach-lanzador'), msgs = $('.ach-msgs'), chips = $('.ach-chips'), form = $('.ach-form');
        const input = form.querySelector('input'), enviar = form.querySelector('button'), herr = $('.ach-herr');

        let ocupado = false, autoLeer = leer('ach_autoleer') === '1', leyendoBtn = null;
        let historial = [];
        try { historial = JSON.parse(sessionStorage.getItem(clave) || '[]'); } catch (e) { historial = []; }

        // ---- Controles de voz en la cabecera
        let btnAuto = null, btnParar = null;
        if (o.voz && Voz.ok) {
            btnAuto = el('button', 'ach-hbtn' + (autoLeer ? ' activo' : ''), '🔊');
            btnAuto.type = 'button';
            btnAuto.title = 'Leer en voz alta todas las respuestas nuevas';
            btnAuto.setAttribute('aria-label', 'Leer todas las respuestas en voz alta');
            btnAuto.setAttribute('aria-pressed', autoLeer ? 'true' : 'false');
            btnAuto.addEventListener('click', () => {
                autoLeer = !autoLeer; guardar('ach_autoleer', autoLeer ? '1' : '0');
                btnAuto.classList.toggle('activo', autoLeer); btnAuto.setAttribute('aria-pressed', autoLeer ? 'true' : 'false');
                if (!autoLeer) detenerVoz();
                notaVoz(autoLeer ? 'Lectura automática activada: leeré las respuestas nuevas.' : 'Lectura automática desactivada.');
            });
            btnParar = el('button', 'ach-hbtn', '⏹'); btnParar.type = 'button'; btnParar.hidden = true; btnParar.title = 'Detener lectura'; btnParar.setAttribute('aria-label', 'Detener lectura');
            btnParar.addEventListener('click', detenerVoz);
            herr.appendChild(btnParar); herr.appendChild(btnAuto);
        }
        herr.style.display = 'contents';
        if (o.extraCabecera) o.extraCabecera(herr);

        function detenerVoz() {
            Voz.parar();
            if (leyendoBtn) { leyendoBtn.classList.remove('leyendo'); leyendoBtn.textContent = '🔊'; leyendoBtn = null; }
            if (btnParar) btnParar.hidden = true;
        }
        function leerMensaje(texto, btn) {
            if (!Voz.ok) return;
            if (leyendoBtn === btn) { detenerVoz(); return; }
            detenerVoz();
            leyendoBtn = btn; if (btn) { btn.classList.add('leyendo'); btn.textContent = '⏸'; }
            if (btnParar) btnParar.hidden = false;
            Voz.hablar(texto, () => { if (leyendoBtn === btn) detenerVoz(); });
        }
        function notaVoz(t) { const m = el('div', 'ach-ia', esc(t)); m.style.alignSelf = 'center'; msgs.appendChild(m); bajar(); }

        // ---- Render
        function bajar() { msgs.scrollTop = msgs.scrollHeight; }

        function msgUsuario(texto) {
            const m = el('div', 'ach-msg user'); m.appendChild(el('div', 'ach-burbuja', esc(texto)));
            msgs.appendChild(m); bajar();
        }

        function msgBot(r, leerAuto) {
            const m = el('div', 'ach-msg bot');
            const fila = el('div', 'ach-fila');
            fila.appendChild(el('div', 'ach-burbuja', formato(r.texto)));
            if (o.voz && Voz.ok) {
                const b = el('button', 'ach-leer', '🔊'); b.type = 'button'; b.title = 'Escuchar esta respuesta'; b.setAttribute('aria-label', 'Escuchar esta respuesta');
                b.addEventListener('click', () => leerMensaje(r.texto, b));
                fila.appendChild(b);
                if (leerAuto && autoLeer) setTimeout(() => leerMensaje(r.texto, b), 150);
            }
            m.appendChild(fila);

            if (r.productos && r.productos.length) {
                const g = el('div', 'ach-prods');
                r.productos.forEach((p) => {
                    const a = el('a', 'ach-prod'); a.href = p.url || '#';
                    a.innerHTML = '<b>' + esc(p.nombre) + '</b><span class="ach-precio">S/ ' + esc(p.precio) + '</span>' +
                        '<small>' + esc([p.marca, p.presentacion].filter(Boolean).join(' · ')) + '</small>' +
                        '<span class="ach-estado ' + esc(p.nivel) + '">' + esc(p.disponibilidad) + '</span>';
                    a.addEventListener('click', (ev) => { ev.preventDefault(); navegar(p.url); });
                    g.appendChild(a);
                });
                m.appendChild(g);
            }
            const hayBotones = (r.enlaces && r.enlaces.length) || (r.acciones && r.acciones.length);
            if (hayBotones) {
                const bt = el('div', 'ach-botones');
                (r.enlaces || []).forEach((e) => {
                    const a = el('a', 'ach-btn', esc(e.etiqueta)); a.href = e.url;
                    a.addEventListener('click', (ev) => { ev.preventDefault(); navegar(e.url); });
                    bt.appendChild(a);
                });
                (r.acciones || []).forEach((ac) => {
                    const b = el('button', 'ach-btn', esc(ac.etiqueta)); b.type = 'button';
                    b.addEventListener('click', () => ejecutar(ac.accion, ac.valor));
                    bt.appendChild(b);
                });
                m.appendChild(bt);
            }
            if (r.origen === 'ia') m.appendChild(el('div', 'ach-ia', 'Respuesta generada con IA · puede no ser exacta'));
            msgs.appendChild(m); bajar();
        }

        function pintarChips(lista) {
            chips.innerHTML = '';
            (lista || []).slice(0, 8).forEach((c) => {
                const etiqueta = typeof c === 'string' ? c : c.etiqueta, mensaje = typeof c === 'string' ? c : c.mensaje;
                const b = el('button', 'ach-chip', esc(etiqueta)); b.type = 'button';
                b.addEventListener('click', () => enviarMensaje(mensaje));
                chips.appendChild(b);
            });
        }

        // ---- Navegación y acciones (en la misma página cuando se puede)
        function navegar(url) {
            if (!url) return;
            try {
                const u = new URL(url, location.origin);
                const q = u.searchParams.get('q');
                if (q && location.pathname.endsWith('/tienda_virtual/tienda.html') && u.pathname === location.pathname) {
                    const caja = document.getElementById('cajaBuscar'), f = document.getElementById('formBuscar');
                    if (caja && f) {
                        caja.value = q; f.requestSubmit ? f.requestSubmit() : f.dispatchEvent(new Event('submit', { cancelable: true }));
                        if (window.matchMedia('(max-width: 720px)').matches) cerrar();
                        const rej = document.getElementById('rejilla'); if (rej) rej.scrollIntoView({ behavior: 'smooth', block: 'start' });
                        return;
                    }
                }
            } catch (e) { /* URL inválida: se navega normal */ }
            location.href = url;
        }
        function ejecutar(accion, valor) {
            if (o.onAccion && o.onAccion(accion, valor) === true) return;
            if (accion === 'click') { const e = document.querySelector(valor); if (e) { if (window.matchMedia('(max-width: 720px)').matches) cerrar(); e.click(); } }
        }

        // ---- Envío
        async function enviarMensaje(texto) {
            texto = String(texto || '').trim();
            if (!texto || ocupado) return;
            ocupado = true; enviar.disabled = true; input.value = '';
            msgUsuario(texto); historial.push({ q: texto });
            const typing = el('div', 'ach-msg bot'); typing.appendChild(el('div', 'ach-burbuja ach-escribiendo', '<i></i><i></i><i></i>')); msgs.appendChild(typing); bajar();
            let r;
            try {
                const res = await fetch(o.endpoint, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(o.cuerpo ? o.cuerpo(texto) : { mensaje: texto }) });
                r = await res.json();
                if (!res.ok || r.success === false) throw new Error(r.message || 'error');
            } catch (e) {
                r = { texto: 'No pude conectarme en este momento. Intenta de nuevo en unos segundos.', chips: [], enlaces: [], acciones: [], productos: [] };
            }
            typing.remove();
            msgBot(r, true); historial.push({ r }); persistir();
            pintarChips(r.chips && r.chips.length ? r.chips : o.chips);
            ocupado = false; enviar.disabled = false; input.focus();
        }
        function persistir() {
            try { sessionStorage.setItem(clave, JSON.stringify(historial.slice(-40))); } catch (e) { /* sin almacenamiento */ }
        }

        // ---- Abrir / cerrar
        function abrir() {
            raiz.classList.add('abierto'); guardar('ach_abierto_' + o.rol, '1');
            lanzador.querySelector('.ach-punto').classList.remove('on');
            if (!msgs.children.length) iniciar();
            setTimeout(() => input.focus(), 50);
        }
        function cerrar() { raiz.classList.remove('abierto'); guardar('ach_abierto_' + o.rol, '0'); detenerVoz(); }
        function iniciar() {
            if (historial.length) {
                historial.forEach((h) => { if (h.q) msgUsuario(h.q); else if (h.r) msgBot(h.r, false); });
                const ultimo = [...historial].reverse().find((h) => h.r);
                pintarChips(ultimo && ultimo.r.chips && ultimo.r.chips.length ? ultimo.r.chips : o.chips);
            } else {
                msgBot({ texto: o.bienvenida, chips: [], enlaces: [], acciones: [], productos: [] }, false);
                pintarChips(o.chips);
            }
        }

        lanzador.addEventListener('click', abrir);
        $('.ach-cerrar').addEventListener('click', cerrar);
        form.addEventListener('submit', (ev) => { ev.preventDefault(); enviarMensaje(input.value); });
        document.addEventListener('keydown', (ev) => { if (ev.key === 'Escape' && raiz.classList.contains('abierto')) cerrar(); });
        window.addEventListener('beforeunload', detenerVoz);
        if (leer('ach_abierto_' + o.rol) === '1' && o.recordarAbierto) abrir();

        return { abrir, cerrar, enviar: enviarMensaje, avisar: () => { if (!raiz.classList.contains('abierto')) lanzador.querySelector('.ach-punto').classList.add('on'); }, raiz, herr };
    }

    window.AlicorpChat = { crear, esc, ICONOS };
})();
