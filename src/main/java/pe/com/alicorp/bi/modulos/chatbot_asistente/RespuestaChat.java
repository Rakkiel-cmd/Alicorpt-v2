package pe.com.alicorp.bi.modulos.chatbot_asistente;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Respuesta estándar de los tres chatbots. Se serializa como JSON:
 * { success, texto, origen, chips[], enlaces[], acciones[], productos[] }.
 *  - chips: botones de respuesta rápida (al tocarlos se envía su "mensaje" como si el usuario lo escribiera).
 *  - enlaces: botones que llevan a otra página/módulo.
 *  - acciones: botones que ejecutan algo en la página actual (ej. abrir el carrito, ir a una pestaña).
 *  - productos: tarjetas de producto (solo chatbot de clientes).
 */
public final class RespuestaChat {

    private String texto;
    private String origen = "reglas";
    private final List<Map<String, Object>> chips = new ArrayList<>();
    private final List<Map<String, Object>> enlaces = new ArrayList<>();
    private final List<Map<String, Object>> acciones = new ArrayList<>();
    private final List<Map<String, Object>> productos = new ArrayList<>();

    private RespuestaChat(String texto) {
        this.texto = texto;
    }

    public static RespuestaChat texto(String texto) {
        return new RespuestaChat(texto);
    }

    public RespuestaChat origen(String o) {
        this.origen = o;
        return this;
    }

    public RespuestaChat chip(String etiqueta) {
        return chip(etiqueta, etiqueta);
    }

    public RespuestaChat chip(String etiqueta, String mensaje) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("etiqueta", etiqueta);
        c.put("mensaje", mensaje);
        chips.add(c);
        return this;
    }

    public RespuestaChat enlace(String etiqueta, String url) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("etiqueta", etiqueta);
        e.put("url", url);
        enlaces.add(e);
        return this;
    }

    public RespuestaChat accion(String etiqueta, String accion, String valor) {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("etiqueta", etiqueta);
        a.put("accion", accion);
        a.put("valor", valor == null ? "" : valor);
        acciones.add(a);
        return this;
    }

    public RespuestaChat producto(Map<String, Object> p) {
        productos.add(p);
        return this;
    }

    public Map<String, Object> build() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("success", true);
        m.put("texto", texto);
        m.put("origen", origen);
        m.put("chips", chips);
        m.put("enlaces", enlaces);
        m.put("acciones", acciones);
        m.put("productos", productos);
        return m;
    }
}
