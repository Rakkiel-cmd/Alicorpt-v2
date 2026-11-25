package pe.com.alicorp.bi.modulos.chatbot_asistente;

import pe.com.alicorp.bi.config.DatabaseConfig;
import pe.com.alicorp.bi.modulos.tienda_buscador_ia.ServicioBuscadorIA;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static pe.com.alicorp.bi.modulos.chatbot_asistente.MotorIntenciones.*;

/** Chatbot de clientes (tienda virtual): productos, disponibilidad, precios y redirección a otros módulos. */
public class ChatbotClienteService {

    private static final String TIENDA = "/tienda_virtual/tienda.html";
    private static final String CONTACTO = "/contacto_soporte/contacto.html";

    /** Palabras de relleno que se descartan para quedarse con el nombre del producto. */
    private static final Set<String> RELLENO = Set.copyOf(java.util.Arrays.asList(
            "hay", "tienen", "tiene", "tienes", "tengo", "stock", "de", "del", "la", "el", "los", "las", "un", "una", "unos", "unas",
            "precio", "precios", "cuanto", "cuesta", "cuestan", "vale", "valen", "costo", "quiero", "busco", "buscando", "necesito",
            "comprar", "venden", "vende", "disponible", "disponibles", "disponibilidad", "producto", "productos", "me", "puedes",
            "podrias", "decir", "dime", "informacion", "sobre", "en", "por", "favor", "para", "que", "es", "son", "esta", "estan",
            "algun", "alguna", "algo", "hola", "a", "y", "o", "con", "ver", "mostrar", "muestrame", "ensename", "si", "quedan",
            "queda", "existe", "existen", "cual", "cuales", "mas", "barato", "alicorp", "tienda", "tiendas", "sucursal", "sucursales",
            "ubicacion", "direccion", "donde", "categoria", "categorias", "catalogo", "buenas", "buenos", "dias", "tardes", "noches",
            "gracias", "ayuda", "ayudame", "lo", "al", "se", "mi", "su", "como", "cuando", "puedo", "quisiera", "queria", "saber",
            "venta", "virtual", "online", "web", "pagina", "unidad", "unidades"));

    private final ServicioBuscadorIA buscador;
    private volatile List<String> categoriasCache = List.of();
    private volatile long categoriasCargadasMs = 0L;

    public ChatbotClienteService(ServicioBuscadorIA buscador) {
        this.buscador = buscador;
    }

    public static List<String> chipsIniciales() {
        return List.of("¿Hay aceite en stock?", "Ver productos recomendados", "¿Cómo compro?", "Consultar mi pedido",
                "Tengo una queja", "Quiero dar una sugerencia");
    }

    public Map<String, Object> responder(String mensajeOriginal, String usuarioId) {
        String norm = normalizar(mensajeOriginal);
        if (norm.isEmpty()) return base("Escríbeme qué producto buscas o toca una de las opciones de abajo.").build();

        String resto = sinRelleno(norm, RELLENO);

        // 1) Quejas, sugerencias, reclamos y dudas → módulo de Contacto
        if (tiene(norm, "queja*", "reclamo*", "sugerencia*", "sugerir", "sugiero", "denuncia*", "contactar", "contacto",
                "asesor", "hablar con", "atencion al cliente", "problema*", "molesto", "mala atencion")) {
            return derivarContacto(norm).build();
        }
        // 2) Pedido / carrito
        if (tiene(norm, "mi pedido", "pedido*", "mi compra", "seguimiento", "rastrear", "estado de mi", "consultar mi")) {
            return base("Para ver el estado de tu compra necesitas tu código de pedido y el correo con el que compraste. Te abro la ventana de **Mi pedido**.")
                    .accion("📦 Abrir Mi pedido", "click", "#btnPedido")
                    .chip("¿Cómo compro?").chip("Tengo una queja").build();
        }
        if (tiene(norm, "carrito")) {
            return base("Aquí está tu carrito. Desde ahí puedes cambiar cantidades y finalizar la compra.")
                    .accion("🛒 Abrir carrito", "click", "#btnCarrito").chip("Ver productos recomendados").build();
        }
        // 3) Cómo comprar
        if (tiene(norm, "como compro", "como comprar", "como puedo comprar", "como hago", "pasos para comprar", "como se compra")) {
            return base("Comprar es sencillo:\n1. Busca el producto en el buscador o por categorías.\n2. Pulsa **Agregar** para ponerlo en el carrito.\n3. Abre el carrito y pulsa **Finalizar compra**.\n4. Completa tus datos y confirma. Recibirás un código para seguir tu pedido en **Mi pedido**.")
                    .accion("🛒 Abrir carrito", "click", "#btnCarrito")
                    .chip("Consultar mi pedido").chip("Ver productos recomendados").build();
        }
        // 4) Información de negocio que el sistema no tiene cargada (no se inventa)
        if (tiene(norm, "horario*", "pago*", "pagar", "tarjeta*", "yape", "plin", "envio*", "delivery", "despacho*", "entrega*",
                "devolucion*", "garantia", "factura*", "boleta*", "cambio de producto")) {
            return base("Ese dato (horarios, formas de pago, envíos o devoluciones) no lo tengo cargado en la tienda, así que prefiero no darte información incorrecta. El equipo de atención puede confirmártelo.")
                    .enlace("✉️ Consultar al equipo", CONTACTO + "?tipo=duda")
                    .chip("¿Hay aceite en stock?").chip("Tengo una queja").build();
        }
        // 5) Saludos / agradecimientos (mensajes cortos)
        if (norm.split(" ").length <= 4 && tiene(norm, "hola", "buenas", "buenos dias", "buenas tardes", "buenas noches", "hey", "saludos", "que tal")) {
            return base("¡Hola! 👋 Soy el asistente de la tienda Alicorp. Puedo decirte si un producto está disponible, su precio, llevarte a él, o guiarte para enviar una queja o sugerencia. ¿Qué necesitas?")
                    .chip("¿Hay aceite en stock?").chip("Ver productos recomendados").chip("Tengo una queja").build();
        }
        if (norm.split(" ").length <= 4 && tiene(norm, "gracias", "chau", "adios", "listo", "ok", "vale gracias", "hasta luego")) {
            return base("¡Con gusto! Si necesitas algo más, aquí estaré. 😊").chip("Ver productos recomendados").chip("Consultar mi pedido").build();
        }
        // 6) Tiendas y categorías (solo si no se pregunta por un producto concreto)
        if (resto.isEmpty() && tiene(norm, "tienda*", "sucursal*", "ubicacion", "direccion", "donde")) {
            return responderTiendas();
        }
        if (tiene(norm, "categoria*", "catalogo", "que venden", "que productos", "que tienen", "que ofrecen")) {
            Optional<RespuestaChat> cat = responderCategoria(resto);
            if (cat.isPresent()) return cat.get().build();
        }
        // 7) Recomendados / más buscados
        if (tiene(norm, "recomend*", "popular*", "destacad*", "mas vendido*", "mas buscado*", "oferta*", "promo*", "descuento*", "novedad*")) {
            return responderRecomendados(tiene(norm, "oferta*", "promo*", "descuento*"));
        }
        // 8) Producto: disponibilidad, precio, búsqueda
        if (!resto.isEmpty()) {
            RespuestaChat r = responderProducto(resto, tiene(norm, "precio*", "cuesta", "cuestan", "cuanto", "vale", "valen", "costo"));
            if (r != null) return r.build();
        } else if (tiene(norm, "stock", "disponible*", "hay", "tienen", "precio*", "cuanto", "buscar", "busco", "quiero", "necesito")) {
            return base("¿De qué producto te hablo? Escribe el nombre o parte de él (por ejemplo: aceite, fideos, detergente).")
                    .chip("Aceite").chip("Fideos").chip("Detergente").build();
        }
        // 9) No reconocido → IA opcional de respaldo → mensaje por defecto
        Optional<String> ia = AsistenteIAOpcional.compartido().responder("cliente", usuarioId, mensajeOriginal);
        if (ia.isPresent()) {
            return base(ia.get()).origen("ia").chip("Ver productos recomendados").chip("Tengo una queja").build();
        }
        return base("No estoy seguro de haberte entendido. Puedo ayudarte con productos (stock y precio), tu pedido, o enviarte al formulario de quejas y sugerencias.")
                .chip("¿Hay aceite en stock?").chip("Consultar mi pedido").chip("Tengo una queja").chip("Quiero dar una sugerencia").build();
    }

    // ------------------------------------------------------------------ Respuestas

    private RespuestaChat derivarContacto(String norm) {
        String tipo = tiene(norm, "sugerencia*", "sugerir", "sugiero") ? "sugerencia"
                : tiene(norm, "queja*", "reclamo*", "denuncia*", "molesto", "mala atencion", "problema*") ? "queja" : "duda";
        String intro = switch (tipo) {
            case "sugerencia" -> "¡Gracias por querer ayudarnos a mejorar! Las sugerencias se envían desde el formulario de Contacto; ya te lo dejo preparado.";
            case "queja" -> "Lamento que hayas tenido un inconveniente. Las quejas y reclamos se registran en el formulario de Contacto para que el equipo te responda; ya te lo dejo preparado.";
            default -> "Para dudas que no pueda resolver yo, el equipo de atención te responde desde el formulario de Contacto.";
        };
        return base(intro).enlace("✉️ Ir al formulario de contacto", CONTACTO + "?tipo=" + tipo)
                .chip("Consultar mi pedido").chip("Ver productos recomendados");
    }

    private Map<String, Object> responderTiendas() {
        List<String> tiendas = List.of();
        try { tiendas = buscador.obtenerTiendasDisponibles(); } catch (RuntimeException ignored) {}
        if (tiendas == null || tiendas.isEmpty()) {
            return base("Por ahora no tengo la lista de tiendas disponible. Puedes consultarla con el equipo de atención.")
                    .enlace("✉️ Consultar al equipo", CONTACTO + "?tipo=duda").build();
        }
        String lista = String.join(", ", tiendas.subList(0, Math.min(12, tiendas.size())));
        return base("Estas son las tiendas registradas en el sistema: " + lista + (tiendas.size() > 12 ? " y otras más." : "."))
                .chip("¿Hay aceite en stock?").chip("Ver productos recomendados").build();
    }

    private Optional<RespuestaChat> responderCategoria(String resto) {
        List<String> cats = categorias();
        if (cats.isEmpty()) return Optional.empty();
        if (!resto.isEmpty()) {
            for (String c : cats) {
                String cn = normalizar(c);
                if (cn.contains(resto) || resto.contains(cn)) {
                    return Optional.of(base("Te llevo a la categoría **" + c + "**.")
                            .enlace("📂 Ver " + c, TIENDA + "?cat=" + codificar(c)));
                }
            }
            return Optional.empty();
        }
        RespuestaChat r = base("Estas son las categorías de la tienda. Toca una para verla:");
        for (String c : cats.subList(0, Math.min(8, cats.size()))) r.enlace("📂 " + c, TIENDA + "?cat=" + codificar(c));
        return Optional.of(r);
    }

    private Map<String, Object> responderRecomendados(boolean pidioOfertas) {
        List<Map<String, Object>> lista = buscarEnCatalogo("", 4);
        String intro = pidioOfertas
                ? "No tengo información de ofertas o descuentos en el sistema, pero estos son los productos mejor posicionados ahora:"
                : "Estos son los productos mejor posicionados ahora, según rotación y disponibilidad:";
        if (lista.isEmpty()) return base("No pude cargar los productos en este momento. Intenta de nuevo en unos segundos.").build();
        RespuestaChat r = base(intro);
        for (Map<String, Object> p : lista) r.producto(tarjeta(p));
        return r.chip("¿Hay aceite en stock?").chip("¿Cómo compro?").build();
    }

    private RespuestaChat responderProducto(String consulta, boolean pidioPrecio) {
        List<Map<String, Object>> lista = buscarEnCatalogo(consulta, 4);
        if (lista.isEmpty()) {
            // Intento por palabras sueltas, de la más larga a la más corta
            List<String> toks = new ArrayList<>(tokens(consulta));
            toks.sort((a, b) -> Integer.compare(b.length(), a.length()));
            for (String t : toks) {
                if (t.length() < 3) continue;
                lista = buscarEnCatalogo(t, 4);
                if (!lista.isEmpty()) break;
            }
        }
        if (lista.isEmpty()) {
            
            return base("No encontré productos que coincidan con «" + consulta + "». Prueba con otra palabra (por ejemplo la marca o el tipo de producto) o revisa las categorías.")
                    .chip("Ver categorías").chip("Ver productos recomendados").chip("Quiero dar una sugerencia");
        }
        Map<String, Object> top = lista.get(0);
        String nombre = String.valueOf(top.get("nombre"));
        String disp = disponibilidad(top);
        StringBuilder t = new StringBuilder();
        t.append("Encontré ").append(lista.size() > 1 ? "estos productos" : "este producto").append(" para «").append(consulta).append("». ");
        t.append("El más relevante es **").append(nombre).append("**: ").append(disp);
        t.append(", precio S/ ").append(precio(top)).append(".");
        RespuestaChat r = base(t.toString());
        for (Map<String, Object> p : lista) r.producto(tarjeta(p));
        r.enlace("🔎 Ver todos los resultados", TIENDA + "?q=" + codificar(consulta));
        r.chip("Ver productos recomendados").chip("¿Cómo compro?").chip("Tengo una queja");
        return r;
    }

    // ------------------------------------------------------------------ Apoyos

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> buscarEnCatalogo(String q, int limite) {
        try {
            Map<String, Object> res = buscador.buscarConComparativa(q, "", limite);
            Object lista = res.get("resultados_ia");
            return lista instanceof List<?> l ? (List<Map<String, Object>>) l : List.of();
        } catch (RuntimeException e) {
            System.err.println("[CHATBOT-CLIENTE] Búsqueda falló: " + e.getMessage());
            return List.of();
        }
    }

    private Map<String, Object> tarjeta(Map<String, Object> p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("nombre", p.get("nombre"));
        m.put("marca", p.get("marca"));
        m.put("presentacion", p.get("presentacion"));
        m.put("precio", precio(p));
        m.put("disponibilidad", disponibilidad(p));
        m.put("nivel", nivel(p));
        m.put("url", TIENDA + "?q=" + codificar(String.valueOf(p.get("nombre"))));
        return m;
    }

    private static int stock(Map<String, Object> p) {
        return p.get("stock") instanceof Number n ? n.intValue() : 0;
    }

    private static String nivel(Map<String, Object> p) {
        int s = stock(p);
        return s <= 0 ? "agotado" : (s <= 10 ? "poco" : "ok");
    }

    private static String disponibilidad(Map<String, Object> p) {
        return switch (nivel(p)) {
            case "agotado" -> "agotado por ahora";
            case "poco" -> "quedan pocas unidades";
            default -> "disponible";
        };
    }

    private static String precio(Map<String, Object> p) {
        double v = p.get("precio") instanceof Number n ? n.doubleValue() : 0.0;
        return String.format(java.util.Locale.US, "%.2f", v);
    }

    private static String codificar(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static RespuestaChat base(String texto) {
        return RespuestaChat.texto(texto);
    }

    private List<String> categorias() {
        long ahora = System.currentTimeMillis();
        if (!categoriasCache.isEmpty() && ahora - categoriasCargadasMs < 10 * 60_000L) return categoriasCache;
        List<String> nuevas = new ArrayList<>();
        try (Connection c = DatabaseConfig.getDataSource().getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT nombre_categoria FROM CATEGORIAS_PRODUCTO ORDER BY nombre_categoria");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) nuevas.add(rs.getString(1));
        } catch (SQLException e) {
            System.err.println("[CHATBOT-CLIENTE] Sin categorías: " + e.getMessage());
        }
        categoriasCache = nuevas;
        categoriasCargadasMs = ahora;
        return nuevas;
    }
}
