/* Portada de la tienda: hero dinámico, buscador, pestañas con "Agregar", categorías, destacados y carrito flotante.
   Comparte el carrito con tienda.html mediante localStorage ("alicorp_carrito_v1"). Solo lee el catálogo. */
(function () {
    "use strict";
    const CLAVE = "alicorp_carrito_v1";
    const $ = (id) => document.getElementById(id);
    const esc = (t) => String(t == null ? "" : t).replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
    const dinero = (n) => "S/ " + Number(n).toFixed(2);
    const ICONO_MAS = '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 5v14M5 12h14"/></svg>';
    const ICONO_OK = '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="m5 12 5 5 9-10"/></svg>';
    // Historial de busqueda (/tienda_virtual/historial_busqueda.js). Si no carga, se usan funciones vacias.
    const H = window.AlicorpHistorial || { registrar() {}, parametros() { return ""; }, tecla() {} };
    const getJson = (url) => fetch(url).then((r) => { if (!r.ok) throw new Error(r.status); return r.json(); });

    // ------------------------------------------------------------ carrito (mismo formato que tienda.js)
    function leerCarrito() { try { return JSON.parse(localStorage.getItem(CLAVE) || "{}") || {}; } catch (e) { return {}; } }
    function guardarCarrito(c) { try { localStorage.setItem(CLAVE, JSON.stringify(c)); } catch (e) { /* sin almacenamiento */ } }
    function unidades() { return Object.values(leerCarrito()).reduce((s, l) => s + (Number(l.cantidad) || 0), 0); }
    function pintarContador(rebote) {
        const n = unidades(), el = $("ptCnt");
        if (!el) return;
        el.textContent = n > 999 ? "999+" : n;
        if (rebote) { const b = $("ptCarro"); b.classList.remove("rebota"); void b.offsetWidth; b.classList.add("rebota"); }
    }
    let toastT = null;
    function aviso(t) {
        const a = $("ptToast"); if (!a) return;
        a.textContent = t; a.classList.add("on");
        clearTimeout(toastT); toastT = setTimeout(() => a.classList.remove("on"), 2400);
    }
    function agregar(p, boton) {
        const c = leerCarrito();
        const actual = c[p.sku] ? Number(c[p.sku].cantidad) : 0;
        if (actual + 1 > Math.min(p.stock, 100000)) { aviso("No hay más unidades disponibles de este producto."); return; }
        c[p.sku] = { sku: p.sku, nombre: p.nombre, foto: p.foto, marca: p.marca, presentacion: p.presentacion, precio: p.precio, imagen: p.imagen, stock: p.stock, cantidad: actual + 1 };
        c[p.sku].categoria = p.categoria;
        H.registrar("agregar", p);
        guardarCarrito(c);
        pintarContador(true);
        aviso("Agregado: " + p.nombre);
        if (boton) {
            boton.classList.add("ok"); boton.innerHTML = ICONO_OK + " Listo";
            setTimeout(() => { boton.classList.remove("ok"); boton.innerHTML = ICONO_MAS + " Agregar"; }, 1400);
        }
    }

    // ------------------------------------------------------------ tarjeta de producto
    const registro = {};                       // sku -> producto (para el botón Agregar)
    function tarjeta(p) {
        registro[p.sku] = p;
        const href = "/tienda_virtual/tienda.html?q=" + encodeURIComponent(p.subcategoria || p.nombre);
        return `<article class="pt-card"><a class="f" href="${esc(href)}" tabindex="-1" aria-hidden="true">${window.fotoProducto(p)}</a>
            <div class="i"><span class="m">${esc(p.marca)}</span><span class="n" title="${esc(p.nombre)}">${esc(p.nombre)}</span><span class="p">${esc(p.presentacion)}</span>
            <div class="pie"><span class="pr">${dinero(p.precio)}</span><button type="button" class="pt-add" data-sku="${esc(p.sku)}">${ICONO_MAS} Agregar</button></div></div></article>`;
    }
    document.addEventListener("click", (e) => {
        const b = e.target.closest(".pt-add");
        if (b && registro[b.dataset.sku]) agregar(registro[b.dataset.sku], b);
    });

    // ------------------------------------------------------------ hero: muro de fotos que se desplaza
    function muroFotos(items) {
        const w = $("ptWall"); if (!w) return;
        const fotos = items.filter((p) => p.foto);
        if (fotos.length < 4) return;
        const cols = 7, listas = Array.from({ length: cols }, () => []);
        for (let i = 0; i < cols * 6; i++) listas[i % cols].push(fotos[(i * 3 + Math.floor(i / cols)) % fotos.length]);
        w.innerHTML = listas.map((l) => {
            const html = l.map((p) => `<div class="pt-foto"><img src="${esc(window.urlFotoProducto(p))}" alt="" loading="lazy" decoding="async" referrerpolicy="no-referrer" onerror="this.parentNode.style.visibility='hidden'"></div>`).join("");
            return `<div class="pt-col">${html}${html}</div>`;      // se duplica para que el bucle sea continuo
        }).join("");
    }

    // ------------------------------------------------------------ buscador con sugerencias
    function conectarBuscador() {
        const inp = $("ptQ"), caja = $("ptAuto"); if (!inp) return;
        let t = null, seq = 0, activa = -1;
        const cerrar = () => { caja.classList.remove("visible"); caja.innerHTML = ""; activa = -1; };
        const resaltar = (txt, q) => { const i = txt.toLowerCase().indexOf(q.toLowerCase()); return i < 0 ? esc(txt) : esc(txt.slice(0, i)) + "<b>" + esc(txt.slice(i, i + q.length)) + "</b>" + esc(txt.slice(i + q.length)); };
        const ir = (texto) => { location.href = "/tienda_virtual/tienda.html?q=" + encodeURIComponent(texto); };
        inp.addEventListener("input", () => {
            H.tecla(inp.value);
            clearTimeout(t);
            const q = inp.value.trim();
            if (q.length < 2) { cerrar(); return; }
            t = setTimeout(async () => {
                const mi = ++seq;
                let d; try { d = await getJson("/api/tienda/sugerencias?limite=6&q=" + encodeURIComponent(q) + H.parametros()); } catch (e) { return; }
                if (mi !== seq || !d.ok || !d.items.length) { if (mi === seq) cerrar(); return; }
                caja.innerHTML = d.items.map((s) => `<div class="op" role="option" data-t="${esc(s.texto)}"><span>${resaltar(s.texto, q)}</span><small>${s.para_ti ? "Para ti · " : ""}${esc(s.tipo)}</small></div>`).join("");
                caja.classList.add("visible"); activa = -1;
            }, 120);
        });
        inp.addEventListener("keydown", (e) => {
            const op = [...caja.querySelectorAll(".op")];
            if (!op.length) return;
            if (e.key === "ArrowDown" || e.key === "ArrowUp") {
                e.preventDefault();
                activa = (activa + (e.key === "ArrowDown" ? 1 : -1) + op.length) % op.length;
                op.forEach((o, i) => o.classList.toggle("activa", i === activa));
            } else if (e.key === "Enter" && activa >= 0) { e.preventDefault(); ir(op[activa].dataset.t); }
            else if (e.key === "Escape") cerrar();
        });
        caja.addEventListener("mousedown", (e) => { const o = e.target.closest(".op"); if (o) { e.preventDefault(); ir(o.dataset.t); } });
        document.addEventListener("click", (e) => { if (!e.target.closest(".pt-buscar")) cerrar(); });
    }

    // ------------------------------------------------------------ pestañas por categoría
    function pestanas(cats) {
        const bar = $("ptTabs"), cont = $("ptPaneles"); if (!bar || !cats.length) return;
        const top = cats.slice().sort((a, b) => b.cantidad - a.cantidad).slice(0, 6);
        bar.innerHTML = top.map((c, i) => `<button type="button" class="pt-tab" role="tab" id="pt-tab-${i}" aria-selected="${i === 0}" aria-controls="pt-pan-${i}" data-i="${i}">${esc(c.categoria)}</button>`).join("");
        cont.innerHTML = top.map((c, i) => `<div class="pt-panel${i === 0 ? " on" : ""}" role="tabpanel" id="pt-pan-${i}" aria-labelledby="pt-tab-${i}"></div>`).join("");
        const cargado = {};
        async function abrir(i) {
            bar.querySelectorAll(".pt-tab").forEach((b, k) => b.setAttribute("aria-selected", k === i));
            cont.querySelectorAll(".pt-panel").forEach((p, k) => p.classList.toggle("on", k === i));
            if (cargado[i]) return;
            cargado[i] = true;
            const p = $("pt-pan-" + i);
            p.innerHTML = '<div class="pt-grid"><div class="pt-esq"></div><div class="pt-esq"></div><div class="pt-esq"></div><div class="pt-esq"></div></div>';
            try {
                const d = await getJson("/api/tienda/productos?por_pagina=60&categoria=" + encodeURIComponent(top[i].categoria));
                const lista = (d.items || []).filter((x) => x.foto && x.stock > 0).slice(0, 8);
                p.innerHTML = lista.length
                    ? `<div class="pt-grid">${lista.map(tarjeta).join("")}</div><p class="pt-mas"><a href="/tienda_virtual/tienda.html?cat=${encodeURIComponent(top[i].categoria)}">Ver los ${Number(top[i].cantidad).toLocaleString("es-PE")} productos de ${esc(top[i].categoria)} &rarr;</a></p>`
                    : '<p class="pt-vacio">Por ahora no hay productos con stock en esta categoría.</p>';
            } catch (e) { cargado[i] = false; p.innerHTML = '<p class="pt-vacio">No pudimos cargar los productos. Intenta de nuevo.</p>'; }
        }
        bar.addEventListener("click", (e) => { const b = e.target.closest(".pt-tab"); if (b) abrir(Number(b.dataset.i)); });
        bar.addEventListener("keydown", (e) => {
            if (e.key !== "ArrowRight" && e.key !== "ArrowLeft") return;
            const tabs = [...bar.querySelectorAll(".pt-tab")], i = tabs.findIndex((b) => b === document.activeElement);
            const n = (i + (e.key === "ArrowRight" ? 1 : -1) + tabs.length) % tabs.length;
            tabs[n].focus(); abrir(n);
        });
        abrir(0);
    }

    // ------------------------------------------------------------ categorías (tarjetas con foto)
    async function tiles(cats, dest) {
        const cont = $("ptTiles"); if (!cont) return;
        const fotoDe = {};
        dest.forEach((p) => { if (p.foto && p.categoria && !fotoDe[p.categoria]) fotoDe[p.categoria] = p; });
        await Promise.all(cats.filter((c) => !fotoDe[c.categoria]).map(async (c) => {
            try {
                const r = await getJson("/api/tienda/productos?por_pagina=12&categoria=" + encodeURIComponent(c.categoria));
                const p = (r.items || []).find((x) => x.foto); if (p) fotoDe[c.categoria] = p;
            } catch (e) { /* queda el recuadro neutro */ }
        }));
        cont.innerHTML = cats.map((c) => {
            const p = fotoDe[c.categoria];
            const img = p ? `<img src="${esc(window.urlFotoProducto(p))}" alt="" loading="lazy" decoding="async" referrerpolicy="no-referrer" onerror="this.remove()">` : "";
            return `<a class="pt-tile" href="/tienda_virtual/tienda.html?cat=${encodeURIComponent(c.categoria)}"><span class="tf">${img}</span><b>${esc(c.categoria)}</b><small>${Number(c.cantidad).toLocaleString("es-PE")} productos</small></a>`;
        }).join("");
    }

    // ------------------------------------------------------------ aparición al desplazarse
    function revelar() {
        const els = document.querySelectorAll(".pt-rev");
        if (!("IntersectionObserver" in window)) { els.forEach((e) => e.classList.add("visto")); return; }
        const o = new IntersectionObserver((es) => es.forEach((x) => { if (x.isIntersecting) { x.target.classList.add("visto"); o.unobserve(x.target); } }), { threshold: .12 });
        els.forEach((e) => o.observe(e));
    }

    // ------------------------------------------------------------ arranque
    document.addEventListener("DOMContentLoaded", async () => {
        pintarContador(false);
        revelar();
        conectarBuscador();
        window.addEventListener("storage", (e) => { if (e.key === CLAVE) pintarContador(false); });
        window.addEventListener("pageshow", () => pintarContador(false));
        let cats = [], dest = [];
        try { const d = await getJson("/api/tienda/categorias"); if (d.ok) cats = d.categorias; } catch (e) { /* sin categorías */ }
        try { const d = await getJson("/api/tienda/destacados"); if (d.ok) dest = d.items; } catch (e) { /* sin destacados */ }
        const total = cats.reduce((s, c) => s + Number(c.cantidad || 0), 0);
        if (total) { $("ptStatProd").textContent = total.toLocaleString("es-PE"); $("ptStatCat").textContent = cats.length; }
        muroFotos(dest);
        pestanas(cats);
        tiles(cats, dest);
        const cd = $("ptDest");
        if (cd) cd.innerHTML = dest.slice(0, 8).map(tarjeta).join("");
    });
})();
