#!/bin/sh
# Inicia el procesador de correo en un servidor Linux (por ejemplo, TecnoWeb).
#   set -a; . ./config/secretos.env; set +a    # contraseñas desde un archivo privado (chmod 600), no en la línea
#   APP_ENV=tecnoweb ./scripts/iniciar.sh
# Para un solo ciclo (uso con cron): ./scripts/iniciar.sh una-vez
set -eu
cd "$(dirname "$0")/.."
# En un clon nuevo no existen (están excluidas de Git): registros y archivo de detención.
mkdir -p logs estado
JAR=target/feder-email-driven.jar
if [ ! -f "$JAR" ]; then
  ./mvnw -q -B -DskipTests package
fi
: "${APP_ENV:=tecnoweb}"
export APP_ENV
# Salida en UTF-8 aunque el servidor tenga otra configuración regional (tildes y ñ de los mensajes).
CODIFICACION="-Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8"
if [ "${1:-}" = "una-vez" ]; then
  exec java $CODIFICACION -jar "$JAR" procesar-una-vez
fi
exec java $CODIFICACION -jar "$JAR" procesar
