package pe.com.alicorp.bi.modulos.chatbot_asistente;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Utilidades del motor por reglas: normaliza el texto (minúsculas, sin tildes ni signos) y
 * detecta palabras clave. Una clave que termina en '*' es un prefijo ("tienda*" coincide con
 * "tienda", "tiendas"); una clave con espacios se busca como frase.
 */
public final class MotorIntenciones {

    private MotorIntenciones() {}

    public static String normalizar(String texto) {
        if (texto == null) return "";
        String n = Normalizer.normalize(texto.toLowerCase(Locale.ROOT).replace("²", "2"), Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "");
        n = n.replaceAll("[^a-z0-9ñ ]", " ").replaceAll("\\s+", " ").trim();
        return n;
    }

    public static Set<String> tokens(String normalizado) {
        if (normalizado.isEmpty()) return Set.of();
        return new HashSet<>(Arrays.asList(normalizado.split(" ")));
    }

    /** true si el texto normalizado contiene al menos una de las claves. */
    public static boolean tiene(String normalizado, String... claves) {
        Set<String> toks = tokens(normalizado);
        String relleno = " " + normalizado + " ";
        for (String clave : claves) {
            if (clave.endsWith("*")) {
                String raiz = clave.substring(0, clave.length() - 1);
                for (String t : toks) {
                    if (t.startsWith(raiz)) return true;
                }
            } else if (clave.contains(" ")) {
                if (relleno.contains(" " + clave + " ")) return true;
            } else if (toks.contains(clave)) {
                return true;
            }
        }
        return false;
    }

    /** Cuántas claves distintas coinciden (sirve para elegir la intención más fuerte). */
    public static int puntaje(String normalizado, String... claves) {
        int n = 0;
        for (String c : claves) {
            if (tiene(normalizado, c)) n++;
        }
        return n;
    }

    /** Quita palabras de relleno para quedarse con el producto que se pregunta. */
    public static String sinRelleno(String normalizado, Set<String> relleno) {
        List<String> utiles = Arrays.stream(normalizado.split(" "))
                .filter(t -> !t.isBlank() && !relleno.contains(t))
                .collect(Collectors.toList());
        return String.join(" ", utiles);
    }
}
