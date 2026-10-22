import cv2
import numpy as np


TAMANO_ESTANDAR = (160, 160)

_clasificador_rostros = cv2.CascadeClassifier(
    cv2.data.haarcascades + "haarcascade_frontalface_default.xml"
)


def capturar_rostro(frame):
    if frame is None:
        return None

    gris = cv2.cvtColor(frame, cv2.COLOR_BGR2GRAY)

    rostros = _clasificador_rostros.detectMultiScale(
        gris,
        scaleFactor=1.1,  
        minNeighbors=5,   
        minSize=(80, 80) 
    )

    if len(rostros) == 0:
        return None

   
    x, y, w, h = max(rostros, key=lambda r: r[2] * r[3])
    rostro_recortado = frame[y:y + h, x:x + w]
    rostro_final = cv2.resize(rostro_recortado, TAMANO_ESTANDAR)

    return rostro_final


def dibujar_deteccion(frame):
    gris = cv2.cvtColor(frame, cv2.COLOR_BGR2GRAY)
    rostros = _clasificador_rostros.detectMultiScale(gris, 1.1, 5, minSize=(80, 80))

    frame_copia = frame.copy()
    for (x, y, w, h) in rostros:
        cv2.rectangle(frame_copia, (x, y), (x + w, y + h), (0, 255, 0), 2)

    return frame_copia



if __name__ == "__main__":
    cap = cv2.VideoCapture(0)

    if not cap.isOpened():
        print("No se pudo acceder a la cámara. Verifica los permisos o el índice (0).")
    else:
        print("Presiona 'q' para salir de la prueba.")
        while True:
            ret, frame = cap.read()
            if not ret:
                break
            frame_visual = dibujar_deteccion(frame)
            cv2.imshow("Prueba - Deteccion de Rostro (presiona q para salir)", frame_visual)
            rostro = capturar_rostro(frame)
            if rostro is not None:
                cv2.imshow("Rostro recortado y listo (160x160)", rostro)

            if cv2.waitKey(1) & 0xFF == ord('q'):
                break

        cap.release()
        cv2.destroyAllWindows()