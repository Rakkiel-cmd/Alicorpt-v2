# Manual de Conocimiento Técnico — Alicorp BI v2 (Contexto para IA)

> **Propósito de este documento:** Proveer a cualquier asistente de IA o desarrollador el mapa completo, exacto y actualizado del repositorio **Alicorp BI v2** (arquitectura 100% Java 21+ / Supabase PostgreSQL 3FN / Frontend Vanilla HTML5-CSS3-JS) para continuar el desarrollo sin romper funcionalidades ni introducir regresiones.

---

## 1. Reglas Críticas del Proyecto (Leer Antes de Modificar)

1. **Base de Datos Intocable (Supabase PostgreSQL 3FN):**
   - El backend se conecta a **Supabase PostgreSQL** en la nube vía JDBC (`org.postgresql:postgresql` + `HikariCP`) leyendo las variables `JDBC_URL`, `JDBC_USER` y `JDBC_PASSWORD` desde `.env` (no versionado; usar `.env.example` como plantilla).
   - Si no existe `.env`, `DatabaseConfig.java` usa automáticamente fallback a **H2 embebido en modo Oracle** (`./data/alicorp_3fn_relacional`).
   - **NO** migrar el motor ni romper el esquema 3FN existente. Todas las consultas sobre `TRANSACCIONES_ALICORP` (100,000+ filas) deben hacerse con agregaciones SQL (`GROUP BY`) para no cargar 100k filas crudas en RAM.
2. **Backend 100% Java 21+ / Sin Python:**
   - El código legado en Python fue archivado en la rama `alicorp-v2-completo-con-python` (`v2.0-python-legacy`). La rama `main` es 100% Java.
   - Compilación objetivo: `--release 21` (probado en **Oracle JDK 26.0.2**).
   - **Maven portátil incluido:** usar siempre `.\mvnw.cmd` (apunta a `tools/maven/` 3.9.9).
3. **Frontend 100% Vanilla (HTML5 / CSS3 / JS ES6+):**
   - No hay React, Angular, Vue ni bundlers (Webpack/Vite). Javalin sirve directamente la carpeta `frontend_corporativo/`.
   - El Dashboard (`dashboard_bi/dashboard.html`) soporta **Modo Claro y Modo Oscuro** mediante variables CSS (`--bg-main`, `--surface`, `--text-main`, `--text-muted`, `--border`, `--accent`). Cualquier componente nuevo debe usar estas variables.
4. **Seguridad y Roles Estrictos:**
   - Existen dos roles en `ROLES_SEGURIDAD`: `analista` (`id_rol = 1`) y `superadmin` (`id_rol = 2`).
   - Las secciones **Estrategia Comercial**, **Administración** y **Seguridad Super Admin** son **EXCLUSIVAS de `superadmin`** tanto en el menú/guardias del frontend como en los endpoints del backend (`403 Forbidden` para analistas o peticiones sin usuario).

---

## 2. Stack Tecnológico (`pom.xml`)

| Capa | Tecnología / Artefacto Maven | Versión | Uso |
| :--- | :--- | :--- | :--- |
| Web / REST | `io.javalin:javalin` + `jackson-databind` | `6.3.0` / `2.17.2` | Servidor HTTP (puerto `5000`), estáticos y API JSON |
| Deep Learning | `org.deeplearning4j:deeplearning4j-core` + `nd4j-native-platform` | `1.0.0-M2.1` | Red Neuronal densa / inferencia Keras (14-D) |
| Machine Learning | `com.github.haifengl:smile-core` | `3.1.1` | `KMeans`, `RidgeRegression`, `DecisionTree` (CART Gini), `CrossValidation.stratify`, `ConfusionMatrix` |
| Visión / Biometría | `org.bytedeco:javacv-platform` | `1.5.10` | OpenCV nativo (160x160 gris ecualizado) + vectores faciales 128-D |
| Base de Datos | `HikariCP` + `postgresql` + `h2` + `ojdbc11` | `5.1.0` / `42.7.3` / `2.2.224` | Pool JDBC optimizado para Supabase Session Pooler (`prepareThreshold=0&tcpKeepAlive=true`) |
| Gráficos | `java.awt.Graphics2D` + `javax.imageio.ImageIO` | JDK Nativo | Renderizado de gráficos estadísticos PNG en memoria |

---

## 3. Estructura Actual del Proyecto (Modular por Sección Web)

### Backend Java (`src/main/java/pe/com/alicorp/bi/`)
```text
pe/com/alicorp/bi/
├── AlicorpBiApplication.java                  # Arranque Javalin, estáticos, alias de rutas con query string y registro de los 6 controladores
├── config/
│   └── DatabaseConfig.java                    # Pool HikariCP (.env -> Supabase PostgreSQL / fallback H2 3FN)
├── application/
│   ├── mail/
│   │   └── CorreoPedido.java                  # Notificación SMTP de confirmación de pedido de la tienda
│   └── search/
│       ├── TrieAutocompletado.java            # Trie recursivo de autocompletado (productos, marcas, categorías)
│       ├── RankingBusqueda.java               # Ranking personalizado por historial de navegador + stock
│       └── MetricasBusqueda.java              # Registro y comparativa de métricas antes/después (alfabético vs IA)
└── modulos/
    ├── acceso_biometria/                      # Login 128-D, Registro, Llaves URL, Auditoría, Actividad del Equipo y SuperAdmin
    │   ├── AccesoBiometriaController.java     # 403 estricto en TODOS los endpoints /api/superadmin/* y /api/seguridad/info_clave
    │   ├── ServicioBiometriaJavaCV.java       # OpenCV 160x160 + Try-with-resources Mat + rechazo de frames vacíos/oscuros
    │   ├── ServicioSeguridadRelacional.java
    │   ├── ServicioActividadEquipo.java
    │   └── AnalisisEquipo.java
    ├── chatbot_asistente/                     # 3 Chatbots por rol (Cliente, Analista + pulso tickets, Centro de Mando SuperAdmin)
    │   ├── ChatbotController.java
    │   ├── ChatbotClienteService.java
    │   ├── ChatbotAnalistaService.java
    │   ├── ChatbotAdminService.java
    │   ├── ExplicadorDashboard.java
    │   ├── AsistenteIAOpcional.java
    │   ├── MotorIntenciones.java
    │   └── RespuestaChat.java
    ├── tienda_buscador_ia/                    # Tienda E-Commerce 3FN + Buscador Predictivo (Trie Recursivo + Smile ML)
    │   ├── TiendaBuscadorController.java
    │   ├── BuscadorCatalogoRepository.java
    │   ├── ProductoCatalogoIA.java
    │   ├── TrieProductos.java
    │   ├── RankingProductosSmileIA.java
    │   └── ServicioBuscadorIA.java
    ├── dashboard_prediccion/                  # Resumen BI, Predicción, DL4J (Keras), K-Means, Estadística, Reportes Mensuales, Competencia y PNGs
    │   ├── DashboardAnaliticaController.java
    │   ├── ServicioRedNeuronalDL4J.java
    │   └── ServicioAnaliticaSmile.java
    ├── estrategia_comercial/                  # Recomendador Exclusivo SuperAdmin (Colaborativo + DecisionTree 5-Fold CV + Recursividad)
    │   ├── RecomendadorController.java
    │   ├── RecomendadorSupabaseRepository.java
    │   ├── DatosTiendaProducto.java
    │   ├── ColaborativoTiendasService.java
    │   ├── NodoEstrategia.java
    │   └── EstrategiaDecisionService.java
    └── contacto_soporte/                      # Buzón de Contacto, Incidencias de Clientes y Detector de Fallas Web
        └── ContactoSoporteController.java
```
> **Recursos (`src/main/resources/`):** `catalogo_extra.tsv` (7,303 SKUs de catálogo complementario).  
> **Modelos ONNX (`models/`):** `face_detection_yunet_2023mar.onnx` (YuNet) y `face_recognition_sface_2021dec.onnx` (SFace 128-D) para biometría neural JavaCV.

### Frontend (`frontend_corporativo/`)
```text
frontend_corporativo/
├── compartido/              # estilos.css, ilustraciones.css, script.js, favicon.ico, favicon.png
├── portal_institucional/    # index.html, clientes.html, inversionistas.html, etica.html, sostenibilidad.html, portada.{css,js}, institucional.{css,js}
├── acceso_seguridad/        # admin_login.html, registro.html, acceso_theme.css (homogeneizado con olas SVG + glassmorphism)
├── tienda_virtual/          # tienda.html, comparativa_busqueda.html, tienda.css, tienda_v2.css, tienda.js, tienda_imagenes.js, historial_busqueda.js
├── chatbot/                 # chatbot.css, chatbot_base.js, chatbot_cliente.js, chatbot_dashboard.js
├── contacto_soporte/        # contacto.html
└── dashboard_bi/            # dashboard.html, dashboard_theme.css
```
> **Compatibilidad de rutas:** `AlicorpBiApplication.java` redirige automáticamente `/`, `/index.html`, `/tienda.html`, `/dashboard.html`, `/admin_login.html`, `/registro.html`, `/contacto.html`, `/clientes.html`, `/etica.html`, `/inversionistas.html` y `/sostenibilidad.html` a sus respectivas carpetas **preservando todos los parámetros de query string** (ej. `/?key=784920` → `/portal_institucional/index.html?key=784920`).

### Diseño UI/UX del Dashboard (`dashboard_bi/dashboard.html` + `dashboard_theme.css`)
- **Navegación Superior Horizontal en Cápsulas SVG (`.top-shell` / `.sb-menu`):** Reemplaza el antiguo menú lateral. Botones circulares minimalistas en estado inactivo que se expanden en cápsula horizontal al activarse, usando íconos SVG inline (sin emojis).
- **Cápsula Flotante Colapsable de Perfil y Biometría (`#floatingProfileCapsule`):** Ubicada en la esquina inferior izquierda (`position: fixed; bottom: 20px; left: 20px; z-index: 900`), muestra perfil, rol, embedding 128-D (`#btnVerVectores`), cronómetro en vivo (`#liveTimerDisplay`) y cierre de sesión, con botón `#btnToggleProfileCapsule` para replegarla y estado persistido en `localStorage`.
- **Fondo Global Animado de Olas Marinas SVG (`.ocean-waves-bg`):** 3 capas SVG sinuosas superpuestas (`#oceanWaveGrad1/2/3`) en la mitad inferior (`38vh–54vh`) con bucle horizontal infinito (`@keyframes waveFlow`). Reacciona dinámicamente a Modo Claro/Oscuro mediante variables CSS (`--wave-1-start/end`, `--wave-2-start/end`, `--wave-3-start/end`).
  - **Modo Claro:** Fondo superior `#ffffff` → `#f8fafc`; olas carbón/negro (`#475569` → `#090d16`); tarjetas `rgba(255, 255, 255, 0.88)` con `backdrop-filter: blur(14px)`.
  - **Modo Oscuro:** Fondo superior obsidiana (`#070a12` → `#0d1321`); olas blanco perla/plata (`#ffffff` → `#475569`); tarjetas `rgba(15, 20, 32, 0.78)` con `backdrop-filter: blur(14px)`.
- **Mapeo de Títulos Ejecutivos de Pestañas (mismos `data-go`):** `inicio` (*Resumen Ejecutivo*), `productos` (*Portafolio y Stock*), `compras` (*Órdenes Web*), `prediccion` (*Pronóstico Lineal*), `keras` (*Simulador Neuronal*), `segmentacion` (*Clusters K-Means*), `estadistica` (*Riesgo Estadístico*), `reporte` (*Cierre Mensual*), `incidencias` (*Incidencias y SLA*), `perfil` (*Mi Perfil y Seguridad* — transversal para `analista` y `superadmin`, edición bloqueada hasta re-verificación facial 128-D), `estrategia` (*Estrategia IA* — Solo SuperAdmin), `administracion` (*Gestión de Equipo* — Solo SuperAdmin), `seguridad` (*Seguridad y Llaves* — Solo SuperAdmin).

---

## 4. Esquema de Base de Datos Relacional 3FN (Supabase PostgreSQL)

Definido en `database/esquema_relacional_3fn.sql` (y migración incremental automática en `database/atencion_tickets_3fn.sql`):

- **Catálogos y Seguridad:**
  - `ROLES_SEGURIDAD` (`id_rol`, `codigo_rol` [`analista`/`superadmin`], `nombre_visible`, `nivel_acceso`)
  - `ESTADOS_USUARIO` (`id_estado`, `codigo_estado` [`activo`/`suspendido`], `permite_login`)
  - `CONFIGURACION_SEGURIDAD` (`clave_parametro = 'clave_acceso_personal'`, `valor_parametro`)
  - `ADMINISTRADORES` (`id_admin`, `usuario`, `nombre_completo`, `email`, `password_hash`, FK `id_rol`, FK `id_estado`, `face_encoding` JSON 128-D, `l2_norm`)
  - `ACTIVIDAD_USUARIOS` (`id_admin`, `fecha_sesion`, `tiempo_segundos`, `primer_ingreso`, `ultimo_latido` — zona horaria Perú `UTC-5`)
  - `AMPLIACION_AUDITORIA` (`id_admin_ejecutor`, `id_admin_objetivo`, `accion`, `detalle`, `fecha_evento`)
  - `SOLICITUDES_CAMBIO_CLAVE` (flujo de aprobación dual entre dos SuperAdmins distintos)
  - `HISTORIAL_ATENCION` (creada automáticamente por `ServicioActividadEquipo` para trazabilidad de atención de incidencias y fallas)
- **Catálogo, Tiendas y Hechos Transaccionales:**
  - `CATEGORIAS_PRODUCTO` (14 categorías)
  - `PRODUCTOS` (~7,303 SKUs activos con `sku_codigo`, `nombre_producto`, `marca`, `presentacion`, `stock_disponible`, `precio_base`, `costo_unitario`, `factor_mercado`, FK `id_categoria`)
  - `TIENDAS` (4 tiendas: *Hipermercado Centro*, *Mayorista San Martín*, *Supermercado Lima Norte*, *Tienda Express Los Olivos*)
  - `TRANSACCIONES_ALICORP` (**100,000 filas reales** con FK `id_producto`, FK `id_tienda`, `fecha_operacion`, `precio_unitario`, `stock_actual`, `temperatura_zona`, `cantidad_vendida`, `ventas_totales`)
- **E-Commerce, Analítica y Soporte:**
  - `COMPRAS_TIENDA` y `COMPRAS_TIENDA_DETALLE`
  - `EMPRESAS_COMPETENCIA` (3 competidores: `PG`, `MOL`, `UNL`)
  - `REPORTES_MENSUALES_PREDICHOS` (persistencia de proyecciones futuras por `periodo_mes`)
  - `INCIDENCIAS_CONTACTO` y `FALLAS_WEB_SISTEMA`

---

## 5. Funcionamiento de los Módulos Principales (IA, ML y Recursividad)

### A. Acceso Biométrico 128-D y Llave URL (`acceso_biometria`)
- El botón **"Personal Autorizado"** está oculto para el público general. Al entrar con `?key=<llave>`, `compartido/script.js` llama a `POST /api/seguridad/validar_key`, habilita el acceso y limpia la llave de la barra de direcciones (`history.replaceState`).
- `ServicioBiometriaJavaCV` usa **OpenCV DNN en JavaCV (`FaceDetectorYN` YuNet + `FaceRecognizerSF` SFace 128-D)** sobre `models/face_detection_yunet_2023mar.onnx` y `models/face_recognition_sface_2021dec.onnx`: detecta y alinea el rostro, extrae el embedding neural de 128 dimensiones normalizado (`L2 = 1.410`) y valida identidad mediante **Similitud del Coseno** (`UMBRAL_COSENO_SFACE = 0.363`, confianza 85%–99.8%), con fallback determinístico sobre ROI central si faltan los `.onnx`.

### B. Buscador Predictivo con Árbol Trie Recursivo + Ranking Smile ML (`tienda_buscador_ia`)
- **`BuscadorCatalogoRepository`**: consulta agregada `GROUP BY` sobre `PRODUCTOS` + `TRANSACCIONES_ALICORP` + `TIENDAS` (~7,303 productos indexados con afinidad histórica por tienda).
- **`TrieProductos`**: Árbol de Prefijos en Java puro (sin tildes, indexado por nombre completo y tokens). Su método `buscarPrefijoRecursivo(String prefijo)` recorre recursivamente el árbol (`navegarPrefijoRecursivo` + `recolectarProductosRecursivo`) en `< 1 ms`.
- **`RankingProductosSmileIA`**: entrena `smile.regression.RidgeRegression` (Smile ML 3.1.1) sobre una muestra estratificada ponderando rotación histórica (45%), stock disponible (25%, con penalización si `stock == 0`), afinidad con la tienda seleccionada (20%) y similitud de contenido (10%).
- **UI (`tienda_virtual/tienda.html` + `tienda.js`)**: barra con *debounce* `220 ms` contra `GET /api/buscador-ia?q=...&tienda=...`, autocompletado en vivo con tiempo en `ms`, selector de tienda e interruptor visual **Orden Tradicional (Alfabético)** vs **Ranking Inteligente (IA)** mostrando puntaje IA y ganancia de posiciones (`▲ +N pos.`).

### C. Recomendador de Estrategia Comercial y Reabastecimiento (`estrategia_comercial` — Solo SuperAdmin)
- **`RecomendadorSupabaseRepository`**: consulta `GROUP BY nombre_tienda, nombre_producto, TO_CHAR(fecha_operacion, 'YYYY-MM')` devolviendo ~480 muestras mensuales agregadas desde Supabase.
- **`ColaborativoTiendasService`**: calcula **Similitud del Coseno** y **Correlación de Pearson** en arreglos `double[]` nativos entre las 4 tiendas para identificar la tienda más similar y sugerir replicar acciones comerciales exitosas.
- **`NodoEstrategia`**: árbol jerárquico propio en Java puro calibrado por percentiles reales, con método estrictamente recursivo `evaluarRutaRecomendacion(NodoEstrategia nodo, DatosTiendaProducto datos)` que devuelve la estrategia final, prioridad (`ALTA`/`MEDIA`/`BAJA`) y la traza paso a paso de cada regla evaluada.
- **`EstrategiaDecisionService`**: entrena `smile.classification.DecisionTree` (CART Gini) con `precio_unitario`, `stock_actual`, `temperatura_zona` y `cantidad_vendida` para 4 estrategias (*Reabastecimiento Urgente*, *Impulso en Góndola / Promoción*, *Liquidación por Sobrestock*, *Mantener Estrategia*) y ejecuta **5-Fold Stratified Cross-Validation** (`Accuracy ~98.75%`, `F1 ~98.27%`, Matriz de Confusión 4×4 y comparativa frente a reglas estáticas fijas).

### D. Dashboard BI, DL4J, K-Means, Estadística y Reportes (`dashboard_prediccion`)
- **`ServicioRedNeuronalDL4J`**: vector escalado de 14 dimensiones en ND4J (`StandardScaler` + `OneHot`) para inferencia en tiempo real (`POST /api/keras/predecir` y `/api/keras/comparar_productos`) y simulación competitiva (`/api/keras/competencia/simular`).
- **`ServicioAnaliticaSmile`**: clustering `KMeans.fit(...)` sobre transacciones reales y distribución normal gaussiana (`erfc`) para riesgo de quiebre de stock por tienda.
- **Reportes Mensuales**: consulta histórica (`/api/reporte_mensual`) y predicción futura persistida (`/api/reporte_mensual/predecir_futuro`).

---

## 6. Catálogo de Endpoints REST (`/api/*`)

- **Acceso, Biometría, Actividad y SuperAdmin (`AccesoBiometriaController`):**
  - `POST /api/seguridad/validar_key`, `POST /api/login_password`, `POST /api/login_facial`, `POST /api/verificar_rostro`, `POST /api/registrar_admin_web`, `POST /api/logout`, `GET /api/usuario_perfil`
  - `POST /api/usuario/reautenticar_biometria`, `POST /api/usuario/actualizar_credenciales` *(re-verificación 128-D + token temporal + hash SHA-256 con salt + auditoría 3FN, disponible para `analista` y `superadmin`)*
  - `GET /api/actividad/hoy`, `POST /api/actividad/latido`, `GET /api/actividad/historial`
  - `GET /api/seguridad/info_clave`, `GET /api/seguridad/generar_clave_random`
  - `GET /api/superadmin/usuarios`, `GET /api/superadmin/vector`, `GET /api/superadmin/auditoria`, `GET /api/superadmin/auditoria_filtrada`, `GET /api/superadmin/equipo`
  - `POST /api/superadmin/suspender`, `POST /api/superadmin/reactivar`, `POST /api/superadmin/ascender_rol`, `POST /api/superadmin/degradar_rol`, `POST /api/superadmin/actualizar_email`
  - `GET /api/superadmin/clave/solicitudes`, `POST /api/superadmin/clave/solicitar`, `POST /api/superadmin/clave/aprobar`, `POST /api/superadmin/clave/rechazar`
- **Tienda y Buscador IA (`TiendaBuscadorController`):**
  - `GET /api/buscador-ia?q={texto}&tienda={tienda}&limite={n}`, `GET /api/buscador-ia/tiendas`
  - `GET /api/tienda/estado`, `GET /api/tienda/categorias`, `GET /api/tienda/destacados`, `GET /api/tienda/productos`, `GET /api/tienda/buscar`
  - `POST /api/tienda/comprar`, `POST /api/tienda/pedido`, `GET /api/tienda/compras`, `POST /api/tienda/compras/estado`
- **Dashboard Analítico y ML (`DashboardAnaliticaController`):**
  - `GET /api/resumen_productos`, `POST /api/predecir_ventas`, `GET /api/explicar_modelo`
  - `GET /api/keras/info`, `POST /api/keras/predecir`, `POST /api/keras/comparar_productos`, `GET /api/keras/competencia/empresas`, `POST /api/keras/competencia/simular`
  - `GET /api/estadistica`, `GET /api/segmentacion`
  - `GET /api/reporte_mensual`, `GET /api/reporte_mensual/predecir_futuro`
  - `GET /api/graficos/regresion`, `GET /api/graficos/correlacion`, `GET /api/graficos/probabilidades`
- **Estrategia Comercial — Solo SuperAdmin (`RecomendadorController`):**
  - `GET /api/recomendador/estrategias?tienda={nombre}&usuario={superadmin}`
  - `GET /api/recomendador/validacion?usuario={superadmin}`
- **Contacto, Incidencias y Fallas Web (`ContactoSoporteController`):**
  - `POST /api/contacto`, `GET /api/incidencias`, `POST /api/incidencias/cambiar_estado`
  - `GET /api/fallas_web`, `GET|POST /api/fallas_web/escanear`, `POST /api/fallas_web/reportar`, `POST /api/fallas_web/cambiar_estado`
- **Chatbots Asistentes por Rol (`ChatbotController`):**
  - `POST /api/chatbot/cliente` *(público: tienda y contacto)*
  - `POST /api/chatbot/contexto` *(detecta rol activo del dashboard)*
  - `POST /api/chatbot/analista`, `GET /api/chatbot/analista/pulso` *(analistas y superadmins activos)*
  - `POST /api/chatbot/admin` *(exclusivo SuperAdmin — `403` para otros roles)*

---

## 7. Orden de Requisitos, Compilación y Ejecución Local

### Orden de Requisitos Previos
1. **Java JDK 21 o superior** (probado en **Oracle JDK 26.0.2** / Adoptium Temurin 21+) con `JAVA_HOME` configurado.
2. **Git** para clonar/actualizar el repositorio.
3. **Apache Maven:** No requiere instalación aparte (incluido en `tools/maven/` 3.9.9 vía `.\mvnw.cmd`).
4. **Archivo `.env`:** Copiar `.env.example` a `.env` con `JDBC_URL`, `JDBC_USER` y `JDBC_PASSWORD` de Supabase PostgreSQL (si se omite `.env`, usa H2 embebido en `./data/`).
5. **Cámara Web (opcional):** Solo para registro y login biométrico facial 128-D.

### Proceso Ordenado de Ejecución en Windows (PowerShell)
1. **Compilar / verificar sintaxis Java:**
   ```powershell
   .\mvnw.cmd clean compile
   ```
2. **Empaquetar Uber-JAR (obligatorio si modificaste archivos `.java`):**
   ```powershell
   .\mvnw.cmd clean package -DskipTests
   ```
   *(Tarda ~1-2 min porque empaqueta nativos de DL4J/JavaCV; los warnings `Discovered module-info.class` son normales. Si solo editaste HTML/CSS/JS en `frontend_corporativo/`, no necesitas recompilar).*
3. **Iniciar el servidor local (puerto `5000`):**
   ```powershell
   .\iniciar_servidor_java.bat
   ```
   *(O directamente con `java -jar target\alicorp-bi-java-2.0.0-shaded.jar`; para usar otro puerto: `$env:PORT="5050"; java -jar target\alicorp-bi-java-2.0.0-shaded.jar`).*
4. **Abrir manualmente en el navegador** *(el script `.bat` solo levanta el servidor y no abre pestañas)*:
   - Portal público: `http://localhost:5000`
   - Dashboard BI: `http://localhost:5000/dashboard_bi/dashboard.html`
   - Tienda Virtual (Buscador IA): `http://localhost:5000/tienda_virtual/tienda.html`
   - Acceso Personal Autorizado: `http://localhost:5000/?key=<TU_LLAVE>` (usar `Ctrl + F5` tras cambios CSS/JS).

### Trampas Conocidas que la IA Debe Evitar
1. **Sesión en Frontend (`usuario_activo` es JSON):** En `localStorage`/`sessionStorage`, `usuario_activo` guarda `{"usuario":"LPerez","nombre_completo":"..."}`. **Nunca** pasar el string crudo como `?usuario=...`; usar `sesionUsuario.usuario` o `JSON.parse(raw).usuario`.
2. **Excepciones en Lambdas Javalin:** Envolver siempre los bloques `try (Connection conn = ...)` con `catch (SQLException e)` para evitar advertencias/errores del compilador Eclipse JDT del IDE y devolver JSON limpio en lugar de 500 HTML.
3. **Nulabilidad y Referencias de Método:** Evitar `Double::sum`, `Integer::sum`, `Double::doubleValue` o `Math::max` dentro de `.merge()`/`.mapToDouble()` si Eclipse JDT tiene null-analysis; preferir lambdas explícitas `(a, b) -> a + b`.
4. **Credenciales:** Nunca commitear `.env`. Solo `.env.example` debe estar en Git (`.gitignore` tiene `.env`, `.env.*`, `!.env.example`).
5. **Rutas Estáticas:** Todos los enlaces dentro de `frontend_corporativo/` deben usar rutas absolutas desde la raíz web (`/compartido/...`, `/portal_institucional/...`, `/tienda_virtual/...`, `/acceso_seguridad/...`, `/contacto_soporte/...`, `/dashboard_bi/...`).
