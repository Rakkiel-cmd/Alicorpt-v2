package pe.com.alicorp.bi.application.search;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Ranking personalizado del buscador de la tienda.
 *
 * En lugar de ordenar los resultados alfabeticamente, se reordenan con un puntaje que mezcla tres senales:
 * (1) relevancia con lo escrito, (2) afinidad con el historial de la persona (categorias, marcas y productos
 * que ya vio o agrego al carrito) y (3) disponibilidad de stock. Los agotados siempre quedan al final.
 *
 * El historial lo guarda el navegador de cada persona y llega como un texto corto en el parametro "perfil".
 * Formato: entradas separadas por "|", cada una "tipo:clave:peso" (tipo c=categoria, m=marca, s=sku; la clave
 * va codificada con encodeURIComponent). Ejemplo: c:Aceites:5|m:Primor:3|s:AC-001:2
 *
 * Es una clase sin dependencias externas, para poder probarla sola.
 */
public final class RankingBusqueda {

    public static final String MODO_IA = "ia";
    public static final String MODO_ALFABETICO = "alfabetico";

    // Pesos del puntaje cuando la persona tiene historial y cuando no
    private static final double CON_PERFIL_RELEVANCIA = 0.25;
    private static final double CON_PERFIL_AFINIDAD = 0.50;
    private static final double CON_PERFIL_STOCK = 0.25;
    private static final double SIN_PERFIL_RELEVANCIA = 0.60;
    private static final double SIN_PERFIL_STOCK = 0.40;
    /** Los agotados conservan solo esta fraccion del puntaje, asi quedan siempre debajo de los disponibles. */
    private static final double FACTOR_AGOTADO = 0.2;
    /** Cuanto puede subir una sugerencia del autocompletar segun la afinidad (el puntaje base va de 0 a 16). */
    private static final double BONO_SUGERENCIA = 12.0;
    private static final double UMBRAL_MOTIVO = 0.2;
    private static final int MAX_POR_MARCA_EN_RECOMENDADOS = 3;
    private static final double PENALIZA_YA_VISTO = 0.25;

    private RankingBusqueda() {
    }

    /** Solo existen dos modos: "ia" (por defecto) y "alfabetico" (la forma tradicional, para comparar). */
    public static String normalizarModo(String modo) {
        if (modo != null && MODO_ALFABETICO.equalsIgnoreCase(modo.trim())) {
            return MODO_ALFABETICO;
        }
        return MODO_IA;
    }

    static String normalizarClave(String s) {
        if (s == null) {
            return "";
        }
        String n = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        return n.toLowerCase(Locale.ROOT).trim();
    }

    private static String texto(Map<String, Object> m, String clave) {
        Object o = m.get(clave);
        return o == null ? "" : String.valueOf(o);
    }

    private static int stockDe(Map<String, Object> m) {
        Object o = m.get("stock");
        return o instanceof Number n ? n.intValue() : 0;
    }

    /** 0 si esta agotado; de 0.5 a 1 segun las unidades disponibles. */
    static double factorStock(int stock) {
        if (stock <= 0) {
            return 0.0;
        }
        return 0.5 + 0.5 * Math.min(1.0, stock / 300.0);
    }

    private static double redondear(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }

    // ------------------------------------------------------------------ perfil de la persona

    /** Gustos de una persona, aprendidos de su historial (categorias, marcas y productos). */
    public static final class Perfil {
        private static final int MAX_ENTRADAS = 60;

        private final Map<String, Double> categorias = new HashMap<>();
        private final Map<String, Double> marcas = new HashMap<>();
        private final Map<String, Double> skus = new HashMap<>();
        private double maxCategoria;
        private double maxMarca;
        private double maxSku;

        private Perfil() {
        }

        public static Perfil vacio() {
            return new Perfil();
        }

        /** Lee el texto que manda el navegador. Lo que no se entienda se ignora, nunca falla. */
        public static Perfil desdeTexto(String texto) {
            Perfil p = new Perfil();
            if (texto == null || texto.isBlank()) {
                return p;
            }
            int leidas = 0;
            for (String entrada : texto.split("\\|")) {
                if (leidas >= MAX_ENTRADAS) {
                    break;
                }
                String[] partes = entrada.split(":", 3);
                if (partes.length != 3) {
                    continue;
                }
                double peso;
                try {
                    peso = Double.parseDouble(partes[2].trim());
                } catch (NumberFormatException e) {
                    continue;
                }
                if (Double.isNaN(peso) || Double.isInfinite(peso) || peso <= 0) {
                    continue;
                }
                peso = Math.min(peso, 1000.0);
                String clave;
                try {
                    clave = normalizarClave(URLDecoder.decode(partes[1], StandardCharsets.UTF_8));
                } catch (IllegalArgumentException e) {
                    continue;
                }
                if (clave.isEmpty()) {
                    continue;
                }
                switch (partes[0].trim()) {
                    case "c" -> p.categorias.merge(clave, peso, (a, b) -> a + b);
                    case "m" -> p.marcas.merge(clave, peso, (a, b) -> a + b);
                    case "s" -> p.skus.merge(clave, peso, (a, b) -> a + b);
                    default -> {
                        continue;
                    }
                }
                leidas++;
            }
            p.maxCategoria = maximo(p.categorias);
            p.maxMarca = maximo(p.marcas);
            p.maxSku = maximo(p.skus);
            return p;
        }

        private static double maximo(Map<String, Double> mapa) {
            double m = 0.0;
            for (double v : mapa.values()) {
                if (v > m) {
                    m = v;
                }
            }
            return m;
        }

        public boolean estaVacio() {
            return categorias.isEmpty() && marcas.isEmpty() && skus.isEmpty();
        }

        /** Peso entre 0 y 1; usa logaritmo para que una sola categoria muy repetida no aplaste a las demas. */
        private static double norma(Map<String, Double> mapa, double max, String clave) {
            if (max <= 0 || clave.isEmpty()) {
                return 0.0;
            }
            Double w = mapa.get(clave);
            if (w == null) {
                return 0.0;
            }
            return Math.log1p(w) / Math.log1p(max);
        }

        public double afinidadCategoria(String categoria) {
            return norma(categorias, maxCategoria, normalizarClave(categoria));
        }

        public double afinidadMarca(String marca) {
            return norma(marcas, maxMarca, normalizarClave(marca));
        }

        public double afinidadSku(String sku) {
            return norma(skus, maxSku, normalizarClave(sku));
        }

        /** Afinidad total de un producto: pesa mas la categoria, luego la marca y un poco el producto ya visto. */
        public double afinidad(String categoria, String marca, String sku) {
            double a = 0.55 * afinidadCategoria(categoria) + 0.35 * afinidadMarca(marca) + 0.10 * afinidadSku(sku);
            return Math.min(1.0, a);
        }

        public double afinidadSugerencia(TrieAutocompletado.Sugerencia s) {
            return switch (s.tipo()) {
                case "categoria" -> afinidadCategoria(s.texto());
                case "marca" -> afinidadMarca(s.texto());
                default -> afinidad(s.categoria(), s.marca(), "");
            };
        }
    }

    // ------------------------------------------------------------------ resultados de busqueda

    /**
     * Reordena los productos encontrados. En modo alfabetico los ordena por nombre. En modo "ia" usa el puntaje
     * descrito arriba. Devuelve copias (no toca el catalogo en memoria) y como maximo "limite" productos.
     * En modo "ia" cada copia trae "ia_puntaje" y, si hay una razon clara, "ia_motivo".
     */
    public static List<Map<String, Object>> reordenar(List<Map<String, Object>> items, Perfil perfil, String modo, int limite) {
        List<Map<String, Object>> salida = new ArrayList<>();
        if (items == null || items.isEmpty()) {
            return salida;
        }
        int tope = Math.max(1, limite);
        if (MODO_ALFABETICO.equals(modo)) {
            List<Map<String, Object>> copia = new ArrayList<>(items);
            copia.sort(Comparator.comparing((Map<String, Object> m) -> normalizarClave(texto(m, "nombre"))));
            return copia.size() > tope ? new ArrayList<>(copia.subList(0, tope)) : copia;
        }
        boolean personalizado = perfil != null && !perfil.estaVacio();
        int n = items.size();
        List<Object[]> puntuados = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            Map<String, Object> it = items.get(i);
            double relevancia = n <= 1 ? 1.0 : 1.0 - (double) i / n;
            int stock = stockDe(it);
            double fs = factorStock(stock);
            double puntaje;
            if (personalizado) {
                double afinidad = perfil.afinidad(texto(it, "categoria"), texto(it, "marca"), texto(it, "sku"));
                puntaje = CON_PERFIL_RELEVANCIA * relevancia + CON_PERFIL_AFINIDAD * afinidad + CON_PERFIL_STOCK * fs;
            } else {
                puntaje = SIN_PERFIL_RELEVANCIA * relevancia + SIN_PERFIL_STOCK * fs;
            }
            if (stock <= 0) {
                puntaje *= FACTOR_AGOTADO;
            }
            puntuados.add(new Object[]{puntaje, i});
        }
        puntuados.sort((a, b) -> {
            int c = Double.compare((Double) b[0], (Double) a[0]);
            return c != 0 ? c : Integer.compare((Integer) a[1], (Integer) b[1]);
        });
        for (Object[] p : puntuados) {
            if (salida.size() >= tope) {
                break;
            }
            Map<String, Object> copia = new LinkedHashMap<>(items.get((Integer) p[1]));
            copia.put("ia_puntaje", redondear((Double) p[0]));
            if (personalizado && stockDe(copia) > 0) {
                String motivo = motivo(perfil, copia);
                if (motivo != null) {
                    copia.put("ia_motivo", motivo);
                }
            }
            salida.add(copia);
        }
        return salida;
    }

    /** Frase corta que explica por que se subio el producto (o null si no hay una razon clara). */
    static String motivo(Perfil perfil, Map<String, Object> item) {
        double ac = perfil.afinidadCategoria(texto(item, "categoria"));
        double am = perfil.afinidadMarca(texto(item, "marca"));
        double as = perfil.afinidadSku(texto(item, "sku"));
        if (as >= 0.5) {
            return "Lo viste o lo compraste antes";
        }
        if (ac >= am && ac >= UMBRAL_MOTIVO) {
            return "Porque te interesa " + texto(item, "categoria");
        }
        if (am >= UMBRAL_MOTIVO) {
            return "Marca que sueles elegir: " + texto(item, "marca");
        }
        return null;
    }

    // ------------------------------------------------------------------ autocompletar

    /**
     * Reordena las sugerencias que devolvio el Trie. En modo alfabetico, por texto. En modo "ia", el puntaje base
     * (stock, marca, categoria) sube segun la afinidad; las agotadas no suben.
     */
    public static List<TrieAutocompletado.Sugerencia> reordenarSugerencias(
            List<TrieAutocompletado.Sugerencia> candidatas, Perfil perfil, String modo, int limite) {
        List<TrieAutocompletado.Sugerencia> copia = new ArrayList<>(candidatas == null ? List.of() : candidatas);
        int tope = Math.max(1, limite);
        if (MODO_ALFABETICO.equals(modo)) {
            copia.sort(Comparator.comparing((TrieAutocompletado.Sugerencia s) -> normalizarClave(s.texto())));
        } else if (perfil != null && !perfil.estaVacio()) {
            copia.sort(Comparator
                    .comparingDouble((TrieAutocompletado.Sugerencia s) -> -puntajeSugerencia(perfil, s))
                    .thenComparing(s -> s.texto()));
        }
        return copia.size() > tope ? new ArrayList<>(copia.subList(0, tope)) : copia;
    }

    private static double puntajeSugerencia(Perfil perfil, TrieAutocompletado.Sugerencia s) {
        double bono = s.puntaje() > 0 ? BONO_SUGERENCIA * perfil.afinidadSugerencia(s) : 0.0;
        return s.puntaje() + bono;
    }

    // ------------------------------------------------------------------ recomendados

    /**
     * Productos disponibles que mas se parecen a los gustos de la persona. Sin historial devuelve una lista vacia.
     * Se limita a pocas unidades por marca para que la lista no sea siempre la misma marca.
     */
    public static List<Map<String, Object>> recomendar(List<Map<String, Object>> items, Perfil perfil, int cantidad) {
        List<Map<String, Object>> salida = new ArrayList<>();
        if (items == null || perfil == null || perfil.estaVacio()) {
            return salida;
        }
        List<Object[]> puntuados = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            Map<String, Object> it = items.get(i);
            int stock = stockDe(it);
            if (stock <= 0) {
                continue;
            }
            double afinidad = perfil.afinidad(texto(it, "categoria"), texto(it, "marca"), "");
            if (afinidad < UMBRAL_MOTIVO) {
                continue;
            }
            // Lo que ya vio o compro se mantiene (en el super se repite la compra), pero va despues de lo nuevo
            double yaVisto = perfil.afinidadSku(texto(it, "sku")) >= 0.5 ? PENALIZA_YA_VISTO : 0.0;
            puntuados.add(new Object[]{0.8 * afinidad + 0.2 * factorStock(stock) - yaVisto, i});
        }
        puntuados.sort((a, b) -> {
            int c = Double.compare((Double) b[0], (Double) a[0]);
            return c != 0 ? c : Integer.compare((Integer) a[1], (Integer) b[1]);
        });
        Map<String, Integer> porMarca = new HashMap<>();
        int tope = Math.max(1, cantidad);
        for (Object[] p : puntuados) {
            if (salida.size() >= tope) {
                break;
            }
            Map<String, Object> original = items.get((Integer) p[1]);
            String marca = normalizarClave(texto(original, "marca"));
            int usados = porMarca.getOrDefault(marca, 0);
            if (usados >= MAX_POR_MARCA_EN_RECOMENDADOS) {
                continue;
            }
            porMarca.put(marca, usados + 1);
            Map<String, Object> copia = new LinkedHashMap<>(original);
            copia.put("ia_puntaje", redondear((Double) p[0]));
            String motivo = motivo(perfil, copia);
            if (motivo != null) {
                copia.put("ia_motivo", motivo);
            }
            salida.add(copia);
        }
        return salida;
    }
}
