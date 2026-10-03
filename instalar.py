import subprocess, sys

def pip(*args):
    subprocess.check_call([sys.executable, "-m", "pip", "install", *args])

pip("--upgrade", "pip")
pip("-r", "requirements.txt")
pip("face_recognition", "--no-deps")

print("\nInstalacion completa. Ejecuta: python server.py")