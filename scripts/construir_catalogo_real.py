"""Construye el catálogo real (data/catalogo_real.json y el CSV para Supabase) a partir de los
productos públicos de Plaza Vea descargados en un JSON.
Uso:  python scripts/construir_catalogo_real.py <plazavea_hojas.json> data/catalogo_real.json productos_alicorp_catalogo.csv 45
Filtra productos fuera de rubro, deja máximo 45 por tipo de producto y simula el stock."""
import json,re,random,collections,unicodedata,csv,sys
raw=json.load(open(sys.argv[1]))
OUT_JSON=sys.argv[2]; OUT_CSV=sys.argv[3]; CAP=int(sys.argv[4]) if len(sys.argv)>4 else 45
def norm(s):
    s=unicodedata.normalize('NFKD',s.lower()); s=''.join(c for c in s if not unicodedata.combining(c))
    return re.sub(r'\s+',' ',re.sub(r'[^a-z0-9 ]',' ',s)).strip()
EXCL_L2={'Juguetes y Estimulación Temprana','Coches y Sillas para bebé','Dormitorio Bebé e Infantil','Seguridad del Bebé e Infantil','Accesorios para perros','Accesorios para gatos','Salud','Vitaminas y Nutrición','Vitaminas y Suplementos Orgánicos','Bienestar Sexual','Packs de Regalo','Canastas Navideñas','Panetones','Limpieza de Calzado','Cosmética Natural','Cuidado Personal Sostenible','Depilación','Protección Adulta','Otras Mascotas','Accesorios de Limpieza','Cuidado del Hogar'}
EXCL_L3=re.compile(r'pa[nñ]ales para perro|navide|regalo|juguete|accesorio|termometro|medicin|preservativ|lubricante|test|mochila|sonaj|chupete|biberon|vajilla|calzado|zapat',re.I)
EXCL_NOMBRE=re.compile(r'\b(pack|combo|set|kit|regalo|navide|navideño|halloween|cafetera|licuadora|mueble|juguete|peluche|alfombra|sarten|olla|cuaderno|perfume|gift|canasta|coche|cuna|silla|termometro|preservativo|condon|vibrador|lubricante|sexual|cerveza|vino|pisco|whisky|ron)\b|\+')
EMOJI={'Abarrotes':'🛒','Bebidas':'🥤','Lácteos y Huevos':'🥛','Quesos y Fiambres':'🧀','Panadería y Pastelería':'🍞','Desayunos':'🥣','Limpieza':'🧼','Cuidado Personal y Salud':'🧴','Mascotas':'🐶','Bebé e Infantil':'🍼','Congelados':'🧊','Carnes, Aves y Pescados':'🥩','Mercado Saludable':'🥗','Pollo Rostizado y Comidas Preparadas':'🍗'}
SIN=[ (r'aceite','cocina freír fritura guisar saltear cocinar'),(r'arroz|menestra|lenteja|frejol|garbanzo','almuerzo guarnición cocina'),
 (r'fideo|spaghetti|tallarin|pasta|lasagna','almuerzo pasta cocina'),(r'galleta','lonchera snack dulce merienda'),(r'leche','desayuno lácteo'),
 (r'yogurt','desayuno lonchera lácteo postre'),(r'detergente|ropa|suavizante|quitamanchas|lavanderia','lavar ropa lavandería limpieza'),
 (r'shampoo|acondicionador|cabello|capilar','cabello lavar cuidado personal'),(r'panal|toallita|bebe','bebé cuidado'),(r'perro|canino','mascota perro'),
 (r'gato|felino','mascota gato'),(r'jabon','lavar baño higiene'),(r'papel higienico|papel toalla|servilleta','baño hogar descartable'),
 (r'\bcafe|infusion|\bte\b|manzanilla|\banis\b','desayuno bebida caliente'),(r'\bpan\b|panaderia','desayuno panadería'),(r'mermelada|miel|manjar','desayuno untar dulce'),
 (r'atun|conserva|sardina|enlatad','enlatado almuerzo'),(r'gaseosa|jugo|nectar|agua|bebida|refresco','bebida refresco sed'),
 (r'pollo|carne|\bres\b|cerdo|embutido|jamon|salchicha|hot dog|chorizo','carne almuerzo parrilla'),(r'chocolate|caramelo|golosina|dulce|chicle','dulce postre golosina'),
 (r'limpiador|lejia|desinfect|limpia|quitagrasa|multiuso|ambientador','limpiar limpieza pisos baño cocina'),(r'mayonesa|ketchup|salsa|aji|condimento|aderezo|vinagre|sazon','salsa condimento aderezo cocina'),
 (r'harina|reposteria|levadura|polvo de hornear|keke|premezcla','hornear repostería pastel'),(r'azucar|endulzante|\bsal\b','cocina endulzar'),
 (r'snack|piqueo|papas|chifle|mani','piqueo snack botana'),(r'cereal|avena|granola','desayuno cereal'),(r'queso|mantequilla|margarina','desayuno lácteo untar'),
 (r'huevo','desayuno proteína cocina'),(r'pasta dental|cepillo|bucal|enjuague','dientes higiene bucal'),(r'desodorante|protector solar|\bcrema|locion|\bgel\b','cuidado personal piel'),
 (r'helado|congelad','postre congelado'),(r'pizza|comida preparada|instant','comida rápida listo'),(r'toalla higienica|proteccion femenina','higiene femenina')]
def titulo(s):
    s=s.strip()
    if s.upper()==s and len(s)>3:
        s=' '.join(w.capitalize() if not re.match(r"^\d",w) else w for w in s.split())
        s=re.sub(r"'S\b","'s",s)
    return s
def marca_bonita(b):
    t=b.strip(); 
    if t.upper()==t: t=' '.join(w.capitalize() for w in t.split()); t=re.sub(r"'S\b","'s",t)
    return t
def presentacion(nombre,marca):
    i=nombre.lower().find(marca.lower())
    if i>=0:
        cola=nombre[i+len(marca):].strip(' -,')
        if 0<len(cola)<=40: return cola
    m=re.search(r'(\d+[\.,]?\d*\s?(?:ml|l|lt|lts|g|gr|kg|oz|un|und|unid|cm|m|tabletas|sobres)\b)',nombre,re.I)
    return m.group(1) if m else 'Unidad'
cand=[]; vistos=set()
for dep,l2,l3,pid,nombre,marca,precio,disp,img in raw:
    if dep not in EMOJI or l2 in EXCL_L2 or not img or precio<=0 or disp<=0 or not marca.strip() or marca.upper().startswith('GEN'): continue
    sub=l3 or l2
    if EXCL_L3.search(sub) or EXCL_NOMBRE.search(norm(nombre)) or '+' in nombre or precio>500: continue
    k=norm(nombre)
    if k in vistos: continue
    vistos.add(k); cand.append((dep,l2,sub,pid,nombre,marca,precio,img))
random.Random(2026).shuffle(cand)
cuenta=collections.Counter(); sel=[]
for c in cand:
    if cuenta[(c[0],c[2])]>=CAP: continue
    cuenta[(c[0],c[2])]+=1; sel.append(c)
sel.sort(key=lambda c:(c[0],c[2],c[4]))
azar=random.Random(7); prods=[]
for i,(dep,l2,sub,pid,nombre,marca,precio,img) in enumerate(sel,1):
    mb=marca_bonita(marca); nom=titulo(nombre)
    nom=re.sub(re.escape(marca),mb,nom,flags=re.I) if marca.upper()==marca else nom
    pres=presentacion(nom,mb)
    txt=norm(f'{dep} {l2} {sub} {nom}')
    et=[]; 
    for pat,kw in SIN:
        if re.search(r'\b(?:'+pat+')',txt): et.append(kw)
    stock=0 if azar.random()<0.015 else int(azar.triangular(120,6000,900))
    prods.append({'sku':f'ALC-{i:06d}','nombre':nom[:200],'marca':mb[:80],'categoria':dep,'subcategoria':sub[:120],'presentacion':pres[:80],
      'precio':round(float(precio),2),'stock':stock,'imagen':EMOJI[dep],'foto':img,'descripcion':f'{nom}. {l2} · {sub}.','etiquetas':' '.join(dict.fromkeys(' '.join(et).split()))})
json.dump(prods,open(OUT_JSON,'w',encoding='utf-8'),ensure_ascii=False,separators=(',',':'))
cols=['sku','nombre','marca','categoria','subcategoria','presentacion','precio','stock','imagen','foto','descripcion','etiquetas']
with open(OUT_CSV,'w',encoding='utf-8-sig',newline='') as f:
    w=csv.DictWriter(f,fieldnames=cols); w.writeheader(); w.writerows(prods)
print(len(prods),'productos;',len({p['subcategoria'] for p in prods}),'tipos;',len({p['marca'] for p in prods}),'marcas')
print(collections.Counter(p['categoria'] for p in prods))
