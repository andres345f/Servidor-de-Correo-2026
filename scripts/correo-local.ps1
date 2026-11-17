<#
  Servidor de correo LOCAL de demostración (SMTP 2525 y POP3 1110, solo en 127.0.0.1).
  Crea el buzón del sistema y un buzón por cada usuario de la semilla, todos con la misma
  clave de demostración (variable FEDER_CLAVE_BUZON_LOCAL; por defecto 'buzon-local').
  No es un servidor real: los mensajes viven en memoria y se pierden al cerrarlo.
#>
$ErrorActionPreference = 'Stop'
$Proyecto = Split-Path -Parent $PSScriptRoot
Set-Location $Proyecto
$Jar = Join-Path $Proyecto 'target\feder-email-driven.jar'
if (-not (Test-Path $Jar)) { & .\mvnw.cmd -q -B -DskipTests package }

$clave = if ($env:FEDER_CLAVE_BUZON_LOCAL) { $env:FEDER_CLAVE_BUZON_LOCAL } else { 'buzon-local' }
$buzones = @(
  'grupo29sa@feder.local',
  'administracion@feder.example', 'almacen.carla.mendez@feder.example', 'ventas.jorge.suarez@feder.example',
  'maria.quispe@feder.example', 'pedro.chuve@feder.example', 'rosa.tomicha@feder.example',
  'elmer.rojas@feder.example', 'silvia.anez@feder.example', 'daniela.flores@feder.example',
  'marco.antelo@feder.example', 'patricia.vargas@feder.example', 'andres.molina@feder.example',
  'logistica.lucia.fernandez@feder.example', 'visitante.nuevo@feder.example'
)
$argumentos = @('-jar', $Jar, 'correo-local') + ($buzones | ForEach-Object { "--cuenta=$($_):$clave" })
# La consola de Windows debe usar UTF-8 para mostrar bien las tildes y la ñ de los mensajes.
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$codificacion = @('-Dfile.encoding=UTF-8', '-Dsun.stdout.encoding=UTF-8', '-Dsun.stderr.encoding=UTF-8')
& java @codificacion @argumentos
