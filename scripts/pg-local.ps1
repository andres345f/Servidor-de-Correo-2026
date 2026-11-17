<#
  Clúster PostgreSQL local y aislado para desarrollo y pruebas de FEDER.

  - Usa los binarios de PostgreSQL ya instalados, pero crea un clúster propio en .pgdata
    (excluido de Git) y lo levanta en 127.0.0.1:55432. No toca el servicio PostgreSQL del sistema.
  - Solo escucha en localhost y usa autenticación "trust" porque es un entorno de desarrollo personal.
    Nunca se usa contra la base institucional.

  Uso:
    .\scripts\pg-local.ps1 init     # crea el clúster y las bases feder_local, feder_test y feder_test_vacia
    .\scripts\pg-local.ps1 start
    .\scripts\pg-local.ps1 stop
    .\scripts\pg-local.ps1 status
    .\scripts\pg-local.ps1 reset-db feder_local   # recrea una base del clúster local y carga esquema + semilla
#>
param(
  [Parameter(Mandatory = $true)][ValidateSet('init', 'start', 'stop', 'status', 'reset-db')][string]$Accion,
  [string]$Base = 'feder_local'
)
$ErrorActionPreference = 'Stop'

$Proyecto = Split-Path -Parent $PSScriptRoot
# FEDER_PGDATA permite usar el mismo clúster desde otra copia de trabajo del repositorio.
$Datos = if ($env:FEDER_PGDATA) { $env:FEDER_PGDATA } else { Join-Path $Proyecto '.pgdata' }
$Puerto = 55432
$Super = 'feder_admin'

# Localizamos los binarios de PostgreSQL: primero PG_BIN, luego la instalación estándar más reciente.
$PgBin = $env:PG_BIN
if (-not $PgBin) {
  $PgBin = Get-ChildItem 'C:\Program Files\PostgreSQL' -Directory -ErrorAction SilentlyContinue |
    Sort-Object { [int]($_.Name -replace '\D', '') } -Descending | Select-Object -First 1 |
    ForEach-Object { Join-Path $_.FullName 'bin' }
}
if (-not $PgBin -or -not (Test-Path (Join-Path $PgBin 'pg_ctl.exe'))) {
  throw 'No se encontraron los binarios de PostgreSQL. Defina la variable PG_BIN con la carpeta bin.'
}
$pg_ctl = Join-Path $PgBin 'pg_ctl.exe'
$psql = Join-Path $PgBin 'psql.exe'
$Log = Join-Path $Datos 'servidor.log'

function Invoke-Psql([string]$Db, [string[]]$Argumentos) {
  # psql escribe avisos (NOTICE) en stderr; Windows PowerShell los convertiría en excepciones.
  # Los silenciamos y decidimos el éxito solo por el código de salida (ON_ERROR_STOP=1).
  $env:PGOPTIONS = '-c client_min_messages=warning'
  $anterior = $ErrorActionPreference
  $ErrorActionPreference = 'Continue'
  try {
    & $psql -h 127.0.0.1 -p $Puerto -U $Super -d $Db -v ON_ERROR_STOP=1 -q @Argumentos 2>&1 | ForEach-Object { "$_" }
    $codigo = $LASTEXITCODE
  } finally {
    $ErrorActionPreference = $anterior
  }
  if ($codigo -ne 0) { throw "psql terminó con código $codigo" }
}

function Start-Cluster {
  & $pg_ctl -D $Datos status *> $null
  if ($LASTEXITCODE -eq 0) { Write-Host "El clúster ya está en ejecución (puerto $Puerto)."; return }
  # pg_ctl deja al servidor heredando los manejadores de salida; si PowerShell captura esa salida
  # el script quedaría esperando indefinidamente. Por eso lo lanzamos como proceso independiente
  # y solo esperamos a que termine pg_ctl (no al servidor).
  $info = New-Object System.Diagnostics.ProcessStartInfo
  $info.FileName = $pg_ctl
  $info.Arguments = "-D `"$Datos`" -l `"$Log`" -o `"-p $Puerto -c listen_addresses=127.0.0.1`" -w start"
  $info.UseShellExecute = $false
  $info.CreateNoWindow = $true
  $proceso = [System.Diagnostics.Process]::Start($info)
  $proceso.WaitForExit()
  if ($proceso.ExitCode -ne 0) { throw "No se pudo iniciar el clúster; revise $Log" }
  Write-Host "Clúster iniciado en 127.0.0.1:$Puerto"
}

function Reset-Database([string]$Nombre) {
  if ($Nombre -notmatch '^feder_[a-z0-9_]+$') { throw 'Solo se permiten bases locales con prefijo feder_' }
  Invoke-Psql 'postgres' @('-c', "DROP DATABASE IF EXISTS $Nombre WITH (FORCE)")
  Invoke-Psql 'postgres' @('-c', "CREATE DATABASE $Nombre ENCODING 'UTF8' TEMPLATE template0")
  # Las bases terminadas en _vacia solo llevan el esquema: las usan las pruebas de instalación (R099).
  $scripts = if ($Nombre -like '*_vacia') { @('01_schema.sql') } else { @('01_schema.sql', '02_seed.sql') }
  foreach ($f in $scripts) {
    $ruta = Join-Path $Proyecto "db\$f"
    if (Test-Path $ruta) { Invoke-Psql $Nombre @('-f', $ruta); Write-Host "  cargado $f en $Nombre" }
  }
}

switch ($Accion) {
  'init' {
    if (-not (Test-Path (Join-Path $Datos 'PG_VERSION'))) {
      # Proveedor "builtin" C.UTF-8 (PostgreSQL 17+): orden binario estable y clasificación Unicode
      # correcta, para que las reglas con [[:alpha:]] acepten nombres con tildes y ñ.
      & (Join-Path $PgBin 'initdb.exe') -D $Datos -U $Super -A trust -E UTF8 --locale-provider=builtin --builtin-locale=C.UTF-8 --locale=C | Out-Null
      if ($LASTEXITCODE -ne 0) { throw 'initdb falló' }
      Write-Host "Clúster creado en $Datos"
    }
    Start-Cluster
    foreach ($b in @('feder_local', 'feder_test', 'feder_test_vacia')) {
      $existe = & $psql -h 127.0.0.1 -p $Puerto -U $Super -d postgres -tAc "SELECT 1 FROM pg_database WHERE datname='$b'"
      if ($existe -ne '1') { Reset-Database $b }
    }
  }
  'start' { Start-Cluster }
  'stop' { & $pg_ctl -D $Datos -w stop -m fast }
  'status' { & $pg_ctl -D $Datos status }
  'reset-db' { Start-Cluster; Reset-Database $Base }
}
