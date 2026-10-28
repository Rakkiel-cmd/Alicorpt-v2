"""
Correo de seguimiento del pedido (tienda en línea).

Cuando un cliente termina una compra, se le envía a SU correo (el que escribió en el
formulario) el número de pedido, el detalle y cómo ver el estado.

Cómo envía: por la API HTTPS de Brevo (plan gratis: 300 correos al día). No se usa SMTP
porque Render en el plan gratis bloquea los puertos SMTP.

Configuración (variables de entorno en Render, NUNCA en el código):
    BREVO_API_KEY        clave de la API de Brevo (empieza con "xkeysib-")
    CORREO_REMITENTE     correo verificado en Brevo desde el que salen los correos
    CORREO_NOMBRE        (opcional) nombre que ve el cliente, por defecto "Alicorp Tienda en línea"
    SITIO_URL            (opcional) por defecto https://alicorpt-v2.onrender.com

Si las variables no existen, no pasa nada: la compra se guarda igual y simplemente no se envía correo.
El envío corre en un hilo aparte: nunca demora ni rompe la compra.
"""

import html
import json
import os
import threading
import urllib.request

URL_API = "https://api.brevo.com/v3/smtp/email"


def correo_configurado():
    return bool(os.environ.get("BREVO_API_KEY", "").strip() and os.environ.get("CORREO_REMITENTE", "").strip())


def _dinero(valor):
    return "S/ " + f"{float(valor):,.2f}"


def _construir_html(nombre, compra_id, total, direccion, detalle, sitio):
    e = html.escape
    filas = "".join(
        f"<tr><td style='padding:6px 8px;border-bottom:1px solid #eee'>{e(str(d['producto']))}</td>"
        f"<td style='padding:6px 8px;border-bottom:1px solid #eee;text-align:center'>{int(d['cantidad'])}</td>"
        f"<td style='padding:6px 8px;border-bottom:1px solid #eee;text-align:right'>{_dinero(d['subtotal'])}</td></tr>"
        for d in detalle
    )
    pasos = ["Pendiente", "Preparando", "Enviado", "Entregado"]
    cinta = "".join(
        f"<td style='text-align:center;padding:6px 4px;font-size:12px;"
        f"{'background:#d62828;color:#fff;font-weight:bold;' if i == 0 else 'background:#f1f1f1;color:#777;'}'>{p}</td>"
        for i, p in enumerate(pasos)
    )
    return f"""
<div style="font-family:Arial,Helvetica,sans-serif;max-width:560px;margin:auto;color:#222">
  <div style="background:#d62828;color:#fff;padding:18px 20px;border-radius:10px 10px 0 0">
    <h2 style="margin:0">¡Gracias por tu compra, {e(nombre)}!</h2>
    <p style="margin:4px 0 0">Recibimos tu pedido correctamente.</p>
  </div>
  <div style="border:1px solid #eee;border-top:0;padding:18px 20px;border-radius:0 0 10px 10px">
    <p style="font-size:16px">N.º de pedido: <b style="font-size:20px">{e(str(compra_id))}</b></p>
    <p style="margin:0 0 6px"><b>Estado actual:</b></p>
    <table width="100%" cellspacing="2" style="border-collapse:separate"><tr>{cinta}</tr></table>
    <h3 style="margin:18px 0 6px">Detalle</h3>
    <table width="100%" style="border-collapse:collapse;font-size:14px">
      <tr style="background:#fafafa"><th align="left" style="padding:6px 8px">Producto</th><th style="padding:6px 8px">Cant.</th><th align="right" style="padding:6px 8px">Subtotal</th></tr>
      {filas}
      <tr><td colspan="2" style="padding:8px;text-align:right"><b>Total</b></td><td style="padding:8px;text-align:right"><b>{_dinero(total)}</b></td></tr>
    </table>
    <p style="font-size:14px"><b>Dirección de entrega:</b> {e(direccion or '-')}</p>
    <h3 style="margin:18px 0 6px">¿Cómo sigo mi pedido?</h3>
    <p style="font-size:14px;margin:0">Entra a la tienda, pulsa <b>📦 Mi pedido</b> y escribe tu número de pedido
    (<b>{e(str(compra_id))}</b>) y este mismo correo.</p>
    <p style="text-align:center;margin:18px 0">
      <a href="{e(sitio)}/tienda.html" style="background:#d62828;color:#fff;text-decoration:none;padding:12px 22px;border-radius:8px;font-weight:bold">Ir a la tienda</a>
    </p>
    <p style="font-size:12px;color:#888">Este es un correo automático de un proyecto académico (SENATI). No respondas a este mensaje.</p>
  </div>
</div>"""


def _enviar(destino, nombre, compra_id, total, direccion, detalle):
    clave = os.environ.get("BREVO_API_KEY", "").strip()
    remitente = os.environ.get("CORREO_REMITENTE", "").strip()
    nombre_remitente = os.environ.get("CORREO_NOMBRE", "Alicorp Tienda en línea").strip()
    sitio = os.environ.get("SITIO_URL", "https://alicorpt-v2.onrender.com").strip().rstrip("/")
    cuerpo = {
        "sender": {"name": nombre_remitente, "email": remitente},
        "to": [{"email": destino, "name": nombre}],
        "subject": f"Tu pedido N.º {compra_id} - seguimiento",
        "htmlContent": _construir_html(nombre, compra_id, total, direccion, detalle, sitio),
    }
    req = urllib.request.Request(
        URL_API, data=json.dumps(cuerpo).encode("utf-8"), method="POST",
        headers={"api-key": clave, "content-type": "application/json", "accept": "application/json"},
    )
    try:
        with urllib.request.urlopen(req, timeout=15) as r:
            print(f"[CORREO] Seguimiento del pedido {compra_id} enviado (HTTP {r.status}).")
    except Exception as e:  # nunca debe romper la compra
        detalle_error = ""
        try:
            detalle_error = e.read().decode("utf-8", "ignore")[:300]
        except Exception:
            pass
        print(f"[CORREO] No se pudo enviar el pedido {compra_id}: {e} {detalle_error}")


def enviar_seguimiento(destino, nombre, compra_id, total, direccion, detalle):
    """Lanza el envío en segundo plano. Devuelve True si se intentó enviar, False si no hay configuración."""
    if not correo_configurado():
        return False
    hilo = threading.Thread(target=_enviar, args=(destino, nombre, compra_id, total, direccion, detalle), daemon=True)
    hilo.start()
    return True
