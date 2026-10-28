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
import random
import re
import threading
import unicodedata
import warnings

import numpy as np
from sklearn.feature_extraction.text import TfidfVectorizer
from sklearn.neural_network import MLPClassifier


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


class BuscadorInteligente:
    def __init__(self, productos):
        self.productos = productos
        self.docs = [
            normalizar(f"{p['nombre']} {p['marca']} {p['categoria']} {p['subcategoria']} {p.get('etiquetas', '')}")
            for p in productos
        ]
        self.palabras_nombre = [set(normalizar(p["nombre"]).split()) for p in productos]
        self.vectorizador = TfidfVectorizer(analyzer="char_wb", ngram_range=(2, 4), sublinear_tf=True, max_features=9000, dtype=np.float32)
        self.matriz = self.vectorizador.fit_transform(self.docs)          # productos x caracteristicas (dispersa)
        self.subcategorias = sorted({p["subcategoria"] for p in productos})
        self.indice_sub = {s: i for i, s in enumerate(self.subcategorias)}
        self.sub_de_producto = np.array([self.indice_sub[p["subcategoria"]] for p in productos])
        self.categoria_de_sub = {}
        for p in productos:
            self.categoria_de_sub[self.indice_sub[p["subcategoria"]]] = p["categoria"]
        self.red = None
        self.entrenada = False
        self.candado = threading.Lock()

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
