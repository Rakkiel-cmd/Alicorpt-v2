/* ==============================================================================
   FOTOS REALES DE PRODUCTOS
   Cada producto del catálogo trae su propia foto real (campo "foto" = "id/archivo.jpg"),
   tomada del catálogo público de Plaza Vea. Si una foto no carga, o el producto no tiene foto,
   se muestra el emoji de su categoría.
   ============================================================================== */
(function () {
    "use strict";
    const PV = "https://plazavea.vteximg.com.br/arquivos/ids/";
    const esc = (t) => String(t == null ? "" : t).replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));

    function urlFoto(p) {
        if (!p.foto) return null;
        const i = p.foto.indexOf("/");
        return PV + p.foto.slice(0, i) + "-450-450" + p.foto.slice(i);        // tamaño 450x450
    }

    // Si la foto falla: primero prueba el tamaño original; si vuelve a fallar, muestra el emoji
    window.fotoFalla = function (img, emoji) {
        if (!img.dataset.reintento && img.src.indexOf("-450-450/") !== -1) {
            img.dataset.reintento = "1";
            img.src = img.src.replace("-450-450/", "/");
            return;
        }
        img.replaceWith(document.createTextNode(emoji));
    };

    // Devuelve el HTML de la imagen del producto (foto real si existe; si no, el emoji)
    window.fotoProducto = function (p) {
        const url = urlFoto(p);
        if (!url) return esc(p.imagen);
        return `<img class="foto-real" src="${esc(url)}" alt="${esc(p.nombre)}" loading="lazy" referrerpolicy="no-referrer" onerror="window.fotoFalla(this,'${esc(p.imagen)}')">`;
    };
})();
