# Buscador predictivo con ranking por IA (tienda en línea)

Resumen técnico para el informe. Parte de la tienda: buscador, carrito y "Mi pedido".

## 1. Qué hace

Mientras la persona escribe, el servidor Java sugiere productos, marcas y categorías en milisegundos. Después, al mostrar los resultados, los **reordena según quién busca**: lo que ya vio, marcó como favorito, agregó al carrito o compró, y el stock disponible. Antes (orden tradicional) los resultados salían de la A a la Z.

## 2. Árbol de prefijos (Trie) con recursividad

- Clase: `application/search/TrieAutocompletado.java`.
- Cada nombre de producto se inserta completo y también desde cada palabra (así "primor" encuentra "Aceite Primor 1L"). Además se insertan las marcas y las categorías.
- Buscar un prefijo baja por el árbol letra por letra; después `recolectar(...)` recorre de forma **recursiva** el subárbol y junta las sugerencias que cuelgan de ese nodo.
- Complejidad: bajar al nodo cuesta O(largo del prefijo); recolectar cuesta O(sugerencias del subárbol), con un tope de 2000 candidatos.
- Endpoint: `GET /api/tienda/sugerencias?q=...&modo=ia|alfabetico&perfil=...`.

## 3. Ranking personalizado

- Clase sin dependencias externas: `application/search/RankingBusqueda.java`.
- **Perfil**: el navegador guarda el historial de cada persona (`localStorage`, archivo `historial_busqueda.js`) con pesos: ver 1, favorito 2, agregar al carrito 3, comprar 5. Cada acción nueva reduce 3 % lo anterior, así lo reciente pesa más. Al buscar manda un resumen corto (top de categorías, marcas y productos); no se guarda nada personal en el servidor.
- **Afinidad** de un producto (de 0 a 1) = 0,55 categoría + 0,35 marca + 0,10 producto ya visto, con escala logarítmica para que una sola categoría muy repetida no aplaste a las demás.
- **Puntaje de un resultado** con historial: 0,25 relevancia + 0,50 afinidad + 0,25 stock. Sin historial: 0,60 relevancia + 0,40 stock. Los agotados conservan solo 20 % del puntaje, así quedan siempre al final.
- En el autocompletar, el puntaje base (stock, marca, categoría) sube hasta 12 puntos según la afinidad.
- **Recomendados "Para ti"**: `GET /api/tienda/recomendados`. Productos disponibles de las categorías y marcas favoritas, máximo 3 por marca; lo ya visto o comprado va después de lo nuevo.
- Cada producto subido trae una razón corta (`ia_motivo`): "Porque te interesa <categoría>", "Marca que sueles elegir: <marca>" o "Lo viste o lo compraste antes".

**Alcance real:** es un modelo de afinidad con pesos definidos y aprendizaje incremental del historial de cada persona. No es una red neuronal entrenada con muchos usuarios. Para llegar a eso haría falta guardar el historial en la base de datos (una tabla nueva en Supabase), decisión pendiente con el equipo.

## 4. Comparativa antes y después

- La tienda tiene un interruptor **Con IA / Alfabético** sobre los resultados.
- Cada búsqueda escrita que termina en un producto agregado se mide: tiempo desde la primera tecla, teclas escritas y posición del producto elegido en la lista (1 = primero). Si la persona cambia de modo a mitad de una búsqueda, esa búsqueda no se cuenta.
- Endpoints: `POST /api/tienda/metricas` (registro), `GET /api/tienda/metricas/resumen` (promedios por modo y mejora porcentual) y `POST /api/tienda/metricas/reiniciar` (solo desde el computador del servidor).
- Página para ver y copiar el resultado: `http://localhost:5000/comparativa_busqueda.html`.
- Los registros se guardan en el archivo `.alicorp_bi/metricas_busqueda.tsv` dentro de la carpeta del usuario (fuera del repositorio). Se puede cambiar con la variable `METRICAS_BUSQUEDA_ARCHIVO`.

### Cómo hacer la prueba

1. Abre la tienda, agrega algunos productos o márcalos como favoritos (para que haya historial).
2. Con el orden **Alfabético**, escribe una búsqueda y agrega el producto que quieres.
3. Cambia a **Con IA**, escribe otra búsqueda distinta y agrega el producto.
4. Repite al menos 5 veces por modo y abre la página de comparativa.

Con pocas mediciones la comparación no es confiable; la página avisa cuando hay menos de 5 por modo.

## 5. Archivos

| Archivo | Para qué sirve |
|---|---|
| `application/search/TrieAutocompletado.java` | Árbol de prefijos con búsqueda recursiva |
| `application/search/RankingBusqueda.java` | Perfil, puntaje, reordenar, recomendar |
| `application/search/MetricasBusqueda.java` | Registro y resumen de la comparativa |
| `infrastructure/web/TiendaAndSupportEndpointsRegistrar.java` | Endpoints `/api/tienda/*` |
| `frontend_corporativo/historial_busqueda.js` | Historial del navegador y medición |
| `frontend_corporativo/tienda.js`, `portada.js` | Buscador, interruptor de modo, "Para ti" |
| `frontend_corporativo/comparativa_busqueda.html` | Resultados de la comparativa |
