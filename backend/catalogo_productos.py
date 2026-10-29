"""
backend/catalogo_productos.py
Catálogo de productos de la tienda Alicorp (más de 7 000 productos).

El catálogo REAL está en data/catalogo_real.json: productos reales de supermercado con su
marca, nombre, presentación, precio y foto verdaderos (obtenidos del catálogo público de Plaza Vea,
ver scripts/construir_catalogo_real.py). El stock es simulado.
Si ese archivo no existe, se usa un catálogo sintético de respaldo (generar_catalogo_sintetico).

- generar_catalogo()      -> lista de diccionarios (un producto por diccionario).
- exportar_csv(ruta)      -> escribe el catálogo en un archivo CSV (para subirlo a Supabase).

Para generar el CSV desde la carpeta del proyecto:
    python -m backend.catalogo_productos

El catálogo es determinista: siempre produce los mismos productos y los mismos SKU,
así el CSV, la base de datos y el buscador inteligente siempre coinciden.
Datos simulados con fines académicos.
"""
import csv
import json
import os
import random
import sys

# ------------------------------------------------------------------------------
# Marcas que se mezclan en el catálogo (datos simulados con fines académicos)
# ------------------------------------------------------------------------------
# factor de precio de la marca: <1 económica, >1 premium
FACTOR_MARCA = {
    "Primor": 1.00, "Cocinero": 0.95, "Capri": 0.97, "Friol": 1.05, "Cil": 0.92, "Sao": 1.08, "Alacena": 1.00,
    "Vita": 0.94, "Oliovita": 1.10, "Don Vittorio": 1.05, "Lavaggi": 0.92, "Molitalia": 1.00, "Nicolini": 0.95,
    "Anita": 0.90, "Costeño": 1.00, "Faraón": 1.02, "Valle Norte": 0.97, "Paisana": 0.96, "Cielo": 0.98,
    "Blanca Flor": 1.00, "Pillsbury": 1.12, "Universal": 0.92, "Royal": 1.10, "Hellmann's": 1.20, "Fanny": 0.95,
    "Kikkoman": 1.35, "Ajinomoto": 1.10, "Maggi": 1.05, "Florida": 1.00, "Campomar": 1.03, "Real": 0.95,
    "Coopmar": 0.93, "Don Fernando": 1.08, "Gloria": 1.00, "Laive": 1.05, "Pura Vida": 1.02, "Nestlé": 1.12,
    "Ideal": 0.92, "Bonlé": 0.94, "Inca Kola": 1.00, "Coca-Cola": 1.02, "Sprite": 1.00, "Fanta": 1.00,
    "San Luis": 0.95, "Frugos": 1.00, "Pulp": 0.97, "Gatorade": 1.15, "Powerade": 1.10, "Kola Real": 0.80,
    "Altomayo": 1.05, "Nescafé": 1.15, "Kirma": 0.95, "McColin's": 1.10, "Hornimans": 1.00, "Winters": 0.95,
    "Milo": 1.10, "Field": 0.95, "Costa": 1.00, "Oreo": 1.12, "Ritz": 1.10, "Donofrio": 1.05, "Lay's": 1.10,
    "Karinto": 0.92, "Pringles": 1.35, "Doritos": 1.10, "Kellogg's": 1.20, "Angel": 0.95, "Bimbo": 1.00,
    "Sapolio": 0.98, "Ace": 1.00, "Bolívar": 0.97, "Opal": 0.95, "Marsella": 0.92, "Patito": 1.00,
    "Clorox": 1.05, "Ayudín": 0.98, "Poett": 1.05, "Downy": 1.15, "Ariel": 1.12, "Magia Blanca": 1.00,
    "Elite": 1.00, "Suave": 0.98, "Scott": 1.10, "Noble": 0.90, "Head & Shoulders": 1.15, "Sedal": 1.00,
    "Pantene": 1.12, "Dove": 1.10, "Colgate": 1.00, "Palmolive": 0.98, "Nivea": 1.12, "Rexona": 1.00,
    "Protex": 1.00, "Johnson's": 1.15, "Huggies": 1.20, "Pampers": 1.20, "Dog Chow": 1.00, "Cat Chow": 1.00,
    "Pedigree": 1.10, "Whiskas": 1.10, "Ricocan": 0.90, "Mimaskot": 0.88, "San Fernando": 1.00, "Otto Kunz": 1.20,
    "Braedt": 1.10, "Redondos": 0.95, "Negrita": 1.00, "Sibarita": 1.05, "Casino": 1.00, "Cua Cua": 1.00,
    "Donuts": 1.00, "Winter's": 1.00, "Sublime": 1.15, "Triangulo": 1.10, "Princesa": 1.05, "Tip Top": 0.98,
}

# ------------------------------------------------------------------------------
# Tamaños comunes con su factor de precio (el precio no crece lineal con el tamaño)
# ------------------------------------------------------------------------------
T_ML = [("200 ml", 0.30), ("500 ml", 0.58), ("1 L", 1.00), ("2 L", 1.85), ("5 L", 4.30), ("Caja x 12 (1 L)", 10.8)]
T_G_PEQ = [("100 g", 0.30), ("200 g", 0.55), ("400 g", 1.00), ("1 kg", 2.30), ("Caja x 12 (400 g)", 10.8)]
T_G_MED = [("250 g", 0.55), ("500 g", 1.00), ("1 kg", 1.85), ("5 kg", 8.60), ("Caja x 20 (500 g)", 18.5)]
T_KG = [("500 g", 0.55), ("1 kg", 1.00), ("5 kg", 4.70), ("25 kg", 22.0), ("50 kg", 43.0)]
T_BEB = [("355 ml", 0.50), ("500 ml", 0.62), ("1 L", 1.00), ("1.5 L", 1.35), ("2.5 L", 2.00), ("3 L", 2.30), ("Pack x 6 (500 ml)", 3.4)]
T_UND = [("Unidad", 1.00), ("Pack x 3", 2.80), ("Pack x 6", 5.30), ("Caja x 12", 10.0), ("Caja x 24", 19.0)]
T_ROLLO = [("4 rollos", 1.00), ("12 rollos", 2.80), ("24 rollos", 5.30), ("40 rollos", 8.60)]
T_DET = [("400 g", 0.60), ("800 g", 1.00), ("2.6 kg", 2.90), ("4 kg", 4.30), ("Caja x 6 (800 g)", 5.50)]
T_LIM = [("500 ml", 0.55), ("1 L", 1.00), ("2 L", 1.80), ("3.8 L", 3.20), ("Caja x 12 (1 L)", 10.6)]
T_PERSONAL = [("90 ml", 0.45), ("180 ml", 0.80), ("375 ml", 1.00), ("750 ml", 1.75), ("Pack x 3 (375 ml)", 2.7)]
T_MASCOTA = [("500 g", 0.55), ("1 kg", 1.00), ("3 kg", 2.60), ("8 kg", 6.20), ("15 kg", 11.0), ("25 kg", 17.5)]

# ------------------------------------------------------------------------------
# Categorías. Cada subcategoría: (nombre base, precio de referencia en soles, palabras clave)
# Las palabras clave sirven para que el buscador inteligente entienda sinónimos
# (por ejemplo, "freír" encuentra aceites).
# ------------------------------------------------------------------------------
CATEGORIAS = [
    {"categoria": "Aceites y grasas", "emoji": "🫒", "grupos": [
        {"marcas": ["Primor", "Cocinero", "Capri", "Friol", "Cil", "Sao", "Alacena", "Vita", "Oliovita"], "tamanos": T_ML, "productos": [
            ("Aceite vegetal", 8.9, "cocina freír fritura guisar saltear cocinar aceite de cocina"),
            ("Aceite de girasol", 10.5, "cocina freír saludable ensalada aceite ligero"),
            ("Aceite de soya", 8.5, "cocina freír fritura económico"),
            ("Aceite de maíz", 11.2, "cocina freír horno repostería"),
            ("Aceite de oliva extra virgen", 29.9, "ensalada gourmet mediterráneo aderezo saludable"),
            ("Aceite de canola", 12.0, "cocina saludable hornear freír"),
        ]},
        {"marcas": ["Primor", "Alacena", "Sao", "Cocinero", "Don Fernando"], "tamanos": T_G_MED[:4], "productos": [
            ("Manteca vegetal", 7.5, "repostería pastelería hornear freír"),
            ("Margarina", 5.9, "pan untar repostería desayuno"),
            ("Mantequilla", 9.8, "pan untar repostería desayuno lácteo"),
        ]},
    ]},
    {"categoria": "Fideos y pastas", "emoji": "🍝", "grupos": [
        {"marcas": ["Don Vittorio", "Lavaggi", "Molitalia", "Nicolini", "Anita"], "tamanos": T_G_MED, "productos": [
            ("Spaghetti", 3.9, "pasta fideo almuerzo tallarín salsa italiana"),
            ("Tallarín", 3.9, "pasta fideo tallarines saltado almuerzo"),
            ("Fideo cabello de ángel", 3.6, "pasta sopa fideo fino caldo"),
            ("Fideo codito", 3.8, "pasta ensalada sopa macarrones"),
            ("Fideo tornillo", 3.8, "pasta ensalada sopa tirabuzón"),
            ("Fideo canuto", 3.7, "pasta sopa guiso"),
            ("Fideo pluma", 4.0, "pasta horno ensalada penne"),
            ("Fideo lasagna", 5.9, "pasta horno lasaña italiana"),
            ("Fideo fettuccine", 4.4, "pasta salsa alfredo italiana"),
            ("Fideo moño", 3.8, "pasta ensalada sopa"),
        ]},
    ]},
    {"categoria": "Arroz y menestras", "emoji": "🍚", "grupos": [
        {"marcas": ["Costeño", "Faraón", "Valle Norte", "Paisana", "Cielo", "Blanca Flor"], "tamanos": T_KG, "productos": [
            ("Arroz extra", 4.2, "almuerzo cereal guarnición básico arroz blanco"),
            ("Arroz superior", 3.9, "almuerzo cereal guarnición básico arroz blanco"),
            ("Arroz integral", 5.5, "saludable fibra cereal dieta"),
            ("Arroz parboilizado", 5.0, "almuerzo granos sueltos cereal"),
            ("Lenteja", 6.8, "menestra guiso proteína legumbre"),
            ("Frejol canario", 7.9, "menestra guiso proteína legumbre"),
            ("Frejol negro", 7.5, "menestra guiso proteína legumbre"),
            ("Pallar", 9.2, "menestra guiso proteína legumbre"),
            ("Garbanzo", 8.4, "menestra guiso ensalada legumbre"),
            ("Arveja partida", 5.9, "menestra sopa puré legumbre"),
            ("Habas secas", 7.2, "menestra guiso legumbre"),
            ("Quinua", 9.5, "superalimento cereal andino saludable proteína"),
            ("Trigo pelado", 4.9, "sopa guiso cereal"),
        ]},
    ]},
    {"categoria": "Harinas y repostería", "emoji": "🌾", "grupos": [
        {"marcas": ["Blanca Flor", "Pillsbury", "Universal", "Royal", "Nicolini"], "tamanos": T_G_MED, "productos": [
            ("Harina preparada", 4.8, "keke pan pastel hornear repostería esponjosa"),
            ("Harina sin preparar", 4.2, "pan masa pizza hornear repostería"),
            ("Harina de trigo", 4.0, "pan masa hornear cocina"),
            ("Maicena", 3.9, "espesar postres mazamorra repostería"),
            ("Polvo de hornear", 2.9, "keke pastel hornear repostería"),
            ("Levadura seca", 2.5, "pan masa hornear panadería"),
            ("Premezcla para keke", 6.2, "postre hornear repostería"),
            ("Premezcla para panqueques", 6.0, "desayuno panqueque hotcakes"),
            ("Harina de maíz", 4.4, "arepas tortillas cocina"),
        ]},
        {"marcas": ["Casino", "Cielo", "Paisana", "Costeño", "Blanca Flor"], "tamanos": T_KG[:4], "productos": [
            ("Azúcar rubia", 3.9, "endulzar postres té café azúcar morena"),
            ("Azúcar blanca", 4.2, "endulzar postres té café repostería"),
            ("Avena en hojuelas", 5.1, "desayuno cereal bebida saludable"),
            ("Sal de mesa", 1.6, "sazonar cocina condimento"),
        ]},
    ]},
    {"categoria": "Salsas y condimentos", "emoji": "🥫", "grupos": [
        {"marcas": ["Alacena", "Hellmann's", "Fanny", "Maggi", "Kikkoman", "Ajinomoto", "Don Fernando"], "tamanos": T_G_PEQ, "productos": [
            ("Mayonesa", 6.9, "sándwich ensalada aderezo salsa papas"),
            ("Kétchup", 5.9, "hamburguesa papas salsa tomate hot dog"),
            ("Mostaza", 4.9, "hot dog hamburguesa salsa aderezo"),
            ("Salsa de tomate", 3.9, "pasta tallarines guiso salsa roja"),
            ("Salsa de soya", 5.5, "chifa saltado sillao oriental"),
            ("Salsa de ají amarillo", 6.5, "criollo ceviche picante cebiche cocina peruana"),
            ("Salsa de rocoto", 6.5, "picante criollo cocina peruana"),
            ("Salsa tártara", 6.9, "pescado frituras aderezo"),
            ("Salsa golf", 6.4, "papas hamburguesa aderezo"),
            ("Aderezo para ensaladas", 6.0, "ensalada vinagreta aderezo"),
            ("Pasta de ají panca", 5.5, "guiso adobo criollo cocina peruana"),
            ("Vinagre blanco", 2.9, "ensalada limpieza cocina conserva"),
            ("Sazonador completo", 3.5, "condimento carnes guisos sazón"),
            ("Caldo de gallina en cubos", 4.5, "sopa guiso sazón caldo"),
            ("Comino molido", 3.2, "condimento guiso especias"),
            ("Orégano", 2.9, "condimento pizza especias"),
            ("Pimienta molida", 3.9, "condimento carnes especias"),
            ("Ajo molido", 3.4, "condimento guiso especias adobo"),
        ]},
    ]},
    {"categoria": "Conservas y enlatados", "emoji": "🥫", "grupos": [
        {"marcas": ["Florida", "Campomar", "Real", "Coopmar", "Don Fernando", "Gloria"], "tamanos": [("170 g", 0.62), ("300 g", 1.00), ("425 g", 1.35), ("Caja x 24 (170 g)", 14.5)], "productos": [
            ("Atún en aceite vegetal", 6.9, "pescado enlatado sándwich ensalada proteína"),
            ("Atún en agua", 6.9, "pescado enlatado ensalada dieta proteína"),
            ("Filete de atún", 8.9, "pescado enlatado ensalada proteína"),
            ("Sardina en tomate", 5.5, "pescado enlatado almuerzo proteína"),
            ("Jurel en salsa de tomate", 5.2, "pescado enlatado almuerzo proteína"),
            ("Durazno en almíbar", 7.5, "fruta enlatada postre"),
            ("Piña en rodajas", 7.0, "fruta enlatada postre"),
            ("Choclo desgranado", 6.5, "verdura enlatada ensalada"),
            ("Champiñones enteros", 7.9, "verdura enlatada pizza guiso"),
            ("Palmito en trozos", 11.5, "verdura enlatada ensalada gourmet"),
            ("Pimiento piquillo", 9.5, "verdura enlatada gourmet"),
            ("Frijoles en lata", 5.0, "menestra enlatada lista almuerzo"),
        ]},
    ]},
    {"categoria": "Lácteos y huevos", "emoji": "🥛", "grupos": [
        {"marcas": ["Gloria", "Laive", "Pura Vida", "Nestlé", "Ideal", "Bonlé"], "tamanos": [("170 g", 0.45), ("400 g", 1.00), ("1 L", 2.0), ("Caja x 24 (400 g)", 21.0)], "productos": [
            ("Leche evaporada entera", 4.4, "leche desayuno postre cocina lata"),
            ("Leche evaporada light", 4.5, "leche dieta desayuno lata"),
            ("Leche condensada", 6.9, "postre dulce manjar repostería"),
            ("Leche en polvo", 18.9, "leche desayuno bebé niños"),
            ("Leche fresca UHT", 4.9, "leche desayuno bebida niños"),
            ("Yogurt natural", 5.9, "desayuno postre saludable probióticos"),
            ("Yogurt de fresa", 5.9, "desayuno postre niños"),
            ("Queso fresco", 12.5, "desayuno sándwich ensalada queso"),
            ("Queso edam", 16.9, "sándwich desayuno queso"),
            ("Queso mozzarella", 17.5, "pizza lasaña queso"),
            ("Mantequilla con sal", 9.5, "pan desayuno untar lácteo"),
            ("Crema de leche", 6.5, "postre salsas cocina lácteo"),
        ]},
        {"marcas": ["San Fernando", "Redondos", "Pura Vida", "Gloria"], "tamanos": [("Bandeja x 15", 1.0), ("Bandeja x 30", 1.9), ("Caja x 180", 11.5)], "productos": [
            ("Huevos pardos", 9.5, "desayuno proteína huevo pollo"),
            ("Huevos blancos", 9.2, "desayuno proteína huevo pollo"),
            ("Huevos de codorniz", 8.0, "piqueo proteína huevo"),
        ]},
    ]},
    {"categoria": "Bebidas", "emoji": "🥤", "grupos": [
        {"marcas": ["Inca Kola", "Coca-Cola", "Sprite", "Fanta", "Kola Real"], "tamanos": T_BEB, "productos": [
            ("Gaseosa sabor original", 3.8, "refresco soda bebida gasificada fiesta"),
            ("Gaseosa sin azúcar", 3.8, "refresco soda dieta bebida gasificada"),
            ("Gaseosa de naranja", 3.7, "refresco soda bebida gasificada"),
            ("Gaseosa de limón", 3.7, "refresco soda bebida gasificada"),
        ]},
        {"marcas": ["San Luis", "Cielo", "San Mateo", "Vida"], "tamanos": T_BEB, "productos": [
            ("Agua mineral sin gas", 1.8, "hidratación sed bebida agua"),
            ("Agua mineral con gas", 2.2, "hidratación sed bebida agua"),
            ("Agua saborizada", 2.5, "hidratación sed bebida"),
        ]},
        {"marcas": ["Frugos", "Pulp", "Gloria", "Watts", "Selva"], "tamanos": T_BEB[1:], "productos": [
            ("Néctar de durazno", 3.5, "jugo fruta bebida desayuno"),
            ("Néctar de naranja", 3.5, "jugo fruta bebida desayuno"),
            ("Néctar de mango", 3.6, "jugo fruta bebida desayuno"),
            ("Néctar de manzana", 3.4, "jugo fruta bebida desayuno"),
            ("Jugo de piña", 3.6, "jugo fruta bebida tropical"),
            ("Jugo de maracuyá", 3.8, "jugo fruta bebida tropical"),
        ]},
        {"marcas": ["Gatorade", "Powerade", "Sporade"], "tamanos": T_BEB[:5], "productos": [
            ("Bebida rehidratante naranja", 4.8, "deporte electrolitos sed energía"),
            ("Bebida rehidratante limón", 4.8, "deporte electrolitos sed energía"),
            ("Bebida rehidratante frutos rojos", 4.9, "deporte electrolitos sed energía"),
        ]},
    ]},
    {"categoria": "Café, té e infusiones", "emoji": "☕", "grupos": [
        {"marcas": ["Altomayo", "Nescafé", "Kirma", "McColin's", "Hornimans", "Winters", "Milo", "Ovomaltina"], "tamanos": [("25 g", 0.35), ("50 g", 0.60), ("100 g", 1.00), ("200 g", 1.85), ("500 g", 4.20), ("Caja x 12 (100 g)", 10.6)], "productos": [
            ("Café instantáneo", 16.9, "desayuno taza bebida caliente cafeína"),
            ("Café pasado molido", 14.5, "desayuno cafetera bebida caliente cafeína"),
            ("Café descafeinado", 18.5, "desayuno bebida caliente sin cafeína"),
            ("Té negro en filtrantes", 6.9, "infusión bebida caliente té"),
            ("Té verde en filtrantes", 7.5, "infusión saludable bebida caliente antioxidante"),
            ("Manzanilla en filtrantes", 5.2, "infusión estómago relajante digestión"),
            ("Anís en filtrantes", 5.2, "infusión digestión estómago"),
            ("Hierbaluisa en filtrantes", 5.2, "infusión relajante digestión"),
            ("Chocolate en polvo", 12.5, "desayuno niños bebida chocolate caliente"),
            ("Cocoa en polvo", 11.0, "repostería postres bebida chocolate"),
        ]},
    ]},
    {"categoria": "Galletas y snacks", "emoji": "🍪", "grupos": [
        {"marcas": ["Field", "Costa", "Oreo", "Ritz", "Donofrio", "Nabisco"], "tamanos": [("Unidad", 1.00), ("Paquete x 6", 5.4), ("Paquete x 12", 10.5), ("Caja x 48", 40.0)], "productos": [
            ("Galleta de soda", 1.2, "piqueo lonchera dieta salada"),
            ("Galleta rellena de chocolate", 1.5, "piqueo lonchera niños dulce"),
            ("Galleta rellena de vainilla", 1.5, "piqueo lonchera niños dulce"),
            ("Galleta de vainilla", 1.3, "piqueo lonchera niños dulce"),
            ("Galleta con chispas de chocolate", 1.8, "piqueo lonchera niños dulce"),
            ("Galleta integral", 1.6, "piqueo saludable fibra dieta"),
            ("Barra de cereal", 1.9, "snack saludable energía deporte lonchera"),
            ("Wafer de chocolate", 1.4, "piqueo lonchera niños dulce"),
        ]},
        {"marcas": ["Lay's", "Karinto", "Pringles", "Doritos", "Cheetos", "Inka Chips"], "tamanos": [("Bolsa pequeña", 1.0), ("Bolsa mediana", 2.1), ("Bolsa familiar", 3.8), ("Caja x 24", 18.0)], "productos": [
            ("Papas fritas clásicas", 2.0, "snack piqueo salado fiesta pasabocas"),
            ("Papas fritas sabor a queso", 2.1, "snack piqueo salado fiesta"),
            ("Chifles de plátano", 2.2, "snack piqueo salado peruano"),
            ("Piqueo snack mix", 2.5, "snack piqueo salado fiesta"),
            ("Maní salado", 2.3, "snack piqueo frutos secos"),
            ("Cancha serrana", 2.0, "snack piqueo salado peruano maíz"),
        ]},
    ]},
    {"categoria": "Panadería y cereales", "emoji": "🍞", "grupos": [
        {"marcas": ["Bimbo", "Kellogg's", "Angel", "Field", "Nestlé", "Quaker"], "tamanos": T_G_MED[:4], "productos": [
            ("Pan de molde blanco", 6.9, "desayuno sándwich pan tostadas"),
            ("Pan de molde integral", 7.5, "desayuno saludable sándwich pan fibra"),
            ("Cereal hojuelas de maíz", 9.5, "desayuno niños leche cereal"),
            ("Cereal de chocolate", 10.9, "desayuno niños leche cereal"),
            ("Granola", 11.5, "desayuno saludable yogurt cereal"),
            ("Avena instantánea", 6.0, "desayuno rápido saludable cereal"),
            ("Mermelada de fresa", 6.5, "desayuno pan untar dulce"),
            ("Mermelada de durazno", 6.5, "desayuno pan untar dulce"),
            ("Miel de abeja", 14.9, "endulzar desayuno natural saludable"),
            ("Crema de avellanas", 15.9, "desayuno pan untar dulce chocolate"),
        ]},
    ]},
    {"categoria": "Dulces y chocolates", "emoji": "🍫", "grupos": [
        {"marcas": ["Sublime", "Triangulo", "Donofrio", "Costa", "Princesa", "Nestlé"], "tamanos": [("Unidad", 1.0), ("Bolsa x 10", 8.2), ("Caja x 24", 18.5), ("Caja x 60", 44.0)], "productos": [
            ("Chocolate con leche", 2.5, "golosina postre antojo dulce niños"),
            ("Chocolate amargo", 3.0, "golosina postre antojo dulce repostería"),
            ("Chocolate con maní", 2.8, "golosina postre antojo dulce"),
            ("Caramelos surtidos", 1.5, "golosina dulce niños fiesta"),
            ("Chicle de menta", 1.2, "golosina aliento dulce"),
            ("Turrón de chocolate", 2.2, "golosina postre antojo dulce"),
            ("Manjar blanco", 4.5, "postre dulce repostería untar"),
            ("Gelatina sabor fresa", 1.6, "postre dulce niños"),
            ("Flan de vainilla", 1.9, "postre dulce niños"),
            ("Mazamorra morada instantánea", 2.0, "postre dulce peruano"),
        ]},
    ]},
    {"categoria": "Limpieza del hogar", "emoji": "🧼", "grupos": [
        {"marcas": ["Ace", "Bolívar", "Opal", "Sapolio", "Marsella", "Ariel", "Magia Blanca"], "tamanos": T_DET, "productos": [
            ("Detergente en polvo", 12.5, "ropa lavar lavandería manchas lavadora blancura"),
            ("Detergente en polvo para ropa de color", 13.0, "ropa lavar lavandería colores"),
            ("Detergente antibacterial", 14.0, "ropa lavar lavandería higiene"),
            ("Quitamanchas en polvo", 11.0, "ropa manchas lavandería"),
        ]},
        {"marcas": ["Ace", "Bolívar", "Ariel", "Downy", "Suavitel", "Sapolio"], "tamanos": T_LIM, "productos": [
            ("Detergente líquido", 18.0, "ropa lavar lavandería lavadora"),
            ("Suavizante de telas", 11.0, "ropa aroma lavandería suavidad"),
            ("Quitamanchas líquido", 12.5, "ropa manchas lavandería"),
        ]},
        {"marcas": ["Sapolio", "Clorox", "Ayudín", "Poett", "Patito", "Marsella", "Opal"], "tamanos": T_LIM, "productos": [
            ("Lejía", 4.5, "desinfectar baño piso limpiar blanqueador"),
            ("Limpiador multiusos", 6.5, "limpiar piso cocina baño desinfectar"),
            ("Desinfectante de pisos", 7.5, "limpiar piso aroma desinfectar"),
            ("Lavavajilla líquido", 5.5, "platos cocina vajilla lavar grasa"),
            ("Limpiavidrios", 8.0, "ventanas vidrios espejos limpiar"),
            ("Limpiador de baños", 8.5, "baño inodoro sarro limpiar"),
            ("Quitagrasa", 9.0, "cocina grasa limpiar horno"),
            ("Ambientador en aerosol", 9.9, "aroma aire hogar desodorante ambiente"),
        ]},
        {"marcas": ["Marsella", "Bolívar", "Sapolio", "Ayudín", "Opal"], "tamanos": [("Barra", 1.0), ("Pack x 3", 2.7), ("Pack x 6", 5.0), ("Caja x 24", 17.5)], "productos": [
            ("Jabón de lavar en barra", 1.8, "ropa lavar lavandería manchas jabón"),
            ("Jabón lavavajilla en pasta", 3.5, "platos cocina vajilla lavar"),
        ]},
    ]},
    {"categoria": "Papel y descartables", "emoji": "🧻", "grupos": [
        {"marcas": ["Elite", "Suave", "Scott", "Noble", "Cottonelle"], "tamanos": T_ROLLO, "productos": [
            ("Papel higiénico doble hoja", 7.5, "baño higiene hogar papel"),
            ("Papel higiénico triple hoja", 9.5, "baño higiene hogar papel suave"),
            ("Papel toalla", 8.5, "cocina limpieza papel absorbente"),
        ]},
        {"marcas": ["Elite", "Suave", "Scott", "Sapolio", "Ayudín"], "tamanos": [("Paquete x 100", 1.0), ("Paquete x 300", 2.7), ("Caja x 12", 11.0)], "productos": [
            ("Servilletas", 3.5, "mesa cocina fiesta papel"),
            ("Bolsas de basura", 5.9, "limpieza residuos hogar basura"),
            ("Esponja multiusos", 4.0, "cocina limpieza platos lavar"),
            ("Vasos descartables", 4.5, "fiesta bebidas descartable"),
            ("Platos descartables", 5.5, "fiesta comida descartable"),
        ]},
    ]},
    {"categoria": "Cuidado personal", "emoji": "🧴", "grupos": [
        {"marcas": ["Head & Shoulders", "Sedal", "Pantene", "Dove", "Palmolive", "Nivea", "Johnson's"], "tamanos": T_PERSONAL, "productos": [
            ("Shampoo anticaspa", 14.9, "cabello caspa cuidado cabello ducha"),
            ("Shampoo para cabello graso", 13.9, "cabello cuidado ducha"),
            ("Shampoo para cabello seco", 13.9, "cabello cuidado ducha"),
            ("Acondicionador", 14.5, "cabello suavidad cuidado ducha"),
            ("Crema corporal hidratante", 15.9, "piel hidratación cuerpo cuidado"),
            ("Protector solar", 29.9, "piel sol verano playa cuidado"),
            ("Gel de baño", 12.5, "ducha cuerpo aroma cuidado"),
            ("Loción para bebé", 13.5, "piel bebé suavidad cuidado"),
        ]},
        {"marcas": ["Colgate", "Palmolive", "Dove", "Protex", "Rexona", "Nivea", "Oral-B"], "tamanos": [("Unidad", 1.0), ("Pack x 3", 2.7), ("Pack x 6", 5.0), ("Caja x 12", 9.6)], "productos": [
            ("Pasta dental", 5.5, "dientes higiene bucal sarro caries"),
            ("Enjuague bucal", 9.5, "boca aliento higiene bucal"),
            ("Cepillo dental", 4.5, "dientes higiene bucal"),
            ("Jabón de tocador", 2.5, "baño ducha manos higiene"),
            ("Desodorante en barra", 10.5, "axilas sudor higiene"),
            ("Desodorante en spray", 11.5, "axilas sudor higiene"),
            ("Toallas higiénicas", 6.9, "higiene femenina cuidado"),
            ("Afeitadora desechable", 5.9, "afeitar barba cuidado"),
        ]},
    ]},
    {"categoria": "Bebé y maternidad", "emoji": "🍼", "grupos": [
        {"marcas": ["Huggies", "Pampers", "Babysec", "Johnson's"], "tamanos": [("Paquete x 20", 1.0), ("Paquete x 40", 1.9), ("Paquete x 80", 3.6), ("Caja x 160", 6.8)], "productos": [
            ("Pañales talla P", 32.0, "bebé recién nacido pañal"),
            ("Pañales talla M", 36.0, "bebé pañal"),
            ("Pañales talla G", 40.0, "bebé pañal"),
            ("Pañales talla XG", 44.0, "bebé pañal"),
            ("Toallitas húmedas", 12.0, "bebé higiene limpieza"),
        ]},
        {"marcas": ["Nestlé", "Gloria", "Similac", "Enfamil"], "tamanos": [("400 g", 1.0), ("800 g", 1.9), ("Caja x 6 (400 g)", 5.6)], "productos": [
            ("Fórmula infantil etapa 1", 62.0, "bebé leche recién nacido alimentación"),
            ("Fórmula infantil etapa 2", 60.0, "bebé leche alimentación"),
            ("Cereal infantil de arroz", 14.0, "bebé papilla alimentación"),
        ]},
    ]},
    {"categoria": "Mascotas", "emoji": "🐶", "grupos": [
        {"marcas": ["Dog Chow", "Pedigree", "Ricocan", "Mimaskot", "Purina"], "tamanos": T_MASCOTA, "productos": [
            ("Alimento para perro adulto", 11.0, "perro mascota comida croquetas can"),
            ("Alimento para cachorro", 13.0, "perro cachorro mascota comida croquetas"),
            ("Alimento para perro razas pequeñas", 12.5, "perro mascota comida croquetas can"),
            ("Alimento para perro senior", 12.0, "perro mascota comida croquetas can"),
        ]},
        {"marcas": ["Cat Chow", "Whiskas", "Purina", "Mimaskot"], "tamanos": T_MASCOTA[:5], "productos": [
            ("Alimento para gato adulto", 12.0, "gato mascota comida croquetas felino"),
            ("Alimento para gatito", 13.5, "gato gatito mascota comida felino"),
            ("Arena para gato", 9.5, "gato mascota higiene felino"),
        ]},
    ]},
    {"categoria": "Carnes y embutidos", "emoji": "🥩", "grupos": [
        {"marcas": ["San Fernando", "Otto Kunz", "Braedt", "Redondos", "Laive"], "tamanos": [("250 g", 0.55), ("500 g", 1.00), ("1 kg", 1.90), ("5 kg", 8.8)], "productos": [
            ("Hot dog de pollo", 8.5, "desayuno parrilla embutido perro caliente"),
            ("Jamón inglés", 14.5, "sándwich desayuno embutido"),
            ("Jamonada", 9.5, "sándwich desayuno embutido"),
            ("Chorizo parrillero", 13.9, "parrilla asado embutido"),
            ("Salchicha huachana", 14.5, "parrilla desayuno embutido"),
            ("Hamburguesa de res", 15.9, "almuerzo carne parrilla"),
            ("Nuggets de pollo", 17.5, "niños almuerzo pollo congelado"),
            ("Pechuga de pollo", 16.9, "almuerzo proteína pollo carne"),
            ("Pollo entero congelado", 14.0, "almuerzo asado carne"),
            ("Milanesa de pollo", 18.5, "almuerzo empanizado pollo"),
        ]},
    ]},
]

_PALABRAS_UNIDAD = {"ml": "ml", "L": "L", "g": "g", "kg": "kg"}


def _redondear_precio(valor):
    """Redondea al múltiplo de 0.10 más cercano y evita precios menores a 0.50."""
    return max(0.5, round(round(valor * 10) / 10, 2))


RUTA_CATALOGO_REAL = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "data", "catalogo_real.json")


def generar_catalogo():
    """Devuelve el catálogo sintético de respaldo cuando Supabase no está disponible."""
    return generar_catalogo_sintetico()


def generar_catalogo_sintetico():
    """Catálogo de respaldo con marcas y productos combinados (datos simulados)."""
    azar = random.Random(2026)
    productos = []
    vistos = set()
    contador = 1
    for cat in CATEGORIAS:
        for grupo in cat["grupos"]:
            for nombre_base, precio_ref, palabras in grupo["productos"]:
                for marca in grupo["marcas"]:
                    for presentacion, factor in grupo["tamanos"]:
                        nombre = f"{nombre_base} {marca} {presentacion}"
                        if nombre in vistos:
                            continue
                        vistos.add(nombre)
                        precio = _redondear_precio(precio_ref * factor * FACTOR_MARCA.get(marca, 1.0) * azar.uniform(0.96, 1.05))
                        # Stock de tienda mayorista: la mayoría entre 200 y 5000, y unos pocos agotados
                        stock = 0 if azar.random() < 0.015 else int(azar.triangular(120, 6000, 900))
                        productos.append({
                            "sku": f"ALC-{contador:06d}",
                            "nombre": nombre,
                            "marca": marca,
                            "categoria": cat["categoria"],
                            "subcategoria": nombre_base,
                            "presentacion": presentacion,
                            "precio": precio,
                            "stock": stock,
                            "imagen": cat["emoji"],
                            "foto": "",
                            "descripcion": f"{nombre_base} {marca} en presentación de {presentacion}. {palabras.capitalize()}.",
                            "etiquetas": palabras,
                        })
                        contador += 1
    return productos


CAMPOS_CSV = ["sku", "nombre", "marca", "categoria", "subcategoria", "presentacion", "precio", "stock", "imagen", "foto", "descripcion", "etiquetas"]


def exportar_csv(ruta):
    """Escribe el catálogo en un CSV (UTF-8 con BOM para que Excel lo lea bien)."""
    productos = generar_catalogo()
    carpeta = os.path.dirname(os.path.abspath(ruta))
    os.makedirs(carpeta, exist_ok=True)
    with open(ruta, "w", encoding="utf-8-sig", newline="") as f:
        escritor = csv.DictWriter(f, fieldnames=CAMPOS_CSV, extrasaction="ignore")
        escritor.writeheader()
        escritor.writerows(productos)
    return len(productos)


if __name__ == "__main__":
    destino = sys.argv[1] if len(sys.argv) > 1 else os.path.join("data", "productos_alicorp_catalogo.csv")
    total = exportar_csv(destino)
    print(f"Catálogo generado: {total} productos -> {destino}")
