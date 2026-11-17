# Alicorp BI v2 — Plataforma Corporativa en Java (3FN + ML + Biometría 128-D)

Sistema corporativo de **Inteligencia de Negocios (BI)**, **E-Commerce** y **Control de Acceso Biométrico 128-D** de Alicorp, migrado al 100% a **Java 21+** (probado en **Oracle JDK 26**) con arquitectura relacional en **3ra Forma Normal (3FN)** sobre **Supabase PostgreSQL** (y modo local embebido **H2**).

> **Nota sobre la versión anterior (Python/Flask):** El código legado en Python fue respaldado en la rama `alicorp-v2-completo-con-python` (y etiqueta `v2.0-python-legacy`) para referencia histórica. La rama principal actual contiene únicamente la arquitectura pura en Java.

---

## 🛠️ Stack Tecnológico

- **Lenguaje y Runtime:** Java 21+ (`--release 21`, compatible con JDK 21, 25 y 26).
- **Servidor Web REST:** Javalin `6.3.0` + Jackson `2.17.2` (puerto por defecto `5000`).
- **Deep Learning:** Deeplearning4j (`1.0.0-M2.1`) + ND4J (reemplazo nativo de TensorFlow/Keras).
- **Machine Learning y Estadística:** Smile ML `3.1.1` (K-Means no supervisado, distribución normal gaussiana y analítica descriptiva).
- **Visión Artificial y Biometría 128-D:** JavaCV `1.5.10` / OpenCV nativo (normalización 160x160, extracción de vectores 128-D y distancia euclidiana).
- **Base de Datos y Pool:** HikariCP `5.1.0` + PostgreSQL JDBC `42.7.3` (Supabase 3FN) + H2 `2.2.224` (`MODE=Oracle` como respaldo local) + Oracle JDBC `ojdbc11`.
- **Frontend:** HTML5, CSS3, JavaScript Vanilla (`frontend_corporativo/`), servido directamente por Javalin.

---

## 📋 Requisitos Previos (Qué necesitas instalar aparte)

Para ejecutar el proyecto en tu máquina local (`localhost`), necesitas instalar:

1. **Java Development Kit (JDK) 21 o superior** (Recomendado: **JDK 21 LTS** o **Oracle JDK 26**).
   - Descarga: [Adoptium Temurin JDK 21+](https://adoptium.net/) o [Oracle JDK](https://www.oracle.com/java/technologies/downloads/).
   - Verifica tu instalación en la terminal:
     ```powershell
     java -version
     ```
   - Asegúrate de tener configurada la variable de entorno **`JAVA_HOME`** apuntando a la carpeta de tu JDK (ej. `C:\Program Files\Java\jdk-26.0.2` o `C:\Program Files\Eclipse Adoptium\jdk-21...`).
2. **Git** (para clonar el repositorio y gestionar ramas).
3. **Cámara Web** (necesaria si deseas probar el registro y login biométrico facial 128-D en vivo).
4. **Apache Maven:** **NO necesitas instalarlo aparte.** El repositorio ya incluye Maven portátil (`3.9.9`) en la carpeta `tools/maven/` junto con el script `mvnw.cmd`.

*(Nota: Si usas un IDE como Antigravity IDE / VS Code o IntelliJ IDEA, instala la extensión oficial de Java / Maven).*

---

## 🚀 Guía de Instalación y Ejecución en Local

### Paso 1: Clonar el Repositorio
```bash
git clone https://github.com/Rakkiel-cmd/Alicorpt-v2.git
cd "Alicorpt-v2"
```

### Paso 2: Configurar las Variables de Entorno (`.env`)
El proyecto incluye una plantilla [`.env.example`](.env.example) sin credenciales reales por seguridad.

1. Copia el archivo `.env.example` y renómbralo a `.env` en la raíz del proyecto:
   ```powershell
   Copy-Item .env.example .env
   ```
2. Abre `.env` y coloca las credenciales reales (`JDBC_URL`, `JDBC_USER`, `JDBC_PASSWORD`) compartidas por el administrador del equipo:
   ```env
   JDBC_URL=jdbc:postgresql://aws-0-sa-east-1.pooler.supabase.com:5432/postgres?sslmode=require
   JDBC_USER=postgres.tu_project_ref
   JDBC_PASSWORD=TU_CONTRASENA_AQUI
   ```
   > **Modo sin conexión a la nube:** Si omites el archivo `.env`, el sistema levantará automáticamente una base de datos relacional local embebida **H2** (en `./data/alicorp_3fn_relacional`) con el esquema 3FN y datos de demostración iniciales.

### Paso 3: Compilar y Ejecutar el Servidor

#### Opción A: Ejecución rápida en Windows (Recomendada)
Haz doble clic o ejecuta en PowerShell / CMD:
```powershell
.\iniciar_servidor_java.bat
```
Este script detecta tu JDK, compila y empaqueta automáticamente el Uber-JAR (`target/alicorp-bi-java-2.0.0-shaded.jar`) si aún no existe, y levanta el servidor en el puerto `5000`.

> **Importante:** Si realizas cambios en el código Java (`.java`), vuelve a empaquetar antes de reiniciar con:
> ```powershell
> .\mvnw.cmd clean package -DskipTests
> ```

#### Opción B: Ejecución manual paso a paso (Windows / Linux / Mac)
1. Compilar y empaquetar con el Maven incluido:
   ```powershell
   .\mvnw.cmd clean package -DskipTests
   ```
   *(La primera vez descargará las dependencias de Maven en `C:\Users\<TU_USUARIO>\.m2\repository`; puede tardar unos minutos debido a las librerías nativas de DL4J y JavaCV).*
2. Ejecutar la aplicación:
   ```powershell
   java -jar target\alicorp-bi-java-2.0.0-shaded.jar
   ```

### Paso 4: Acceder al Sistema en el Navegador
Una vez veas el mensaje `Servidor activo en: http://localhost:5000` en la terminal:

- **Portal Corporativo y Tienda:** [http://localhost:5000](http://localhost:5000)
- **Acceso para Personal Autorizado (Login / Registro):** Agrega el parámetro `?key=` con la llave corporativa configurada en la base de datos (por defecto en modo local: `http://localhost:5000/?key=784920`).

---

## 📁 Estructura del Proyecto

```text
alicorp v2/
├── src/main/java/pe/com/alicorp/bi/
│   ├── AlicorpBiApplication.java                  # Punto de entrada Javalin, seguridad y rutas core
│   ├── config/
│   │   └── DatabaseConfig.java                    # Pool HikariCP (Supabase PostgreSQL / H2 3FN)
│   ├── application/
│   │   ├── biometria/ServicioBiometriaJavaCV.java # Extracción de vectores 128-D y distancia euclidiana
│   │   ├── ml/ServicioAnaliticaSmile.java         # K-Means y distribución normal (Smile ML)
│   │   ├── ml/ServicioRedNeuronalDL4J.java        # Inferencia Deep Learning (DL4J + ND4J)
│   │   └── security/ServicioSeguridadRelacional.java # Roles, auditoría, latidos y llaves 3FN
│   └── infrastructure/web/
│       ├── DashboardEndpointsRegistrar.java       # Endpoints de resumen, predicción y SuperAdmin
│       └── TiendaAndSupportEndpointsRegistrar.java # Tienda 3FN, reportes, competencia, fallas y PNGs
├── frontend_corporativo/                          # Portal HTML5 / CSS3 / JS (Clientes, Tienda, Dashboard)
├── database/
│   ├── esquema_relacional_3fn.sql                 # DDL completo en 3ra Forma Normal (PostgreSQL / H2)
│   └── oracle_3fn_ddl.sql                         # Script DDL compatible con Oracle XE
├── tools/maven/                                   # Apache Maven 3.9.9 portátil
├── .env.example                                   # Plantilla de variables de entorno (copiar a .env)
├── iniciar_servidor_java.bat                      # Lanzador rápido para Windows
├── mvnw.cmd                                       # Wrapper de Maven
└── pom.xml                                        # Dependencias y configuración de build Java 21
```

---

## 🧩 Módulos y Funcionalidades Principales

- **Seguridad Biométrica 128-D:** Registro y autenticación de analistas y Super Administradores mediante captura facial por cámara web (sin almacenar fotografías en disco; solo el vector normalizado de 128 dimensiones y su norma L2).
- **Control de Llave Secreta y Auditoría (SuperAdmin):** Gestión de roles (`analista` / `superadmin`), suspensión de cuentas, registro de actividad diaria (zona horaria Perú UTC-5) y flujo de aprobación dual para cambio de llave URL.
- **Dashboard Analítico y ML:**
  - Resumen sobre **100,000 transacciones** normalizadas en 3FN.
  - Simulación e inferencia en tiempo real con **DL4J (Keras)** y comparativa competitiva.
  - Segmentación no supervisada **K-Means** con **Smile ML**.
  - Gráficos estadísticos renderizados nativamente en **Java 2D (PNG)**.
  - Proyección de **Reportes Mensuales Futuros** con persistencia en `REPORTES_MENSUALES_PREDICHOS`.
- **Tienda en Línea 3FN e Incidencias:** Catálogo de productos con control de stock, carrito de compras transaccional, seguimiento de pedidos, buzón de contacto y detector automático de fallas web (`FALLAS_WEB_SISTEMA`).
