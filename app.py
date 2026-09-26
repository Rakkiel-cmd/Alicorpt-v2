from flask import Flask, render_template, request
import pandas as pd
import numpy as np
from sklearn.neural_network import MLPRegressor
from sklearn.model_selection import train_test_split
from sklearn.preprocessing import StandardScaler

app = Flask(__name__)

@app.route('/', methods=['GET', 'POST'])
def index():
    resultado_mostrado = False
    media_ventas = 0
    mediana_ventas = 0
    desviacion_ventas = 0
    total_registros = 100000
    prediccion_resultado = 0
    mse = 0
    r2 = 0

    if request.method == 'POST':
        # Cargar o simular el CSV de 100k
        try:
            df = pd.read_csv('transacciones_alicorp_100k.csv')
        except FileNotFoundError:
            np.random.seed(42)
            n_registros = 100000
            productos = ['Aceite Primor 1L', 'Fideos Don Vittorio 500g', 'Mayonesa Alacena 500g', 'Detergente Bolívar 2kg', 'Harina Blanca Flor 1kg']
            tiendas = ['Supermercado Lima Norte', 'Hipermercado Centro', 'Tienda Express Los Olivos', 'Mayorista San Martín']
            fechas = pd.date_range(start='2024-01-01', periods=730, freq='D')
            
            data = {
                'id_transaccion': range(1, n_registros + 1),
                'fecha': np.random.choice(fechas, n_registros),
                'producto': np.random.choice(productos, n_registros),
                'tienda': np.random.choice(tiendas, n_registros),
                'precio_unitario': np.random.choice([8.5, 4.2, 7.0, 15.0, 5.5], n_registros),
                'stock_actual': np.random.randint(50, 500, n_registros),
                'temperatura_zona': np.random.uniform(18.0, 30.0, n_registros)
            }
            df = pd.DataFrame(data)
            df['cantidad_vendida'] = np.random.poisson(lam=15, size=n_registros) + (df['stock_actual'] * 0.01).astype(int)
            df['ventas_totales'] = df['cantidad_vendida'] * df['precio_unitario']
            df.to_csv('transacciones_alicorp_100k.csv', index=False)

        # Estadísticas
        total_registros = len(df)
        media_ventas = round(df['ventas_totales'].mean(), 2)
        mediana_ventas = round(df['ventas_totales'].median(), 2)
        desviacion_ventas = round(df['ventas_totales'].std(), 2)

        # Red Neuronal
        df['producto_code'] = df['producto'].astype('category').cat.codes
        df['tienda_code'] = df['tienda'].astype('category').cat.codes
        df['dia_anio'] = pd.to_datetime(df['fecha']).dt.dayofyear

        X = df[['precio_unitario', 'stock_actual', 'temperatura_zona', 'producto_code', 'tienda_code', 'dia_anio']]
        y = df['cantidad_vendida']

        X_train, X_test, y_train, y_test = train_test_split(X, y, test_size=0.1, random_state=42)
        scaler = StandardScaler()
        X_train_scaled = scaler.fit_transform(X_train)
        
        red_neuronal = MLPRegressor(hidden_layer_sizes=(16, 8), max_iter=25, random_state=42)
        red_neuronal.fit(X_train_scaled, y_train)

        # Métricas de evaluación
        X_test_scaled = scaler.transform(X_test)
        y_pred = red_neuronal.predict(X_test_scaled)
        mse = round(np.mean((y_pred - y_test) ** 2), 2)
        r2 = round(red_neuronal.score(X_test_scaled, y_test), 4)

        # Predicción de ejemplo
        escenario_prueba = np.array([[8.5, 200, 22.0, 0, 1, 150]])
        escenario_scaled = scaler.transform(escenario_prueba)
        prediccion_resultado = round(red_neuronal.predict(escenario_scaled)[0], 2)
        resultado_mostrado = True

    return render_template('index.html', 
                           resultado_mostrado=resultado_mostrado,
                           total_registros=total_registros, 
                           media_ventas=media_ventas, 
                           mediana_ventas=mediana_ventas, 
                           desviacion_ventas=desviacion_ventas,
                           prediccion_resultado=prediccion_resultado,
                           mse=mse,
                           r2=r2)

if __name__ == '__main__':
    app.run(debug=True)