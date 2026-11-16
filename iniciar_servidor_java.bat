@echo off
title ALICORP BI v2 - Servidor 100% Java 26 (DL4J + Smile ML + JavaCV + SQL 3FN)
cls
echo =========================================================================
echo    ALICORP BI v2 - PLATAFORMA CORPORATIVA EN JAVA
echo    Base de Datos Relacional 3FN (H2 Modo Oracle / Local)
echo =========================================================================
echo.

if not defined JAVA_HOME (
    if exist "C:\Program Files\Java\jdk-26.0.2" (
        set "JAVA_HOME=C:\Program Files\Java\jdk-26.0.2"
    ) else if exist "C:\Program Files\Java\latest" (
        set "JAVA_HOME=C:\Program Files\Java\latest"
    )
)

echo [*] Verificando compilacion de la aplicacion Java...
if not exist "target\alicorp-bi-java-2.0.0-shaded.jar" (
    echo [*] Compilando paquetes y generando Uber-JAR con Maven...
    call "%~dp0mvnw.cmd" clean package -DskipTests
    if errorlevel 1 (
        echo [ERROR] Falla al compilar el proyecto Java.
        pause
        exit /b 1
    )
)

echo.
echo [*] Iniciando Servidor Web Java en el puerto 5000...
echo [*] Abre en tu navegador: http://localhost:5000
echo.
"%JAVA_HOME%\bin\java.exe" -jar "target\alicorp-bi-java-2.0.0-shaded.jar"
pause
