# Alicorp BI v2 — Plataforma Corporativa en Java (3FN + ML + Biometría 128-D)

Sistema corporativo de **Inteligencia de Negocios (BI)**, **E-Commerce** y **Control de Acceso Biométrico 128-D** de Alicorp, migrado al 100% a **Java 21+** (probado en **Oracle JDK 26**) con arquitectura relacional en **3ra Forma Normal (3FN)** sobre **Supabase PostgreSQL** (y modo local embebido **H2**).

> **Nota sobre la versión anterior (Python/Flask):** El código legado en Python fue respaldado en la rama `alicorp-v2-completo-con-python` (y etiqueta `v2.0-python-legacy`) para referencia histórica. La rama principal actual contiene únicamente la arquitectura pura en Java.

---

## 🛠️ Stack Tecnológico

- **Lenguaje y Runtime:** Java 21+ (`--release 21`, compatible con JDK 21, 25 y 26).
- **Servidor Web REST:** Javalin `6.3.0` + Jackson `2.17.2` (puerto por defecto `5000`).
- **Deep Learning:** Deeplearning4j (`1.0.0-M2.1`) + ND4J (reemplazo nativo de TensorFlow/Keras).
- **Machine Learning y Estadística:** Smile ML `3.1.1` (K-Means no supervisado, distribución normal gaussiana y analítica descriptiva).
- **Visión Artificial y Biometría Neural 128-D:** JavaCV `1.5.10` / OpenCV DNN con modelos neuronales ONNX **YuNet** (detección y alineación facial) + **SFace** (embeddings 128-D L2-normalizados con similitud del coseno `>= 0.363`, precisión 85%–99.8%) en `models/`.
- **Seguridad y Sesiones:** Defensa en 3 capas desde el backend (Key en sesión servidor, cookies `HttpOnly` + `SameSite=Lax` + `SecureRequestOnly=true` compatibles con `http://localhost:5000` y proxy HTTPS / Cloudflare Tunnel vía `X-Forwarded-Proto`), vistas administrativas fuera de la carpeta pública (`protected_views/`) y bloqueo Anti-DevTools (`HTTP 403 BIOMETRIC_REQUIRED`).
- **Base de Datos y Pool:** HikariCP `5.1.0` + PostgreSQL JDBC `42.7.3` (Supabase 3FN con lectura/escritura de latidos en UTC convertidos a hora de Perú UTC-5) + H2 `2.2.224` (`MODE=Oracle` como respaldo local) + Oracle JDBC `ojdbc11`.
- **Frontend:** HTML5, CSS3, JavaScript Vanilla (`frontend_corporativo/` para contenido público y `protected_views/` para vistas protegidas servidas exclusivamente por Javalin).

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
5. **Cámara Web** *(Obligatoria para activar la biometría inicial en **Mi Perfil y Seguridad** y desbloquear las pestañas del Dashboard BI)*.
6. **Modelos ONNX de Biometría (`models/`):** Ya incluidos en el repositorio (`face_detection_yunet_2023mar.onnx` y `face_recognition_sface_2021dec.onnx`).

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
*(Opcional: puedes definir `$env:ADMIN_ACCESS_KEY = "TU_LLAVE"` en PowerShell para sobrescribir la llave almacenada en `CONFIGURACION_SEGURIDAD` de la base de datos).*

> **Modo offline:** Si no creas `.env`, el sistema usa automáticamente una base embebida **H2** en `./data/alicorp_3fn_relacional`.

### 3. Compilar y Empaquetar el Backend Java
Ejecuta en PowerShell (obligatorio la primera vez o cada vez que modifiques archivos `.java`):
```powershell
.\mvnw.cmd clean package -DskipTests
```
*(La primera ejecución descarga dependencias en `~/.m2/repository` y genera `target\alicorp-bi-java-2.0.0-shaded.jar`. Si solo modificaste archivos HTML/CSS/JS en `frontend_corporativo/` o `protected_views/`, no necesitas recompilar).*

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
> **Exposición pública opcional (Cloudflare Tunnel HTTPS):** en una segunda terminal, ejecuta `cloudflared tunnel --url http://localhost:5000`. Para volver a modo 100% local, simplemente detén `cloudflared` con `Ctrl + C` (no requiere cambios de código).

### 5. Abrir Manualmente en el Navegador
> **Importante:** `iniciar_servidor_java.bat` solo levanta el servidor HTTP Javalin; **no abre el navegador automáticamente**.

Cuando la consola confirme que el servidor está activo en el puerto `5000`, abre tu navegador en:
- **Portal Corporativo:** http://localhost:5000
- **Tienda Virtual (Buscador Predictivo IA):** http://localhost:5000/tienda_virtual/tienda.html
- **Acceso para Personal Autorizado (Capa 1 — requiere Key válida):** `http://localhost:5000/acceso_seguridad/admin_login.html?key=<TU_LLAVE>` *(por defecto `784920` en BD o el valor de `ADMIN_ACCESS_KEY`)*.
- **Dashboard Ejecutivo BI (requiere sesión autenticada):** http://localhost:5000/dashboard_bi/dashboard.html *(sin sesión redirige automáticamente a la tienda/login)*.

*(Si realizaste cambios en CSS/JS y no los ves reflejados, recarga con `Ctrl + F5`).*

---

## 👤 Alta Manual de Trabajadores en Supabase (3FN)

El registro web público fue eliminado por seguridad. Los nuevos trabajadores se dan de alta manualmente en **Supabase SQL Editor** (`public.administradores`) con `face_encoding = NULL` y `l2_norm = NULL`:

```sql
WITH nuevo_trabajador AS (
    SELECT
        'jperez'::varchar                   AS usuario,
        'Juan Perez Quispe'::varchar        AS nombre_completo,
        'jperez@alicorp.com.pe'::varchar    AS email,
        'ClaveTemporal2026'::text           AS password_plano,
        'analista'::varchar                 AS codigo_rol, -- 'analista' | 'superadmin'
        encode(extensions.gen_random_bytes(16), 'hex') AS salt_hex
)
INSERT INTO public.administradores (
    usuario, nombre_completo, email, password_hash, id_rol, id_estado, face_encoding, l2_norm
)
SELECT
    n.usuario, n.nombre_completo, n.email,
    'sha256$' || n.salt_hex || '$' || encode(sha256(convert_to(n.salt_hex || ':' || n.password_plano, 'UTF8')), 'hex'),
    (SELECT id_rol FROM public.roles_seguridad WHERE codigo_rol = n.codigo_rol),
    (SELECT id_estado FROM public.estados_usuario WHERE codigo_estado = 'activo'),
    NULL, NULL
FROM nuevo_trabajador n;
```

- Al iniciar sesión por primera vez con usuario y contraseña, el nuevo trabajador entra directamente a **Mi Perfil y Seguridad** con la insignia **`⚠️ Biometría Pendiente`**.
- Todas las demás pestañas muestran un esqueleto con `blur` y el backend bloquea toda consulta de datos con **`HTTP 403 { "error": "BIOMETRIC_REQUIRED" }`** (protección Anti-DevTools).
- Al registrar su rostro 128-D en **Perfil**, el vector se guarda en Supabase, la insignia cambia a **`Vector 128-D Activo`** y se desbloquean de inmediato todas las pestañas de su rol.

---

## 📁 Estructura del Proyecto

```text
alicorp v2/
├── src/main/java/pe/com/alicorp/bi/
│   ├── AlicorpBiApplication.java                  # Arranque Javalin, cookies HTTPS/Proxy, defensa 3 capas y bloqueo 403 BIOMETRIC_REQUIRED
│   ├── config/
│   │   └── DatabaseConfig.java                    # Pool HikariCP (Supabase PostgreSQL / H2 3FN)
│   └── modulos/                                   # Código Java organizado por sección web
│       ├── acceso_biometria/                      # Login 128-D (YuNet+SFace), Mi Perfil, Activación Biométrica Inicial, Llaves, Auditoría y Latidos UTC
│       ├── chatbot_asistente/                     # 3 Chatbots por rol (Cliente, Analista, Centro de Mando SuperAdmin)
│       ├── tienda_buscador_ia/                    # Tienda 3FN + Árbol Trie Recursivo + Ranking Smile ML
│       ├── dashboard_prediccion/                  # Resumen, Keras DL4J, K-Means, Estadística, Reportes, PNGs
│       ├── estrategia_comercial/                  # Filtrado Colaborativo + DecisionTree + Cross-Validation
│       └── contacto_soporte/                      # Buzón de Contacto, Incidencias y Fallas Web
├── protected_views/                               # Vistas administrativas privadas (fuera de estáticos públicos)
│   ├── admin_login.html                           # Acceso protegido por Capa 1 (Key en sesión servidor)
│   └── dashboard.html                             # Dashboard BI protegido por Capa 2/3 + modo Anti-DevTools blur
├── models/                                        # Modelos neuronales ONNX para biometría JavaCV
│   ├── face_detection_yunet_2023mar.onnx          # Detector facial YuNet
│   └── face_recognition_sface_2021dec.onnx        # Extractor de embeddings 128-D SFace
├── frontend_corporativo/                          # Frontend estático público HTML5/CSS3/JS
│   ├── compartido/                                # estilos.css, ilustraciones.css, script.js, favicon
│   ├── portal_institucional/                      # index.html, clientes.html, inversionistas.html, etica.html, sostenibilidad.html
│   ├── acceso_seguridad/                          # acceso_theme.css (olas SVG + glassmorphism)
│   ├── tienda_virtual/                            # tienda.html, comparativa_busqueda.html, tienda.css, tienda_v2.css, tienda.js, historial_busqueda.js
│   ├── chatbot/                                   # chatbot.css, chatbot_base.js, chatbot_cliente.js, chatbot_dashboard.js
│   ├── contacto_soporte/                          # contacto.html
│   └── dashboard_bi/                              # dashboard_theme.css
├── database/
│   ├── esquema_relacional_3fn.sql                 # DDL completo en 3ra Forma Normal (PostgreSQL / H2)
│   ├── oracle_3fn_ddl.sql                         # Script DDL compatible con Oracle XE
│   └── atencion_tickets_3fn.sql                   # Trazabilidad de tickets e incidencias
├── docs/                                          # Manual Word (Manual_Sistema_Alicorp_BI_Java.docx) y documentación técnica
├── tools/maven/                                   # Apache Maven 3.9.9 portátil
├── .env.example                                   # Plantilla de variables de entorno (copiar a .env)
├── iniciar_servidor_java.bat                      # Lanzador rápido para Windows
├── mvnw.cmd                                       # Wrapper de Maven
└── pom.xml                                        # Dependencias y configuración de build Java 21
```

---

## 🧩 Módulos y Funcionalidades Principales

- **Seguridad en 3 Capas, Biometría Neural 128-D y Mi Perfil:** Sin registro público web; alta manual en 3FN con `face_encoding = NULL`, activación facial inicial en **Mi Perfil y Seguridad** (YuNet + SFace ONNX), bloqueo backend `403 BIOMETRIC_REQUIRED` (Anti-DevTools), re-verificación facial para cambios de credenciales (`SHA-256` con salt) y logout con invalidación total de sesión + cabeceras `no-store`.
- **Chatbots Asistentes por Rol:** Asistente comercial para clientes en tienda/contacto, asistente de tickets en vivo para analistas y Centro de Mando para SuperAdmin.
- **Control de Llave Secreta, Latidos UTC y Auditoría (SuperAdmin):** Gestión de roles (`analista` / `superadmin`) sin pérdida de biometría, suspensión de cuentas, registro de latidos diarios en UTC mostrados en hora exacta de Perú (UTC-5) vinculados estrictamente a la sesión del servidor y flujo de aprobación dual para cambio de llave.
- **Dashboard Analítico y ML:**
  - Resumen sobre **100,000 transacciones** normalizadas en 3FN.
  - Simulación e inferencia en tiempo real con **DL4J (Keras)** y comparativa competitiva.
  - Segmentación no supervisada **K-Means** con **Smile ML**.
  - Gráficos estadísticos renderizados nativamente en **Java 2D (PNG)**.
  - Proyección de **Reportes Mensuales Futuros** con persistencia en `REPORTES_MENSUALES_PREDICHOS`.
- **Tienda en Línea 3FN e Incidencias:** Catálogo de productos con control de stock, carrito de compras transaccional, seguimiento de pedidos, buzón de contacto y detector automático de fallas web (`FALLAS_WEB_SISTEMA`).
