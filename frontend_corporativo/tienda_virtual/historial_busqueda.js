/*
 * Historial de busqueda y medicion para la comparativa del informe.
 *
 *  - Guarda en este navegador (localStorage) lo que la persona ve, marca como favorito, agrega al carrito y compra:
 *    categorias, marcas y productos, con un peso que va bajando con el tiempo. Nada sale del navegador salvo un
 *    resumen corto (el "perfil") que se manda al buscar para que el servidor ordene los resultados.
 *  - Mide cada busqueda terminada en un clic (tiempo, teclas escritas y posicion del producto elegido) y la manda
 *    a /api/tienda/metricas para comparar el orden alfabetico (antes) con el ranking por IA (despues).
 *
 * Todo esta protegido con try/catch: si el almacenamiento esta bloqueado, la tienda sigue funcionando igual.
 */
(function () {
    "use strict";

    var CLAVE = "alicorp_historial_v1";
    var CLAVE_MODO = "alicorp_modo_busqueda";
    var CLAVE_SESION = "alicorp_busqueda_sesion";
    var PESO = { ver: 1, fav: 2, agregar: 3, comprar: 5 };
    var DECAIMIENTO = 0.97;      // cada accion nueva le quita 3% a lo anterior: lo reciente pesa mas
    var MAX_CLAVES = 40;
    var INACTIVIDAD_MS = 120000; // una busqueda sin actividad por 2 minutos se descarta

    function lsLeer(clave) { try { var t = window.localStorage.getItem(clave); return t ? JSON.parse(t) : null; } catch (e) { return null; } }
    function lsEscribir(clave, valor) { try { window.localStorage.setItem(clave, JSON.stringify(valor)); } catch (e) { /* sin almacenamiento */ } }
    function ssLeer(clave) { try { var t = window.sessionStorage.getItem(clave); return t ? JSON.parse(t) : null; } catch (e) { return null; } }
    function ssEscribir(clave, valor) { try { window.sessionStorage.setItem(clave, JSON.stringify(valor)); } catch (e) { /* sin almacenamiento */ } }
    function ssBorrar(clave) { try { window.sessionStorage.removeItem(clave); } catch (e) { /* sin almacenamiento */ } }

    // ------------------------------------------------------------------ historial
    function cargar() {
        var h = lsLeer(CLAVE);
        if (!h || typeof h !== "object") h = {};
        ["c", "m", "s"].forEach(function (k) { if (!h[k] || typeof h[k] !== "object") h[k] = {}; });
        return h;
    }

    function recortar(mapa) {
        var claves = Object.keys(mapa);
        if (claves.length <= MAX_CLAVES) return;
        claves.sort(function (a, b) { return mapa[b] - mapa[a]; }).slice(MAX_CLAVES).forEach(function (k) { delete mapa[k]; });
    }

    /** tipo: ver, fav, agregar o comprar. p: producto con categoria, marca y sku (los que falten se ignoran). */
    function registrar(tipo, p) {
        if (!p || !PESO[tipo]) return;
        var h = cargar();
        ["c", "m", "s"].forEach(function (k) {
            Object.keys(h[k]).forEach(function (x) {
                h[k][x] *= DECAIMIENTO;
                if (h[k][x] < 0.05) delete h[k][x];
            });
        });
        var w = PESO[tipo];
        if (p.categoria) h.c[p.categoria] = (h.c[p.categoria] || 0) + w;
        if (p.marca) h.m[p.marca] = (h.m[p.marca] || 0) + w;
        if (p.sku) h.s[p.sku] = (h.s[p.sku] || 0) + w;
        recortar(h.c); recortar(h.m); recortar(h.s);
        lsEscribir(CLAVE, h);
    }

    function mejores(mapa, n) {
        return Object.keys(mapa).sort(function (a, b) { return mapa[b] - mapa[a]; }).slice(0, n);
    }

    /** Texto corto para el servidor. Formato: tipo:clave:peso separados por "|" (la clave va codificada). */
    function perfilTexto() {
        var h = cargar(), partes = [];
        mejores(h.c, 8).forEach(function (k) { partes.push("c:" + encodeURIComponent(k) + ":" + h.c[k].toFixed(2)); });
        mejores(h.m, 8).forEach(function (k) { partes.push("m:" + encodeURIComponent(k) + ":" + h.m[k].toFixed(2)); });
        mejores(h.s, 12).forEach(function (k) { partes.push("s:" + encodeURIComponent(k) + ":" + h.s[k].toFixed(2)); });
        return partes.join("|");
    }

    function tieneHistorial() { return perfilTexto() !== ""; }

    function borrarHistorial() { lsEscribir(CLAVE, {}); }

    // ------------------------------------------------------------------ modo de orden
    function modo() {
        var m = lsLeer(CLAVE_MODO);
        return m === "alfabetico" ? "alfabetico" : "ia";
    }

    function ponerModo(m) {
        lsEscribir(CLAVE_MODO, m === "alfabetico" ? "alfabetico" : "ia");
        ssBorrar(CLAVE_SESION); // una busqueda a medias con otro modo ya no es comparable
    }

    /** Parametros para agregar al final de la URL de /buscar y /sugerencias. */
    function parametros() {
        var p = perfilTexto();
        return "&modo=" + modo() + (p ? "&perfil=" + encodeURIComponent(p) : "");
    }

    // ------------------------------------------------------------------ medicion de busquedas
    function nuevoId() {
        return "s" + Date.now().toString(36) + Math.random().toString(36).slice(2, 8);
    }

    function sesion() {
        var s = ssLeer(CLAVE_SESION);
        if (!s || typeof s !== "object" || !s.t0) return null;
        if (Date.now() - (s.ult || s.t0) > INACTIVIDAD_MS) { ssBorrar(CLAVE_SESION); return null; }
        return s;
    }

    /** Se llama en cada tecla del buscador. La primera tecla inicia el cronometro; con la caja vacia se reinicia. */
    function tecla(texto) {
        var q = String(texto || "").trim();
        if (!q) { ssBorrar(CLAVE_SESION); return; }
        var ahora = Date.now();
        var s = sesion();
        if (!s) s = { id: nuevoId(), t0: ahora, teclas: 0, q: "", modo: modo() };
        s.teclas += 1;
        s.q = q.slice(0, 80);
        s.ult = ahora;
        ssEscribir(CLAVE_SESION, s);
    }

    /** La persona eligio un producto de los resultados. posicion empieza en 1. Devuelve true si se midio. */
    function terminar(p, posicion, accion) {
        var s = sesion();
        if (!s || !p || !(posicion >= 1)) return false;
        ssBorrar(CLAVE_SESION);
        if (s.modo !== modo()) return false;
        var cuerpo = {
            sesion: s.id,
            modo: s.modo,
            consulta: s.q || "",
            ms: Math.max(0, Date.now() - s.t0),
            teclas: s.teclas,
            posicion: Math.min(500, Math.floor(posicion)),
            accion: accion || "agregar",
            sku: p.sku || ""
        };
        try {
            window.fetch("/api/tienda/metricas", {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify(cuerpo),
                keepalive: true
            }).catch(function () { /* la medicion nunca debe molestar la compra */ });
        } catch (e) { /* sin red */ }
        return true;
    }

    window.AlicorpHistorial = {
        registrar: registrar,
        perfilTexto: perfilTexto,
        tieneHistorial: tieneHistorial,
        borrarHistorial: borrarHistorial,
        modo: modo,
        ponerModo: ponerModo,
        parametros: parametros,
        tecla: tecla,
        terminar: terminar
    };
})();
