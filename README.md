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

## 📋 Orden de Requisitos Previos

Instala y verifica estos requisitos **en orden** antes de ejecutar el proyecto en tu máquina local (`localhost`):

1. **Java Development Kit (JDK) 21 o superior** *(Obligatorio — probado en JDK 21 LTS y Oracle JDK 26)*:
   - Descarga: [Adoptium Temurin JDK 21+](https://adoptium.net/) o [Oracle JDK](https://www.oracle.com/java/technologies/downloads/).
   - Configura la variable de entorno **`JAVA_HOME`** apuntando a la carpeta de tu JDK (ej. `C:\Program Files\Java\jdk-26.0.2`).
   - Verifica en PowerShell:
     ```powershell
     java -version
     ```
2. **Git** *(Obligatorio para clonar y sincronizar `main`)*.
3. **Apache Maven:** **NO necesitas instalarlo.** El repositorio incluye Maven portátil (`3.9.9`) en `tools/maven/` mediante `.\mvnw.cmd`.
4. **Credenciales de Base de Datos (`.env`):** Necesitas `JDBC_URL`, `JDBC_USER` y `JDBC_PASSWORD` de Supabase PostgreSQL *(si lo omites, arrancará en modo local embebido H2)*.
5. **Cámara Web** *(Opcional: solo si probarás el registro y login biométrico facial 128-D en vivo)*.

---

## 🚀 Proceso Ordenado de Instalación y Ejecución en Local

### 1. Clonar el Repositorio
```powershell
git clone https://github.com/Rakkiel-cmd/Alicorpt-v2.git
cd "Alicorpt-v2"
```

### 2. Configurar Variables de Entorno (`.env`)
```powershell
Copy-Item .env.example .env
```
Edita `.env` con las credenciales reales compartidas por el equipo:
```env
JDBC_URL=jdbc:postgresql://aws-0-sa-east-1.pooler.supabase.com:5432/postgres?sslmode=require
JDBC_USER=postgres.tu_project_ref
JDBC_PASSWORD=TU_CONTRASENA_AQUI
```
> **Modo offline:** Si no creas `.env`, el sistema usa automáticamente una base embebida **H2** en `./data/alicorp_3fn_relacional`.

### 3. Compilar y Empaquetar el Backend Java
Ejecuta en PowerShell (obligatorio la primera vez o cada vez que modifiques archivos `.java`):
```powershell
.\mvnw.cmd clean package -DskipTests
```
*(La primera ejecución descarga dependencias en `~/.m2/repository` y genera `target\alicorp-bi-java-2.0.0-shaded.jar`. Si solo modificaste archivos HTML/CSS/JS dentro de `frontend_corporativo/`, no necesitas recompilar).*

### 4. Iniciar el Servidor Local
En PowerShell o CMD, ejecuta cualquiera de las dos opciones y **deja la ventana abierta**:
```powershell
.\iniciar_servidor_java.bat
```
O directamente con Java:
```powershell
java -jar target\alicorp-bi-java-2.0.0-shaded.jar
```
> **¿Puerto 5000 ocupado?** Cambia el puerto antes de ejecutar:
> ```powershell
> $env:PORT="5050"; java -jar target\alicorp-bi-java-2.0.0-shaded.jar
> ```

### 5. Abrir Manualmente en el Navegador
> **Importante:** `iniciar_servidor_java.bat` solo levanta el servidor HTTP Javalin; **no abre el navegador automáticamente**.

Cuando la consola confirme que el servidor está activo en el puerto `5000`, abre tu navegador en:
- **Portal Corporativo:** http://localhost:5000
- **Dashboard Ejecutivo BI (Light/Dark + Olas SVG):** http://localhost:5000/dashboard_bi/dashboard.html
- **Tienda Virtual (Buscador Predictivo IA):** http://localhost:5000/tienda_virtual/tienda.html
- **Acceso para Personal Autorizado (Login / Registro):** `http://localhost:5000/?key=<TU_LLAVE>` *(ej. `http://localhost:5000/?key=784920` en modo H2 local)*.

*(Si realizaste cambios en CSS/JS y no los ves reflejados, recarga con `Ctrl + F5`).*

---

## 📁 Estructura del Proyecto

```text
alicorp v2/
├── src/main/java/pe/com/alicorp/bi/
│   ├── AlicorpBiApplication.java                  # Punto de entrada Javalin y alias de rutas web
│   ├── config/
│   │   └── DatabaseConfig.java                    # Pool HikariCP (Supabase PostgreSQL / H2 3FN)
│   └── modulos/                                   # Código Java organizado por sección web
│       ├── acceso_biometria/                      # Login 128-D, Registro, Llaves, Auditoría y Equipo
│       ├── tienda_buscador_ia/                    # Tienda 3FN + Árbol Trie Recursivo + Ranking Smile ML
│       ├── dashboard_prediccion/                  # Resumen, Keras DL4J, K-Means, Estadística, Reportes, PNGs
│       ├── estrategia_comercial/                  # Filtrado Colaborativo + DecisionTree + Cross-Validation
│       └── contacto_soporte/                      # Buzón de Contacto, Incidencias y Fallas Web
├── frontend_corporativo/                          # Frontend HTML5/CSS3/JS organizado por sección
│   ├── compartido/                                # estilos.css, ilustraciones.css, script.js, favicon
│   ├── portal_institucional/                      # index.html, clientes.html, inversionistas.html, etica.html, sostenibilidad.html
│   ├── acceso_seguridad/                          # admin_login.html, registro.html
│   ├── tienda_virtual/                            # tienda.html, tienda.css, tienda.js, tienda_imagenes.js
│   ├── contacto_soporte/                          # contacto.html
│   └── dashboard_bi/                              # dashboard.html, dashboard_theme.css
├── database/
│   ├── esquema_relacional_3fn.sql                 # DDL completo en 3ra Forma Normal (PostgreSQL / H2)
│   ├── oracle_3fn_ddl.sql                         # Script DDL compatible con Oracle XE
│   └── atencion_tickets_3fn.sql                   # Trazabilidad de tickets e incidencias
├── docs/                                          # Manual Word y documentación técnica
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
