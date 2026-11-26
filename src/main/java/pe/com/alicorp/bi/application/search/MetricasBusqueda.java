package pe.com.alicorp.bi.application.search;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Mide la busqueda de la tienda para la comparativa del informe: busqueda alfabetica (antes) contra busqueda
 * con ranking por IA (despues). Cada registro es una busqueda que termino en un clic: cuanto tardo la persona,
 * cuantas teclas escribio y en que posicion estaba el producto que eligio.
 *
 * Los registros quedan en memoria y se guardan en un archivo de texto (una linea por registro, separada por
 * tabuladores) para que no se pierdan al reiniciar el servidor. Sin dependencias externas.
 */
public final class MetricasBusqueda {

    /** Una busqueda terminada. posicion empieza en 1. accion: ver, agregar o comprar. */
    public record Registro(long ts, String sesion, String modo, String consulta, long ms, int teclas,
                           int posicion, String accion, String sku) {
    }

    private static final Set<String> ACCIONES = Set.of("ver", "agregar", "comprar");
    private static final long MAX_MS = 600_000L;
    private static final int MAX_TECLAS = 500;
    private static final int MAX_POSICION = 500;
    private static final int MINIMO_POR_MODO = 5;

    private final List<Registro> registros = new ArrayList<>();
    private final Path archivo;
    private boolean cargado = false;

    /** archivo puede ser null: entonces solo se guarda en memoria. */
    public MetricasBusqueda(Path archivo) {
        this.archivo = archivo;
    }

    // ------------------------------------------------------------------ registrar

    private static String limpiar(String s, int max) {
        if (s == null) {
            return "";
        }
        String t = s.replaceAll("[\\t\\r\\n]+", " ").trim();
        return t.length() > max ? t.substring(0, max) : t;
    }

    private static String soloIdentificador(String s, int max) {
        String t = limpiar(s, max).replaceAll("[^A-Za-z0-9_-]", "");
        return t.length() > max ? t.substring(0, max) : t;
    }

    private static Double numero(Object o) {
        if (o instanceof Number n) {
            double d = n.doubleValue();
            return Double.isNaN(d) || Double.isInfinite(d) ? null : d;
        }
        if (o instanceof String s) {
            try {
                double d = Double.parseDouble(s.trim());
                return Double.isNaN(d) || Double.isInfinite(d) ? null : d;
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private static String cadena(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    /** Valida lo que manda el navegador. Devuelve false si algo no es valido (no se guarda nada). */
    public synchronized boolean registrarDesdeMapa(Map<String, ?> datos) {
        if (datos == null) {
            return false;
        }
        String modo = cadena(datos.get("modo")).trim();
        if (!RankingBusqueda.MODO_IA.equals(modo) && !RankingBusqueda.MODO_ALFABETICO.equals(modo)) {
            return false;
        }
        String accion = cadena(datos.get("accion")).trim();
        if (!ACCIONES.contains(accion)) {
            return false;
        }
        Double ms = numero(datos.get("ms"));
        Double teclas = numero(datos.get("teclas"));
        Double posicion = numero(datos.get("posicion"));
        if (ms == null || teclas == null || posicion == null) {
            return false;
        }
        if (ms < 0 || ms > MAX_MS || teclas < 0 || teclas > MAX_TECLAS || posicion < 1 || posicion > MAX_POSICION) {
            return false;
        }
        Registro r = new Registro(
                System.currentTimeMillis(),
                soloIdentificador(cadena(datos.get("sesion")), 40),
                modo,
                limpiar(cadena(datos.get("consulta")), 80),
                Math.round(ms),
                (int) Math.round(teclas),
                (int) Math.round(posicion),
                accion,
                limpiar(cadena(datos.get("sku")), 60));
        cargarSiHaceFalta();
        registros.add(r);
        guardar(r);
        return true;
    }

    // ------------------------------------------------------------------ archivo

    private void cargarSiHaceFalta() {
        if (cargado) {
            return;
        }
        cargado = true;
        if (archivo == null || !Files.isRegularFile(archivo)) {
            return;
        }
        try {
            for (String linea : Files.readAllLines(archivo, StandardCharsets.UTF_8)) {
                String[] c = linea.split("\t", 9);
                if (c.length < 9) {
                    continue;
                }
                try {
                    registros.add(new Registro(Long.parseLong(c[0]), c[1], c[2], c[8], Long.parseLong(c[3]),
                            Integer.parseInt(c[4]), Integer.parseInt(c[5]), c[6], c[7]));
                } catch (NumberFormatException e) {
                    // linea danada: se ignora
                }
            }
        } catch (IOException e) {
            System.err.println("[!] No se pudo leer el archivo de metricas de busqueda: " + e.getMessage());
        }
    }

    private void guardar(Registro r) {
        if (archivo == null) {
            return;
        }
        String linea = r.ts() + "\t" + r.sesion() + "\t" + r.modo() + "\t" + r.ms() + "\t" + r.teclas() + "\t"
                + r.posicion() + "\t" + r.accion() + "\t" + r.sku() + "\t" + r.consulta() + "\n";
        try {
            Path carpeta = archivo.toAbsolutePath().getParent();
            if (carpeta != null) {
                Files.createDirectories(carpeta);
            }
            Files.writeString(archivo, linea, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            System.err.println("[!] No se pudo guardar la metrica de busqueda: " + e.getMessage());
        }
    }

    /** Borra todos los registros (memoria y archivo). Sirve para empezar una prueba limpia. */
    public synchronized void reiniciar() {
        registros.clear();
        cargado = true;
        if (archivo != null) {
            try {
                Files.deleteIfExists(archivo);
            } catch (IOException e) {
                System.err.println("[!] No se pudo borrar el archivo de metricas: " + e.getMessage());
            }
        }
    }

    // ------------------------------------------------------------------ resumen

    private static double redondear(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private static double media(List<? extends Number> valores) {
        if (valores.isEmpty()) {
            return 0.0;
        }
        double suma = 0.0;
        for (Number v : valores) {
            suma += v.doubleValue();
        }
        return suma / valores.size();
    }

    private static double mediana(List<Long> valores) {
        if (valores.isEmpty()) {
            return 0.0;
        }
        List<Long> orden = new ArrayList<>(valores);
        Collections.sort(orden);
        int n = orden.size();
        return n % 2 == 1 ? orden.get(n / 2) : (orden.get(n / 2 - 1) + orden.get(n / 2)) / 2.0;
    }

    private Map<String, Object> resumenDeModo(String modo) {
        List<Long> tiempos = new ArrayList<>();
        List<Integer> teclas = new ArrayList<>();
        List<Integer> posiciones = new ArrayList<>();
        int enTop3 = 0;
        int agregados = 0;
        for (Registro r : registros) {
            if (!modo.equals(r.modo())) {
                continue;
            }
            tiempos.add(r.ms());
            teclas.add(r.teclas());
            posiciones.add(r.posicion());
            if (r.posicion() <= 3) {
                enTop3++;
            }
            if (!"ver".equals(r.accion())) {
                agregados++;
            }
        }
        int n = tiempos.size();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("n", n);
        m.put("tiempo_medio_s", redondear(media(tiempos) / 1000.0));
        m.put("tiempo_mediana_s", redondear(mediana(tiempos) / 1000.0));
        m.put("teclas_medias", redondear(media(teclas)));
        m.put("posicion_media", redondear(media(posiciones)));
        m.put("pct_top3", n == 0 ? 0.0 : redondear(100.0 * enTop3 / n));
        m.put("pct_agregaron", n == 0 ? 0.0 : redondear(100.0 * agregados / n));
        return m;
    }

    /** Mejora de "ia" frente a "alfabetico", en porcentaje. Positivo = la busqueda con IA fue mejor. */
    private static double mejora(Map<String, Object> ia, Map<String, Object> alf, String campo) {
        double base = ((Number) alf.get(campo)).doubleValue();
        double nuevo = ((Number) ia.get(campo)).doubleValue();
        return base <= 0 ? 0.0 : redondear(100.0 * (base - nuevo) / base);
    }

    public synchronized Map<String, Object> resumen() {
        cargarSiHaceFalta();
        Map<String, Object> ia = resumenDeModo(RankingBusqueda.MODO_IA);
        Map<String, Object> alf = resumenDeModo(RankingBusqueda.MODO_ALFABETICO);
        int nIa = ((Number) ia.get("n")).intValue();
        int nAlf = ((Number) alf.get("n")).intValue();
        Map<String, Object> salida = new LinkedHashMap<>();
        salida.put("ok", true);
        salida.put("total", registros.size());
        salida.put("minimo_por_modo", MINIMO_POR_MODO);
        salida.put("datos_suficientes", nIa >= MINIMO_POR_MODO && nAlf >= MINIMO_POR_MODO);
        salida.put(RankingBusqueda.MODO_IA, ia);
        salida.put(RankingBusqueda.MODO_ALFABETICO, alf);
        if (nIa > 0 && nAlf > 0) {
            Map<String, Object> mejoras = new LinkedHashMap<>();
            mejoras.put("tiempo_pct", mejora(ia, alf, "tiempo_medio_s"));
            mejoras.put("teclas_pct", mejora(ia, alf, "teclas_medias"));
            mejoras.put("posicion_pct", mejora(ia, alf, "posicion_media"));
            salida.put("mejora", mejoras);
        }
        List<Map<String, Object>> ultimos = new ArrayList<>();
        for (int i = registros.size() - 1; i >= 0 && ultimos.size() < 12; i--) {
            Registro r = registros.get(i);
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("ts", r.ts());
            f.put("modo", r.modo());
            f.put("consulta", r.consulta());
            f.put("tiempo_s", redondear(r.ms() / 1000.0));
            f.put("teclas", r.teclas());
            f.put("posicion", r.posicion());
            f.put("accion", r.accion());
            ultimos.add(f);
        }
        salida.put("ultimos", ultimos);
        return salida;
    }
}
