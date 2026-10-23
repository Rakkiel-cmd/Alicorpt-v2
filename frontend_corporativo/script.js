document.addEventListener("DOMContentLoaded", () => {
    const contenedorMarcas = document.getElementById("marcas-container");

    // Solo ejecutar si estamos en la página principal con contenedor de marcas
    if (contenedorMarcas) {
        // Consumir el JSON simulando una llamada a una API
        fetch("data.json")
            .then(response => {
                if (!response.ok) throw new Error("Error al cargar los datos.");
                return response.json();
            })
            .then(datos => {
                renderizarMarcas(datos);
            })
            .catch(error => {
                console.error("Error:", error);
                contenedorMarcas.innerHTML = "<p>Hubo un problema al cargar la información.</p>";
            });
    }

    function renderizarMarcas(datos) {
        contenedorMarcas.innerHTML = ""; // Limpiar
        
        datos.forEach(item => {
            const card = document.createElement("div");
            card.className = "card";
            
            card.innerHTML = `
                <img src="${item.imagen}" alt="${item.titulo}" class="card-img">
                <div class="card-content">
                    <h3>${item.titulo}</h3>
                    <p>${item.descripcion}</p>
                </div>
            `;
            
            contenedorMarcas.appendChild(card);
        });
    }
});

// ==============================================================================
// GESTIÓN DE SESIÓN ACTIVA EN NAVBAR PÚBLICA (ALICORP)
// - Persistencia de sesión al navegar atrás en el navegador o volver al inicio.
// - Transforma "Personal Autorizado" por el Nombre del Usuario logueado.
// - Menú desplegable interactivo: "Volver al dashboard" o "Cerrar sesión".
// ==============================================================================
function inicializarNavbarSesion() {
    let sesion = null;
    try {
        const raw = sessionStorage.getItem('usuario_activo') || localStorage.getItem('usuario_activo');
        if (raw) {
            sesion = JSON.parse(raw);
            // Sincronizar ambos almacenes si uno faltaba
            if (!sessionStorage.getItem('usuario_activo')) sessionStorage.setItem('usuario_activo', raw);
            if (!localStorage.getItem('usuario_activo')) localStorage.setItem('usuario_activo', raw);
        }
    } catch (e) {
        console.warn("Error leyendo sesión del usuario:", e);
    }

    const navActionsContainers = document.querySelectorAll('.nav-actions');
    if (!navActionsContainers || navActionsContainers.length === 0) return;

    navActionsContainers.forEach(container => {
        // Si NO hay sesión activa, restaurar el botón estándar "Personal Autorizado"
        if (!sesion || (!sesion.usuario && !sesion.nombre_completo)) {
            container.innerHTML = `<a href="admin_login.html" class="btn-outline">Personal Autorizado</a>`;
            return;
        }

        // Si HAY sesión activa:
        const nombreCompleto = (sesion.nombre_completo || sesion.usuario || 'Usuario').trim();
        const partes = nombreCompleto.split(/\s+/);
        // Mostrar nombre corto en el botón (ej: "Juan Pérez") para que mantenga un look limpio y elegante
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
                // Cerrar cualquier otro dropdown abierto
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
                try {
                    sessionStorage.removeItem('usuario_activo');
                    localStorage.removeItem('usuario_activo');
                } catch (err) {
                    console.warn("Error limpiando sesión:", err);
                }
                // Re-inicializar el navbar para mostrar inmediatamente "Personal Autorizado"
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

// Ejecución al cargar el DOM y en 'pageshow' (clave para que al presionar la flecha atrás del navegador se actualice sin recarga)
document.addEventListener("DOMContentLoaded", inicializarNavbarSesion);
window.addEventListener("pageshow", inicializarNavbarSesion);
