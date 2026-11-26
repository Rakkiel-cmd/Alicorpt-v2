/* ==============================================================================
   FOTOS REALES DE PRODUCTOS
   Cada producto del catálogo trae su propia foto real (campo "foto" = "id/archivo.jpg"),
   tomada del catálogo público de Plaza Vea. Si el producto no tiene foto, o la foto no carga,
   se muestra un recuadro neutro con el ícono de producto y la marca (sin emojis).
   ============================================================================== */
(function () {
    "use strict";
    const PV = "https://plazavea.vteximg.com.br/arquivos/ids/";
    const esc = (t) => String(t == null ? "" : t).replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));

    // Estilo del recuadro "sin foto" (se inyecta una sola vez para que sirva también en la portada)
    if (!document.getElementById("estilo-sin-foto")) {
        const st = document.createElement("style");
        st.id = "estilo-sin-foto";
        st.textContent = ".sin-foto{width:100%;height:100%;display:flex;flex-direction:column;align-items:center;justify-content:center;gap:8px;background:#f3f4f6;color:#9ca3af;font-size:.68rem;font-weight:600;letter-spacing:.06em;text-transform:uppercase;text-align:center;padding:8px}.sin-foto svg{width:38px;height:38px;stroke:currentColor;fill:none;stroke-width:1.5;stroke-linecap:round;stroke-linejoin:round}";
        document.head.appendChild(st);
    }
    const ICONO_CAJA = '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M21 8v8a2 2 0 0 1-1 1.7l-7 4a2 2 0 0 1-2 0l-7-4A2 2 0 0 1 3 16V8a2 2 0 0 1 1-1.7l7-4a2 2 0 0 1 2 0l7 4A2 2 0 0 1 21 8z"/><path d="M3.3 7 12 12l8.7-5M12 22V12"/></svg>';

    function recuadro(marca) {
        return `<div class="sin-foto" role="img" aria-label="Imagen no disponible">${ICONO_CAJA}<span>${esc(marca || "")}</span></div>`;
    }

    function urlFoto(p) {
        if (!p.foto) return null;
        const i = p.foto.indexOf("/");
        return PV + p.foto.slice(0, i) + "-450-450" + p.foto.slice(i);        // tamaño 450x450
    }
    window.urlFotoProducto = urlFoto;

    // Si la foto falla: primero prueba el tamaño original; si vuelve a fallar, muestra el recuadro neutro
    window.fotoFalla = function (img) {
        if (!img.dataset.reintento && img.src.indexOf("-450-450/") !== -1) {
            img.dataset.reintento = "1";
            img.src = img.src.replace("-450-450/", "/");
            return;
        }
        const caja = document.createElement("div");
        caja.innerHTML = recuadro(img.dataset.marca);
        img.replaceWith(caja.firstChild);
    };

    // Devuelve el HTML de la imagen del producto (foto real si existe; si no, recuadro neutro)
    window.fotoProducto = function (p) {
        const url = urlFoto(p);
        if (!url) return recuadro(p.marca);
        return `<img class="foto-real" src="${esc(url)}" alt="${esc(p.nombre)}" data-marca="${esc(p.marca)}" loading="lazy" decoding="async" referrerpolicy="no-referrer" onerror="window.fotoFalla(this)">`;
    };
})();
