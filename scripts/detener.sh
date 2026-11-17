#!/bin/sh
# Detención ordenada: el procesador termina el ciclo en curso y sale.
set -eu
cd "$(dirname "$0")/.."
mkdir -p estado
touch estado/DETENER
echo "Solicitud de detención enviada."
