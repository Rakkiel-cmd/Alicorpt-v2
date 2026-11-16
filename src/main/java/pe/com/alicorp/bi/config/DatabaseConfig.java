package pe.com.alicorp.bi.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
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
            HikariConfig config = new HikariConfig();
            String jdbcUrl = System.getenv().getOrDefault(
                    "JDBC_URL",
                    "jdbc:h2:file:./data/alicorp_3fn_relacional;MODE=Oracle;DATABASE_TO_UPPER=TRUE;DB_CLOSE_DELAY=-1"
            );
            String jdbcUser = System.getenv().getOrDefault("JDBC_USER", "ADMIN");
            String jdbcPass = System.getenv().getOrDefault("JDBC_PASSWORD", "Alicorp3FN2026");

            config.setJdbcUrl(jdbcUrl);
            config.setUsername(jdbcUser);
            config.setPassword(jdbcPass);
            config.setMaximumPoolSize(10);
            config.setMinimumIdle(2);
            config.setPoolName("Alicorp3FNPool");

            dataSource = new HikariDataSource(config);
            inicializarEsquema3FN();
        }
        return dataSource;
    }

    private static void inicializarEsquema3FN() {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {

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

            // Verificar si las tablas maestras ya tienen datos
            try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM ROLES_SEGURIDAD")) {
                if (rs.next() && rs.getInt(1) == 0) {
                    poblarCatalogosMaestros3FN(conn);
                }
            }
        } catch (Exception e) {
            System.err.println("[!] Error inicializando esquema 3FN: " + e.getMessage());
        }
    }

    private static void poblarCatalogosMaestros3FN(Connection conn) throws Exception {
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
            st.executeUpdate("INSERT INTO PRODUCTOS (id_producto, sku_codigo, nombre_producto, id_categoria, precio_base, costo_unitario, factor_mercado) " +
                    "VALUES (1, 'ALI-PRI-1L', 'Aceite Primor 1L', 1, 8.50, 5.80, 0.0250)");
            st.executeUpdate("INSERT INTO PRODUCTOS (id_producto, sku_codigo, nombre_producto, id_categoria, precio_base, costo_unitario, factor_mercado) " +
                    "VALUES (2, 'ALI-BOL-2K', 'Detergente Bolívar 2kg', 2, 12.50, 6.40, -0.0200)");
            st.executeUpdate("INSERT INTO PRODUCTOS (id_producto, sku_codigo, nombre_producto, id_categoria, precio_base, costo_unitario, factor_mercado) " +
                    "VALUES (3, 'ALI-CDV-500', 'Fideos Don Vittorio 500g', 3, 4.20, 2.90, 0.0350)");
            st.executeUpdate("INSERT INTO PRODUCTOS (id_producto, sku_codigo, nombre_producto, id_categoria, precio_base, costo_unitario, factor_mercado) " +
                    "VALUES (4, 'ALI-HBF-1K', 'Harina Blanca Flor 1kg', 3, 4.80, 4.35, -0.0150)");
            st.executeUpdate("INSERT INTO PRODUCTOS (id_producto, sku_codigo, nombre_producto, id_categoria, precio_base, costo_unitario, factor_mercado) " +
                    "VALUES (5, 'ALI-ALA-500', 'Mayonesa Alacena 500g', 4, 7.50, 5.15, 0.0300)");

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
