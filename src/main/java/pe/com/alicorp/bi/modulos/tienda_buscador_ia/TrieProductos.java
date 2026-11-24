package pe.com.alicorp.bi.modulos.tienda_buscador_ia;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Árbol de Prefijos (Trie) implementado en Java puro para autocompletado en tiempo real.
 * Implementa inserción y búsqueda estrictamente recursivas (buscarPrefijoRecursivo)
 * sobre nombres, marcas y categorías normalizadas sin tildes.
 */
public class TrieProductos {

    private static class NodoTrie {
        final Map<Character, NodoTrie> hijos = new TreeMap<>();
        boolean esFinPalabra = false;
        final List<ProductoCatalogoIA> productos = new ArrayList<>();
    }

    private final NodoTrie raiz = new NodoTrie();
    private int totalProductosIndexados = 0;

    /**
     * Normaliza texto a minúsculas sin tildes ni caracteres especiales redundantes.
     */
    public static String normalizarTexto(String texto) {
        if (texto == null) return "";
        String sinTildes = Normalizer.normalize(texto.trim().toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "");
        return sinTildes.replaceAll("[^a-z0-9\\s]", " ").replaceAll("\\s+", " ").trim();
    }

    /**
     * Indexa un producto por su nombre completo y por cada palabra clave (nombre, marca, categoría)
     * para que cualquier prefijo escrito por el usuario sea encontrado en O(L + K).
     */
    public void insertarProducto(ProductoCatalogoIA producto) {
        if (producto == null) return;
        String nombreNorm = normalizarTexto(producto.getNombre());
        if (!nombreNorm.isEmpty()) {
            insertarRecursivo(raiz, nombreNorm, 0, producto);
        }

        // Indexar también cada token del nombre, marca y categoría para autocompletado natural
        String textoCombinado = normalizarTexto(
                producto.getNombre() + " " + producto.getMarca() + " " + producto.getCategoria()
        );
        for (String token : textoCombinado.split(" ")) {
            if (token.length() >= 2) {
                insertarRecursivo(raiz, token, 0, producto);
            }
        }
        totalProductosIndexados++;
    }

    /**
     * Inserción estrictamente recursiva carácter por carácter en el Árbol Trie.
     */
    private void insertarRecursivo(NodoTrie nodo, String clave, int indice, ProductoCatalogoIA producto) {
        if (indice == clave.length()) {
            nodo.esFinPalabra = true;
            if (!contieneSku(nodo.productos, producto.getSku())) {
                nodo.productos.add(producto);
            }
            return;
        }
        char c = clave.charAt(indice);
        NodoTrie siguiente = nodo.hijos.computeIfAbsent(c, k -> new NodoTrie());
        insertarRecursivo(siguiente, clave, indice + 1, producto);
    }

    private boolean contieneSku(List<ProductoCatalogoIA> lista, String sku) {
        for (ProductoCatalogoIA p : lista) {
            if (p.getSku().equals(sku)) return true;
        }
        return false;
    }

    /**
     * Método público estrictamente recursivo para autocompletar búsquedas por prefijo.
     * Si el usuario escribe varias palabras (ej. "aceite pri"), intersecta o combina
     * los resultados recursivos de cada prefijo del árbol.
     */
    public List<ProductoCatalogoIA> buscarPrefijoRecursivo(String prefijo) {
        String norm = normalizarTexto(prefijo);
        if (norm.isEmpty()) {
            Map<String, ProductoCatalogoIA> todos = new LinkedHashMap<>();
            recolectarProductosRecursivo(raiz, todos, 60);
            return new ArrayList<>(todos.values());
        }

        // 1. Búsqueda recursiva exacta por frase completa
        Map<String, ProductoCatalogoIA> encontrados = new LinkedHashMap<>();
        NodoTrie nodoFrase = navegarPrefijoRecursivo(raiz, norm, 0);
        if (nodoFrase != null) {
            recolectarProductosRecursivo(nodoFrase, encontrados, 2000);
        }

        // 2. Si hay múltiples palabras, realizar búsqueda recursiva por cada token e intersectar
        String[] tokens = norm.split(" ");
        if (tokens.length > 1) {
            Map<String, ProductoCatalogoIA> interseccion = null;
            for (String token : tokens) {
                if (token.length() < 2) continue;
                NodoTrie nodoToken = navegarPrefijoRecursivo(raiz, token, 0);
                Map<String, ProductoCatalogoIA> parcial = new LinkedHashMap<>();
                if (nodoToken != null) {
                    recolectarProductosRecursivo(nodoToken, parcial, 2500);
                }
                if (interseccion == null) {
                    interseccion = parcial;
                } else {
                    interseccion.keySet().retainAll(parcial.keySet());
                }
            }
            if (interseccion != null) {
                for (Map.Entry<String, ProductoCatalogoIA> e : interseccion.entrySet()) {
                    encontrados.putIfAbsent(e.getKey(), e.getValue());
                }
            }
            // Si la intersección estricta fue vacía, unir por el token más largo para no dejar sin sugerencias
            if (encontrados.isEmpty()) {
                for (String token : tokens) {
                    if (token.length() < 3) continue;
                    NodoTrie nodoToken = navegarPrefijoRecursivo(raiz, token, 0);
                    if (nodoToken != null) {
                        recolectarProductosRecursivo(nodoToken, encontrados, 120);
                    }
                }
            }
        }

        return new ArrayList<>(encontrados.values());
    }

    /**
     * Descenso estrictamente recursivo por el Trie hasta el nodo que representa el prefijo.
     */
    private NodoTrie navegarPrefijoRecursivo(NodoTrie nodoActual, String prefijo, int indice) {
        if (nodoActual == null) {
            return null;
        }
        if (indice == prefijo.length()) {
            return nodoActual;
        }
        char c = prefijo.charAt(indice);
        return navegarPrefijoRecursivo(nodoActual.hijos.get(c), prefijo, indice + 1);
    }

    /**
     * Recorrido DFS estrictamente recursivo para recolectar todos los productos bajo un subárbol.
     */
    private void recolectarProductosRecursivo(NodoTrie nodo, Map<String, ProductoCatalogoIA> acumulador, int limite) {
        if (nodo == null || acumulador.size() >= limite) {
            return;
        }
        if (nodo.esFinPalabra) {
            for (ProductoCatalogoIA p : nodo.productos) {
                acumulador.putIfAbsent(p.getSku(), p);
                if (acumulador.size() >= limite) {
                    return;
                }
            }
        }
        for (NodoTrie hijo : nodo.hijos.values()) {
            if (acumulador.size() >= limite) {
                return;
            }
            recolectarProductosRecursivo(hijo, acumulador, limite);
        }
    }

    public int getTotalProductosIndexados() {
        return totalProductosIndexados;
    }
}
