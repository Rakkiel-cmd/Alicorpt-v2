package pe.com.alicorp.bi.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.Random;

/**
 * Configura el Pool de Conexiones Relacional (HikariCP) en 3ra Forma Normal (3FN).
 * Por defecto levanta un motor SQL Relacional Local en Modo Compatibilidad Oracle (H2 MODE=Oracle)
 * sin requerir conexión a Oracle Cloud externo, permitiendo también conectar a Oracle XE o PostgreSQL local
 * mediante variables de entorno JDBC_URL, JDBC_USER, JDBC_PASSWORD.
 */
public class DatabaseConfig {

    private static HikariDataSource dataSource;

    public static synchronized DataSource getDataSource() {
        if (dataSource == null) {
            java.util.Map<String, String> env = cargarEnvOpcional();
            HikariConfig config = new HikariConfig();
            String jdbcUrl = env.getOrDefault(
                    "JDBC_URL",
                    "jdbc:h2:file:./data/alicorp_3fn_relacional;MODE=Oracle;DATABASE_TO_UPPER=TRUE;DB_CLOSE_DELAY=-1"
            );
            String jdbcUser = env.getOrDefault("JDBC_USER", "ADMIN");
            String jdbcPass = env.getOrDefault("JDBC_PASSWORD", "Alicorp3FN2026");

            if (jdbcUrl.contains("postgresql") && !jdbcUrl.contains("prepareThreshold")) {
                jdbcUrl += (jdbcUrl.contains("?") ? "&" : "?") + "prepareThreshold=0&tcpKeepAlive=true";
            }

            config.setJdbcUrl(jdbcUrl);
            config.setUsername(jdbcUser);
            config.setPassword(jdbcPass);
            config.setMaximumPoolSize(6);
            config.setMinimumIdle(1);
            config.setConnectionTimeout(15000);
            config.setIdleTimeout(120000);
            config.setMaxLifetime(240000);
            config.setKeepaliveTime(60000);
            config.setPoolName("Alicorp3FNPool");

            dataSource = new HikariDataSource(config);
            inicializarEsquema3FN();
        }
        return dataSource;
    }

    private static java.util.Map<String, String> cargarEnvOpcional() {
        java.util.Map<String, String> mapa = new java.util.HashMap<>(System.getenv());
        Path envPath = Path.of(".env");
        if (Files.exists(envPath)) {
            try {
                for (String linea : Files.readAllLines(envPath)) {
                    String l = linea.trim();
                    if (l.isEmpty() || l.startsWith("#") || !l.contains("=")) continue;
                    int idx = l.indexOf('=');
                    String k = l.substring(0, idx).trim();
                    String v = l.substring(idx + 1).trim();
                    if (!v.isEmpty()) mapa.putIfAbsent(k, v);
                }
            } catch (IOException ignored) {}
        }
        return mapa;
    }

    private static void inicializarEsquema3FN() {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {

            // Si las tablas maestras ya existen y tienen datos (ej. Supabase migrado), omitir re-ejecucion DDL
            try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM ROLES_SEGURIDAD")) {
                if (rs.next() && rs.getInt(1) > 0) {
                    return;
                }
            } catch (SQLException ignored) {}

            Path rutaSql = Path.of("database", "esquema_relacional_3fn.sql");
            if (Files.exists(rutaSql)) {
                String script = Files.readString(rutaSql);
                for (String sentencia : script.split(";")) {
                    String sql = sentencia.trim();
                    if (!sql.isEmpty()) {
                        stmt.execute(sql);
                    }
                }
            }

            try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM ROLES_SEGURIDAD")) {
                if (rs.next() && rs.getInt(1) == 0) {
                    poblarCatalogosMaestros3FN(conn);
                }
            }
        } catch (SQLException | IOException e) {
            System.err.println("[!] Error inicializando esquema 3FN: " + e.getMessage());
        }
    }

    private static void poblarCatalogosMaestros3FN(Connection conn) throws SQLException {
        conn.setAutoCommit(false);
        try (Statement st = conn.createStatement()) {
            // 1. Roles de Seguridad (PK: id_rol)
            st.executeUpdate("INSERT INTO ROLES_SEGURIDAD (id_rol, codigo_rol, nombre_visible, nivel_acceso, descripcion) " +
                    "VALUES (1, 'analista', 'Analista BI', 1, 'Acceso a dashboards predictivos y reportes')");
            st.executeUpdate("INSERT INTO ROLES_SEGURIDAD (id_rol, codigo_rol, nombre_visible, nivel_acceso, descripcion) " +
                    "VALUES (2, 'superadmin', 'Super Administrador', 10, 'Control total de usuarios, auditoria y llaves')");

            // 2. Estados de Usuario (PK: id_estado)
            st.executeUpdate("INSERT INTO ESTADOS_USUARIO (id_estado, codigo_estado, permite_login, descripcion) " +
                    "VALUES (1, 'activo', 'S', 'Cuenta habilitada')");
            st.executeUpdate("INSERT INTO ESTADOS_USUARIO (id_estado, codigo_estado, permite_login, descripcion) " +
                    "VALUES (2, 'suspendido', 'N', 'Cuenta suspendida por SuperAdmin')");

            // 3. Categorías de Producto (PK: id_categoria)
            st.executeUpdate("INSERT INTO CATEGORIAS_PRODUCTO (id_categoria, nombre_categoria) VALUES (1, 'Abarrotes')");
            st.executeUpdate("INSERT INTO CATEGORIAS_PRODUCTO (id_categoria, nombre_categoria) VALUES (2, 'Limpieza del Hogar')");
            st.executeUpdate("INSERT INTO CATEGORIAS_PRODUCTO (id_categoria, nombre_categoria) VALUES (3, 'Pastas y Harinas')");
            st.executeUpdate("INSERT INTO CATEGORIAS_PRODUCTO (id_categoria, nombre_categoria) VALUES (4, 'Salsas y Untables')");

            // 4. Productos (PK: id_producto, FK: id_categoria -> CATEGORIAS_PRODUCTO)
            st.executeUpdate("INSERT INTO PRODUCTOS (id_producto, sku_codigo, nombre_producto, marca, presentacion, stock_disponible, id_categoria, precio_base, costo_unitario, factor_mercado) " +
                    "VALUES (1, 'ALI-PRI-1L', 'Aceite Primor 1L', 'Primor', 'Botella 1 L', 1200, 1, 8.50, 5.80, 0.0250)");
            st.executeUpdate("INSERT INTO PRODUCTOS (id_producto, sku_codigo, nombre_producto, marca, presentacion, stock_disponible, id_categoria, precio_base, costo_unitario, factor_mercado) " +
                    "VALUES (2, 'ALI-BOL-2K', 'Detergente Bolívar 2kg', 'Bolívar', 'Bolsa 2 kg', 850, 2, 12.50, 6.40, -0.0200)");
            st.executeUpdate("INSERT INTO PRODUCTOS (id_producto, sku_codigo, nombre_producto, marca, presentacion, stock_disponible, id_categoria, precio_base, costo_unitario, factor_mercado) " +
                    "VALUES (3, 'ALI-CDV-500', 'Fideos Don Vittorio 500g', 'Don Vittorio', 'Paquete 500 g', 2400, 3, 4.20, 2.90, 0.0350)");
            st.executeUpdate("INSERT INTO PRODUCTOS (id_producto, sku_codigo, nombre_producto, marca, presentacion, stock_disponible, id_categoria, precio_base, costo_unitario, factor_mercado) " +
                    "VALUES (4, 'ALI-HBF-1K', 'Harina Blanca Flor 1kg', 'Blanca Flor', 'Bolsa 1 kg', 1500, 3, 4.80, 4.35, -0.0150)");
            st.executeUpdate("INSERT INTO PRODUCTOS (id_producto, sku_codigo, nombre_producto, marca, presentacion, stock_disponible, id_categoria, precio_base, costo_unitario, factor_mercado) " +
                    "VALUES (5, 'ALI-ALA-500', 'Mayonesa Alacena 500g', 'Alacena', 'Doypack 500 g', 1100, 4, 7.50, 5.15, 0.0300)");
            st.executeUpdate("INSERT INTO PRODUCTOS (id_producto, sku_codigo, nombre_producto, marca, presentacion, stock_disponible, id_categoria, precio_base, costo_unitario, factor_mercado) " +
                    "VALUES (6, 'ALI-COC-1L', 'Aceite Cocinero 1L', 'Cocinero', 'Botella 1 L', 950, 1, 7.90, 5.40, 0.0150)");
            st.executeUpdate("INSERT INTO PRODUCTOS (id_producto, sku_codigo, nombre_producto, marca, presentacion, stock_disponible, id_categoria, precio_base, costo_unitario, factor_mercado) " +
                    "VALUES (7, 'ALI-OPA-1K', 'Detergente Opal Ultra 1kg', 'Opal', 'Bolsa 1 kg', 720, 2, 9.20, 5.90, 0.0100)");
            st.executeUpdate("INSERT INTO PRODUCTOS (id_producto, sku_codigo, nombre_producto, marca, presentacion, stock_disponible, id_categoria, precio_base, costo_unitario, factor_mercado) " +
                    "VALUES (8, 'ALI-TAR-400', 'Salsa Huancaína Alacena 400g', 'Alacena', 'Doypack 400 g', 640, 4, 6.90, 4.50, 0.0200)");

            // 5. Tiendas (PK: id_tienda)
            st.executeUpdate("INSERT INTO TIENDAS (id_tienda, codigo_tienda, nombre_tienda, canal_venta, distrito_zona, temperatura_prom) " +
                    "VALUES (1, 'TND-HIP-CEN', 'Hipermercado Centro', 'Hipermercado', 'Cercado de Lima', 23.5)");
            st.executeUpdate("INSERT INTO TIENDAS (id_tienda, codigo_tienda, nombre_tienda, canal_venta, distrito_zona, temperatura_prom) " +
                    "VALUES (2, 'TND-MAY-SMA', 'Mayorista San Martín', 'Mayorista', 'San Martín de Porres', 24.2)");
            st.executeUpdate("INSERT INTO TIENDAS (id_tienda, codigo_tienda, nombre_tienda, canal_venta, distrito_zona, temperatura_prom) " +
                    "VALUES (3, 'TND-SUP-LNO', 'Supermercado Lima Norte', 'Supermercado', 'Independencia', 24.0)");
            st.executeUpdate("INSERT INTO TIENDAS (id_tienda, codigo_tienda, nombre_tienda, canal_venta, distrito_zona, temperatura_prom) " +
                    "VALUES (4, 'TND-EXP-OLI', 'Tienda Express Los Olivos', 'Express', 'Los Olivos', 23.8)");

            // 6. Administradores iniciales (FKs: id_rol -> ROLES_SEGURIDAD, id_estado -> ESTADOS_USUARIO)
            String vectorDemo128D = generarVector128DJson(42L);
            st.executeUpdate("INSERT INTO ADMINISTRADORES (id_admin, usuario, nombre_completo, email, password_hash, id_rol, id_estado, face_encoding, l2_norm) " +
                    "VALUES (1, 'LPerez', 'Leonardo Perez', 'lperez@alicorp.com.pe', 'admin123', 2, 1, '" + vectorDemo128D + "', 1.4102)");
            st.executeUpdate("INSERT INTO ADMINISTRADORES (id_admin, usuario, nombre_completo, email, password_hash, id_rol, id_estado, face_encoding, l2_norm) " +
                    "VALUES (2, 'LLatorre', 'Leonardo Latorre', 'llatorre@alicorp.com.pe', 'admin123', 2, 1, '" + generarVector128DJson(84L) + "', 1.3985)");

            // 7. Configuración de Seguridad (FK: id_actualizado_por -> ADMINISTRADORES)
            st.executeUpdate("INSERT INTO CONFIGURACION_SEGURIDAD (clave_parametro, valor_parametro, id_actualizado_por) " +
                    "VALUES ('clave_acceso_personal', '784920', 1)");

            // 8. Empresas Competidoras (FK: id_producto_alicorp -> PRODUCTOS)
            st.executeUpdate("INSERT INTO EMPRESAS_COMPETENCIA (codigo_empresa, nombre_empresa, perfil_estrategia, id_producto_alicorp, marca_competidora, precio_promedio, costo_unitario, stock_promedio, ventas_historicas_soles, ganancias_netas_historicas, perdida_estimada_competencia, cuota_mercado_pct) " +
                    "VALUES ('PG', 'Procter & Gamble Perú', 'Liderazgo en detergentes y cuidado del hogar', 2, 'Ace / Ariel 2kg', 12.90, 6.80, 750, 485000.00, 145000.00, 32000.00, 34.50)");
            st.executeUpdate("INSERT INTO EMPRESAS_COMPETENCIA (codigo_empresa, nombre_empresa, perfil_estrategia, id_producto_alicorp, marca_competidora, precio_promedio, costo_unitario, stock_promedio, ventas_historicas_soles, ganancias_netas_historicas, perdida_estimada_competencia, cuota_mercado_pct) " +
                    "VALUES ('MOL', 'Molitalia S.A.', 'Competencia directa en pastas y harinas', 3, 'Molitalia 500g', 4.10, 2.85, 1900, 392000.00, 98000.00, 21500.00, 28.20)");
            st.executeUpdate("INSERT INTO EMPRESAS_COMPETENCIA (codigo_empresa, nombre_empresa, perfil_estrategia, id_producto_alicorp, marca_competidora, precio_promedio, costo_unitario, stock_promedio, ventas_historicas_soles, ganancias_netas_historicas, perdida_estimada_competencia, cuota_mercado_pct) " +
                    "VALUES ('UNL', 'Unilever Perú', 'Competencia en salsas y aceites premium', 5, 'Hellmanns 500g', 7.80, 5.30, 880, 315000.00, 87000.00, 18400.00, 22.80)");

            // Reiniciar secuencias IDENTITY para evitar colisiones en nuevos registros
            st.executeUpdate("ALTER TABLE ADMINISTRADORES ALTER COLUMN id_admin RESTART WITH 10");
            st.executeUpdate("ALTER TABLE PRODUCTOS ALTER COLUMN id_producto RESTART WITH 20");
            st.executeUpdate("ALTER TABLE TIENDAS ALTER COLUMN id_tienda RESTART WITH 10");
        }

        // 8. Semilla de Transacciones Normalizadas (con FKs id_producto e id_tienda, sin repetir textos)
        String sqlTrx = "INSERT INTO TRANSACCIONES_ALICORP " +
                "(fecha_operacion, id_producto, id_tienda, precio_unitario, stock_actual, temperatura_zona, cantidad_vendida, ventas_totales) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
        double[] preciosBase = {8.50, 12.50, 4.20, 4.80, 7.50};
        Random rng = new Random(42);
        LocalDate fechaInicio = LocalDate.of(2024, 1, 1);

        try (PreparedStatement ps = conn.prepareStatement(sqlTrx)) {
            for (int i = 0; i < 5000; i++) {
                int idProd = (i % 5) + 1;
                int idTienda = (i % 4) + 1;
                LocalDate fecha = fechaInicio.plusDays(i % 365);
                double precio = Math.round((preciosBase[idProd - 1] + (rng.nextDouble() - 0.5) * 0.8) * 100.0) / 100.0;
                int stock = 50 + rng.nextInt(450);
                double temp = Math.round((18.0 + rng.nextDouble() * 12.0) * 10.0) / 10.0;
                int cant = Math.max(1, (int) Math.round(14 + (stock * 0.015) - (precio * 0.4) + rng.nextGaussian() * 3));
                double ventas = Math.round(cant * precio * 100.0) / 100.0;

                ps.setDate(1, java.sql.Date.valueOf(fecha));
                ps.setInt(2, idProd);
                ps.setInt(3, idTienda);
                ps.setDouble(4, precio);
                ps.setInt(5, stock);
                ps.setDouble(6, temp);
                ps.setInt(7, cant);
                ps.setDouble(8, ventas);
                ps.addBatch();
                if (i % 1000 == 999) {
                    ps.executeBatch();
                }
            }
            ps.executeBatch();
        }

        conn.commit();
        conn.setAutoCommit(true);
        System.out.println("[OK] Base de datos relacional 3FN inicializada con llaves primarias y foráneas estrictas.");
    }

    private static String generarVector128DJson(long seed) {
        Random r = new Random(seed);
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < 128; i++) {
            if (i > 0) sb.append(",");
            double val = Math.round((r.nextGaussian() * 0.12) * 1000000.0) / 1000000.0;
            sb.append(val);
        }
        sb.append("]");
        return sb.toString();
    }
}
