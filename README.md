# Alicorp v2 - Sistema Corporativo con Reconocimiento Facial

Este es el sistema de intranet y landing page corporativa de Alicorp, el cual incluye un panel de administración (Dashboard) y un sistema de Login de Alta Seguridad protegido por Reconocimiento Facial y Supabase.

## Arquitectura del Proyecto

- **Frontend:** HTML5, CSS3 (Vanilla), JavaScript. Interfaz responsiva y amigable.
- **Backend:** Python (Flask).
- **IA Biometría:** Dlib / `face_recognition` (Extracción de Encodings de 128 dimensiones).
- **Base de Datos:** Supabase (PostgreSQL).

## Requisitos Previos

Para ejecutar el proyecto en tu máquina local (`localhost`), necesitas:

1. **Python 3.9 o superior** instalado.
2. **Cámara Web** activa conectada a la PC (necesaria para el login y registro facial).
3. **Cuenta de Supabase** (Base de datos gratuita en la nube).
4. **Git** (Opcional, para control de versiones).

---

## Guía de Instalación y Ejecución Local

### Paso 1: Clonar el Repositorio
Si aún no tienes los archivos, clónalos desde GitHub:
```bash
git clone https://github.com/Rakkiel-cmd/Alicorpt-v2.git
cd "Alicorpt-v2"
```

### Paso 2: Crear un Entorno Virtual (Opcional pero recomendado)
Es buena práctica instalar las librerías en un entorno virtual para no chocar con otros proyectos.
```bash
python -m venv venv
# En Windows:
venv\Scripts\activate
# En Mac/Linux:
source venv/bin/activate
```

### Paso 3: Instalar Dependencias
Instala todas las librerías necesarias ejecutando:
```bash
python instalar.py
```
### Paso 4: Configurar la Base de Datos (Supabase)
El proyecto usa Supabase para guardar los usuarios y las firmas matemáticas de sus rostros (encodings), **NO** guarda las fotos por motivos de seguridad.

1. En la carpeta principal del proyecto, crea un archivo llamado `.env` (si no existe).
2. Agrega tus credenciales de Supabase de la siguiente manera:
```env
SUPABASE_URL=https://tu-proyecto.supabase.co
SUPABASE_KEY=tu-clave-anon-publica
```
3. Asegúrate de tener una tabla llamada `administradores` en Supabase con las columnas:
   - `id` (int8 o UUID, autogenerado)
   - `usuario` (text)
   - `password` (text)
   - `face_encoding` (json , para guardar el vector matemático)

### Paso 5: ¿Dónde se guardan los rostros?
El sistema tiene dos capas:
- **Principal (Nube):** Los rostros se escanean en la web, el servidor Flask extrae la "firma matemática" (128 números) y la guarda directamente en Supabase. Las fotos **NO** se guardan como archivo `.jpg` ni `.png` por seguridad y ahorro de espacio.
- **Caché Local:** Al iniciar el servidor, este descarga las firmas matemáticas de Supabase a la memoria RAM de Python para hacer comparaciones ultra-rápidas durante el login, sin consumir recursos de la red en tiempo real.

*(Si por alguna razón necesitas usar fotos físicas locales en lugar de la web, puedes colocarlas en la carpeta `rostros_autorizados/`, el sistema intentará leerlas también).*

### Paso 6: Ejecutar el Servidor
Enciende el backend ejecutando:
```bash
python server.py
```
*(También puedes usar `gunicorn server:app` si estás en un entorno de producción como Linux).*

### Paso 7: Acceder al Sistema
Abre tu navegador web y entra a la dirección local predeterminada de Flask:
👉 **[http://localhost:5000](http://localhost:5000)**

*(Nota: Si el terminal te indica que el servidor se abrió en otro puerto como el 8000 o el 10000, simplemente usa esa dirección en su lugar).*

1. **Página Principal:** Verás la landing page institucional.
2. **Registro:** Ve a "Personal Autorizado" > "✚ Nuevo Registro". Ingresa un usuario, contraseña y escanea tu rostro con la cámara.
3. **Login:** Ve a "Personal Autorizado" y haz login con tu rostro o con contraseña + rostro.
4. **Dashboard:** Al pasar la seguridad, accederás al panel de control con lectura de base de datos masiva (optimizada).

---
**Consideraciones para Producción (Render):**
En servidores gratuitos de Render (512MB RAM), el modelo facial y el procesamiento masivo del dashboard (100k registros) pueden consumir mucha memoria. Por ello, hemos creado el archivo `Procfile` y hemos optimizado la lectura de datos mediante fragmentación (*chunking*) para mantener todo fluido.
