FROM python:3.11-slim

# Instalar herramientas de compilacion para dlib, opencv y face_recognition
RUN apt-get update && apt-get install -y --no-install-recommends \
    build-essential \
    cmake \
    libgl1 \
    libglib2.0-0 \
    curl \
    && rm -rf /var/lib/apt/lists/*

# Configurar usuario no-root requerido por Hugging Face Spaces (UID 1000)
RUN useradd -m -u 1000 user
USER user
ENV HOME=/home/user \
    PATH=/home/user/.local/bin:$PATH \
    PYTHONUNBUFFERED=1

WORKDIR $HOME/app

# Instalar dependencias de Python
COPY --chown=user requirements.txt .
RUN pip install --no-cache-dir --user -r requirements.txt

# Copiar el codigo del proyecto
COPY --chown=user . .

# Puerto estandar de Hugging Face Spaces
EXPOSE 7860

# Iniciar servidor con Gunicorn en el puerto 7860
CMD ["gunicorn", "server:app", "--bind", "0.0.0.0:7860", "--workers", "2", "--timeout", "120"]
