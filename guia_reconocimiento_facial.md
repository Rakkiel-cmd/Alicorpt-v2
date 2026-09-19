# Guía de Trabajo: Login con Reconocimiento Facial

Este documento divide el trabajo del **Subproyecto 1** (Login con Reconocimiento Facial) para que los 3 integrantes puedan avanzar en paralelo sin pisarse el código. 

## Objetivo
Implementar un sistema de seguridad biométrica usando Python y OpenCV, que valide si el rostro de la cámara pertenece a un administrador autorizado antes de darle acceso al dashboard de predicciones de ventas (Subproyecto 2).

---

### Integrante 1: Captura y Preprocesamiento de Rostros (Computer Vision)
**Responsabilidad:** Leer la cámara, detectar dónde hay un rostro y enviarlo limpio.
- **Herramientas:** `cv2` (OpenCV) y `mediapipe` o `haarcascades`.
- **Tareas:**
  1. Crear una función (ej. `capturar_rostro(frame)`) que reciba una imagen o lea directamente de la cámara.
  2. Aplicar un modelo de detección facial para encontrar las coordenadas del rostro (bounding box).
  3. Recortar la imagen para que solo contenga el cuadrado del rostro.
  4. Redimensionar el rostro a un tamaño estándar (ej. 160x160 píxeles) y convertirlo a formato estándar según lo pida el Integrante 2.
- **Entregable:** Un script `captura.py` con una función que devuelve un array de numpy (la foto del rostro lista para analizar).

### Integrante 2: Modelo de Reconocimiento Facial (Machine Learning)
**Responsabilidad:** Recibir la foto del rostro del Integrante 1 y decir a quién pertenece (o si es el Administrador).
- **Herramientas:** `face_recognition` (librería de Python) o extraer embeddings con un modelo pre-entrenado (`DeepFace`, `dlib`).
- **Tareas:**
  1. Crear una carpeta llamada `rostros_autorizados/` donde se guardará una foto tuya (o del administrador de prueba).
  2. Crear una función (ej. `validar_rostro(rostro_capturado)`) que reciba el rostro del Integrante 1.
  3. Cargar la foto autorizada y calcular sus características (embeddings/encodings).
  4. Calcular las características del rostro capturado y compararlos usando distancia euclidiana o la función de la librería elegida.
  5. Retornar `True` si es el administrador o `False` si es un impostor.
- **Entregable:** Un script `modelo_facial.py` que exporte la función de validación.

### Integrante 3: Integración en Streamlit (Frontend/Backend)
**Responsabilidad:** Unir los scripts de los Integrantes 1 y 2 dentro de `app.py` y gestionar el flujo de la aplicación web.
- **Herramientas:** `streamlit`, `cv2`.
- **Tareas:**
  1. Ir al archivo `app.py` en la sección de "VISTA LOGIN DE ADMINISTRADOR".
  2. Capturar la imagen que viene de `st.camera_input` (es un objeto tipo archivo, hay que convertirlo a una imagen leíble por OpenCV con `PIL` o `numpy`).
  3. Pasar la imagen por la función del **Integrante 1** para limpiarla y extraer el rostro.
  4. Pasar el rostro extraído por la función del **Integrante 2** para validarla.
  5. Si la función del Integrante 2 devuelve `True`, cambiar `st.session_state.view` para ir a la vista del Dashboard (Subproyecto 2) y cargar el CSV.
  6. Si devuelve `False`, mostrar un mensaje de `st.error("Acceso denegado. Rostro no reconocido.")`.
- **Entregable:** La versión final de `app.py` con la lógica de login conectada y validando el acceso.
