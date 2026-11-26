/* Comportamientos compartidos: menú de celular, aparición al desplazarse, contadores y ayudas del formulario de contacto. */
(function () {
    "use strict";
    document.addEventListener("DOMContentLoaded", () => {
        // ---- Menú desplegable en celular
        const barra = document.querySelector(".navbar"), cont = barra && barra.querySelector(".nav-container"), nav = barra && barra.querySelector(".nav-links");
        if (barra && cont && nav && !cont.querySelector(".nav-burger")) {
            const b = document.createElement("button");
            b.type = "button"; b.className = "nav-burger"; b.setAttribute("aria-label", "Abrir menú"); b.setAttribute("aria-expanded", "false");
            b.innerHTML = "<span></span>";
            cont.appendChild(b);
            const cerrar = () => { barra.classList.remove("menu-abierto"); b.setAttribute("aria-expanded", "false"); b.setAttribute("aria-label", "Abrir menú"); };
            b.addEventListener("click", (e) => {
                e.stopPropagation();
                const abre = !barra.classList.contains("menu-abierto");
                barra.classList.toggle("menu-abierto", abre);
                b.setAttribute("aria-expanded", String(abre)); b.setAttribute("aria-label", abre ? "Cerrar menú" : "Abrir menú");
            });
            document.addEventListener("click", (e) => { if (!barra.contains(e.target)) cerrar(); });
            document.addEventListener("keydown", (e) => { if (e.key === "Escape") cerrar(); });
            window.addEventListener("resize", () => { if (window.innerWidth > 900) cerrar(); });
            nav.addEventListener("click", (e) => { if (e.target.closest("a")) cerrar(); });
        }
        // Sombra del menú al bajar
        if (barra) { const s = () => barra.classList.toggle("sombra", window.scrollY > 8); s(); window.addEventListener("scroll", s, { passive: true }); }

        // ---- Aparición al desplazarse + contadores
        const contar = (el) => {
            const meta = Number(el.dataset.contar), pre = el.dataset.pre || "", suf = el.dataset.suf || "";
            if (window.matchMedia("(prefers-reduced-motion: reduce)").matches) { el.textContent = pre + meta + suf; return; }
            const t0 = performance.now(), dur = 1300;
            const paso = (t) => { const k = Math.min(1, (t - t0) / dur), e = 1 - Math.pow(1 - k, 3); el.textContent = pre + Math.round(meta * e) + suf; if (k < 1) requestAnimationFrame(paso); };
            requestAnimationFrame(paso);
        };
        const aparece = (el) => { el.classList.add("visto"); el.querySelectorAll("[data-contar]").forEach(contar); if (el.dataset.contar) contar(el); };
        const els = document.querySelectorAll(".inst-rev, .inst-stat");
        if (!("IntersectionObserver" in window)) els.forEach(aparece);
        else {
            const o = new IntersectionObserver((es) => es.forEach((x) => { if (x.isIntersecting) { aparece(x.target); o.unobserve(x.target); } }), { threshold: .15 });
            els.forEach((e) => o.observe(e));
        }

        // ---- Contacto: temas rápidos, texto inicial desde ?asunto= y contador de letras
        const msg = document.getElementById("txtMensaje");
        if (msg) {
            const TEMAS = { consulta: "Consulta: ", queja: "Queja: ", sugerencia: "Sugerencia: ", proveedor: "Quiero ser proveedor de Alicorp. Mi empresa y lo que ofrezco: ", incidente: "Incidente a reportar: " };
            const botones = [...document.querySelectorAll(".inst-temas button")];
            const contador = document.getElementById("contadorMensaje");
            const pintarContador = () => { if (contador) contador.textContent = msg.value.length + " / " + (msg.maxLength || 2000); };
            const poner = (clave) => {
                const t = TEMAS[clave]; if (!t) return;
                const previos = Object.values(TEMAS);
                const actual = msg.value;
                const prefijo = previos.find((p) => actual.startsWith(p));
                msg.value = t + (prefijo ? actual.slice(prefijo.length) : actual);
                botones.forEach((x) => x.classList.toggle("on", x.dataset.tema === clave));
                pintarContador(); msg.focus(); msg.setSelectionRange(msg.value.length, msg.value.length);
            };
            botones.forEach((x) => x.addEventListener("click", () => poner(x.dataset.tema)));
            msg.addEventListener("input", pintarContador);
            const asunto = new URLSearchParams(location.search).get("asunto");
            if (asunto && TEMAS[asunto] && !msg.value) { msg.value = TEMAS[asunto]; botones.forEach((x) => x.classList.toggle("on", x.dataset.tema === asunto)); }
            pintarContador();
        }
    });
})();
