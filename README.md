# FEDER Email-Driven

Sistema de información de **FEDER** (Federación de comunidades productoras agrícolas) que se opera
**por correo electrónico**: el usuario escribe un comando en el asunto de un correo, el sistema lo lee
del buzón por POP3, lo ejecuta contra PostgreSQL y responde por SMTP con un correo HTML.

Proyecto 1 de la materia INF513 (Tecnología Web), grupo 29SA. Implementa los ocho casos de uso
del modelo técnico aprobado (25 clases UML, restricciones R001–R102).

| Caso de uso | Contenido | Comandos |
|---|---|---|
| CU1 | Usuarios, roles, invitaciones y bitácora | 14 |
| CU2 | Productores y comunidades agrícolas | 13 |
| CU3 | Producción agrícola, lotes y trazabilidad de origen | 13 |
| CU4 | Catálogo, compras a productores, inventario y alertas | 30 |
| CU5 | Carrito, ventas inmediatas y encargos | 14 |
| CU6 | Pagos, facturas, entregas, devoluciones, reembolsos y notificaciones | 30 |
| CU7 | Indicadores financieros | 12 |
| CU8 | Reportes y estadísticas (con PDF y Excel adjuntos) | 4 |
| — | `HELP` y `CONFIRMAR` | 2 |

El catálogo completo, generado a partir del código, está en [docs/COMANDOS.md](docs/COMANDOS.md).

## Arquitectura

Tres capas estrictas; las dependencias solo van hacia abajo y la capa de negocio no conoce el correo
ni el HTML (los contratos `GeneradorRespuesta` y `EnviadorCorreo` se declaran en negocio y los
implementa presentación).

```
com.feder.presentacion   POP3, SMTP y MIME sobre sockets; analizador de comandos; respuestas HTML y adjuntos
com.feder.negocio        casos de uso CU1–CU8, seguridad, confirmaciones, mensajería, bitácora
com.feder.datos          repositorios JDBC (PreparedStatement) y gestor de transacciones
com.feder.modelo         las 25 entidades y sus enumeraciones
com.feder.configuracion  configuración central (APP_ENV) y registros
com.feder.Aplicacion     raíz de composición: el único lugar donde se conectan las capas
```

Diagramas: [docs/diagramas](docs/diagramas) (componentes, despliegue, secuencias, analizador,
casos de uso y modelos entidad-relación por caso de uso).

### Ciclo de procesamiento

1. Envía las respuestas pendientes de la bandeja de salida (`correo_saliente`).
2. Lista el buzón con `UIDL` y, por cada mensaje nuevo, en **una sola transacción**: lo reclama por
   `(buzón, uidl)`, ejecuta el comando, encola la respuesta y los avisos a terceros, y lo marca procesado.
3. Vuelve a enviar lo pendiente.

Si el proceso se detiene en cualquier punto, el siguiente ciclo continúa sin perder ni duplicar
operaciones: un mensaje ya registrado no se vuelve a ejecutar y una respuesta solo se marca enviada
cuando el servidor SMTP la aceptó. Los errores transitorios de la base (conexión, bloqueos) deshacen
todo y el mensaje se reintenta; los de un comando concreto se responden al usuario.

**Envío de respuestas: «al menos una vez».** Antes de contactar al servidor SMTP, cada respuesta se
reserva (con su intento contado) durante el plazo máximo del envío más un minuto, y el envío solo empieza
si la reserva es reciente (si el proceso quedó suspendido más de medio minuto antes de empezar, no envía y
la reserva vence sola; una suspensión más larga en pleno envío no puede descartarse y, en ese caso, el
correo podría salir dos veces). Un envío aceptado se registra siempre; un fallo solo se registra si la respuesta sigue con la misma
reserva (mismo intento y misma marca de tiempo), así que un intento antiguo no deshace lo que registró otro,
aunque el operador haya reactivado la respuesta. SMTP no permite saber si el servidor aceptó un mensaje
cuando la conexión se corta después de enviar los datos y antes de su respuesta, ni cuando el proceso cae
justo después de la aceptación: en esos casos la respuesta se reenvía en un intento posterior y puede
llegar dos veces, siempre con el mismo `Message-ID` (los clientes de correo pueden reconocer el
duplicado). La operación de negocio nunca se repite y los reenvíos están limitados por
`procesamiento.max.intentos.envio` (después, el operador decide con `reintentar-envios`).

### Seguridad

- **El remitente (`From`) puede falsificarse.** Por eso la respuesta siempre va a la dirección
  **registrada** de la cuenta, no a la que aparece en el mensaje.
- **Todo comando que modifica datos exige confirmación**: el sistema envía a la dirección registrada un
  código de 8 caracteres, de un solo uso y válido 30 minutos, y la operación se ejecuta solo al recibir
  `CONFIRMAR["CODIGO"]` desde esa misma cuenta. Se guarda solo el hash SHA-256 del código y la
  autorización se vuelve a comprobar al confirmar.
- Permisos por rol vigente (ADMINISTRADOR, PERSONAL_FEDER, PRODUCTOR, CLIENTE) y por propiedad de los
  datos (un cliente solo ve y opera sus pedidos, pagos, facturas y devoluciones).
- Límite de solicitudes por remitente y hora; se ignoran respuestas automáticas y rebotes (RFC 3834)
  para no crear bucles de correo.
- Las contraseñas solo se leen de variables de entorno o de archivos privados excluidos de Git;
  nunca se escriben en registros, respuestas ni mensajes de error.

## Tecnologías

- Java 17 y Maven (incluye el *wrapper*: `mvnw` / `mvnw.cmd`, no hace falta instalar Maven).
- PostgreSQL (probado en la versión 18) con el controlador JDBC 42.7.
- POP3 y SMTP implementados directamente sobre sockets, con TLS implícito o STARTTLS.
- JUnit 5 para las pruebas. Sin otras dependencias en tiempo de ejecución.

## Estructura del repositorio

```
config/      plantillas de configuración (*.example.properties); las copias privadas no se versionan
db/          01_schema.sql (esquema), 02_seed.sql (datos de demostración), migraciones/ y verificaciones/
docs/        COMANDOS.md, diagramas SVG y el documento técnico PUDS (PUDS_FEDER_INF513_P1_grupo29sa.pdf)
scripts/     PostgreSQL local, correo local, inicio/parada, demostración y diagnóstico de TecnoWeb
src/main/    código fuente (java/) y plantillas HTML de las respuestas (resources/plantillas/)
src/test/    pruebas unitarias, de integración con PostgreSQL y de extremo a extremo por sockets
```

## Requisitos

- JDK 17 o superior en el `PATH` (`java -version`).
- PostgreSQL 17 o superior instalado (solo se usan sus binarios; el proyecto crea su propio clúster).
  Si no está en `C:\Program Files\PostgreSQL\<versión>\bin`, defina `PG_BIN` con esa carpeta.
- Windows PowerShell 5.1 o PowerShell 7 para los scripts `.ps1`. En Linux: `sh`, `java` y `psql`.

## Puesta en marcha local (paso a paso)

El entorno local usa dos PostgreSQL y un servidor de correo de demostración (SMTP 2525 y POP3 1110, solo en 127.0.0.1):

- **La aplicación** usa el PostgreSQL instalado en Windows (servicio del sistema, `127.0.0.1:5432`, base `feder_local`).
- **Las pruebas automáticas** usan un clúster PostgreSQL propio del proyecto (`127.0.0.1:55432`, base `feder_test`).

> El clúster del proyecto acepta conexiones sin contraseña (autenticación `trust`) y solo escucha en 127.0.0.1:
> está pensado para un equipo personal de desarrollo. No lo use en un equipo compartido ni con datos reales.

```powershell
# 1. Configuración privada local: copie la plantilla y escriba la contraseña de su PostgreSQL en db.password
Copy-Item config\application-local.example.properties config\application-local.properties

# 2a. Base de la aplicación en el PostgreSQL de Windows (una sola vez): crear feder_local y cargar esquema y semilla
psql -h 127.0.0.1 -p 5432 -U postgres -c "CREATE DATABASE feder_local ENCODING 'UTF8' TEMPLATE template0"
psql -h 127.0.0.1 -p 5432 -U postgres -d feder_local -f db\01_schema.sql
psql -h 127.0.0.1 -p 5432 -U postgres -d feder_local -f db\02_seed.sql

# 2b. Clúster PostgreSQL de pruebas del proyecto (bases feder_local, feder_test y feder_test_vacia)
.\scripts\pg-local.ps1 init

# 3. Compilar
.\mvnw.cmd -B -DskipTests package

# 4. En una ventana: servidor de correo local (buzón del sistema y de cada usuario de la semilla)
.\scripts\correo-local.ps1

# 5. En otra ventana: el procesador de correo (pausa de 5 segundos entre una revisión del buzón y la siguiente)
.\scripts\iniciar.ps1
```

### Enviar un comando en el entorno local

```powershell
.\scripts\demo.ps1 enviar -De elmer.rojas@feder.example -Asunto 'LISPRO["*"]'
.\scripts\demo.ps1 leer   -De elmer.rojas@feder.example
```

Un comando que modifica datos devuelve primero el código de confirmación:

```powershell
.\scripts\demo.ps1 enviar -De almacen.carla.mendez@feder.example -Asunto 'INSCAT["Infusiones","Té y hierbas aromáticas"]'
.\scripts\demo.ps1 leer   -De almacen.carla.mendez@feder.example     # muestra CONFIRMAR["XXXXXXXX"]
.\scripts\demo.ps1 enviar -De almacen.carla.mendez@feder.example -Asunto 'CONFIRMAR["XXXXXXXX"]'
.\scripts\demo.ps1 leer   -De almacen.carla.mendez@feder.example
```

También puede usarse cualquier cliente de correo (Thunderbird, por ejemplo) contra
127.0.0.1:2525/1110 sin cifrado, con usuario = dirección completa y clave `buzon-local`.

### Detener

- Procesador: `Ctrl+C` en su ventana o `.\scripts\detener.ps1` (termina el ciclo en curso y sale).
- Servidor de correo local: `Ctrl+C` en su ventana.
- Clúster PostgreSQL: `.\scripts\pg-local.ps1 stop`.

Para volver la base local a su estado inicial: `.\scripts\pg-local.ps1 reset-db feder_local`
(solo acepta bases del clúster local con prefijo `feder_`).

## Formato de los comandos

```
COMANDO["parametro1","parametro2",...]
```

- El comando va en el **asunto**; el cuerpo del correo se ignora.
- Todos los parámetros van entre comillas dobles. Dentro de un valor, `\"` es una comilla y `\\` una
  barra invertida. Un parámetro opcional se envía vacío (`""`); `"*"` significa «todos» donde se indica.
- `HELP["*"]` lista los comandos disponibles para su rol; `HELP["REGPAG"]` muestra la sintaxis,
  los parámetros y un ejemplo de un comando.
- Si hay un error de sintaxis, la respuesta indica la posición exacta y, si el nombre se parece a
  un comando existente, lo sugiere.

### Codificación del asunto

Los clientes de correo envían las tildes y la ñ del asunto como «palabras codificadas» de RFC 2047
(`=?UTF-8?B?...?=` o `=?ISO-8859-1?Q?...?=`). El sistema las decodifica de forma **estricta**: nunca
adivina caracteres. Así se garantiza que un comando nunca llegue a la base de datos con texto alterado
(por ejemplo `Caf�` en lugar de `Café`).

- **Codificación válida**: el comando se procesa normalmente. Se admiten UTF-8, ISO-8859-1, windows-1252,
  US-ASCII, UTF-16, UTF-32 y los demás juegos estándar, en Base64 o en Q. También se admiten palabras
  contiguas, cabeceras plegadas y un carácter UTF-8 partido entre dos palabras (cada palabra en otro juego
  se decodifica por separado).
- **Codificación inválida**: el correo se responde con el título «Asunto con codificación no válida» y el
  motivo: juego de caracteres desconocido o no admitido, Base64 mal formado, codificación Q mal formada o
  bytes que no son texto válido en el juego declarado. **No se ejecuta ningún comando** ni se modifica
  ningún dato de negocio. En el registro de mensajes el asunto queda como «(asunto con codificación no
  válida)», para no guardar datos dudosos como un código de confirmación. Las respuestas automáticas y los
  rebotes se descartan antes y nunca se contestan.
- **Bytes de 8 bits sin codificar** (sin RFC 2047): se decide cabecera por cabecera. En el asunto solo se
  acepta UTF-8 válido (RFC 6532). Cualquier otro byte de 8 bits se rechaza, porque no permite saber qué se
  escribió: un asunto Latin-1 y uno UTF-8 truncado se ven iguales. Los clientes actuales nunca envían así el
  asunto. En las demás cabeceras (por ejemplo, el nombre del remitente) esos bytes se leen como ISO-8859-1,
  por compatibilidad con clientes antiguos.
- Una palabra codificada pegada a otro texto (por ejemplo dentro de comillas: `HELP["=?UTF-8?Q?=2A?="]`)
  no se decodifica: es un dato literal (RFC 2047 §5).
- Las etiquetas de ISO-8859-1 se interpretan como windows-1252, igual que en los navegadores, para aceptar
  € y las comillas tipográficas. No se admiten los juegos propios de Java (`x-...`: autodetección,
  variantes con marca de orden de bytes), CESU-8 ni los juegos con estado ISO-2022-KR e ISO-2022-CN. En
  ISO-2022-JP cada palabra debe terminar en modo ASCII (RFC 1468), para que no cambie el sentido de la siguiente.
- Los parámetros no pueden contener caracteres de control (C0, DEL ni C1) ni el carácter de reemplazo
  U+FFFD, que indica un texto dañado antes de llegar al sistema.
- Un nombre de remitente mal codificado se muestra literal y no impide procesar el comando.

## Configuración

El entorno se elige con la variable **`APP_ENV`** (`local` o `tecnoweb`), sin tocar código. Cada valor
se busca en este orden:

1. Variable de entorno: la clave en mayúsculas con `_` (por ejemplo `DB_URL`, `MAIL_HOST`, `DB_PASSWORD`).
2. Archivo privado `config/application-<entorno>.properties` (excluido de Git).
3. Valores por defecto no sensibles.

Las claves desconocidas se rechazan (protege de errores de escritura). Las contraseñas
(`db.password`, `mail.password`) se definen **solo** como variables de entorno o en el archivo privado;
en el entorno `tecnoweb` son obligatorias y su ausencia se explica antes de intentar conectar.

| Clave | Uso |
|---|---|
| `db.url`, `db.username`, `db.password` | Conexión JDBC a PostgreSQL |
| `mail.host` (o `mail.pop3.host` / `mail.smtp.host`) | Servidor de correo |
| `mail.username`, `mail.password`, `mail.from` | Buzón del sistema y dirección de las respuestas |
| `mail.pop3.port`, `mail.pop3.security` | `TLS` (995), `STARTTLS` (110 con STLS) o `NINGUNO` (solo local) |
| `mail.smtp.port`, `mail.smtp.security`, `mail.smtp.auth` | `STARTTLS` (587), `TLS` (465) o `NINGUNO`; `PLAIN`, `LOGIN` o `NINGUNA` |
| `mail.timeout.ms`, `mail.plazo.envio.segundos`, `mail.plazo.recepcion.segundos` | Espera por lectura y plazo máximo de cada envío SMTP y de cada operación POP3, que cubre la conexión TCP, el saludo TLS y el intercambio (un servidor lento no detiene el ciclo). La resolución del nombre del servidor (DNS), que Java no puede interrumpir, se espera como máximo `mail.timeout.ms` |
| `mail.permitir.texto.plano` | Solo `true` en local: permite credenciales sin cifrar contra 127.0.0.1 |
| `mail.pop3.eliminar.procesados` | Borra del buzón los mensajes ya procesados (por defecto no) |
| `procesamiento.intervalo.segundos` | Pausa entre ciclos en modo continuo |
| `seguridad.confirmacion.minutos`, `seguridad.limite.solicitudes.por.hora` | Confirmaciones y límite por remitente |

Las plantillas comentadas están en `config/application-local.example.properties` y
`config/application-tecnoweb.example.properties`.

## Base de datos

- `db/01_schema.sql`: esquema generado a partir del **modelo técnico** aprobado (25 tablas del modelo
  más 3 tablas técnicas del procesador de correo), con 46 claves foráneas, 114 restricciones CHECK y
  17 disparadores que protegen invariantes. Se ejecuta en una sola transacción y **no elimina nada**:
  si encuentra tablas de FEDER ya creadas, se detiene sin modificar la base.
- `db/02_seed.sql`: datos de demostración realistas (con direcciones `@feder.example`, que no pertenecen
  a personas reales). Es solo para entornos locales o de demostración.
- `db/migraciones/`: cambios del esquema posteriores a la primera instalación, numerados en orden
  (`001_correo_saliente_notificacion.sql`). Cada migración se ejecuta en una sola transacción, solo
  agrega objetos (nunca borra ni reescribe datos) y puede repetirse sin efecto. En una base instalada
  con el `01_schema.sql` actual no hay nada que aplicar.
- `db/verificaciones/`: comprobaciones del esquema y de la semilla, pruebas de las reglas en SQL y la
  [matriz de trazabilidad](db/verificaciones/MATRIZ_RESTRICCIONES.md) de las 102 restricciones.

### Actualizar una instalación existente

`01_schema.sql` no se vuelve a ejecutar sobre una base que ya tiene FEDER. Los cambios del esquema se
aplican con la acción `actualizar-esquema`, en dos pasos:

```powershell
# 1. Solo lectura: muestra la base, el usuario, el servidor y las migraciones pendientes
java -jar target\feder-email-driven.jar actualizar-esquema
# 2. Aplica las pendientes; exige el nombre exacto de la base que mostró el paso 1
java -jar target\feder-email-driven.jar actualizar-esquema --confirmar=feder_local
```

Cada migración se aplica en su propia transacción y después se vuelve a comprobar el resultado. Si la
base no tiene el esquema de FEDER, no se toca. Las acciones que procesan correo (`procesar`,
`procesar-una-vez` y `reintentar-envios`) comprueban el esquema al arrancar: si falta una migración se
detienen con un aviso y no leen el buzón; la actualización nunca se ejecuta sola.

### Primer administrador

Una base recién instalada no tiene cuentas. El primer ADMINISTRADOR se crea con un procedimiento de
instalación, fuera de los comandos por correo, que solo funciona mientras no exista ningún administrador:

```powershell
$env:APP_ENV = 'local'
java -jar target\feder-email-driven.jar inicializar-admin --correo=administracion@feder.example --ci=1234567 --nombres=Nombre --apellidos=Apellido --telefono=70000000
```

## Pruebas

```powershell
.\scripts\pg-local.ps1 init      # una vez: crea feder_test y feder_test_vacia
.\mvnw.cmd -B test
```

La suite incluye pruebas unitarias (analizador, MIME, formato, configuración), pruebas de cada caso de
uso contra PostgreSQL real (reglas, permisos, concurrencia con transacciones simultáneas) y pruebas de
extremo a extremo que envían correos reales por sockets al servidor local y comprueban la respuesta.
Las pruebas que necesitan PostgreSQL se omiten con un aviso si el clúster local no está en marcha.

## Despliegue en TecnoWeb

Valores institucionales conocidos (sin contraseñas):

| Variable | Valor |
|---|---|
| `DB_URL` | `jdbc:postgresql://db.tecnoweb.org.bo:5432/db_grupo29sa` |
| `DB_USERNAME` | `grupo29sa` |
| `MAIL_HOST` | `mail.tecnoweb.org.bo` |
| `MAIL_USERNAME` | `grupo29sa` |
| `MAIL_FROM` | `grupo29sa@tecnoweb.org.bo` |

Procedimiento seguro, en este orden:

1. **Configuración**: copie `config/application-tecnoweb.example.properties` como
   `config/application-tecnoweb.properties` y defina las contraseñas **solo** como variables de entorno
   en la sesión (no en archivos versionados):
   ```powershell
   $env:DB_PASSWORD = Read-Host 'Contraseña de PostgreSQL'
   $env:MAIL_PASSWORD = Read-Host 'Contraseña del correo'
   ```
2. **Diagnóstico no destructivo**: `.\scripts\diagnostico-tecnoweb.ps1`. Comprueba DNS, puertos de correo,
   saludo y capacidades POP3/SMTP, autenticación (sin leer, borrar ni enviar mensajes) y la conexión a
   PostgreSQL (solo lectura). Ajuste puertos y cifrado según el resultado.
3. **Verificar el destino antes de instalar**: `java -jar target\feder-email-driven.jar instalar-esquema`
   muestra la base, el usuario y las tablas existentes **sin modificar nada**. La instalación solo se
   ejecuta repitiendo el comando con `--confirmar=<nombre exacto de la base>`, y se detiene si ya hay
   tablas de FEDER.
4. **Si la base ya tenía FEDER instalado** (versión anterior): detenga el procesador, ejecute
   `java -jar target\feder-email-driven.jar actualizar-esquema` para ver, **sin modificar nada**, las
   migraciones pendientes, y repítalo con `--confirmar=db_grupo29sa` para aplicarlas. Solo agregan
   columnas, claves e índices: los datos existentes se conservan. Mientras falte alguna, el procesador
   se niega a arrancar.
5. **Primer administrador** con `inicializar-admin`, usando una dirección real del grupo.
6. **Correo de prueba**: desde la dirección del administrador, envíe `HELP["*"]` a
   `grupo29sa@tecnoweb.org.bo` y ejecute `.\scripts\iniciar.ps1 -Entorno tecnoweb -UnaVez`.
7. **Operación continua**: `.\scripts\iniciar.ps1 -Entorno tecnoweb` (Windows) o, en el servidor Linux,
   `scripts/iniciar.sh` o la línea de `scripts/crontab.ejemplo` (un ciclo por minuto con `flock`).

Los datos de demostración (`02_seed.sql`) **no** se cargan en la base institucional. Ningún script
ejecuta `DROP`, `TRUNCATE` ni borrados contra ella.

## Limitaciones conocidas

- **Pagos**: no hay un proveedor de pagos real. La pasarela local solo devuelve las indicaciones de pago;
  el personal verifica cada cobro QR o con tarjeta (`CONPAG`/`RECPAG`). Ningún comando realiza cobros.
- La factura no lleva código de autorización fiscal (no hay integración con un servicio de facturación).
- No existe un comando para cambiar el correo de una cuenta.
- El vencimiento de lotes (`VENLTF`) y la expiración de reservas (`EXPRES`) los ejecuta el personal, porque
  los movimientos de inventario exigen un responsable; las invitaciones vencidas se anulan solas.

## Repositorio

Código publicado en <https://github.com/Dan10104/PROYECTO-FEDER-1ER-PARCIAL>.

```bash
git clone https://github.com/Dan10104/PROYECTO-FEDER-1ER-PARCIAL.git
cd PROYECTO-FEDER-1ER-PARCIAL
./mvnw -B -DskipTests package        # en Windows: .\mvnw.cmd -B -DskipTests package
```

En Linux, si los scripts perdieron el permiso de ejecución al copiarse desde Windows:
`chmod +x mvnw scripts/*.sh`. Las configuraciones privadas (`config/application-*.properties` sin
`.example`, `*.env`) están excluidas en `.gitignore` y nunca deben subirse.

## Integrantes

Grupo 29SA de INF513 — Tecnología Web (ver la carátula del PUDS).

## Preguntas frecuentes

**No llega ninguna respuesta.** Compruebe que el procesador está en marcha y revise `logs/error.log`.
Las respuestas que el servidor SMTP rechazó repetidamente quedan en estado FALLIDO; tras corregir la
causa, `java -jar target\feder-email-driven.jar reintentar-envios` las vuelve a poner en cola.

**«Confirmación requerida»: ¿por qué?** El comando modifica datos. Envíe el `CONFIRMAR["..."]` indicado
desde la misma cuenta antes de 30 minutos. Si no lo pidió usted, ignore el mensaje: sin el código no se
ejecuta nada.

**¿Puedo enviar el mismo correo dos veces?** Sí. Cada mensaje del buzón se procesa una sola vez, y los
comandos de pago, retorno y reposición admiten una clave de idempotencia para que un reenvío no duplique
la operación.

**¿Qué pasa si se corta la conexión con la base o con el correo?** El ciclo se reintenta con esperas
crecientes (hasta 10 minutos) sin perder mensajes; las respuestas pendientes se envían cuando el servidor
vuelve a estar disponible.
