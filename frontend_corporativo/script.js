// ==============================================================================
// GESTIÓN DE SEGURIDAD (LLAVE SECRETA URL) Y SESIÓN ACTIVA EN NAVBAR PÚBLICA
// 1. Botón "Personal Autorizado" 100% oculto para la clientela por defecto.
// 2. Desbloqueo mediante URL con parámetro ?key=... (o ?clave=..., ?access=...).
// 3. Validación contra el backend (Supabase / respaldo seguro).
// 4. Limpieza instantánea de la URL para que la llave no quede visible.
// 5. Si ya está logueado, persiste la sesión mostrando el nombre y menú interactivo.
// ==============================================================================

function mostrarToastSeguridad(mensaje) {
    const antiguo = document.querySelector('.toast-alerta-seguridad');
    if (antiguo) antiguo.remove();

    const toast = document.createElement('div');
    toast.className = 'toast-alerta-seguridad';
    toast.innerHTML = `<span class="toast-icon">🛡️</span> <span>${mensaje}</span>`;
    document.body.appendChild(toast);

    setTimeout(() => {
        toast.style.opacity = '0';
        toast.style.transform = 'translateY(20px)';
        toast.style.transition = 'all 0.3s ease';
        setTimeout(() => toast.remove(), 300);
    }, 4000);
}

// Flag en memoria de la página actual: solo es true si se ingresó con ?key=... válida en esta carga
let keyValidadaEnEstaCarga = false;

// Verifica si vino una key en la URL (?key=... o ?clave=...) y la valida en el backend
async function verificarKeyUrl() {
    try {
        // Limpiar siempre cualquier residuo antiguo en localStorage para evitar que quede pegado
        localStorage.removeItem('empleado_desbloqueado');

        const urlObj = new URL(window.location.href);
        const params = urlObj.searchParams;
        const key = params.get('key') || params.get('clave') || params.get('access') || params.get('token');

        if (key) {
            // Limpiar inmediatamente la URL para que no quede visible en el navegador ni historial
            params.delete('key');
            params.delete('clave');
            params.delete('access');
            params.delete('token');
            const nuevaUrl = urlObj.pathname + (params.toString() ? '?' + params.toString() : '') + urlObj.hash;
            window.history.replaceState({}, document.title, nuevaUrl);

            // Validar la key en el backend de Python / Supabase
            const resp = await fetch('/api/seguridad/validar_key', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ key: key.trim() })
            });

            const data = await resp.json();
            if (resp.ok && data.success && data.desbloqueado) {
                keyValidadaEnEstaCarga = true;
                sessionStorage.setItem('empleado_desbloqueado', 'true');
                mostrarToastSeguridad('🔑 Acceso de Personal Autorizado desbloqueado');
                inicializarNavbarSesion();
                return true;
            } else {
                keyValidadaEnEstaCarga = false;
                sessionStorage.removeItem('empleado_desbloqueado');
                console.warn("Llave de acceso no válida.");
            }
        } else {
            // Si entró a la página pública SIN ?key=... en la URL:
            keyValidadaEnEstaCarga = false;
            const haySesion = !!(sessionStorage.getItem('usuario_activo') || localStorage.getItem('usuario_activo'));
            if (!haySesion) {
                sessionStorage.removeItem('empleado_desbloqueado');
            }
        }
    } catch (e) {
        console.warn("Error al procesar llave de URL:", e);
    }
    return false;
}

function inicializarNavbarSesion() {
    let sesion = null;
    try {
        const raw = sessionStorage.getItem('usuario_activo') || localStorage.getItem('usuario_activo');
        if (raw) {
            sesion = JSON.parse(raw);
            if (!sessionStorage.getItem('usuario_activo')) sessionStorage.setItem('usuario_activo', raw);
            if (!localStorage.getItem('usuario_activo')) localStorage.setItem('usuario_activo', raw);
        }
    } catch (e) {
        console.warn("Error leyendo sesión del usuario:", e);
    }

    const estaLogueado = !!(sesion && (sesion.usuario || sesion.nombre_completo));
    const estaDesbloqueado = keyValidadaEnEstaCarga;

    const navActionsContainers = document.querySelectorAll('.nav-actions');
    if (!navActionsContainers || navActionsContainers.length === 0) return;

    navActionsContainers.forEach(container => {
        // Si NO está logueado Y TAMPOCO ingresó con ?key=... en esta vista: OCULTAR COMPLETAMENTE
        if (!estaLogueado && !estaDesbloqueado) {
            container.classList.remove('desbloqueado');
            container.style.display = 'none';
            document.body.classList.remove('empleado-desbloqueado');
            container.innerHTML = '';
            return;
        }

        // Si llegó aquí, está autorizado para ver la sección de personal:
        container.classList.add('desbloqueado');
        container.style.display = 'inline-flex';
        document.body.classList.add('empleado-desbloqueado');

        // CASO A: Está desbloqueado por llave en esta carga pero AÚN NO ha iniciado sesión
        if (!estaLogueado) {
            container.innerHTML = `<a href="admin_login.html" class="btn-outline">Personal Autorizado</a>`;
            return;
        }

        // CASO B: Ya inició sesión -> Mostrar botón con nombre del usuario y dropdown
        const nombreCompleto = (sesion.nombre_completo || sesion.usuario || 'Usuario').trim();
        const partes = nombreCompleto.split(/\s+/);
        const nombreCorto = partes.length > 2 ? `${partes[0]} ${partes[1]}` : nombreCompleto;
        const inicial = nombreCompleto.charAt(0).toUpperCase() || 'U';

        container.innerHTML = `
            <div class="user-nav-dropdown">
                <button type="button" class="btn-user-nav" id="btnUserNav" aria-expanded="false" title="${nombreCompleto}">
                    <span class="user-nav-avatar">${inicial}</span>
                    <span class="user-nav-name">${nombreCorto}</span>
                    <span class="user-nav-arrow">▼</span>
                </button>
                <div class="user-nav-menu" id="userNavMenu" role="menu">
                    <div class="user-nav-menu-header">
                        <small>Sesión activa</small>
                        <b title="${nombreCompleto}">${nombreCompleto}</b>
                    </div>
                    <a href="dashboard.html" class="user-nav-menu-item" role="menuitem" id="btnNavGoDashboard">
                        <span>📊</span>
                        <span>Volver al Dashboard</span>
                    </a>
                    <button type="button" class="user-nav-menu-item logout" id="btnNavLogout" role="menuitem">
                        <span>🚪</span>
                        <span>Cerrar sesión</span>
                    </button>
                </div>
            </div>
        `;

        const btnUserNav = container.querySelector('#btnUserNav');
        const userNavMenu = container.querySelector('#userNavMenu');
        const btnNavLogout = container.querySelector('#btnNavLogout');

        if (btnUserNav && userNavMenu) {
            btnUserNav.addEventListener('click', (e) => {
                e.stopPropagation();
                const estaAbierto = userNavMenu.classList.contains('show');
                document.querySelectorAll('.user-nav-menu.show').forEach(m => m.classList.remove('show'));
                document.querySelectorAll('.btn-user-nav.active').forEach(b => {
                    b.classList.remove('active');
                    b.setAttribute('aria-expanded', 'false');
                });

                if (!estaAbierto) {
                    userNavMenu.classList.add('show');
                    btnUserNav.classList.add('active');
                    btnUserNav.setAttribute('aria-expanded', 'true');
                }
            });
        }

        if (btnNavLogout) {
            btnNavLogout.addEventListener('click', (e) => {
                e.stopPropagation();
                keyValidadaEnEstaCarga = false;
                try {
                    sessionStorage.removeItem('usuario_activo');
                    localStorage.removeItem('usuario_activo');
                    // Al cerrar sesión, también re-bloqueamos para que vuelva a estar 100% oculto para clientes
                    sessionStorage.removeItem('empleado_desbloqueado');
                    localStorage.removeItem('empleado_desbloqueado');
                } catch (err) {
                    console.warn("Error limpiando sesión:", err);
                }
                inicializarNavbarSesion();
            });
        }
    });
}

// Cerrar dropdown al dar clic afuera
document.addEventListener('click', (e) => {
    if (!e.target.closest('.user-nav-dropdown')) {
        document.querySelectorAll('.user-nav-menu.show').forEach(menu => menu.classList.remove('show'));
        document.querySelectorAll('.btn-user-nav.active').forEach(btn => {
            btn.classList.remove('active');
            btn.setAttribute('aria-expanded', 'false');
        });
    }
});

// Cerrar dropdown al presionar Escape
document.addEventListener('keydown', (e) => {
    if (e.key === 'Escape') {
        document.querySelectorAll('.user-nav-menu.show').forEach(menu => menu.classList.remove('show'));
        document.querySelectorAll('.btn-user-nav.active').forEach(btn => {
            btn.classList.remove('active');
            btn.setAttribute('aria-expanded', 'false');
        });
    }
});

// Función de arranque unificada
async function iniciarSistemaNavegacion() {
    // Primero actualizamos estado inicial (oculta el botón de inmediato si no hay sesión)
    inicializarNavbarSesion();
    // Luego verificamos si vino ?key=... en la URL
    await verificarKeyUrl();
    inicializarNavbarSesion();
}

// Ejecución al cargar el DOM y en 'pageshow' cuando vuelve desde bfcache (botón atrás)
document.addEventListener("DOMContentLoaded", () => iniciarSistemaNavegacion());
window.addEventListener("pageshow", (e) => {
    if (e && e.persisted) {
        iniciarSistemaNavegacion();
    }
});

// ==============================================================================
// MONITOR EN TIEMPO REAL DE ERRORES DE CONSOLA Y FALLAS EN EL PORTAL PÚBLICO
// ==============================================================================
(function iniciarMonitorFallasPortal() {
    const reportadosEnSesion = new Set();

    function enviarFallaWeb(titulo, detalle, tipo, gravedad) {
        try {
            const clave = `${tipo}|${titulo}`.slice(0, 140);
            if (reportadosEnSesion.has(clave)) return;
            reportadosEnSesion.add(clave);

            fetch('/api/fallas_web/reportar', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                    titulo_error: titulo,
                    detalle_tecnico: detalle,
                    tipo_falla: tipo || 'Error Web Consola',
                    gravedad: gravedad || 'Media',
                    origen: `Portal Público (${window.location.pathname || '/index.html'})`
                })
            }).catch(() => {});
        } catch (e) {}
    }

    window.addEventListener('error', (ev) => {
        if (!ev) return;
        if (ev.message) {
            const archivo = ev.filename ? ev.filename.split('/').pop() : 'script';
            const linea = ev.lineno ? `:${ev.lineno}` : '';
            enviarFallaWeb(
                `Error JS en consola: ${ev.message}`,
                `Archivo: ${archivo}${linea} en ${window.location.pathname}`,
                'Error Web Consola',
                'Alta'
            );
        }
    });

    window.addEventListener('unhandledrejection', (ev) => {
        const razon = ev && ev.reason ? (ev.reason.message || String(ev.reason)) : 'Promesa rechazada sin capturar';
        if (razon && !razon.includes('fallas_web')) {
            enviarFallaWeb(
                `Excepción asíncrona en web: ${razon.slice(0, 160)}`,
                `Detectado en ${window.location.pathname}`,
                'Bug / Promesa JS',
                'Media'
            );
        }
    });
})();
