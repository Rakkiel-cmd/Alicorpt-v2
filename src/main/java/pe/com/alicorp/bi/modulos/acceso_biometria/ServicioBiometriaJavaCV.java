package pe.com.alicorp.bi.modulos.acceso_biometria;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.bytedeco.javacpp.indexer.FloatIndexer;
import org.bytedeco.opencv.opencv_core.*;
import org.bytedeco.opencv.opencv_objdetect.FaceDetectorYN;
import org.bytedeco.opencv.opencv_objdetect.FaceRecognizerSF;

import static org.bytedeco.opencv.global.opencv_core.CV_8UC1;
import static org.bytedeco.opencv.global.opencv_imgcodecs.*;
import static org.bytedeco.opencv.global.opencv_imgproc.*;

import java.io.File;
import java.util.*;

/**
 * Servicio de Visión Artificial y Biometría 128-D en Java utilizando JavaCV (OpenCV DNN).
 * Usa detección facial YuNet (FaceDetectorYN) + alineación + extracción de embedding neural
 * de 128 dimensiones con SFace (FaceRecognizerSF) sobre modelos ONNX locales.
 * Si los archivos ONNX no están presentes, aplica fallback determinístico sobre ROI central.
 */
public class ServicioBiometriaJavaCV {

    public static final double UMBRAL_DISTANCIA = 0.78;
    public static final double UMBRAL_COSENO_SFACE = 0.363;
    public static final int DIMENSIONES = 128;

    private static final String RUTA_YUNET = "models/face_detection_yunet_2023mar.onnx";
    private static final String RUTA_SFACE = "models/face_recognition_sface_2021dec.onnx";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final FaceDetectorYN detectorYuNet;
    private final FaceRecognizerSF reconocedorSFace;
    private final boolean motorNeuralActivo;
    private final Object lockDnn = new Object();

    public ServicioBiometriaJavaCV() {
        FaceDetectorYN det = null;
        FaceRecognizerSF rec = null;
        boolean ok = false;
        File fYunet = new File(RUTA_YUNET);
        File fSface = new File(RUTA_SFACE);
        if (fYunet.exists() && fSface.exists()) {
            try {
                det = FaceDetectorYN.create(fYunet.getAbsolutePath(), "", new Size(320, 320), 0.60f, 0.30f, 5000, 0, 0);
                rec = FaceRecognizerSF.create(fSface.getAbsolutePath(), "", 0, 0);
                ok = (det != null && rec != null);
                if (ok) {
                    System.out.println("[OK] Biometría Neural JavaCV activa (YuNet + SFace 128-D ONNX).");
                }
            } catch (Exception e) {
                System.err.println("[!] No se pudo inicializar YuNet/SFace ONNX, usando fallback ROI: " + e.getMessage());
                det = null;
                rec = null;
                ok = false;
            }
        } else {
            System.out.println("[!] Modelos ONNX no encontrados en models/. Usando extractor espacial ROI.");
        }
        this.detectorYuNet = det;
        this.reconocedorSFace = rec;
        this.motorNeuralActivo = ok;
    }

    public boolean isMotorNeuralActivo() {
        return motorNeuralActivo;
    }

    /**
     * Detecta y alinea el rostro con YuNet y extrae el embedding neural de 128 dimensiones con SFace.
     */
    public double[] extraerEmbedding128D(String imagenBase64) {
        if (imagenBase64 == null || imagenBase64.isBlank()) {
            return null;
        }
        try {
            int coma = imagenBase64.indexOf(',');
            String payload = (coma >= 0 ? imagenBase64.substring(coma + 1) : imagenBase64).replaceAll("\\s+", "");
            if (payload.isEmpty()) {
                return null;
            }
            byte[] bytes = Base64.getDecoder().decode(payload);
            if (bytes.length < 32) {
                return null;
            }

            try (Mat raw = new Mat(1, bytes.length, CV_8UC1)) {
                raw.data().put(bytes);
                try (Mat imgBgr = imdecode(raw, IMREAD_COLOR)) {
                    if (imgBgr == null || imgBgr.empty()) {
                        return null;
                    }
                    if (motorNeuralActivo) {
                        return extraerConSFace(imgBgr);
                    }
                    return extraerFallbackRoi(imgBgr);
                }
            }
        } catch (Exception e) {
            return null;
        }
    }

    private double[] extraerConSFace(Mat imgBgr) {
        synchronized (lockDnn) {
            try (Mat faces = new Mat();
                 Mat aligned = new Mat();
                 Mat feature = new Mat()) {
                detectorYuNet.setInputSize(new Size(imgBgr.cols(), imgBgr.rows()));
                detectorYuNet.detect(imgBgr, faces);
                if (faces.rows() < 1) {
                    return null;
                }

                try (Mat rostroPrincipal = faces.row(0)) {
                    reconocedorSFace.alignCrop(imgBgr, rostroPrincipal, aligned);
                }
                if (aligned.empty()) {
                    return null;
                }
                reconocedorSFace.feature(aligned, feature);
                if (feature.empty() || feature.cols() < DIMENSIONES) {
                    return null;
                }

                double[] vector = new double[DIMENSIONES];
                double sumaCuadrados = 0.0;
                try (FloatIndexer idx = feature.createIndexer()) {
                    for (int d = 0; d < DIMENSIONES; d++) {
                        double val = idx.get(0, d);
                        vector[d] = val;
                        sumaCuadrados += val * val;
                    }
                }

                double normActual = Math.sqrt(sumaCuadrados);
                if (!Double.isFinite(normActual) || normActual <= 1e-6) {
                    return null;
                }
                // Normalizar a escala L2 ~ 1.410 para compatibilidad con visor 128-D manteniendo invarianza angular
                double factor = 1.410 / normActual;
                for (int d = 0; d < DIMENSIONES; d++) {
                    vector[d] = Math.round((vector[d] * factor) * 1000000.0) / 1000000.0;
                }
                return vector;
            }
        }
    }

    private double[] extraerFallbackRoi(Mat imgBgr) {
        // Recortar ROI central del óvalo guía (~48% ancho x 76% alto) para minimizar ruido del fondo
        int w = imgBgr.cols();
        int h = imgBgr.rows();
        int rw = Math.max(40, (int) Math.round(w * 0.48));
        int rh = Math.max(40, (int) Math.round(h * 0.76));
        int rx = Math.max(0, (w - rw) / 2);
        int ry = Math.max(0, (h - rh) / 2);

        try (Mat roi = new Mat(imgBgr, new Rect(rx, ry, Math.min(rw, w - rx), Math.min(rh, h - ry)));
             Mat img160 = new Mat();
             Mat imgGris = new Mat()) {
            resize(roi, img160, new Size(160, 160));
            cvtColor(img160, imgGris, COLOR_BGR2GRAY);
            equalizeHist(imgGris, imgGris);

            double[] vector = new double[DIMENSIONES];
            org.bytedeco.javacpp.BytePointer ptr = imgGris.data();
            int totalPixeles = 160 * 160;
            int bloque = totalPixeles / DIMENSIONES;

            double sumaCuadrados = 0.0;
            for (int d = 0; d < DIMENSIONES; d++) {
                double acum = 0.0;
                int inicio = d * bloque;
                for (int p = 0; p < bloque; p++) {
                    int val = ptr.get(inicio + p) & 0xFF;
                    acum += (val - 127.5) / 127.5;
                }
                vector[d] = Math.round((acum / bloque) * 1000000.0) / 1000000.0;
                sumaCuadrados += vector[d] * vector[d];
            }

            double normActual = Math.sqrt(sumaCuadrados);
            if (normActual <= 1e-6) {
                return null;
            }
            double factor = 1.410 / normActual;
            for (int d = 0; d < DIMENSIONES; d++) {
                vector[d] = Math.round((vector[d] * factor) * 1000000.0) / 1000000.0;
            }
            return vector;
        }
    }

    /**
     * Similitud del Coseno invariante a escala entre dos vectores 128-D.
     */
    public double calcularSimilitudCoseno(double[] a, double[] b) {
        if (a == null || b == null || a.length != DIMENSIONES || b.length != DIMENSIONES) {
            return -1.0;
        }
        double dot = 0.0, normA = 0.0, normB = 0.0;
        for (int i = 0; i < DIMENSIONES; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        double denom = Math.sqrt(normA) * Math.sqrt(normB);
        return denom > 1e-9 ? dot / denom : -1.0;
    }

    /**
     * Calcula la distancia euclidiana nativa en arreglos double[].
     */
    public double calcularDistanciaEuclidiana(double[] a, double[] b) {
        if (a == null || b == null || a.length != DIMENSIONES || b.length != DIMENSIONES) {
            return Double.MAX_VALUE;
        }
        double suma = 0.0;
        for (int i = 0; i < DIMENSIONES; i++) {
            double diff = a[i] - b[i];
            suma += diff * diff;
        }
        return Math.sqrt(suma);
    }

    /**
     * Evalúa si dos embeddings corresponden a la misma persona usando el umbral neural SFace
     * (coseno >= 0.363) o distancia euclidiana en modo fallback.
     */
    public boolean esMatchBiometrico(double[] vivo, double[] guardado) {
        if (vivo == null || guardado == null) return false;
        if (motorNeuralActivo) {
            return calcularSimilitudCoseno(vivo, guardado) >= UMBRAL_COSENO_SFACE;
        }
        return calcularDistanciaEuclidiana(vivo, guardado) <= UMBRAL_DISTANCIA;
    }

    /**
     * Calcula una métrica de puntuación superior (mejor = mayor) para seleccionar el mejor candidato.
     */
    public double puntajeSimilitud(double[] vivo, double[] guardado) {
        if (motorNeuralActivo) {
            return calcularSimilitudCoseno(vivo, guardado);
        }
        double d = calcularDistanciaEuclidiana(vivo, guardado);
        return d == Double.MAX_VALUE ? -Double.MAX_VALUE : -d;
    }

    /**
     * Convierte la similitud biométrica en un porcentaje legible (85% - 99.8% en match neural SFace).
     */
    public double calcularPorcentajeConfianza(double[] vivo, double[] guardado) {
        if (motorNeuralActivo) {
            double cos = calcularSimilitudCoseno(vivo, guardado);
            if (cos < UMBRAL_COSENO_SFACE) {
                return Math.max(0.0, Math.round(Math.max(0.0, cos) * 1000.0) / 10.0);
            }
            double ratio = Math.min(1.0, (cos - UMBRAL_COSENO_SFACE) / (0.82 - UMBRAL_COSENO_SFACE));
            double pct = 85.0 + ratio * 14.8;
            return Math.round(Math.min(99.8, pct) * 10.0) / 10.0;
        }
        double dist = calcularDistanciaEuclidiana(vivo, guardado);
        return Math.max(70.0, Math.min(99.9, Math.round((1.0 - dist / 1.5) * 1000.0) / 10.0));
    }

    public double compararVectores(double[] a, double[] b) {
        return calcularDistanciaEuclidiana(a, b);
    }

    public String serializarVectorJson(double[] vector) {
        if (vector == null || vector.length != DIMENSIONES) return null;
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(vector[i]);
        }
        sb.append(']');
        return sb.toString();
    }

    public double calcularNormaL2(double[] vector) {
        if (vector == null) return 0.0;
        double suma = 0.0;
        for (double v : vector) {
            suma += v * v;
        }
        return Math.round(Math.sqrt(suma) * 10000.0) / 10000.0;
    }

    public double[] parsearVectorJson(String jsonArray) {
        if (jsonArray == null || jsonArray.isBlank()) return null;
        try {
            List<Double> lista = MAPPER.readValue(jsonArray, new TypeReference<List<Double>>() {});
            if (lista.size() != DIMENSIONES) return null;
            double[] arr = new double[DIMENSIONES];
            for (int i = 0; i < DIMENSIONES; i++) {
                arr[i] = lista.get(i);
            }
            return arr;
        } catch (Exception e) {
            return null;
        }
    }
}
