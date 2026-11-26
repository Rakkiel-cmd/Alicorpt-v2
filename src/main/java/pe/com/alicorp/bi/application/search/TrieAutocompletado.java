package pe.com.alicorp.bi.application.search;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Arbol de prefijos (Trie) en memoria para autocompletar productos, marcas y categorias.
 * La busqueda del prefijo y la recoleccion de coincidencias se hacen de forma recursiva.
 */
public final class TrieAutocompletado {

    /** Una sugerencia que se muestra al usuario. tipo: "producto", "marca" o "categoria". */
    public record Sugerencia(String texto, String tipo, double puntaje, String categoria, String marca) {
        /** Constructor corto: para marcas y categorias, el propio texto es la marca o la categoria. */
        public Sugerencia(String texto, String tipo, double puntaje) {
            this(texto, tipo, puntaje,
                    "categoria".equals(tipo) ? texto : "",
                    "marca".equals(tipo) ? texto : "");
        }
    }

    private static final class Nodo {
        final TreeMap<Character, Nodo> hijos = new TreeMap<>();
        List<Sugerencia> finales;
    }

    private static final int MAX_CANDIDATOS = 2000;

    private final Nodo raiz = new Nodo();
    private int claves = 0;

    /** Inserta una clave ya normalizada (minusculas, sin tildes). */
    public void insertar(String clave, Sugerencia sugerencia) {
        if (clave == null || clave.isEmpty()) {
            return;
        }
        Nodo n = raiz;
        for (int i = 0; i < clave.length(); i++) {
            n = n.hijos.computeIfAbsent(clave.charAt(i), k -> new Nodo());
        }
        if (n.finales == null) {
            n.finales = new ArrayList<>(2);
        }
        n.finales.add(sugerencia);
        claves++;
    }

    public int totalClaves() {
        return claves;
    }

    /** Devuelve hasta "limite" sugerencias que empiezan con el prefijo, ordenadas por puntaje. */
    public List<Sugerencia> autocompletar(String prefijo, int limite) {
        if (prefijo == null || limite <= 0) {
            return List.of();
        }
        String pref = prefijo.length() > 120 ? prefijo.substring(0, 120) : prefijo;
        Nodo n = raiz;
        for (int i = 0; i < pref.length(); i++) {
            n = n.hijos.get(pref.charAt(i));
            if (n == null) {
                return List.of();
            }
        }
        List<Sugerencia> candidatos = new ArrayList<>();
        recolectar(n, candidatos);

        Map<String, Sugerencia> unicas = new LinkedHashMap<>();
        for (Sugerencia s : candidatos) {
            Sugerencia previa = unicas.get(s.texto());
            if (previa == null || s.puntaje() > previa.puntaje()) {
                unicas.put(s.texto(), s);
            }
        }
        List<Sugerencia> ordenadas = new ArrayList<>(unicas.values());
        ordenadas.sort(Comparator.comparingDouble(Sugerencia::puntaje).reversed()
                .thenComparing(Sugerencia::texto));
        return ordenadas.size() > limite ? new ArrayList<>(ordenadas.subList(0, limite)) : ordenadas;
    }

    /** Recorrido recursivo del subarbol: junta todas las sugerencias que cuelgan de este nodo. */
    private void recolectar(Nodo nodo, List<Sugerencia> salida) {
        if (salida.size() >= MAX_CANDIDATOS) {
            return;
        }
        if (nodo.finales != null) {
            salida.addAll(nodo.finales);
        }
        for (Nodo hijo : nodo.hijos.values()) {
            if (salida.size() >= MAX_CANDIDATOS) {
                return;
            }
            recolectar(hijo, salida);
        }
    }
}
