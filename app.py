import streamlit as st

# Configuración de la página
st.set_page_config(
    page_title="Alicorp | Alimentamos el mañana",
    page_icon="🏢",
    layout="wide",
    initial_sidebar_state="collapsed"
)

# --- MANEJO DE ESTADOS (RUTEO) ---
if 'view' not in st.session_state:
    st.session_state.view = 'home'

def go_to_login():
    st.session_state.view = 'login'

def go_to_home():
    st.session_state.view = 'home'

# --- CSS PERSONALIZADO ---
st.markdown("""
<style>
    /* Estilos globales */
    .stApp {
        background-color: #f8f9fa;
    }
    .header-container {
        display: flex;
        justify-content: space-between;
        align-items: center;
        padding: 10px 0px;
        border-bottom: 2px solid #E5E7EB;
        margin-bottom: 20px;
    }
    .logo-text {
        font-size: 2.5rem;
        font-weight: 800;
        color: #e02b20; /* Rojo corporativo */
        margin: 0;
        padding: 0;
    }
    .hero-section {
        background-color: #1E3A8A; /* Azul oscuro */
        color: white;
        padding: 3rem 2rem;
        border-radius: 10px;
        text-align: center;
        margin-bottom: 2rem;
    }
    .hero-title {
        font-size: 3rem;
        font-weight: 700;
        margin-bottom: 1rem;
    }
    .hero-subtitle {
        font-size: 1.5rem;
        font-weight: 300;
    }
    .product-card {
        background-color: white;
        padding: 2rem;
        border-radius: 8px;
        box-shadow: 0 4px 6px rgba(0,0,0,0.05);
        text-align: center;
        border-top: 4px solid #e02b20;
    }
</style>
""", unsafe_allow_html=True)

# --- VISTA PRINCIPAL (LANDING PAGE) ---
if st.session_state.view == 'home':
    # Barra de navegación superior
    col_logo, col_btn = st.columns([4, 1])
    with col_logo:
        st.markdown('<p class="logo-text">Alicorp</p>', unsafe_allow_html=True)
    with col_btn:
        st.button("🔒 Acceso Administrador", on_click=go_to_login, use_container_width=True)
    
    # Sección Hero
    st.markdown("""
    <div class="hero-section">
        <h1 class="hero-title">Alimentamos el mañana</h1>
        <p class="hero-subtitle">Innovación, calidad y compromiso para el desarrollo de grandes y pequeños negocios.</p>
    </div>
    """, unsafe_allow_html=True)
    
    # Sección de Servicios / Marcas
    st.subheader("Nuestros Pilares de Negocio")
    st.write("---")
    
    p1, p2, p3 = st.columns(3)
    
    with p1:
        st.markdown("""
        <div class="product-card">
            <h3>🛒 Consumo Masivo</h3>
            <p>Marcas líderes que acompañan a las familias todos los días con productos de alta calidad y nutrición.</p>
        </div>
        """, unsafe_allow_html=True)
        st.image("https://via.placeholder.com/400x200.png?text=Productos+Masivos", use_container_width=True)

    with p2:
        st.markdown("""
        <div class="product-card">
            <h3>🏭 B2B (Proveedores)</h3>
            <p>Insumos y soluciones integrales para potenciar el crecimiento de panaderías, restaurantes e industrias.</p>
        </div>
        """, unsafe_allow_html=True)
        st.image("https://via.placeholder.com/400x200.png?text=Soluciones+Industriales", use_container_width=True)

    with p3:
        st.markdown("""
        <div class="product-card">
            <h3>🌱 Sostenibilidad</h3>
            <p>Comprometidos con el medio ambiente y el desarrollo responsable de nuestras comunidades operativas.</p>
        </div>
        """, unsafe_allow_html=True)
        st.image("https://via.placeholder.com/400x200.png?text=Desarrollo+Sostenible", use_container_width=True)
        
    st.write("---")
    st.markdown("<p style='text-align: center; color: gray;'>© 2026 Plataforma Alicorp - Proyecto Grupal Universitario</p>", unsafe_allow_html=True)

# --- VISTA LOGIN DE ADMINISTRADOR ---
elif st.session_state.view == 'login':
    col_back, _ = st.columns([1, 5])
    with col_back:
        st.button("← Volver a Inicio", on_click=go_to_home)
        
    st.markdown("<h2 style='text-align: center; color: #1E3A8A;'>Acceso Seguro - Panel de Administración</h2>", unsafe_allow_html=True)
    st.write("---")
    
    col_login1, col_login2, col_login3 = st.columns([1, 2, 1])
    
    with col_login2:
        st.info("💡 **Subproyecto 1:** Aquí se integrará el sistema de Reconocimiento Facial (OpenCV / Python).")
        
        # Simulación de formulario
        with st.form("login_form"):
            st.text_input("Usuario (Ej. admin_ventas)")
            st.text_input("Contraseña", type="password")
            
            st.write("📷 **Validación Biométrica Requerida**")
            st.camera_input("Captura de Rostro para IA (En desarrollo...)")
            
            submit = st.form_submit_button("Ingresar al Dashboard de Ventas", use_container_width=True)
            
            if submit:
                st.warning("El módulo de validación facial y acceso a la base de datos (CSV) aún está en construcción por el equipo de ML.")
