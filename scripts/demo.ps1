<#
  Cliente de demostración para el entorno local (con scripts\correo-local.ps1 en ejecución).
    .\scripts\demo.ps1 enviar -De elmer.rojas@feder.example -Asunto 'LISPRO["*"]'
    .\scripts\demo.ps1 leer   -De elmer.rojas@feder.example
  Envía el comando al buzón del sistema por SMTP y lee las respuestas del usuario por POP3.
#>
param(
  [Parameter(Mandatory = $true)][ValidateSet('enviar', 'leer')][string]$Accion,
  [Parameter(Mandatory = $true)][string]$De,
  [string]$Asunto
)
$ErrorActionPreference = 'Stop'
$Proyecto = Split-Path -Parent $PSScriptRoot
$Jar = Join-Path $Proyecto 'target\feder-email-driven.jar'
$clave = if ($env:FEDER_CLAVE_BUZON_LOCAL) { $env:FEDER_CLAVE_BUZON_LOCAL } else { 'buzon-local' }
# La consola de Windows debe mostrar UTF-8 para ver bien las tildes de las respuestas.
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$codificacion = @('-Dstdout.encoding=UTF-8', '-Dsun.stdout.encoding=UTF-8')
if ($Accion -eq 'enviar') {
  if (-not $Asunto) { throw 'Indique -Asunto con el comando, por ejemplo: HELP["*"]' }
  # Windows PowerShell 5.1 altera las comillas dobles y los espacios al pasar argumentos a programas
  # externos. Por eso el asunto se entrega en una variable de entorno, que llega intacta a Java.
  $env:FEDER_ASUNTO = $Asunto
  try {
    & java @codificacion -jar $Jar cliente-demo enviar "--de=$De" "--clave=$clave"
  } finally {
    Remove-Item Env:\FEDER_ASUNTO -ErrorAction SilentlyContinue
  }
} else {
  & java @codificacion -jar $Jar cliente-demo leer "--de=$De" "--clave=$clave"
}
