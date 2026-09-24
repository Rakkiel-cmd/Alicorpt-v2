document.addEventListener("DOMContentLoaded", () => {
    const contenedorMarcas = document.getElementById("marcas-container");

    // Solo ejecutar si estamos en la página principal
    if (contenedorMarcas) {
        // Consumir el JSON simulando una llamada a una API
        fetch("data.json")
            .then(response => {
                if (!response.ok) throw new Error("Error al cargar los datos.");
                return response.json();
            })
            .then(datos => {
                renderizarMarcas(datos);
            })
            .catch(error => {
                console.error("Error:", error);
                contenedorMarcas.innerHTML = "<p>Hubo un problema al cargar la información.</p>";
            });
    }

    function renderizarMarcas(datos) {
        contenedorMarcas.innerHTML = ""; // Limpiar
        
        datos.forEach(item => {
            const card = document.createElement("div");
            card.className = "card";
            
            card.innerHTML = `
                <img src="${item.imagen}" alt="${item.titulo}" class="card-img">
                <div class="card-content">
                    <h3>${item.titulo}</h3>
                    <p>${item.descripcion}</p>
                </div>
            `;
            
            contenedorMarcas.appendChild(card);
        });
    }
});
