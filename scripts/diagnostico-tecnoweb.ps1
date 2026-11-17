<#
  Diagnóstico NO destructivo del entorno TecnoWeb: DNS, puertos de correo, POP3 (saludo,
  capacidades y autenticación sin leer ni borrar mensajes), SMTP (saludo y extensiones, sin
  enviar nada) y PostgreSQL (conexión y conteo de tablas).
  Requiere config\application-tecnoweb.properties. Sin las variables MAIL_PASSWORD y DB_PASSWORD
  comprueba igualmente DNS, puertos, saludo y capacidades, y omite la autenticación y la base de datos.
#>
$ErrorActionPreference = 'Stop'
$Proyecto = Split-Path -Parent $PSScriptRoot
Set-Location $Proyecto
$Jar = Join-Path $Proyecto 'target\feder-email-driven.jar'
if (-not (Test-Path $Jar)) { & .\mvnw.cmd -q -B -DskipTests package }
$env:APP_ENV = 'tecnoweb'
# La consola de Windows debe usar UTF-8 para mostrar bien las tildes y la ñ de los mensajes.
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$codificacion = @('-Dfile.encoding=UTF-8', '-Dsun.stdout.encoding=UTF-8', '-Dsun.stderr.encoding=UTF-8')
& java @codificacion -jar $Jar diagnostico
