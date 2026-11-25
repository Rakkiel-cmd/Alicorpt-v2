package pe.com.alicorp.bi.modulos.chatbot_asistente;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Respaldo OPCIONAL con IA gratuita (Google Gemini). Solo se usa cuando el motor por reglas no
 * reconoce la pregunta. Viene APAGADO: se activa poniendo CHATBOT_IA_API_KEY en el .env.
 *
 * Protecciones: límite de consultas por usuario y día, caché de respuestas repetidas, tiempo máximo
 * de espera, pausa automática si el servicio devuelve error/cuota agotada, e instrucción de sistema
 * que prohíbe inventar datos (stock, precios, cifras). Si algo falla devuelve vacío y el chatbot
 * sigue funcionando con las reglas.
 *
 * Variables (.env): CHATBOT_IA_API_KEY, CHATBOT_IA_MODELO (def. gemini-flash-latest),
 * CHATBOT_IA_LIMITE_DIA (def. 15 por usuario).
 */
public final class AsistenteIAOpcional {

    private static final AsistenteIAOpcional INSTANCIA = new AsistenteIAOpcional();
    public static AsistenteIAOpcional compartido() { return INSTANCIA; }

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long TTL_CACHE_MS = 60 * 60 * 1000L;
    private static final long PAUSA_TRAS_ERROR_MS = 60_000L;
    private static final int MAX_CACHE = 300;

    private final String apiKey;
    private final String modelo;
    private final int limiteDia;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4)).build();
    private final Map<String, Object[]> cache = new ConcurrentHashMap<>();       // pregunta -> {texto, expira}
    private final Map<String, int[]> usoPorUsuario = new ConcurrentHashMap<>();   // usuario|fecha -> {contador}
    private volatile long pausadoHasta = 0L;

    private AsistenteIAOpcional() {
        Map<String, String> env = cargarEnv();
        this.apiKey = env.getOrDefault("CHATBOT_IA_API_KEY", "").trim();
        this.modelo = env.getOrDefault("CHATBOT_IA_MODELO", "gemini-flash-latest").trim();
        int lim = 15;
        try { lim = Integer.parseInt(env.getOrDefault("CHATBOT_IA_LIMITE_DIA", "15").trim()); } catch (NumberFormatException ignored) {}
        this.limiteDia = Math.max(1, lim);
    }

    public boolean habilitada() {
        return !apiKey.isEmpty();
    }

    /** Devuelve la respuesta de la IA o vacío si está apagada, sin cuota, en pausa o falló. */
    public Optional<String> responder(String rol, String usuario, String pregunta) {
        if (!habilitada() || pregunta == null || pregunta.isBlank()) return Optional.empty();
        if (System.currentTimeMillis() < pausadoHasta) return Optional.empty();

        String clave = rol + "|" + MotorIntenciones.normalizar(pregunta);
        Object[] enCache = cache.get(clave);
        if (enCache != null && (long) enCache[1] > System.currentTimeMillis()) {
            return Optional.of((String) enCache[0]);
        }
        if (!consumirCuota(rol + ":" + (usuario == null ? "anon" : usuario))) return Optional.empty();

        try {
            Map<String, Object> cuerpo = new LinkedHashMap<>();
            cuerpo.put("systemInstruction", Map.of("parts", List.of(Map.of("text", instruccion(rol)))));
            cuerpo.put("contents", List.of(Map.of("role", "user",
                    "parts", List.of(Map.of("text", pregunta.length() > 500 ? pregunta.substring(0, 500) : pregunta)))));
            cuerpo.put("generationConfig", Map.of("temperature", 0.3, "maxOutputTokens", 300));

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create("https://generativelanguage.googleapis.com/v1beta/models/" + modelo + ":generateContent"))
                    .timeout(Duration.ofSeconds(8))
                    .header("Content-Type", "application/json")
                    .header("x-goog-api-key", apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(cuerpo)))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                pausadoHasta = System.currentTimeMillis() + PAUSA_TRAS_ERROR_MS;   // 429/5xx/modelo retirado: pausa y usa reglas
                System.err.println("[CHATBOT-IA] HTTP " + resp.statusCode() + " (pausa 60 s, el chatbot sigue con reglas)");
                return Optional.empty();
            }
            JsonNode raiz = JSON.readTree(resp.body());
            String texto = raiz.path("candidates").path(0).path("content").path("parts").path(0).path("text").asText("").trim();
            if (texto.isEmpty()) return Optional.empty();
            if (cache.size() >= MAX_CACHE) cache.clear();
            cache.put(clave, new Object[]{texto, System.currentTimeMillis() + TTL_CACHE_MS});
            return Optional.of(texto);
        } catch (IOException | InterruptedException | RuntimeException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            pausadoHasta = System.currentTimeMillis() + PAUSA_TRAS_ERROR_MS;
            System.err.println("[CHATBOT-IA] Falló la consulta: " + e.getMessage());
            return Optional.empty();
        }
    }

    private boolean consumirCuota(String quien) {
        usoPorUsuario.keySet().removeIf(k -> !k.endsWith("|" + LocalDate.now()));
        int[] c = usoPorUsuario.computeIfAbsent(quien + "|" + LocalDate.now(), k -> new int[1]);
        synchronized (c) {
            if (c[0] >= limiteDia) return false;
            c[0]++;
            return true;
        }
    }

    private static String instruccion(String rol) {
        String base = "Eres el asistente de texto de la plataforma Alicorp BI. Responde en español, en máximo 4 oraciones, "
                + "con tono neutral y claro. NUNCA inventes datos: no des cifras de stock, precios, cantidades de tickets, "
                + "fechas ni nombres de personas; si te preguntan algo así, di que no tienes ese dato en este momento y sugiere "
                + "usar los botones del chat. Si no sabes algo, dilo. No des consejos legales ni médicos. ";
        return switch (rol) {
            case "cliente" -> base + "Ayudas a clientes de la tienda virtual con dudas generales sobre cómo comprar, buscar productos "
                    + "y contactar a la empresa. Para quejas o sugerencias indica que usen la página de Contacto.";
            case "analista" -> base + "Ayudas a analistas a entender conceptos de analítica (regresión, R², K-Means, redes neuronales, "
                    + "estadística descriptiva) y a gestionar tickets de soporte.";
            default -> base + "Ayudas a administradores a interpretar la actividad del equipo, la auditoría y la seguridad de la plataforma.";
        };
    }

    private static Map<String, String> cargarEnv() {
        Map<String, String> mapa = new HashMap<>(System.getenv());
        Path p = Path.of(".env");
        if (Files.exists(p)) {
            try {
                for (String linea : Files.readAllLines(p)) {
                    String l = linea.trim();
                    if (l.isEmpty() || l.startsWith("#") || !l.contains("=")) continue;
                    int i = l.indexOf('=');
                    String v = l.substring(i + 1).trim();
                    if (!v.isEmpty()) mapa.putIfAbsent(l.substring(0, i).trim(), v);
                }
            } catch (IOException ignored) {}
        }
        return mapa;
    }
}
