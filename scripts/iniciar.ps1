<#
  Inicia el procesador de correo de FEDER.
    .\scripts\iniciar.ps1                   # entorno de APP_ENV (local si no está definida), procesamiento continuo
    .\scripts\iniciar.ps1 -Entorno tecnoweb # entorno institucional (requiere DB_PASSWORD y MAIL_PASSWORD)
    .\scripts\iniciar.ps1 -UnaVez           # un solo ciclo
  Para detenerlo: Ctrl+C en esta ventana o .\scripts\detener.ps1 desde otra.
#>
param(
  [ValidateSet('local', 'tecnoweb')][string]$Entorno,
  [switch]$UnaVez
)
$ErrorActionPreference = 'Stop'
$Proyecto = Split-Path -Parent $PSScriptRoot
Set-Location $Proyecto

$Jar = Join-Path $Proyecto 'target\feder-email-driven.jar'
if (-not (Test-Path $Jar)) {
  Write-Host 'Compilando el proyecto (primera ejecución)...'
  & .\mvnw.cmd -q -B -DskipTests package
  if ($LASTEXITCODE -ne 0) { throw 'La compilación falló.' }
}

# Prioridad: -Entorno explícito; si no, la variable APP_ENV ya definida; si tampoco, local.
# Así este script y scripts/iniciar.sh eligen el mismo entorno para el mismo APP_ENV.
if (-not $Entorno) {
  $Entorno = if ($env:APP_ENV) { $env:APP_ENV.Trim().ToLowerInvariant() } else { 'local' }
}
if ($Entorno -notin @('local', 'tecnoweb')) { throw "APP_ENV debe ser 'local' o 'tecnoweb' (valor recibido: $Entorno)." }
Write-Host "Entorno: $Entorno"
$env:APP_ENV = $Entorno
$accion = if ($UnaVez) { 'procesar-una-vez' } else { 'procesar' }
# La consola de Windows debe usar UTF-8 para mostrar bien las tildes y la ñ de los mensajes.
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$codificacion = @('-Dfile.encoding=UTF-8', '-Dsun.stdout.encoding=UTF-8', '-Dsun.stderr.encoding=UTF-8')
& java @codificacion -jar $Jar $accion
