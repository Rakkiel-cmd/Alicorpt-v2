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

    // ---------------------------------------------------------------- estado
    const estado = { categoria: "", marca: "", orden: "", pagina: 1, consulta: "", resultadosBusqueda: [], categorias: [] };
    let carrito = {};   // { sku: {sku, nombre, marca, presentacion, precio, imagen, stock, cantidad} }
    const CLAVE = "alicorp_carrito_v1";

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
            `<button type="button" class="cat-item activa" data-cat=""><span class="emoji">🛍️</span>Todos<span class="n">${total}</span></button>` +
            estado.categorias.map((c) => `<button type="button" class="cat-item" data-cat="${esc(c.categoria)}"><span class="emoji">${esc(c.emoji)}</span>${esc(c.categoria)}<span class="n">${c.cantidad}</span></button>`).join("");
    }

    function marcarCategoria() {
        document.querySelectorAll(".cat-item").forEach((b) => b.classList.toggle("activa", b.dataset.cat === estado.categoria));
    }

    // ---------------------------------------------------------------- tarjetas de producto
    function tarjeta(p) {
        const agotado = p.stock <= 0;
        const sello = agotado ? '<span class="sello agotado">Agotado</span>' : (p.stock < 200 ? '<span class="sello">Últimas unidades</span>' : "");
        return `
        <article class="producto" data-sku="${esc(p.sku)}">
            <div class="foto">${sello}${window.fotoProducto(p)}</div>
            <div class="info">
                <span class="marca">${esc(p.marca)}</span>
                <span class="nombre">${esc(p.nombre)}</span>
                <span class="present">${esc(p.presentacion)}</span>
                <span class="precio">${dinero(p.precio)}</span>
                <span class="stock">${agotado ? "Sin stock" : "Stock: " + p.stock.toLocaleString("es-PE") + " unid."}</span>
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

    function pintarProductos(items) {
        enPantalla = {};
        items.forEach((p) => { enPantalla[p.sku] = p; });
        $("rejilla").innerHTML = items.length
            ? items.map(tarjeta).join("")
            : '<div class="vacio" style="grid-column:1/-1"><span class="grande">🤔</span>No encontré productos para esa búsqueda.<br>Prueba con otras palabras, por ejemplo "aceite" o "detergente".</div>';
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
        esqueletos();
        const params = new URLSearchParams({ pagina: estado.pagina, por_pagina: 24 });
        if (estado.categoria) params.set("categoria", estado.categoria);
        if (estado.marca) params.set("marca", estado.marca);
        if (estado.orden) params.set("orden", estado.orden);
        let d;
        try { d = await pedir("/api/tienda/productos?" + params); } catch (e) { d = { error: "No se pudo conectar con el servidor." }; }
        if (!d.ok) {
            $("rejilla").innerHTML = `<div class="vacio" style="grid-column:1/-1"><span class="grande">⚠️</span>${esc(d.error || "No se pudieron cargar los productos.")}</div>`;
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
        cont.innerHTML = `<button type="button" data-pag="${pagina - 1}" ${pagina <= 1 ? "disabled" : ""}>← Anterior</button>
            <span>Página ${pagina} de ${paginas}</span>
            <button type="button" data-pag="${pagina + 1}" ${pagina >= paginas ? "disabled" : ""}>Siguiente →</button>`;
    }

    // ---------------------------------------------------------------- búsqueda inteligente (red neuronal)
    async function buscar(texto) {
        const q = texto.trim();
        if (!q) { limpiarBusqueda(); return; }
        estado.consulta = q;
        $("btnLimpiar").classList.add("visible");
        esqueletos();
        $("paginacion").innerHTML = "";
        let d;
        try { d = await pedir("/api/tienda/buscar?limite=48&q=" + encodeURIComponent(q)); } catch (e) { d = { error: "No se pudo conectar con el servidor." }; }
        if (!d.ok) {
            $("rejilla").innerHTML = `<div class="vacio" style="grid-column:1/-1"><span class="grande">⚠️</span>${esc(d.error || "Error al buscar.")}</div>`;
            return;
        }
        estado.resultadosBusqueda = d.items;
        $("tituloLista").textContent = 'Resultados para "' + q + '"';
        $("conteoLista").textContent = d.total + " productos";
        estado.categoria = ""; marcarCategoria();
        llenarMarcas([...new Set(d.items.map((p) => p.marca))].sort());
        aplicarFiltrosBusqueda();

        const cinta = $("cintaInteligente");
        let html = '<span class="cerebro">🧠</span>';
        if (d.intencion) {
            html += `<span>Entendí que buscas <span class="etiqueta">${esc(d.intencion.subcategoria)}</span> <span style="color:#667">en ${esc(d.intencion.categoria)}</span></span><small>Seguridad de la red neuronal: ${Math.round(d.intencion.confianza * 100)}%</small>`;
        } else {
            html += `<span>Busqué productos parecidos a lo que escribiste.</span><small>${esc(d.motor)}</small>`;
        }
        cinta.innerHTML = html;
        cinta.classList.add("visible");
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
        estado.consulta = "";
        estado.marca = ""; estado.pagina = 1;
        cargarProductos();
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
            aviso("✅ " + cantidad.toLocaleString("es-PE") + " x " + p.nombre + " agregado");
        }
        carrito[p.sku] = { sku: p.sku, nombre: p.nombre, foto: p.foto, marca: p.marca, presentacion: p.presentacion, precio: p.precio, imagen: p.imagen, stock: p.stock, cantidad: nueva };
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
        const lista = $("carritoLista");
        const lineas = Object.values(carrito);
        if (!lineas.length) {
            lista.innerHTML = '<div class="vacio" style="margin-top:20px"><span class="grande">🛒</span>Tu carrito está vacío.<br>Agrega productos desde la tienda.</div>';
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
    }

    // ---------------------------------------------------------------- finalizar compra
    function abrirModalCompra() {
        const t = totales();
        if (!t.lineas) return;
        $("cajaCompra").innerHTML = `
            <h2>Finalizar compra</h2>
            <p class="sub">${t.lineas} producto(s) · ${t.unidades.toLocaleString("es-PE")} unidades · <b>${dinero(t.total)}</b></p>
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
        carrito = {}; guardarCarrito(); pintarCarrito(); abrirCarrito(false);
        $("cajaCompra").innerHTML = `
            <div class="exito">
                <div class="check">✅</div>
                <h2>¡Compra registrada!</h2>
                <p class="sub">Gracias por tu compra. Te contactaremos para coordinar la entrega.</p>
                <div class="num">N.º de compra: ${esc(d.compra_id)}<br>Total: ${dinero(d.total)}<br><small>Guardada en: ${d.guardado_en === "supabase" ? "base de datos Supabase" : "respaldo del servidor"}</small></div>
                ${d.correo_enviado ? `<p class="sub">📧 Te enviamos el seguimiento de tu pedido a <b>${esc(cuerpo.correo)}</b>. Si no lo ves, revisa Spam.</p>` : ""}
                <p class="sub">Guarda tu número de compra: con él y tu correo puedes ver el estado en <b>📦 Mi pedido</b>.</p>
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

    function recordarPedido(id, correo) {
        try { localStorage.setItem("alicorp_ultimo_pedido", JSON.stringify({ id: String(id), correo })); } catch (e) { /* sin almacenamiento */ }
    }
    function ultimoPedido() {
        try { return JSON.parse(localStorage.getItem("alicorp_ultimo_pedido")) || {}; } catch (e) { return {}; }
    }

    function abrirModalPedido(idInicial, correoInicial) {
        const u = ultimoPedido();
        $("cajaCompra").innerHTML = `
            <h2>📦 Mi pedido</h2>
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
        const pasos = ESTADOS.map((e, i) => `<div class="paso ${pos >= 0 && i < pos ? "hecho" : ""} ${i === pos ? "actual" : ""}"><i>${pos >= 0 && i < pos ? "✓" : i + 1}</i>${ETIQUETA[e]}</div>`).join("");
        const fecha = p.fecha ? new Date(p.fecha).toLocaleString("es-PE") : "";
        $("resPedido").innerHTML = `
            <div class="pasos">${pasos}</div>
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
        $("btnVaciar").onclick = () => { if (Object.keys(carrito).length && confirm("¿Vaciar todo el carrito?")) { carrito = {}; guardarCarrito(); pintarCarrito(); } };
        $("modalCompra").onclick = (e) => { if (e.target === $("modalCompra")) $("modalCompra").classList.remove("visible"); };
        document.addEventListener("keydown", (e) => { if (e.key === "Escape") { abrirCarrito(false); $("modalCompra").classList.remove("visible"); } });

        $("formBuscar").onsubmit = (e) => { e.preventDefault(); buscar($("cajaBuscar").value); };
        $("btnLimpiar").onclick = limpiarBusqueda;
        $("cajaBuscar").addEventListener("input", () => {
            $("btnLimpiar").classList.toggle("visible", $("cajaBuscar").value.length > 0);
            clearTimeout(conectarEventos._t);
            conectarEventos._t = setTimeout(() => { if ($("cajaBuscar").value.trim().length >= 3) buscar($("cajaBuscar").value); }, 450);
        });
        $("sugerencias").onclick = (e) => {
            const b = e.target.closest("button[data-q]");
            if (b) { $("cajaBuscar").value = b.dataset.q; buscar(b.dataset.q); }
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

        // Tarjetas de producto
        $("rejilla").onclick = (e) => {
            const boton = e.target.closest("button[data-accion]");
            if (!boton) return;
            const tarj = boton.closest(".producto");
            const p = enPantalla[tarj.dataset.sku];
            const campo = tarj.querySelector('[data-campo="cant"]');
            const valor = Math.max(1, Math.floor(Number(campo.value)) || 1);
            const accion = boton.dataset.accion;
            if (accion === "mas") campo.value = valor + 1;
            else if (accion === "menos") campo.value = Math.max(1, valor - 1);
            else if (accion === "fijar") campo.value = boton.dataset.n;
            else if (accion === "agregar") { agregarAlCarrito(p, valor); campo.value = 1; }
        };

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
        conectarEventos();
        pintarCarrito();
        esqueletos();
        try { await cargarCategorias(); } catch (e) { /* si falla, se muestra el error al cargar productos */ }
        // Si llegan desde la portada con ?q=aceite, se busca directamente
        const parametros = new URLSearchParams(location.search);
        const q = parametros.get("q");
        const cat = parametros.get("cat");
        if (q) { $("cajaBuscar").value = q; buscar(q); }
        else {
            if (cat && estado.categorias.some((c) => c.categoria === cat)) { estado.categoria = cat; marcarCategoria(); }
            cargarProductos();
        }
    });
})();
