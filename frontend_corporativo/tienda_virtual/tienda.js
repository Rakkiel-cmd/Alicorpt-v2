/* ==============================================================================
   TIENDA ALICORP: lógica de la tienda y del carrito de compras
   Todo se pide al servidor con fetch (el servidor consulta la base de datos con SQL).
   ============================================================================== */
(function () {
    "use strict";

    const $ = (id) => document.getElementById(id);
    const dinero = (n) => "S/ " + Number(n).toLocaleString("es-PE", { minimumFractionDigits: 2, maximumFractionDigits: 2 });
    const esc = (t) => String(t == null ? "" : t).replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
    const LIMITE_LINEA = 100000;

    // Íconos de línea (SVG en línea, sin emojis)
    const svg = (d) => `<svg viewBox="0 0 24 24" aria-hidden="true" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">${d}</svg>`;
    const ICON = {
        heart: svg('<path d="M20.8 4.6a5.5 5.5 0 0 0-7.8 0L12 5.7l-1-1.1a5.5 5.5 0 0 0-7.8 7.8l1 1.1L12 21l7.8-7.5 1-1.1a5.5 5.5 0 0 0 0-7.8z"/>'),
        check: svg('<path d="M5 12.5l4.5 4.5L19 7.5"/>'),
        search: svg('<circle cx="11" cy="11" r="7"/><path d="m20 20-3.5-3.5"/>'),
        cart: svg('<circle cx="9" cy="20" r="1.5"/><circle cx="18" cy="20" r="1.5"/><path d="M2 3h3l2.6 11.2a2 2 0 0 0 2 1.5h7.6a2 2 0 0 0 2-1.5L21 7H6"/>'),
        warn: svg('<path d="M12 3 2 20h20L12 3z"/><path d="M12 10v4M12 17.5v.01"/>'),
        mail: svg('<rect x="3" y="5" width="18" height="14" rx="2"/><path d="m3 7 9 6 9-6"/>'),
    };

    // ---------------------------------------------------------------- estado
    const estado = {
        categoria: "",
        marca: "",
        orden: "",
        pagina: 1,
        consulta: "",
        tiendaIA: "",
        modoIA: (window.AlicorpHistorial && window.AlicorpHistorial.modo() === "alfabetico") ? "tradicional" : "ia", // "ia" (Smile ML + tu historial) | "tradicional" (Alfabético)
        busquedaIA: null,
        resultadosBusqueda: [],
        categorias: []
    };
    let carrito = {};   // { sku: {sku, nombre, marca, presentacion, precio, imagen, stock, cantidad} }
    const CLAVE = "alicorp_carrito_v1";
    let favoritos = new Set();
    try { favoritos = new Set(JSON.parse(localStorage.getItem("alicorp_favoritos") || "[]")); } catch (e) { /* sin almacenamiento */ }

    function guardarCarrito() {
        try { localStorage.setItem(CLAVE, JSON.stringify(carrito)); } catch (e) { /* sin almacenamiento: queda en memoria */ }
    }
    function cargarCarrito() {
        try { carrito = JSON.parse(localStorage.getItem(CLAVE) || "{}") || {}; } catch (e) { carrito = {}; }
    }

    function aviso(texto) {
        const a = $("aviso");
        a.textContent = texto;
        a.classList.add("visible");
        clearTimeout(aviso._t);
        aviso._t = setTimeout(() => a.classList.remove("visible"), 2600);
    }

    // Historial y medicion de busquedas (/tienda_virtual/historial_busqueda.js). Si no carga, se usan funciones vacias.
    const H = window.AlicorpHistorial || {
        registrar() {}, perfilTexto() { return ""; }, tieneHistorial() { return false; }, modo() { return "ia"; },
        ponerModo() {}, parametros() { return ""; }, tecla() {}, terminar() { return false; },
    };

    async function pedir(url, opciones) {
        const r = await fetch(url, opciones);
        const datos = await r.json().catch(() => ({}));
        if (!r.ok && !datos.error) datos.error = "Error del servidor (" + r.status + ")";
        return datos;
    }

    // ---------------------------------------------------------------- categorías
    async function cargarCategorias() {
        const d = await pedir("/api/tienda/categorias");
        estado.categorias = d.categorias || [];
        const panel = $("panelCategorias");
        const total = estado.categorias.reduce((s, c) => s + c.cantidad, 0);
        panel.innerHTML = '<h3>Categorías</h3>' +
            `<button type="button" class="cat-item activa" data-cat=""><span class="nom">Todos</span><span class="n">${total.toLocaleString("es-PE")}</span></button>` +
            estado.categorias.map((c) => `<button type="button" class="cat-item" data-cat="${esc(c.categoria)}"><span class="nom">${esc(c.categoria)}</span><span class="n">${c.cantidad.toLocaleString("es-PE")}</span></button>`).join("");
    }

    function marcarCategoria() {
        document.querySelectorAll(".cat-item").forEach((b) => b.classList.toggle("activa", b.dataset.cat === estado.categoria));
    }

    // ---------------------------------------------------------------- tarjetas de producto
    function tarjeta(p, i) {
        const agotado = p.stock <= 0;
        const sello = agotado ? '<span class="sello agotado">Agotado</span>' : (p.stock < 200 ? '<span class="sello">Pocas unidades</span>' : "");
        const esModoIA = estado.consulta && estado.modoIA === "ia" && typeof p.puntaje_ia === "number";
        const badgeIA = esModoIA ? `<span class="badge-ia" title="Puntaje Smile ML">⭐ IA ${p.puntaje_ia.toFixed(1)}%</span>` : "";
        let badgeDelta = "";
        if (esModoIA && typeof p.delta_posicion === "number") {
            if (p.delta_posicion > 0) badgeDelta = `<span class="badge-delta sube">▲ +${p.delta_posicion} pos. vs alfabético</span>`;
            else if (p.delta_posicion < 0) badgeDelta = `<span class="badge-delta baja">▼ ${p.delta_posicion} pos. vs alfabético</span>`;
            else badgeDelta = `<span class="badge-delta igual">• #${p.posicion_ia} igual que alfabético</span>`;
        } else if (estado.consulta && estado.modoIA === "tradicional" && p.posicion_tradicional) {
            badgeDelta = `<span class="badge-delta igual">🔤 Orden A-Z #${p.posicion_tradicional}</span>`;
        }
        const razonHtml = esModoIA && p.razon_ranking ? `<div class="razon-ia">${esc(p.razon_ranking)}</div>` : "";
        return `
        <article class="producto" data-sku="${esc(p.sku)}" style="--i:${Math.min(i || 0, 12)}">
            <div class="foto">${sello}${badgeIA}
                <button type="button" class="fav${favoritos.has(p.sku) ? " on" : ""}" data-accion="fav" aria-label="Guardar en favoritos">${ICON.heart}</button>
                ${window.fotoProducto(p)}</div>
            <div class="info">
                <span class="marca">${esc(p.marca)}</span>
                ${p.ia_motivo ? `<span class="ia-motivo">${esc(p.ia_motivo)}</span>` : ""}
                <span class="nombre">${esc(p.nombre)}</span>
                <span class="present">${esc(p.presentacion)}</span>
                ${badgeDelta}
                ${razonHtml}
                <span class="precio">${dinero(p.precio)}</span>
                <span class="stock ${agotado ? "cero" : (p.stock < 200 ? "bajo" : "")}">${agotado ? "Sin stock" : (p.stock < 200 ? "Quedan " + p.stock.toLocaleString("es-PE") + " unid." : "Disponible · " + p.stock.toLocaleString("es-PE") + " unid.")}</span>
                <div class="acciones">
                    <div class="cantidad">
                        <button type="button" data-accion="menos" aria-label="Menos">−</button>
                        <input type="number" min="1" max="${Math.min(p.stock, LIMITE_LINEA)}" value="1" data-campo="cant" aria-label="Cantidad">
                        <button type="button" data-accion="mas" aria-label="Más">+</button>
                    </div>
                    <button type="button" class="btn-agregar" data-accion="agregar" ${agotado ? "disabled" : ""}>Agregar</button>
                </div>
                <div class="atajos">
                    <button type="button" data-accion="fijar" data-n="12">x12</button>
                    <button type="button" data-accion="fijar" data-n="50">x50</button>
                    <button type="button" data-accion="fijar" data-n="100">x100</button>
                    <button type="button" data-accion="fijar" data-n="500">x500</button>
                </div>
            </div>
        </article>`;
    }

    // se guardan los productos que están en pantalla para poder agregarlos por sku
    let enPantalla = {};
    let destacadosMapa = {};
    let hayDestacados = false;
    let paraTiMapa = {};
    let hayParaTi = false;

    function mostrarDestacados(ver) {
        $("seccionDestacados").hidden = !(ver && hayDestacados);
        $("seccionParaTi").hidden = !(ver && hayParaTi);
    }

    // Productos que se parecen a lo que la persona ya vio, marco como favorito o compro (el servidor los ordena)
    async function cargarParaTi() {
        const perfil = H.perfilTexto();
        if (!perfil) { hayParaTi = false; $("seccionParaTi").hidden = true; return; }
        let d;
        try { d = await pedir("/api/tienda/recomendados?limite=10&perfil=" + encodeURIComponent(perfil)); } catch (e) { return; }
        if (!d || !d.ok || !d.items || !d.items.length) { hayParaTi = false; $("seccionParaTi").hidden = true; return; }
        paraTiMapa = {};
        d.items.forEach((p) => { paraTiMapa[p.sku] = p; });
        $("carrilParaTi").innerHTML = d.items.map(tarjeta).join("");
        hayParaTi = true;
        $("seccionParaTi").hidden = !(!estado.categoria && !estado.marca && !estado.consulta && !estado.orden && estado.pagina === 1);
    }

    // Si la persona busco escribiendo y agrega un producto de los resultados, se mide su posicion en la lista
    function medirClic(tarj, p, accion) {
        if (!estado.consulta || !tarj.closest("#rejilla")) return;
        const posicion = [...$("rejilla").querySelectorAll(".producto")].indexOf(tarj) + 1;
        if (posicion > 0) H.terminar(p, posicion, accion);
    }

    // ---- Portada dinámica: producto destacado que rota solo y cambia el color del banner
    let heroItems = [], heroIdx = 0, heroTimer = null;
    const esEscritorio = () => window.matchMedia("(min-width: 901px)").matches;

    // Cada cambio de producto: la tarjeta sale hacia un lado y la nueva entra desde el lado contrario.
    // En el cambio automático el sentido se alterna (derecha, izquierda, derecha...).
    let heroSentido = -1, heroCambio = null;
    function mostrarHero(i, sentido) {
        const caja = $("heroShow");
        if (!heroItems.length) return;
        heroIdx = (i + heroItems.length) % heroItems.length;
        const p = heroItems[heroIdx];
        const tarjeta = $("hsCard");
        caja.querySelectorAll(".hs-dot").forEach((d, k) => d.classList.toggle("on", k === heroIdx));
        const pintar = (entra) => {
            tarjeta.className = "hs-card";
            tarjeta.dataset.sku = p.sku;
            tarjeta.innerHTML = `
            <div class="hs-foto"><img src="${esc(window.urlFotoProducto(p))}" alt="${esc(p.nombre)}" decoding="async" referrerpolicy="no-referrer" onerror="window.heroFalla()"></div>
            <div class="hs-info">
                <span class="hs-marca">${esc(p.marca)}</span>
                <b class="hs-nombre">${esc(p.nombre)}</b>
                <div class="hs-pie"><span class="hs-precio">${dinero(p.precio)}</span><button type="button" class="btn-agregar" data-hs="agregar">Agregar</button></div>
            </div>`;
            void tarjeta.offsetWidth;
            tarjeta.classList.add(entra);
        };
        clearTimeout(heroCambio);
        if (!tarjeta.dataset.sku || window.matchMedia("(prefers-reduced-motion: reduce)").matches) { pintar("entra"); return; }
        if (!sentido) { heroSentido = -heroSentido; sentido = heroSentido; }      // 1 = sale a la derecha, -1 = a la izquierda
        tarjeta.className = "hs-card " + (sentido > 0 ? "sale-d" : "sale-i");
        heroCambio = setTimeout(() => pintar(sentido > 0 ? "entra-i" : "entra-d"), 260);
    }

    function arrancarHero() {
        clearInterval(heroTimer);
        if (heroItems.length < 2 || !esEscritorio() || window.matchMedia("(prefers-reduced-motion: reduce)").matches) return;
        heroTimer = setInterval(() => mostrarHero(heroIdx + 1), 5500);
    }

    window.heroFalla = function () {
        heroItems.splice(heroIdx, 1);
        if (!heroItems.length) { $("heroShow").innerHTML = ""; return; }
        pintarHero(null, true);
    };

    function pintarHero(items, rehacer) {
        const caja = $("heroShow");
        if (!caja || !window.urlFotoProducto) return;
        if (!rehacer) heroItems = items.filter((p) => p.foto && p.stock > 0).slice(0, 5);
        if (!heroItems.length) return;
        caja.innerHTML = '<div class="hs-card" id="hsCard"></div><div class="hs-dots">' +
            heroItems.map((_, k) => `<button type="button" class="hs-dot" data-i="${k}" aria-label="Ver producto ${k + 1}"></button>`).join("") + "</div>";
        mostrarHero(rehacer ? Math.min(heroIdx, heroItems.length - 1) : 0);
        caja.onclick = (e) => {
            const dot = e.target.closest(".hs-dot");
            if (dot) { const n = Number(dot.dataset.i); mostrarHero(n, n > heroIdx ? -1 : 1); arrancarHero(); return; }
            if (e.target.closest('[data-hs="agregar"]')) {
                const p = heroItems[heroIdx];
                volar($("hsCard").querySelector(".hs-foto"));
                agregarAlCarrito(p, 1);
            }
        };
        caja.onmouseenter = () => clearInterval(heroTimer);
        caja.onmouseleave = arrancarHero;
        arrancarHero();
    }

    // ---- Mosaico "Compra por categoría" con la foto de un producto de cada una
    function pintarTiles(items) {
        const cont = $("tilesCategorias");
        if (!cont) return;
        const vistos = new Set(), tiles = [];
        items.forEach((p) => { if (p.foto && p.categoria && !vistos.has(p.categoria)) { vistos.add(p.categoria); tiles.push(p); } });
        if (tiles.length < 3) return;
        cont.innerHTML = tiles.map((p, k) => `<button type="button" class="tile t${k % 5}" data-cat="${esc(p.categoria)}">
            <span class="tile-foto"><img src="${esc(window.urlFotoProducto(p))}" alt="" loading="lazy" decoding="async" referrerpolicy="no-referrer" onerror="this.remove()"></span>
            <span class="tile-nombre">${esc(p.categoria)}</span></button>`).join("");
        $("seccionTiles").hidden = false;
        cont.onclick = (e) => {
            const b = e.target.closest(".tile");
            if (!b) return;
            $("cajaBuscar").value = ""; $("btnLimpiar").classList.remove("visible");
            estado.categoria = b.dataset.cat; estado.marca = ""; estado.pagina = 1;
            marcarCategoria(); cargarProductos();
            $("tiendaCuerpo").scrollIntoView({ behavior: "smooth", block: "start" });
        };
    }

    // ---- Aparición suave de secciones al hacer scroll
    function revelar() {
        const els = document.querySelectorAll(".reveal");
        if (!("IntersectionObserver" in window)) { els.forEach((e) => e.classList.add("visto")); return; }
        const ob = new IntersectionObserver((lista) => lista.forEach((x) => { if (x.isIntersecting) { x.target.classList.add("visto"); ob.unobserve(x.target); } }), { threshold: .12 });
        els.forEach((e) => ob.observe(e));
    }

    async function cargarDestacados() {
        let d;
        try { d = await pedir("/api/tienda/destacados"); } catch (e) { return; }
        if (!d || !d.ok || !d.items || !d.items.length) return;
        destacadosMapa = {};
        d.items.forEach((p) => { destacadosMapa[p.sku] = p; });
        $("carrilDestacados").innerHTML = d.items.map(tarjeta).join("");
        pintarHero(d.items);
        pintarTiles(d.items);
        hayDestacados = true;
        mostrarDestacados(!estado.categoria && !estado.marca && !estado.consulta && !estado.orden && estado.pagina === 1);
    }

    function alternarFavorito(sku, boton) {
        const activo = !favoritos.has(sku);
        if (activo) favoritos.add(sku); else favoritos.delete(sku);
        try { localStorage.setItem("alicorp_favoritos", JSON.stringify([...favoritos])); } catch (e) { /* sin almacenamiento */ }
        document.querySelectorAll(".producto").forEach((t) => {
            if (t.dataset.sku !== sku) return;
            const f = t.querySelector(".fav");
            f.classList.toggle("on", activo);
        });
        aviso(activo ? "Guardado en tus favoritos" : "Quitado de tus favoritos");
    }

    function pintarProductos(items) {
        enPantalla = {};
        items.forEach((p) => { enPantalla[p.sku] = p; });
        $("rejilla").innerHTML = items.length
            ? items.map(tarjeta).join("")
            : `<div class="vacio" style="grid-column:1/-1"><span class="grande">${ICON.search}</span>No encontramos productos para esa búsqueda.<br>Prueba con otras palabras, por ejemplo "aceite" o "detergente".</div>`;
    }

    function esqueletos() {
        $("rejilla").innerHTML = Array.from({ length: 8 }, () => '<div class="esqueleto"></div>').join("");
    }

    function llenarMarcas(marcas) {
        const sel = $("selMarca");
        const actual = estado.marca;
        sel.innerHTML = '<option value="">Todas las marcas</option>' + marcas.map((m) => `<option value="${esc(m)}"${m === actual ? " selected" : ""}>${esc(m)}</option>`).join("");
    }

    // ---------------------------------------------------------------- listado normal (consulta SQL por categoría / marca / orden)
    async function cargarProductos() {
        estado.consulta = "";
        $("cintaInteligente").classList.remove("visible");
        mostrarDestacados(!estado.categoria && !estado.marca && !estado.orden && estado.pagina === 1);
        esqueletos();
        const params = new URLSearchParams({ pagina: estado.pagina, por_pagina: 24 });
        if (estado.categoria) params.set("categoria", estado.categoria);
        if (estado.marca) params.set("marca", estado.marca);
        if (estado.orden) params.set("orden", estado.orden);
        let d;
        try { d = await pedir("/api/tienda/productos?" + params); } catch (e) { d = { error: "No se pudo conectar con el servidor." }; }
        if (!d.ok) {
            $("rejilla").innerHTML = `<div class="vacio" style="grid-column:1/-1"><span class="grande">${ICON.warn}</span>${esc(d.error || "No se pudieron cargar los productos.")}</div>`;
            return;
        }
        $("tituloLista").textContent = estado.categoria || "Todos los productos";
        $("conteoLista").textContent = d.total.toLocaleString("es-PE") + " productos";
        llenarMarcas(d.marcas);
        pintarProductos(d.items);
        pintarPaginacion(d.pagina, d.paginas);
    }

    function pintarPaginacion(pagina, paginas) {
        const cont = $("paginacion");
        if (paginas <= 1) { cont.innerHTML = ""; return; }
        cont.innerHTML = `<button type="button" data-pag="${pagina - 1}" ${pagina <= 1 ? "disabled" : ""}>Anterior</button>
            <span>Página ${pagina} de ${paginas}</span>
            <button type="button" data-pag="${pagina + 1}" ${pagina >= paginas ? "disabled" : ""}>Siguiente</button>`;
    }

    // ---------------------------------------------------------------- búsqueda predictiva IA (Árbol Trie Recursivo + Smile ML)
    function ocultarAutocompletado() {
        const box = $("autocompletadoIA");
        if (box) box.classList.remove("visible");
    }

    function pintarAutocompletado(datos) {
        const box = $("autocompletadoIA");
        if (!box) return;
        const lista = estado.modoIA === "ia" ? (datos.resultados_ia || []) : (datos.resultados_tradicional || []);
        if (!lista.length || !estado.consulta) {
            box.classList.remove("visible");
            return;
        }
        const top = lista.slice(0, 6);
        const modoTxt = estado.modoIA === "ia" ? "Ranking Inteligente (Smile ML)" : "Orden Tradicional (A-Z)";
        box.innerHTML =
            `<div class="auto-cabecera"><span>⚡ Árbol Trie Recursivo · ${modoTxt}</span><span>${datos.tiempo_recursivo_ms} ms (${datos.total_encontrados} coincidencias)</span></div>` +
            top.map((p) => {
                const badge = estado.modoIA === "ia" && typeof p.puntaje_ia === "number"
                    ? `<span style="background:#1e3a8a;color:#fff;padding:2px 8px;border-radius:999px;font-size:.72rem;font-weight:800;">IA ${p.puntaje_ia.toFixed(1)}%</span>`
                    : `<span style="background:#e2e8f0;color:#334155;padding:2px 8px;border-radius:999px;font-size:.72rem;font-weight:700;">#${p.posicion_tradicional || ""}</span>`;
                return `<button type="button" class="auto-item" data-auto-q="${esc(p.nombre)}">
                    <div class="auto-izq">
                        <span class="auto-nom">${esc(p.nombre)}</span>
                        <span class="auto-sub">${esc(p.marca)} · ${esc(p.categoria)} · Stock: ${Number(p.stock || 0).toLocaleString("es-PE")}</span>
                    </div>
                    <div class="auto-der">
                        <strong style="color:#1e3a8a;font-size:.86rem;">${dinero(p.precio)}</strong>
                        ${badge}
                    </div>
                </button>`;
            }).join("");
        box.classList.add("visible");
    }

    function renderizarComparativaIA() {
        const d = estado.busquedaIA;
        if (!d) return;
        const listaActiva = estado.modoIA === "ia" ? (d.resultados_ia || []) : (d.resultados_tradicional || []);
        estado.resultadosBusqueda = listaActiva;

        const modoLabel = estado.modoIA === "ia" ? "Ranking Inteligente (IA)" : "Orden Tradicional (Alfabético)";
        $("tituloLista").textContent = 'Resultados para "' + d.query + '" · ' + modoLabel;
        $("conteoLista").textContent = d.total_encontrados + " productos";
        llenarMarcas([...new Set(listaActiva.map((p) => p.marca))].sort());
        aplicarFiltrosBusqueda();

        const metrica = $("metricaTrie");
        if (metrica) {
            metrica.textContent = `⚡ Trie recursivo: ${d.tiempo_recursivo_ms} ms · ${d.total_encontrados} hallados`;
            metrica.style.display = "inline-block";
        }

        const cinta = $("cintaInteligente");
        if (estado.modoIA === "ia") {
            cinta.innerHTML = `<span class="cerebro">${ICON.search}</span>
                <span><strong>Ranking Inteligente (Smile ML)</strong> activo para <span class="etiqueta">${esc(d.tienda)}</span> · Prioriza rotación histórica, stock disponible y afinidad.${d.personalizado ? " Además se ajusta a lo que has visto y comprado." : ""}</span>
                <small>Trie recursivo: ${d.tiempo_recursivo_ms} ms · Fuente: Supabase PostgreSQL</small>`;
        } else {
            cinta.innerHTML = `<span class="cerebro">${ICON.search}</span>
                <span><strong>Orden Tradicional (Alfabético A-Z)</strong> activo · Alterna a <em>Ranking Inteligente (IA)</em> para comparar la relevancia comercial.</span>
                <small>Trie recursivo: ${d.tiempo_recursivo_ms} ms · ${d.total_encontrados} coincidencias</small>`;
        }
        cinta.classList.add("visible");
    }

    async function buscar(texto, mostrarDropdown = false) {
        const q = texto.trim();
        if (!q) { limpiarBusqueda(); return; }
        estado.consulta = q;
        mostrarDestacados(false);
        $("btnLimpiar").classList.add("visible");
        if (!mostrarDropdown) {
            ocultarAutocompletado();
            esqueletos();
        }
        $("paginacion").innerHTML = "";
        let d;
        const params = new URLSearchParams({ q, limite: 48 });
        if (estado.tiendaIA) params.set("tienda", estado.tiendaIA);
        try { d = await pedir("/api/buscador-ia?" + params.toString() + H.parametros()); } catch (e) { d = { error: "No se pudo conectar con el servidor." }; }
        if (!d.success && !d.ok) {
            $("rejilla").innerHTML = `<div class="vacio" style="grid-column:1/-1"><span class="grande">⚠️</span>${esc(d.error || "Error al buscar.")}</div>`;
            return;
        }
        estado.busquedaIA = d;
        estado.categoria = ""; marcarCategoria();
        renderizarComparativaIA();
        if (mostrarDropdown) pintarAutocompletado(d);
    }

    function aplicarFiltrosBusqueda() {
        let items = estado.resultadosBusqueda.slice();
        if (estado.marca) items = items.filter((p) => p.marca === estado.marca);
        if (estado.orden === "precio_asc") items.sort((a, b) => a.precio - b.precio);
        else if (estado.orden === "precio_desc") items.sort((a, b) => b.precio - a.precio);
        else if (estado.orden === "nombre") items.sort((a, b) => a.nombre.localeCompare(b.nombre, "es"));
        pintarProductos(items);
    }

    function limpiarBusqueda() {
        $("cajaBuscar").value = "";
        $("btnLimpiar").classList.remove("visible");
        ocultarAutocompletado();
        const metrica = $("metricaTrie");
        if (metrica) metrica.style.display = "none";
        estado.consulta = "";
        estado.busquedaIA = null;
        estado.marca = ""; estado.pagina = 1;
        cargarProductos();
    }

    // ---------------------------------------------------------------- animaciones y extras del carrito
    function volar(origen) {
        if (!origen || !window.Element.prototype.animate) return;
        const img = origen.querySelector("img");
        const r = origen.getBoundingClientRect();
        const destino = $("btnCarrito").getBoundingClientRect();
        if (!r.width || !destino.width) return;
        const el = document.createElement("div");
        el.className = "vuela";
        el.innerHTML = img ? `<img src="${esc(img.src)}" alt="">` : '<span style="display:grid;place-items:center;height:100%;width:100%;color:#6b7280">' + ICON.cart + '</span>';
        const lado = 90;
        el.style.cssText += `width:${lado}px;height:${lado}px;left:${r.left + r.width / 2 - lado / 2}px;top:${r.top + r.height / 2 - lado / 2}px;`;
        document.body.appendChild(el);
        const dx = destino.left + destino.width / 2 - (r.left + r.width / 2);
        const dy = destino.top + destino.height / 2 - (r.top + r.height / 2);
        const anim = el.animate(
            [{ transform: "translate(0,0) scale(1)", opacity: 1 }, { transform: `translate(${dx * 0.6}px,${dy * 0.6 - 60}px) scale(.7)`, opacity: .95, offset: .6 }, { transform: `translate(${dx}px,${dy}px) scale(.12)`, opacity: .2 }],
            { duration: 750, easing: "cubic-bezier(.5,0,.75,0)" });
        anim.onfinish = () => el.remove();
    }

    function actualizarExtras(t) {
        $("bmUnidades").textContent = t.unidades > 999 ? "999+" : t.unidades;
        $("bmTotal").textContent = dinero(t.total);
        $("barraMovil").classList.toggle("visible", t.lineas > 0);
        document.body.classList.toggle("con-barra", t.lineas > 0);
    }

    // ---------------------------------------------------------------- carrito
    function totales() {
        const lineas = Object.values(carrito);
        return {
            lineas: lineas.length,
            unidades: lineas.reduce((s, l) => s + l.cantidad, 0),
            total: lineas.reduce((s, l) => s + l.cantidad * l.precio, 0),
        };
    }

    function agregarAlCarrito(p, cantidad) {
        cantidad = Math.floor(Number(cantidad));
        if (!cantidad || cantidad < 1) { aviso("Escribe una cantidad válida."); return; }
        const actual = carrito[p.sku] ? carrito[p.sku].cantidad : 0;
        let nueva = actual + cantidad;
        const tope = Math.min(p.stock, LIMITE_LINEA);
        if (nueva > tope) {
            nueva = tope;
            aviso("Solo hay " + tope.toLocaleString("es-PE") + " unidades disponibles de este producto.");
        } else {
            aviso(cantidad.toLocaleString("es-PE") + " x " + p.nombre + " agregado al carrito");
        }
        carrito[p.sku] = { sku: p.sku, nombre: p.nombre, foto: p.foto, marca: p.marca, categoria: p.categoria, presentacion: p.presentacion, precio: p.precio, imagen: p.imagen, stock: p.stock, cantidad: nueva };
        H.registrar("agregar", p);
        guardarCarrito();
        pintarCarrito();
        const b = $("btnCarrito");
        b.classList.remove("rebote"); void b.offsetWidth; b.classList.add("rebote");
    }

    function cambiarCantidad(sku, nueva) {
        const l = carrito[sku];
        if (!l) return;
        nueva = Math.floor(Number(nueva));
        if (!nueva || nueva < 1) nueva = 1;
        const tope = Math.min(l.stock, LIMITE_LINEA);
        if (nueva > tope) { nueva = tope; aviso("Máximo disponible: " + tope.toLocaleString("es-PE")); }
        l.cantidad = nueva;
        guardarCarrito();
        pintarCarrito();
    }

    function pintarCarrito() {
        const t = totales();
        $("contadorCarrito").textContent = t.unidades > 999 ? "999+" : t.unidades;
        $("resLineas").textContent = t.lineas;
        $("resUnidades").textContent = t.unidades.toLocaleString("es-PE");
        $("resTotal").textContent = dinero(t.total);
        $("btnComprar").disabled = t.lineas === 0;
        actualizarExtras(t);
        const lista = $("carritoLista");
        const lineas = Object.values(carrito);
        if (!lineas.length) {
            lista.innerHTML = `<div class="vacio" style="margin-top:20px"><span class="grande">${ICON.cart}</span>Tu carrito está vacío.<br>Agrega productos desde la tienda.</div>`;
            return;
        }
        lista.innerHTML = lineas.map((l) => `
            <div class="linea" data-sku="${esc(l.sku)}">
                <div class="mini">${window.fotoProducto(l)}</div>
                <div>
                    <div class="n">${esc(l.nombre)}</div>
                    <div class="p">${esc(l.marca)} · ${dinero(l.precio)} c/u</div>
                    <div class="controles">
                        <div class="cantidad">
                            <button type="button" data-ca="menos" aria-label="Menos">−</button>
                            <input type="number" min="1" value="${l.cantidad}" data-ca="input" aria-label="Cantidad">
                            <button type="button" data-ca="mas" aria-label="Más">+</button>
                        </div>
                        <button type="button" class="btn-agregar" style="flex:none;padding:0 10px" data-ca="diez">+10</button>
                        <button type="button" class="btn-agregar" style="flex:none;padding:0 10px" data-ca="cien">+100</button>
                        <button type="button" class="quitar" data-ca="quitar">Quitar</button>
                    </div>
                </div>
                <div class="sub">${dinero(l.cantidad * l.precio)}</div>
            </div>`).join("");
    }

    function abrirCarrito(abrir) {
        $("carritoPanel").classList.toggle("visible", abrir);
        $("fondoOscuro").classList.toggle("visible", abrir);
        document.body.classList.toggle("carrito-abierto", abrir);
    }

    // ---------------------------------------------------------------- finalizar compra
    function abrirModalCompra() {
        const t = totales();
        if (!t.lineas) return;
        $("cajaCompra").innerHTML = `
            <div class="pasos-compra"><span class="ok">1. Carrito</span><span class="act">2. Tus datos</span><span>3. Confirmación</span></div>
            <h2>Finalizar compra</h2>
            <p class="sub">${t.lineas} producto(s) · ${t.unidades.toLocaleString("es-PE")} unidades · <b>${dinero(t.total)}</b></p>
            <details class="resumen-compra">
                <summary>Ver resumen de mi pedido</summary>
                ${Object.values(carrito).map((l) => `<div class="it"><span>${esc(l.cantidad)} x ${esc(l.nombre)}</span><b>${dinero(l.cantidad * l.precio)}</b></div>`).join("")}
            </details>
            <form id="formCompra" autocomplete="on">
                <label for="cNombre">Nombre completo</label>
                <input id="cNombre" required minlength="2" maxlength="120" placeholder="Ej: María Pérez">
                <label for="cCorreo">Correo electrónico</label>
                <input id="cCorreo" type="email" required maxlength="120" placeholder="tucorreo@ejemplo.com">
                <label for="cTelefono">Teléfono (opcional)</label>
                <input id="cTelefono" maxlength="40" placeholder="999 999 999">
                <label for="cDireccion">Dirección de entrega</label>
                <textarea id="cDireccion" rows="2" maxlength="300" placeholder="Calle, número, distrito"></textarea>
                <div class="error" id="errCompra"></div>
                <div class="fila-botones">
                    <button type="button" class="btn-secundario" id="cCancelar">Volver</button>
                    <button type="submit" class="btn-grande" id="cConfirmar">Confirmar compra</button>
                </div>
            </form>`;
        $("modalCompra").classList.add("visible");
        $("cNombre").focus();
        $("cCancelar").onclick = () => $("modalCompra").classList.remove("visible");
        $("formCompra").onsubmit = enviarCompra;
    }

    async function enviarCompra(ev) {
        ev.preventDefault();
        const err = $("errCompra");
        err.classList.remove("visible");
        const boton = $("cConfirmar");
        boton.disabled = true; boton.textContent = "Enviando...";
        const cuerpo = {
            nombre: $("cNombre").value, correo: $("cCorreo").value, telefono: $("cTelefono").value, direccion: $("cDireccion").value,
            items: Object.values(carrito).map((l) => ({ sku: l.sku, cantidad: l.cantidad })),
        };
        let d;
        try {
            d = await pedir("/api/tienda/comprar", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(cuerpo) });
        } catch (e) { d = { ok: false, error: "No se pudo conectar con el servidor. Intenta otra vez." }; }
        if (!d.ok) {
            err.textContent = d.error || "No se pudo completar la compra.";
            err.classList.add("visible");
            boton.disabled = false; boton.textContent = "Confirmar compra";
            return;
        }
        Object.values(carrito).forEach((l) => H.registrar("comprar", l));
        carrito = {}; guardarCarrito(); pintarCarrito(); abrirCarrito(false);
        $("cajaCompra").innerHTML = `
            <div class="exito">
                <div class="pasos-compra"><span class="ok">1. Carrito</span><span class="ok">2. Tus datos</span><span class="ok">3. Confirmación</span></div>
                <div class="check">${ICON.check}</div>
                <h2>Compra registrada</h2>
                <p class="sub">Gracias por tu compra. Te contactaremos para coordinar la entrega.</p>
                <dl class="num">
                    <div><dt>N.º de compra</dt><dd>${esc(d.compra_id)}</dd></div>
                    <div><dt>Total</dt><dd>${dinero(d.total)}</dd></div>
                </dl>
                ${d.correo_enviado ? `<p class="aviso-correo">${ICON.mail}<span>Enviamos el seguimiento de tu pedido a <b>${esc(cuerpo.correo)}</b>. Si no lo ves, revisa la carpeta de spam.</span></p>` : `<p class="aviso-correo">${ICON.mail}<span>Tu compra quedó registrada, pero no pudimos enviar el correo ahora. Guarda tu número de compra para consultar el estado.</span></p>`}
                <p class="sub">Guarda tu número de compra: con él y tu correo puedes ver el estado en <b>Mi pedido</b>.</p>
                <button type="button" class="btn-secundario" id="cVerPedido" style="width:100%;margin-bottom:8px;">Ver estado de mi pedido</button>
                <button type="button" class="btn-grande" id="cSeguir">Seguir comprando</button>
            </div>`;
        recordarPedido(d.compra_id, cuerpo.correo);
        $("cVerPedido").onclick = () => abrirModalPedido(d.compra_id, cuerpo.correo);
        $("cSeguir").onclick = () => { $("modalCompra").classList.remove("visible"); cargarProductos(); };
    }


    // ---------------------------------------------------------------- consultar mi pedido
    const ESTADOS = ["pendiente", "preparando", "enviado", "entregado"];
    const ETIQUETA = { pendiente: "Recibido", preparando: "Preparando", enviado: "En camino", entregado: "Entregado" };
    const MENSAJE = {
        pendiente: "Recibimos tu pedido. En breve empezamos a prepararlo.",
        preparando: "Estamos preparando tu pedido.",
        enviado: "Tu pedido va en camino.",
        entregado: "Tu pedido fue entregado. Gracias por tu compra.",
    };

    function recordarPedido(id, correo) {
        try { localStorage.setItem("alicorp_ultimo_pedido", JSON.stringify({ id: String(id), correo })); } catch (e) { /* sin almacenamiento */ }
    }
    function ultimoPedido() {
        try { return JSON.parse(localStorage.getItem("alicorp_ultimo_pedido")) || {}; } catch (e) { return {}; }
    }

    function abrirModalPedido(idInicial, correoInicial) {
        const u = ultimoPedido();
        $("cajaCompra").innerHTML = `
            <h2>Mi pedido</h2>
            <p class="sub">Escribe el número de compra y el correo con el que compraste.</p>
            <form id="formPedido" autocomplete="on">
                <label for="pId">N.º de compra</label>
                <input id="pId" required maxlength="30" placeholder="Ej: 15" value="${esc(idInicial || u.id || "")}">
                <label for="pCorreo">Correo electrónico</label>
                <input id="pCorreo" type="email" required maxlength="120" placeholder="tucorreo@ejemplo.com" value="${esc(correoInicial || u.correo || "")}">
                <div class="error" id="errPedido"></div>
                <div class="fila-botones">
                    <button type="button" class="btn-secundario" id="pCerrar">Cerrar</button>
                    <button type="submit" class="btn-grande" id="pConsultar">Consultar</button>
                </div>
            </form>
            <div id="resPedido"></div>`;
        $("modalCompra").classList.add("visible");
        $("pCerrar").onclick = () => $("modalCompra").classList.remove("visible");
        $("formPedido").onsubmit = consultarPedido;
    }

    async function consultarPedido(ev) {
        ev.preventDefault();
        const err = $("errPedido"); err.classList.remove("visible");
        $("resPedido").innerHTML = "";
        const boton = $("pConsultar"); boton.disabled = true; boton.textContent = "Buscando...";
        let d;
        try {
            d = await pedir("/api/tienda/pedido", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ compra_id: $("pId").value, correo: $("pCorreo").value }) });
        } catch (e) { d = { ok: false, error: "No se pudo conectar con el servidor. Intenta otra vez." }; }
        boton.disabled = false; boton.textContent = "Consultar";
        if (!d.ok) { err.textContent = d.error || "No se pudo consultar el pedido."; err.classList.add("visible"); return; }
        recordarPedido($("pId").value, $("pCorreo").value);
        const p = d.pedido;
        const estado = String(p.estado || "pendiente").toLowerCase();
        const pos = ESTADOS.indexOf(estado);
        const pasos = ESTADOS.map((e, i) => `<div class="paso ${pos >= 0 && i < pos ? "hecho" : ""} ${i === pos ? "actual" : ""}"><i>${pos >= 0 && i < pos ? ICON.check : i + 1}</i>${ETIQUETA[e]}</div>`).join("");
        const prog = pos <= 0 ? 0 : Math.round((pos / (ESTADOS.length - 1)) * 100);
        const fecha = p.fecha ? new Date(p.fecha).toLocaleString("es-PE") : "";
        $("resPedido").innerHTML = `
            <div class="pasos" style="--prog:${prog}%">${pasos}</div>
            ${pos >= 0 ? `<div class="mensaje-estado">${MENSAJE[estado]}</div>` : ""}
            ${pos < 0 ? `<p class="sub">Estado: <b>${esc(p.estado)}</b></p>` : ""}
            <p class="sub">Compra N.º ${esc(p.id)} · ${esc(fecha)}<br>A nombre de: ${esc(p.cliente || "")}</p>
            <div class="pedido-lista">${(p.items || []).map((i) => `<div><span>${esc(i.cantidad)} x ${esc(i.producto)}</span><b>${dinero(i.subtotal)}</b></div>`).join("")}</div>
            <p class="sub"><b>Total: ${dinero(p.total)}</b></p>`;
    }

    // ---------------------------------------------------------------- eventos
    function conectarEventos() {
        $("btnCarrito").onclick = () => abrirCarrito(true);
        $("btnCerrarCarrito").onclick = () => abrirCarrito(false);
        $("fondoOscuro").onclick = () => abrirCarrito(false);
        $("btnComprar").onclick = abrirModalCompra;
        $("btnPedido").onclick = () => abrirModalPedido();
        $("btnSeguir").onclick = () => abrirCarrito(false);
        $("barraMovil").onclick = () => abrirCarrito(true);
        $("dstPrev").onclick = () => $("carrilDestacados").scrollBy({ left: -480, behavior: "smooth" });
        $("dstNext").onclick = () => $("carrilDestacados").scrollBy({ left: 480, behavior: "smooth" });
        $("btnVaciar").onclick = () => { if (Object.keys(carrito).length && confirm("¿Vaciar todo el carrito?")) { carrito = {}; guardarCarrito(); pintarCarrito(); } };
        $("modalCompra").onclick = (e) => { if (e.target === $("modalCompra")) $("modalCompra").classList.remove("visible"); };
        document.addEventListener("keydown", (e) => { if (e.key === "Escape") { abrirCarrito(false); $("modalCompra").classList.remove("visible"); } });

        $("formBuscar").onsubmit = (e) => { e.preventDefault(); clearTimeout(conectarEventos._t); buscar($("cajaBuscar").value, false); };
        $("btnLimpiar").onclick = limpiarBusqueda;
        $("cajaBuscar").addEventListener("input", () => {
            const val = $("cajaBuscar").value;
            H.tecla(val);
            $("btnLimpiar").classList.toggle("visible", val.length > 0);
            clearTimeout(conectarEventos._t);
            if (!val.trim()) { ocultarAutocompletado(); return; }
            conectarEventos._t = setTimeout(() => {
                if ($("cajaBuscar").value.trim().length >= 2) buscar($("cajaBuscar").value, true);
            }, 220);
        });
        if ($("autocompletadoIA")) {
            $("autocompletadoIA").onclick = (e) => {
                const item = e.target.closest(".auto-item");
                if (!item) return;
                const qSel = item.dataset.autoQ;
                $("cajaBuscar").value = qSel;
                ocultarAutocompletado();
                buscar(qSel, false);
            };
        }
        document.addEventListener("click", (e) => {
            if (!e.target.closest(".buscador-envoltura")) ocultarAutocompletado();
        });
        if ($("switchModoIA")) {
            $("switchModoIA").querySelectorAll(".modo-btn").forEach((b) => b.classList.toggle("activo", b.dataset.modo === estado.modoIA));
            $("switchModoIA").onclick = (e) => {
                const btn = e.target.closest(".modo-btn");
                if (!btn) return;
                estado.modoIA = btn.dataset.modo;
                H.ponerModo(estado.modoIA === "ia" ? "ia" : "alfabetico");
                $("switchModoIA").querySelectorAll(".modo-btn").forEach((b) => b.classList.toggle("activo", b === btn));
                if (estado.busquedaIA) {
                    renderizarComparativaIA();
                    if ($("autocompletadoIA") && $("autocompletadoIA").classList.contains("visible")) {
                        pintarAutocompletado(estado.busquedaIA);
                    }
                }
            };
        }
        if ($("selTiendaIA")) {
            $("selTiendaIA").onchange = () => {
                estado.tiendaIA = $("selTiendaIA").value;
                if (estado.consulta) buscar(estado.consulta, false);
            };
        }
        $("sugerencias").onclick = (e) => {
            const b = e.target.closest("button[data-q]");
            if (b) { $("cajaBuscar").value = b.dataset.q; buscar(b.dataset.q, false); }
        };

        $("panelCategorias").onclick = (e) => {
            const b = e.target.closest(".cat-item");
            if (!b) return;
            $("cajaBuscar").value = ""; $("btnLimpiar").classList.remove("visible");
            estado.categoria = b.dataset.cat; estado.marca = ""; estado.pagina = 1;
            marcarCategoria(); cargarProductos();
        };
        $("selMarca").onchange = () => {
            estado.marca = $("selMarca").value; estado.pagina = 1;
            if (estado.consulta) aplicarFiltrosBusqueda(); else cargarProductos();
        };
        $("selOrden").onchange = () => {
            estado.orden = $("selOrden").value; estado.pagina = 1;
            if (estado.consulta) aplicarFiltrosBusqueda(); else cargarProductos();
        };
        $("paginacion").onclick = (e) => {
            const b = e.target.closest("button[data-pag]");
            if (!b || b.disabled) return;
            estado.pagina = Number(b.dataset.pag);
            cargarProductos();
            window.scrollTo({ top: 300, behavior: "smooth" });
        };

        // Tarjetas de producto (la rejilla y el carril de destacados usan el mismo manejador)
        const clicTarjeta = (e) => {
            const boton = e.target.closest("button[data-accion]");
            if (!boton) return;
            const tarj = boton.closest(".producto");
            const accion = boton.dataset.accion;
            const p = enPantalla[tarj.dataset.sku] || destacadosMapa[tarj.dataset.sku] || paraTiMapa[tarj.dataset.sku];
            if (accion === "fav") {
                alternarFavorito(tarj.dataset.sku, boton);
                if (p && favoritos.has(p.sku)) H.registrar("fav", p);
                return;
            }
            if (!p) return;
            const campo = tarj.querySelector('[data-campo="cant"]');
            const valor = Math.max(1, Math.floor(Number(campo.value)) || 1);
            if (accion === "mas") campo.value = valor + 1;
            else if (accion === "menos") campo.value = Math.max(1, valor - 1);
            else if (accion === "fijar") campo.value = boton.dataset.n;
            else if (accion === "agregar") { volar(tarj.querySelector(".foto")); agregarAlCarrito(p, valor); campo.value = 1; medirClic(tarj, p, "agregar"); }
        };
        $("rejilla").onclick = clicTarjeta;
        $("carrilDestacados").onclick = clicTarjeta;
        $("carrilParaTi").onclick = clicTarjeta;
        $("prtPrev").onclick = () => $("carrilParaTi").scrollBy({ left: -480, behavior: "smooth" });
        $("prtNext").onclick = () => $("carrilParaTi").scrollBy({ left: 480, behavior: "smooth" });

        // Líneas del carrito
        $("carritoLista").onclick = (e) => {
            const b = e.target.closest("[data-ca]");
            if (!b || b.dataset.ca === "input") return;
            const sku = b.closest(".linea").dataset.sku;
            const l = carrito[sku];
            const a = b.dataset.ca;
            if (a === "mas") cambiarCantidad(sku, l.cantidad + 1);
            else if (a === "menos") cambiarCantidad(sku, l.cantidad - 1);
            else if (a === "diez") cambiarCantidad(sku, l.cantidad + 10);
            else if (a === "cien") cambiarCantidad(sku, l.cantidad + 100);
            else if (a === "quitar") { delete carrito[sku]; guardarCarrito(); pintarCarrito(); }
        };
        $("carritoLista").onchange = (e) => {
            const i = e.target.closest('input[data-ca="input"]');
            if (i) cambiarCantidad(i.closest(".linea").dataset.sku, i.value);
        };
    }

    // ---------------------------------------------------------------- inicio
    document.addEventListener("DOMContentLoaded", async () => {
        cargarCarrito();
        revelar();
        conectarEventos();
        pintarCarrito();
        esqueletos();
        try { await cargarCategorias(); } catch (e) { /* si falla, se muestra el error al cargar productos */ }
        cargarDestacados();
        cargarParaTi();
        // Si llegan desde la portada con ?q=aceite, se busca directamente
        const parametros = new URLSearchParams(location.search);
        const q = parametros.get("q");
        const cat = parametros.get("cat");
        if (q) { $("cajaBuscar").value = q; buscar(q); }
        else {
            if (cat && estado.categorias.some((c) => c.categoria === cat)) { estado.categoria = cat; marcarCategoria(); }
            cargarProductos();
        }
        // Enlaces de la portada: ?pedido=1 abre "Mi pedido" y ?carrito=1 abre el carrito
        if (parametros.get("pedido") === "1") abrirModalPedido();
        else if (parametros.get("carrito") === "1") abrirCarrito(true);
    });
})();
