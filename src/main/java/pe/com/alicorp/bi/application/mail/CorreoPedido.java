package pe.com.alicorp.bi.application.mail;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Correo de seguimiento del pedido (tienda en linea), enviado por la API HTTPS de Brevo.
 *
 * Configuracion en el archivo .env (nunca en el codigo ni en GitHub):
 *   BREVO_API_KEY      clave de la API de Brevo (empieza con "xkeysib-")
 *   CORREO_REMITENTE   correo verificado en Brevo desde el que salen los mensajes
 *   CORREO_NOMBRE      (opcional) nombre que ve el cliente
 *   SITIO_URL          (opcional) direccion publica de la tienda (si se omite, usa el origen HTTPS/Host de la peticion)
 *
 * Si faltan las claves no pasa nada: la compra se guarda igual y simplemente no se envia correo.
 * El envio es asincrono, asi que nunca demora ni rompe la compra.
 */
public final class CorreoPedido {

    /** Una linea del pedido: producto, cantidad y subtotal. */
    public record Linea(String producto, int cantidad, double subtotal) {
    }

    private static final String URL_API = "https://api.brevo.com/v3/smtp/email";
    private static final HttpClient CLIENTE = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private static final ObjectMapper JSON = new ObjectMapper();

    private CorreoPedido() {
    }

    private static Map<String, String> config() {
        Map<String, String> mapa = new HashMap<>(System.getenv());
        Path envPath = Path.of(".env");
        if (Files.exists(envPath)) {
            try {
                for (String linea : Files.readAllLines(envPath, StandardCharsets.UTF_8)) {
                    String l = linea.trim();
                    if (l.isEmpty() || l.startsWith("#") || !l.contains("=")) {
                        continue;
                    }
                    int i = l.indexOf('=');
                    String v = l.substring(i + 1).trim();
                    if (v.length() >= 2 && (v.startsWith("\"") && v.endsWith("\"") || v.startsWith("'") && v.endsWith("'"))) {
                        v = v.substring(1, v.length() - 1);
                    }
                    if (!v.isEmpty()) {
                        mapa.putIfAbsent(l.substring(0, i).trim(), v);
                    }
                }
            } catch (IOException ignorado) {
                // sin .env: se usan solo las variables del sistema
            }
        }
        return mapa;
    }

    public static boolean configurado() {
        Map<String, String> c = config();
        return !c.getOrDefault("BREVO_API_KEY", "").isBlank() && !c.getOrDefault("CORREO_REMITENTE", "").isBlank();
    }

    /** Lanza el envio en segundo plano. Devuelve true si se intento enviar, false si falta la configuracion. */
    public static boolean enviarSeguimiento(String destino, String nombre, int compraId, double total, String direccion, List<Linea> detalle) {
        return enviarSeguimiento(destino, nombre, compraId, total, direccion, detalle, "");
    }

    public static boolean enviarSeguimiento(String destino, String nombre, int compraId, double total, String direccion, List<Linea> detalle, String sitioFallback) {
        Map<String, String> c = config();
        String clave = c.getOrDefault("BREVO_API_KEY", "").trim();
        String remitente = c.getOrDefault("CORREO_REMITENTE", "").trim();
        if (clave.isEmpty() || remitente.isEmpty()) {
            return false;
        }
        String nombreRemitente = c.getOrDefault("CORREO_NOMBRE", "Alicorp Tienda en línea").trim();
        String sitioEnv = c.getOrDefault("SITIO_URL", "").trim();
        String sitioBase = !sitioEnv.isEmpty() ? sitioEnv : (sitioFallback == null ? "" : sitioFallback.trim());
        String sitio = sitioBase.replaceAll("/+$", "");
        try {
            Map<String, Object> cuerpo = Map.of(
                    "sender", Map.of("name", nombreRemitente, "email", remitente),
                    "to", List.of(Map.of("email", destino, "name", nombre)),
                    "subject", "Tu pedido N.º " + compraId + " - seguimiento",
                    "htmlContent", construirHtml(nombre, compraId, total, direccion, detalle, sitio));
            HttpRequest peticion = HttpRequest.newBuilder(URI.create(URL_API))
                    .timeout(Duration.ofSeconds(15))
                    .header("api-key", clave)
                    .header("content-type", "application/json")
                    .header("accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(cuerpo), StandardCharsets.UTF_8))
                    .build();
            CLIENTE.sendAsync(peticion, HttpResponse.BodyHandlers.ofString())
                    .thenAccept(r -> {
                        if (r.statusCode() / 100 == 2) {
                            System.out.println("[CORREO] Seguimiento del pedido " + compraId + " enviado (HTTP " + r.statusCode() + ").");
                        } else {
                            String texto = r.body() == null ? "" : r.body();
                            System.err.println("[CORREO] Brevo rechazo el pedido " + compraId + " (HTTP " + r.statusCode() + "): "
                                    + texto.substring(0, Math.min(300, texto.length())));
                        }
                    })
                    .exceptionally(e -> {
                        System.err.println("[CORREO] No se pudo enviar el pedido " + compraId + ": " + e.getMessage());
                        return null;
                    });
            return true;
        } catch (Exception e) {
            System.err.println("[CORREO] No se pudo preparar el correo del pedido " + compraId + ": " + e.getMessage());
            return false;
        }
    }

    private static String e(String t) {
        if (t == null) {
            return "";
        }
        return t.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
    }

    private static String dinero(double v) {
        return String.format(Locale.US, "S/ %,.2f", v);
    }

    private static String construirHtml(String nombre, int compraId, double total, String direccion, List<Linea> detalle, String sitio) {
        StringBuilder filas = new StringBuilder();
        for (Linea l : detalle) {
            filas.append("<tr><td style='padding:10px 8px;border-bottom:1px solid #e5e7eb'>").append(e(l.producto()))
                    .append("</td><td style='padding:10px 8px;border-bottom:1px solid #e5e7eb;text-align:center'>").append(l.cantidad())
                    .append("</td><td style='padding:10px 8px;border-bottom:1px solid #e5e7eb;text-align:right'>").append(dinero(l.subtotal()))
                    .append("</td></tr>");
        }
        String[] pasos = {"Recibido", "Preparando", "En camino", "Entregado"};
        StringBuilder cinta = new StringBuilder();
        for (int i = 0; i < pasos.length; i++) {
            cinta.append("<td style='text-align:center;padding:9px 4px;font-size:12px;border-radius:4px;")
                    .append(i == 0 ? "background:#1e3a8a;color:#ffffff;font-weight:bold;" : "background:#f3f4f6;color:#6b7280;")
                    .append("'>").append(pasos[i]).append("</td>");
            if (i < pasos.length - 1) {
                cinta.append("<td width='4'></td>");
            }
        }
        String dir = (direccion == null || direccion.isBlank()) ? "-" : direccion;
        return "<div style=\"background:#f5f6f8;padding:24px 12px;font-family:Arial,Helvetica,sans-serif;color:#1f2937\">"
                + "<div style=\"max-width:580px;margin:auto;background:#ffffff;border:1px solid #e5e7eb;border-radius:8px;overflow:hidden\">"
                + "<div style=\"background-color:#1e3a8a;color:#ffffff;padding:24px 26px\">"
                + "<div style=\"font-size:12px;letter-spacing:1.5px;opacity:.85\">ALICORP | TIENDA EN LÍNEA</div>"
                + "<h2 style=\"margin:10px 0 4px;font-size:22px;font-weight:bold\">Gracias por tu compra, " + e(nombre) + "</h2>"
                + "<p style=\"margin:0;font-size:14px;opacity:.9\">Recibimos tu pedido correctamente.</p></div>"
                + "<div style=\"padding:24px 26px\">"
                + "<p style=\"font-size:14px;margin:0 0 18px;color:#6b7280\">N.º de pedido<br><b style=\"font-size:24px;color:#1e3a8a\">" + compraId + "</b></p>"
                + "<p style=\"margin:0 0 8px;font-size:13px;font-weight:bold\">Estado actual</p>"
                + "<table width=\"100%\" cellspacing=\"0\" cellpadding=\"0\" style=\"border-collapse:separate\"><tr>" + cinta + "</tr></table>"
                + "<h3 style=\"margin:26px 0 6px;font-size:15px\">Detalle de tu pedido</h3>"
                + "<table width=\"100%\" style=\"border-collapse:collapse;font-size:14px\">"
                + "<tr style=\"background:#f9fafb\"><th align=\"left\" style=\"padding:9px 8px;font-size:12px;color:#6b7280\">PRODUCTO</th><th style=\"padding:9px 8px;font-size:12px;color:#6b7280\">CANT.</th><th align=\"right\" style=\"padding:9px 8px;font-size:12px;color:#6b7280\">SUBTOTAL</th></tr>"
                + filas
                + "<tr><td colspan=\"2\" style=\"padding:14px 8px;text-align:right\"><b>Total</b></td><td style=\"padding:14px 8px;text-align:right;font-size:17px;color:#1f2937\"><b>" + dinero(total) + "</b></td></tr></table>"
                + "<p style=\"font-size:14px;margin:6px 0 0\"><b>Dirección de entrega:</b> " + e(dir) + "</p>"
                + "<h3 style=\"margin:24px 0 6px;font-size:15px\">¿Cómo sigo mi pedido?</h3>"
                + "<p style=\"font-size:14px;margin:0;line-height:1.5\">Entra a la tienda, pulsa <b>Mi pedido</b> y escribe tu número de pedido (<b>" + compraId + "</b>) y este mismo correo.</p>"
                + "<p style=\"text-align:center;margin:24px 0\"><a href=\"" + e(sitio) + "/tienda.html\" style=\"background-color:#e31b23;color:#ffffff;text-decoration:none;padding:13px 28px;border-radius:6px;font-weight:bold;display:inline-block\">Ir a la tienda</a></p>"
                + "<p style=\"font-size:12px;color:#9ca3af;margin:0;border-top:1px solid #e5e7eb;padding-top:14px\">Este es un correo automático de un proyecto académico (SENATI). No respondas a este mensaje.</p>"
                + "</div></div></div>";
    }
}
