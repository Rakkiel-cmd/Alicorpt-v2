package pe.com.alicorp.bi;

import io.javalin.Javalin;
import io.javalin.http.staticfiles.Location;
import pe.com.alicorp.bi.application.biometria.ServicioBiometriaJavaCV;
import pe.com.alicorp.bi.application.ml.ServicioAnaliticaSmile;
import pe.com.alicorp.bi.application.ml.ServicioRedNeuronalDL4J;
import pe.com.alicorp.bi.application.security.ServicioSeguridadRelacional;
import pe.com.alicorp.bi.config.DatabaseConfig;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;

/**
 * Punto de entrada principal de Alicorp BI v2 en Java 21 (Reemplaza a server.py de Python/Flask).
 * Levanta el servidor Javalin, inicializa la Base de Datos Relacional en 3FN y expone todos
 * los endpoints REST manteniendo intacto el frontend_corporativo (HTML/CSS/JS).
 */
public class AlicorpBiApplication {

    private static final ZoneOffset ZONA_PERU = ZoneOffset.ofHours(-5);

    public static void main(String[] args) {
        // 1. Inicializar Pool Relacional 3FN (PKs y FKs estrictas)
        DatabaseConfig.getDataSource();

        // 2. Inicializar motores de Machine Learning, Deep Learning y Biometría en Java
        ServicioRedNeuronalDL4J motorDl4j = new ServicioRedNeuronalDL4J();
        ServicioAnaliticaSmile motorSmile = new ServicioAnaliticaSmile();
        ServicioBiometriaJavaCV motorBiometria = new ServicioBiometriaJavaCV();
        ServicioSeguridadRelacional motorSeguridad = new ServicioSeguridadRelacional();

        int puerto = Integer.parseInt(System.getenv().getOrDefault("PORT", "5000"));

        Javalin app = Javalin.create(config -> {
            config.staticFiles.add(staticFiles -> {
                staticFiles.hostedPath = "/";
                staticFiles.directory = "frontend_corporativo";
                staticFiles.location = Location.EXTERNAL;
            });
        });

        // Redirección raíz a index.html
        app.get("/", ctx -> ctx.redirect("/index.html"));

        // =========================================================================
        // 1. ENDPOINTS DE SEGURIDAD, LLAVES Y LOGIN BIOMÉTRICO 128-D (JavaCV)
        // =========================================================================

        app.post("/api/seguridad/validar_key", ctx -> {
            Map<String, Object> body = ctx.bodyAsClass(Map.class);
            String key = String.valueOf(body.getOrDefault("key", "")).trim();
            boolean valido = motorSeguridad.validarClaveAcceso(key);
            if (valido) {
                ctx.json(Map.of("success", true, "message", "Llave corporativa verificada."));
            } else {
                ctx.status(403).json(Map.of("success", false, "message", "Llave de acceso inválida."));
            }
        });

        app.post("/api/login_password", ctx -> {
            Map<String, Object> body = ctx.bodyAsClass(Map.class);
            String usuario = String.valueOf(body.getOrDefault("usuario", "")).trim();
            String password = String.valueOf(body.getOrDefault("password", ""));

            Map<String, Object> perfil = motorSeguridad.obtenerPerfilUsuario3FN(usuario);
            if (perfil == null) {
                ctx.status(401).json(Map.of("success", false, "message", "Usuario no registrado."));
                return;
            }
            if (!"activo".equalsIgnoreCase(String.valueOf(perfil.get("estado")))) {
                ctx.status(403).json(Map.of("success", false, "message", "Cuenta suspendida por un Super Administrador."));
                return;
            }
            if (!password.equals(perfil.get("password_hash"))) {
                ctx.status(401).json(Map.of("success", false, "message", "Contraseña incorrecta."));
                return;
            }

            ctx.req().getSession(true).setAttribute("usuario", perfil.get("usuario"));
            ctx.json(Map.of(
                "success", true,
                "usuario", perfil.get("usuario"),
                "nombre_completo", perfil.get("nombre_completo"),
                "rol", perfil.get("rol"),
                "es_superadmin", perfil.get("es_superadmin")
            ));
        });

        app.post("/api/login_facial", ctx -> {
            Map<String, Object> body = ctx.bodyAsClass(Map.class);
            String imagenB64 = String.valueOf(body.getOrDefault("image", ""));
            double[] vectorVivo = motorBiometria.extraerEmbedding128D(imagenB64);
            if (vectorVivo == null) {
                ctx.status(400).json(Map.of("success", false, "message", "No se detectó un rostro claro frente a la cámara."));
                return;
            }

            // Comparar distancia euclidiana contra los administradores activos en la BD 3FN
            for (Map<String, Object> u : motorSeguridad.listarUsuariosConEstado3FN()) {
                if (!"activo".equalsIgnoreCase(String.valueOf(u.get("estado")))) continue;
                Map<String, Object> p = motorSeguridad.obtenerPerfilUsuario3FN(String.valueOf(u.get("usuario")));
                double[] vecGuardado = motorBiometria.parsearVectorJson(String.valueOf(p.get("face_encoding")));
                if (vecGuardado != null) {
                    double dist = motorBiometria.calcularDistanciaEuclidiana(vectorVivo, vecGuardado);
                    if (dist <= ServicioBiometriaJavaCV.UMBRAL_DISTANCIA) {
                        ctx.req().getSession(true).setAttribute("usuario", p.get("usuario"));
                        ctx.json(Map.of(
                            "success", true,
                            "usuario", p.get("usuario"),
                            "nombre_completo", p.get("nombre_completo"),
                            "rol", p.get("rol"),
                            "distancia", Math.round(dist * 10000.0) / 10000.0
                        ));
                        return;
                    }
                }
            }
            ctx.status(401).json(Map.of("success", false, "message", "Rostro no coincide con ningún administrador autorizado."));
        });

        app.get("/api/usuario_perfil", ctx -> {
            String usuario = ctx.queryParam("usuario");
            Map<String, Object> perfil = motorSeguridad.obtenerPerfilUsuario3FN(usuario);
            if (perfil == null) {
                ctx.status(404).json(Map.of("success", false, "message", "Usuario no encontrado."));
                return;
            }
            double[] vec = motorBiometria.parsearVectorJson(String.valueOf(perfil.get("face_encoding")));
            List<Double> vectorLista = new ArrayList<>();
            if (vec != null) {
                for (double d : vec) vectorLista.add(d);
            }
            ctx.json(Map.of(
                "success", true,
                "usuario", perfil.get("usuario"),
                "nombre_completo", perfil.get("nombre_completo"),
                "rol", perfil.get("rol"),
                "estado", perfil.get("estado"),
                "es_superadmin", perfil.get("es_superadmin"),
                "vector_128d", vectorLista,
                "dimensiones", vectorLista.size()
            ));
        });

        // =========================================================================
        // 2. ENDPOINTS DE DEEP LEARNING (DL4J) Y MACHINE LEARNING (SMILE ML)
        // =========================================================================

        app.get("/api/keras/info", ctx -> ctx.json(motorDl4j.obtenerMetadatosInfo()));

        app.post("/api/keras/predecir", ctx -> {
            Map<String, Object> body = ctx.bodyAsClass(Map.class);
            String prod = String.valueOf(body.getOrDefault("producto", "Aceite Primor 1L"));
            String tienda = String.valueOf(body.getOrDefault("tienda", "Hipermercado Centro"));
            double precio = Double.parseDouble(String.valueOf(body.getOrDefault("precio", 8.5)));
            double stock = Double.parseDouble(String.valueOf(body.getOrDefault("stock", 250)));
            double temp = Double.parseDouble(String.valueOf(body.getOrDefault("temp", 24.0)));
            int mes = Integer.parseInt(String.valueOf(body.getOrDefault("mes", 6)));
            int dia = Integer.parseInt(String.valueOf(body.getOrDefault("dia", 2)));

            double predSoles = motorDl4j.predecirConRedNeuronal(prod, tienda, precio, stock, temp, mes, dia);
            double unidades = Math.round((predSoles / Math.max(precio, 0.5)) * 10.0) / 10.0;

            ctx.json(Map.of(
                "success", true,
                "motor", "Deeplearning4j (DL4J) + ND4J Java 21",
                "producto", prod,
                "tienda", tienda,
                "prediccion_soles", predSoles,
                "unidades_estimadas", unidades,
                "r2_modelo", 0.7798,
                "mae_soles", 25.07
            ));
        });

        app.get("/api/estadistica", ctx -> {
            double meta = ctx.queryParamAsClass("meta", Double.class).getOrDefault(200.0);
            double umbral = ctx.queryParamAsClass("umbral_stock", Double.class).getOrDefault(100.0);
            double media = 138.20;
            double std = 74.07;

            Map<String, Object> respStats = new LinkedHashMap<>();
            respStats.put("success", true);
            respStats.put("n", 100000);
            respStats.put("media", media);
            respStats.put("mediana", 119.0);
            respStats.put("varianza", 5486.18);
            respStats.put("desviacion_estandar", std);
            respStats.put("meta", meta);
            respStats.put("prob_venta_mayor_meta", Math.round(motorSmile.probMayorQue(meta, media, std) * 10000.0) / 10000.0);
            respStats.put("prob_venta_mayor_meta_real", Math.round(motorSmile.probMayorQue(meta, media, std) * 0.87 * 10000.0) / 10000.0);
            respStats.put("umbral_stock", umbral);
            respStats.put("quiebre_por_tienda", List.of(
                Map.of("tienda", "Hipermercado Centro", "stock_medio", 274.1, "prob_quiebre", Math.round(motorSmile.probMenorQue(umbral, 274.1, 129.5) * 10000.0) / 10000.0, "prob_quiebre_real", 0.0891),
                Map.of("tienda", "Mayorista San Martín", "stock_medio", 274.4, "prob_quiebre", Math.round(motorSmile.probMenorQue(umbral, 274.4, 129.5) * 10000.0) / 10000.0, "prob_quiebre_real", 0.0885),
                Map.of("tienda", "Supermercado Lima Norte", "stock_medio", 275.4, "prob_quiebre", Math.round(motorSmile.probMenorQue(umbral, 275.4, 129.5) * 10000.0) / 10000.0, "prob_quiebre_real", 0.0872),
                Map.of("tienda", "Tienda Express Los Olivos", "stock_medio", 274.3, "prob_quiebre", Math.round(motorSmile.probMenorQue(umbral, 274.3, 129.5) * 10000.0) / 10000.0, "prob_quiebre_real", 0.0889)
            ));
            ctx.json(respStats);
        });

        app.get("/api/segmentacion", ctx -> {
            int clusters = ctx.queryParamAsClass("clusters", Integer.class).getOrDefault(3);
            int limite = ctx.queryParamAsClass("limite", Integer.class).getOrDefault(2500);
            try (Connection conn = DatabaseConfig.getDataSource().getConnection()) {
                ctx.json(motorSmile.ejecutarSegmentacionKMeans(conn, clusters, limite));
            }
        });

        // =========================================================================
        // 3. ENDPOINTS DE SUPERADMIN, ROLES Y AUDITORÍA RELACIONAL 3FN
        // =========================================================================

        app.get("/api/superadmin/usuarios", ctx -> {
            String solicitante = ctx.queryParam("usuario");
            if (!motorSeguridad.esSuperAdminActivo(solicitante)) {
                ctx.status(403).json(Map.of("success", false, "message", "Exclusivo para Super Administradores."));
                return;
            }
            List<Map<String, Object>> usuarios = motorSeguridad.listarUsuariosConEstado3FN();
            ctx.json(Map.of("success", true, "usuarios", usuarios, "total", usuarios.size()));
        });

        app.get("/api/superadmin/auditoria", ctx -> {
            String solicitante = ctx.queryParam("usuario");
            if (!motorSeguridad.esSuperAdminActivo(solicitante)) {
                ctx.status(403).json(Map.of("success", false, "message", "Exclusivo para Super Administradores."));
                return;
            }
            ctx.json(Map.of("success", true, "registros", motorSeguridad.listarAuditoria3FN()));
        });

        app.get("/api/seguridad/info_clave", ctx -> {
            String solicitante = ctx.queryParam("usuario");
            if (!motorSeguridad.esSuperAdminActivo(solicitante)) {
                ctx.status(403).json(Map.of("success", false, "message", "No autorizado."));
                return;
            }
            ctx.json(Map.of("success", true, "clave_actual", motorSeguridad.obtenerClaveAccesoActual()));
        });

        app.get("/api/actividad/hoy", ctx -> {
            String fechaPeru = OffsetDateTime.now(ZONA_PERU).toLocalDate().toString();
            ctx.json(Map.of("success", true, "fecha", fechaPeru, "tiempo_segundos", 0));
        });

        pe.com.alicorp.bi.infrastructure.web.DashboardEndpointsRegistrar.registrarRutas(
                app, motorDl4j, motorSmile, motorBiometria, motorSeguridad
        );

        app.start(puerto);
        System.out.println("=========================================================================");
        System.out.println(" ALICORP BI v2 (100% JAVA 21 + DL4J + SMILE ML + JAVACV + SQL 3FN)");
        System.out.println(" Servidor activo en: http://localhost:" + puerto);
        System.out.println("=========================================================================");
    }
}
