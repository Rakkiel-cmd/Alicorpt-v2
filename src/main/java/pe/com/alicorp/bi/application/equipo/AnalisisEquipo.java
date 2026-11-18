package pe.com.alicorp.bi.application.equipo;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Lógica pura (sin base de datos) del dashboard "Actividad del equipo":
 * periodos, series semanales, regresión lineal y normalización de acciones de auditoría.
 * Al no tocar la BD se puede probar de forma aislada.
 */
public final class AnalisisEquipo {

    public static final int MIN_SEMANAS = 4;
    public static final int SEMANAS_HISTORIAL = 12;

    private static final Pattern MES = Pattern.compile("\\d{4}-(0[1-9]|1[0-2])");
    private static final Pattern ANIO = Pattern.compile("\\d{4}");

    private AnalisisEquipo() {}

    /** Rango [desde, hastaExclusivo) con su etiqueta (AAAA-MM o AAAA). */
    public record Rango(LocalDate desde, LocalDate hastaExclusivo, String etiqueta) {}

    public static Rango rangoPeriodo(String periodo, String ref, LocalDate hoy) {
        String r = ref == null ? "" : ref.trim();
        if ("anio".equals(periodo)) {
            int anio = ANIO.matcher(r).matches() ? Integer.parseInt(r) : hoy.getYear();
            return new Rango(LocalDate.of(anio, 1, 1), LocalDate.of(anio + 1, 1, 1), String.valueOf(anio));
        }
        if (!r.isEmpty() && !MES.matcher(r).matches()) {
            throw new IllegalArgumentException("El mes debe tener formato AAAA-MM.");
        }
        int anio = r.isEmpty() ? hoy.getYear() : Integer.parseInt(r.substring(0, 4));
        int mes = r.isEmpty() ? hoy.getMonthValue() : Integer.parseInt(r.substring(5, 7));
        LocalDate desde = LocalDate.of(anio, mes, 1);
        return new Rango(desde, desde.plusMonths(1), String.format("%04d-%02d", anio, mes));
    }

    public static LocalDate inicioSemana(LocalDate d) {
        return d.with(DayOfWeek.MONDAY);
    }

    /**
     * Serie semanal por colaborador. Empieza en la primera semana con datos (para no castigar a quien
     * entró hace poco), no más atrás de 12 semanas, y termina en la última semana completa.
     */
    public static Map<String, double[]> seriesSemanales(Map<String, Map<LocalDate, Double>> valoresPorDia, LocalDate semanaFin) {
        Map<String, double[]> series = new LinkedHashMap<>();
        LocalDate tope = semanaFin.minusWeeks(SEMANAS_HISTORIAL - 1L);
        for (Map.Entry<String, Map<LocalDate, Double>> e : valoresPorDia.entrySet()) {
            TreeMap<LocalDate, Double> porSemana = new TreeMap<>();
            for (Map.Entry<LocalDate, Double> d : e.getValue().entrySet()) {
                LocalDate semana = inicioSemana(d.getKey());
                if (!semana.isAfter(semanaFin)) porSemana.merge(semana, d.getValue(), Double::sum);
            }
            if (porSemana.isEmpty()) continue;
            LocalDate primera = porSemana.firstKey().isBefore(tope) ? tope : porSemana.firstKey();
            List<Double> valores = new ArrayList<>();
            for (LocalDate s = primera; !s.isAfter(semanaFin); s = s.plusWeeks(1)) {
                valores.add(porSemana.getOrDefault(s, 0.0));
            }
            series.put(e.getKey(), valores.stream().mapToDouble(Double::doubleValue).toArray());
        }
        return series;
    }

    /**
     * Regresión lineal simple (mínimos cuadrados) sobre la serie semanal. El "próximo mes" son las
     * siguientes 4 semanas proyectadas por la recta (nunca negativas). Con menos de 4 semanas no inventa nada.
     */
    public static Map<String, Object> proyectar(double[] serie) {
        Map<String, Object> out = new LinkedHashMap<>();
        int n = serie.length;
        if (n < MIN_SEMANAS) {
            out.put("estado", "insuficiente");
            out.put("semanas", n);
            out.put("minimo", MIN_SEMANAS);
            return out;
        }
        double sx = 0, sy = 0, sxx = 0, sxy = 0;
        for (int i = 0; i < n; i++) { sx += i; sy += serie[i]; sxx += (double) i * i; sxy += i * serie[i]; }
        double pendiente = (n * sxy - sx * sy) / (n * sxx - sx * sx);
        double intercepto = (sy - pendiente * sx) / n;
        double media = sy / n, ssRes = 0, ssTot = 0;
        for (int i = 0; i < n; i++) {
            double ajuste = pendiente * i + intercepto;
            ssRes += (serie[i] - ajuste) * (serie[i] - ajuste);
            ssTot += (serie[i] - media) * (serie[i] - media);
        }
        // Serie constante sin error: la recta la explica por completo (R² = 1)
        double r2 = ssTot > 0 ? 1.0 - ssRes / ssTot : (ssRes < 1e-9 ? 1.0 : 0.0);
        r2 = Math.max(0.0, r2);
        double proximo = 0, ultimo = 0;
        for (int i = 0; i < 4; i++) proximo += Math.max(0.0, pendiente * (n + i) + intercepto);
        for (int i = Math.max(0, n - 4); i < n; i++) ultimo += serie[i];
        out.put("estado", "ok");
        out.put("semanas", n);
        out.put("proximo_mes", redondear(proximo, 1));
        out.put("ultimo_mes", redondear(ultimo, 1));
        out.put("pendiente_semanal", redondear(pendiente, 2) + 0.0);
        out.put("r2", redondear(r2, 3));
        out.put("confianza", r2 >= 0.7 ? "alta" : r2 >= 0.4 ? "media" : "baja");
        return out;
    }

    public static double redondear(double v, int decimales) {
        double f = Math.pow(10, decimales);
        return Math.round(v * f) / f;
    }

    /** Convierte el texto libre de AMPLIACION_AUDITORIA en una clave estable para filtros y conteos. */
    public static String normalizarAccion(String accion, String detalle) {
        String a = accion == null ? "" : accion.toUpperCase();
        String d = detalle == null ? "" : detalle.toUpperCase();
        if (a.contains("REACT")) return "reactivacion";
        if (a.contains("SUSP")) return "suspension";
        if (a.contains("DEGRAD")) return "degradacion_rol";
        if (a.contains("ASCEN")) return "ascenso_rol";
        if (a.contains("CAMBIO_ROL")) return d.contains("SUPERADMIN") ? "ascenso_rol" : "degradacion_rol";
        if (a.contains("CLAVE")) return "cambio_clave";
        if (a.contains("EMAIL") || a.contains("CORREO")) return "actualizacion_email";
        return accion == null || accion.isBlank() ? "otra" : accion.toLowerCase();
    }

    /** Quita los comodines de LIKE y recorta, para usar el texto del usuario de forma segura. */
    public static String sanitizarLike(String texto, int max) {
        if (texto == null) return "";
        String limpio = texto.replaceAll("[%_\\\\]", " ").trim().toUpperCase();
        return limpio.length() > max ? limpio.substring(0, max) : limpio;
    }
}
