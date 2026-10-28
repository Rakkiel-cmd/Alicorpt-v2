"""
backend/buscador_inteligente.py
Buscador inteligente de productos con una red neuronal ligera (scikit-learn).

Cómo funciona (explicado fácil):
1. TF-IDF de caracteres (TfidfVectorizer): convierte cada producto y cada búsqueda en números.
   Como usa pedacitos de 2 a 4 letras, entiende errores de escritura ("espageti" ~ "spaghetti").
2. Red neuronal (MLPClassifier): aprende a adivinar la INTENCIÓN de la búsqueda, es decir,
   a qué tipo de producto se refiere. Por ejemplo "para freir" -> Aceite vegetal.
   Se entrena con miles de búsquedas de práctica creadas a partir del propio catálogo.
3. El puntaje final de cada producto mezcla tres cosas:
      similitud de texto (coseno)  +  probabilidad de la red neuronal  +  palabras exactas encontradas.
"""
import hashlib
import os
import random
import re
import threading
import unicodedata
import warnings

import numpy as np

# Modelo ya entrenado (se crea con scripts/entrenar_buscador.py). Así el servidor NO entrena nada al arrancar:
# solo lo lee, usa muy poca memoria y funciona rápido hasta en Render gratis.
RUTA_MODELO = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "data", "buscador_modelo.npz")


def huella_catalogo(productos):
    """Resumen corto de los SKU del catálogo; sirve para saber si el modelo guardado es de este mismo catálogo."""
    return hashlib.md5("|".join(sorted(str(p["sku"]) for p in productos)).encode()).hexdigest()


def normalizar(texto):
    """Pasa a minúsculas, quita tildes y signos: 'Aceite Vegetal, 1 L' -> 'aceite vegetal 1 l'."""
    texto = unicodedata.normalize("NFKD", str(texto).lower())
    texto = "".join(c for c in texto if not unicodedata.combining(c))
    texto = re.sub(r"[^a-z0-9ñ ]+", " ", texto)
    return re.sub(r"\s+", " ", texto).strip()


# Palabras que no ayudan a buscar ("para", "de", "el"...). Se ignoran en las búsquedas.
PALABRAS_VACIAS = {"para", "de", "del", "la", "el", "los", "las", "un", "una", "con", "en", "y", "o", "por", "al", "que", "me", "mi", "quiero", "necesito", "busco", "algo"}


def normalizar_consulta(texto):
    """Normaliza la búsqueda y le quita las palabras vacías ('pan para el desayuno' -> 'pan desayuno')."""
    palabras = [w for w in normalizar(texto).split() if w not in PALABRAS_VACIAS]
    return " ".join(palabras)


def _con_errores(texto, azar):
    """Simula un error de tipeo: borra o intercambia una letra."""
    if len(texto) < 5:
        return texto
    i = azar.randrange(1, len(texto) - 1)
    if azar.random() < 0.5:
        return texto[:i] + texto[i + 1:]
    return texto[:i] + texto[i + 1] + texto[i] + texto[i + 2:]


class VectorizadorLigero:
    """Hace lo mismo que TfidfVectorizer(analyzer='char_wb', ngram_range=(2,4), sublinear_tf=True) pero ya entrenado y solo con numpy."""

    def __init__(self, vocabulario, idf):
        self.vocab = {str(t): i for i, t in enumerate(vocabulario)}
        self.idf = np.asarray(idf, dtype=np.float32)
        self.n = len(self.vocab)

    def transform(self, textos):
        from scipy import sparse
        filas, cols, vals = [], [], []
        for fila, texto in enumerate(textos):
            cuenta = {}
            for palabra in texto.split():
                w = " " + palabra + " "
                for n in (2, 3, 4):
                    for i in range(len(w) - n + 1):
                        j = self.vocab.get(w[i:i + n])
                        if j is not None:
                            cuenta[j] = cuenta.get(j, 0) + 1
            if not cuenta:
                continue
            idx = np.fromiter(cuenta.keys(), dtype=np.int64)
            tf = 1.0 + np.log(np.fromiter(cuenta.values(), dtype=np.float64))
            v = tf * self.idf[idx]
            norma = np.sqrt((v * v).sum())
            if norma > 0:
                v = v / norma
            filas.extend([fila] * len(idx))
            cols.extend(idx.tolist())
            vals.extend(v.tolist())
        return sparse.csr_matrix((np.array(vals, dtype=np.float32), (filas, cols)), shape=(len(textos), self.n), dtype=np.float32)


class RedLigera:
    """La red neuronal ya entrenada (1 capa oculta ReLU + softmax), calculada solo con numpy."""

    def __init__(self, w1, b1, w2, b2, clases):
        self.w1, self.b1, self.w2, self.b2 = w1, b1, w2, b2
        self.classes_ = np.asarray(clases, dtype=np.int64)

    def predict_proba(self, x):
        oculta = np.maximum(x @ self.w1 + self.b1, 0.0)
        z = np.asarray(oculta @ self.w2 + self.b2)
        z = z - z.max(axis=1, keepdims=True)
        e = np.exp(z)
        return e / e.sum(axis=1, keepdims=True)


class BuscadorInteligente:
    def __init__(self, productos):
        self.productos = productos
        self.docs = [
            normalizar(f"{p['nombre']} {p['marca']} {p['categoria']} {p['subcategoria']} {p.get('etiquetas', '')}")
            for p in productos
        ]
        self.palabras_nombre = [set(normalizar(p["nombre"]).split()) for p in productos]
        self.red = None
        self.entrenada = False
        self.candado = threading.Lock()
        self.origen = "entrenado en el servidor"
        if not self._cargar_modelo_guardado():
            from sklearn.feature_extraction.text import TfidfVectorizer
            self.vectorizador = TfidfVectorizer(analyzer="char_wb", ngram_range=(2, 4), sublinear_tf=True, max_features=9000, dtype=np.float32)
            self.matriz = self.vectorizador.fit_transform(self.docs)          # productos x caracteristicas (dispersa)
        self.subcategorias = sorted({p["subcategoria"] for p in productos})
        self.indice_sub = {s: i for i, s in enumerate(self.subcategorias)}
        self.sub_de_producto = np.array([self.indice_sub[p["subcategoria"]] for p in productos])
        self.categoria_de_sub = {}
        for p in productos:
            self.categoria_de_sub[self.indice_sub[p["subcategoria"]]] = p["categoria"]

    # ------------------------------------------------------------------ modelo guardado
    def _cargar_modelo_guardado(self):
        """Lee data/buscador_modelo.npz si existe y corresponde a este mismo catálogo. Devuelve True si lo logró."""
        try:
            if not os.path.exists(RUTA_MODELO):
                return False
            from scipy import sparse
            with np.load(RUTA_MODELO, allow_pickle=False) as m:
                if str(m["huella"]) != huella_catalogo(self.productos) or int(m["n_productos"]) != len(self.productos):
                    print("[TIENDA] El modelo guardado no coincide con el catálogo; se entrenará de nuevo.")
                    return False
                self.vectorizador = VectorizadorLigero(m["vocabulario"], m["idf"])
                self.matriz = sparse.csr_matrix((m["mat_data"], m["mat_indices"], m["mat_indptr"]), shape=tuple(m["mat_shape"]), dtype=np.float32)
                self.red = RedLigera(m["w1"].astype(np.float32), m["b1"].astype(np.float32), m["w2"].astype(np.float32), m["b2"].astype(np.float32), m["clases"])
            self.entrenada = True
            self.origen = "modelo guardado (data/buscador_modelo.npz)"
            return True
        except Exception as e:
            print("[TIENDA] No se pudo leer el modelo guardado:", e)
            return False

    def guardar_modelo(self, ruta=RUTA_MODELO):
        """Guarda el vectorizador y la red ya entrenados (los usa scripts/entrenar_buscador.py)."""
        self.entrenar()
        v = self.vectorizador
        vocab = np.array(sorted(v.vocabulary_, key=v.vocabulary_.get))
        m = self.matriz.tocsr()
        red = self.red
        np.savez_compressed(
            ruta, huella=np.array(huella_catalogo(self.productos)), n_productos=np.array(len(self.productos)),
            vocabulario=vocab, idf=v.idf_.astype(np.float32),
            mat_data=m.data.astype(np.float32), mat_indices=m.indices.astype(np.int32), mat_indptr=m.indptr.astype(np.int32), mat_shape=np.array(m.shape),
            w1=red.coefs_[0].astype(np.float16), b1=red.intercepts_[0].astype(np.float32),
            w2=red.coefs_[1].astype(np.float16), b2=red.intercepts_[1].astype(np.float32), clases=red.classes_,
        )

    # ------------------------------------------------------------------ entrenamiento
    def _consultas_de_practica(self, cantidad=5000, semilla=7):
        """Crea búsquedas de ejemplo (con sinónimos y errores) para enseñarle a la red."""
        azar = random.Random(semilla)
        por_sub = {}
        for p in self.productos:
            por_sub.setdefault(p["subcategoria"], []).append(p)
        subs = list(por_sub)
        X, y = [], []
        for _ in range(cantidad):
            sub = azar.choice(subs)
            p = azar.choice(por_sub[sub])
            palabras_tag = normalizar(p.get("etiquetas", "")).split()
            estilo = azar.random()
            if estilo < 0.20:
                q = sub                                                     # "aceite vegetal"
            elif estilo < 0.35:
                q = f"{sub} {p['marca']}"                                    # "aceite vegetal primor"
            elif estilo < 0.50 and palabras_tag:
                q = " ".join(azar.sample(palabras_tag, k=min(len(palabras_tag), azar.randint(1, 3))))   # "freir cocina"
            elif estilo < 0.62:
                q = f"{sub} {p['presentacion']}"                             # "aceite vegetal 1 l"
            elif estilo < 0.82:
                largas = [w for w in sub.split() if len(normalizar(w)) > 3 and normalizar(w) not in PALABRAS_VACIAS]
                q = azar.choice(largas) if largas else sub                   # una sola palabra: "aceite"
            else:
                q = f"{azar.choice(sub.split())} {p['marca']}"
            q = normalizar_consulta(q)
            if azar.random() < 0.30:
                q = _con_errores(q, azar)
            X.append(q)
            y.append(self.indice_sub[sub])
        return X, y

    def entrenar(self):
        with self.candado:
            if self.entrenada:
                return
            from sklearn.neural_network import MLPClassifier
            X, y = self._consultas_de_practica()
            red = MLPClassifier(hidden_layer_sizes=(64,), max_iter=25, batch_size=256, random_state=42, alpha=1e-4)
            with warnings.catch_warnings():
                warnings.simplefilter("ignore")     # evita el aviso 'no convergió' (es normal con pocas épocas)
                red.fit(self.vectorizador.transform(X), y)
            self.red = red
            self.entrenada = True

    # ------------------------------------------------------------------ búsqueda
    def intencion(self, consulta_norm):
        """Devuelve (vector de probabilidades por subcategoría, mejor subcategoría, probabilidad)."""
        if not self.entrenada or not consulta_norm:
            return None, None, 0.0
        q = self.vectorizador.transform([consulta_norm])
        probs_clases = self.red.predict_proba(q)[0]
        probs = np.zeros(len(self.subcategorias))
        probs[self.red.classes_] = probs_clases
        mejor = int(np.argmax(probs))
        return probs, mejor, float(probs[mejor])

    def buscar(self, consulta, limite=24):
        q = normalizar_consulta(consulta)
        if not q:
            return {"items": [], "total": 0, "motor": "vacio", "intencion": None}
        vector = self.vectorizador.transform([q])
        similitud = (self.matriz @ vector.T).toarray().ravel()          # coseno (los vectores ya están normalizados)

        palabras = [w for w in q.split() if len(w) > 1]
        if palabras:
            # cuántas palabras COMPLETAS de la búsqueda aparecen en el nombre del producto
            aciertos = np.array([sum(1 for w in palabras if w in nombre) for nombre in self.palabras_nombre]) / len(palabras)
        else:
            aciertos = np.zeros(len(self.productos))

        probs, mejor, prob_mejor = self.intencion(q)
        if probs is not None:
            impulso = probs[self.sub_de_producto]
            puntaje = 0.50 * similitud + 0.30 * impulso + 0.20 * aciertos
            motor = "red neuronal (MLP) + TF-IDF"
        else:
            puntaje = 0.75 * similitud + 0.25 * aciertos
            motor = "TF-IDF (la red neuronal aún se está entrenando)"

        # Si ni una sola palabra ni letras parecidas coinciden, la búsqueda no tiene sentido ("xyzabc")
        if float(similitud.max()) < 0.20 and float(aciertos.max()) == 0:
            return {"items": [], "total": 0, "motor": motor, "intencion": None}
        candidatos = [int(i) for i in np.argsort(-puntaje)[:400] if puntaje[i] > 0.05]
        orden = self._diversificar(candidatos, limite)
        items = [self.productos[i] for i in orden]

        # Solo mostramos la "intención detectada" si coincide con lo que realmente se encontró
        intencion = None
        if probs is not None and prob_mejor >= 0.30 and items:
            if items[0]["categoria"] == self.categoria_de_sub[mejor]:
                intencion = {
                    "subcategoria": items[0]["subcategoria"],
                    "categoria": items[0]["categoria"],
                    "confianza": round(prob_mejor, 3),
                }
        return {"items": items, "total": len(items), "motor": motor, "intencion": intencion}

    def _diversificar(self, candidatos, limite):
        """Primero muestra el mejor producto de cada (tipo, marca); luego el resto. Así no salen 24 tamaños de lo mismo."""
        vistos, primeros, resto = set(), [], []
        for i in candidatos:
            clave = (self.productos[i]["subcategoria"], self.productos[i]["marca"])
            if clave in vistos:
                resto.append(i)
            else:
                vistos.add(clave)
                primeros.append(i)
        return (primeros + resto)[:limite]
