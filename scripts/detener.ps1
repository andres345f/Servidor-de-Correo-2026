<#
  Pide al procesador de correo que se detenga de forma ordenada: termina el ciclo en curso
  (no se pierde ningún mensaje ni respuesta) y luego sale.
#>
$Proyecto = Split-Path -Parent $PSScriptRoot
$Estado = Join-Path $Proyecto 'estado'
New-Item -ItemType Directory -Force $Estado | Out-Null
New-Item -ItemType File -Force (Join-Path $Estado 'DETENER') | Out-Null
Write-Host 'Solicitud de detención enviada. El procesador terminará el ciclo actual y se cerrará.'
