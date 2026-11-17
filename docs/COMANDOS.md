# Catálogo de comandos FEDER

Documento generado a partir del código (`java -jar feder-email-driven.jar documentar-comandos`).

Formato general del asunto del correo: `COMANDO["param1","param2",...]`. Todos los parámetros van entre comillas dobles; dentro de un valor se escribe `\"` para una comilla y `\\` para una barra invertida. `"*"` significa «todos» solo donde se indica. Un parámetro opcional se envía vacío (`""`).

Los comandos marcados con **Confirmación: sí** modifican datos: el sistema responde con un código de un solo uso (válido 30 minutos) enviado a la dirección registrada, y la operación se ejecuta al enviar `CONFIRMAR["CODIGO"]` desde esa misma cuenta.

Total de comandos: 142 (más `CONFIRMAR`).

## CU1 - Gestión de Usuarios

| Comando | Descripción | Acceso | Confirmación |
|---|---|---|---|
| `REGCLI` | Registro público como cliente con la dirección de correo del remitente; si los datos ya pertenecen a una cuenta, se avisa a esa cuenta en su dirección registrada | Público | sí |
| `ACTCLI` | Activa voluntariamente el rol CLIENTE en su propia cuenta aceptando los términos | Usuario registrado | sí |
| `VERUSU` | Muestra los datos de su cuenta (el ADMINISTRADOR puede consultar cualquiera) | Usuario registrado | no |
| `MODUSU` | Modifica sus nombres, apellidos o teléfono; deje en "" lo que no cambia | Usuario registrado | sí |
| `LISUSU` | Lista las cuentas de usuario con sus roles vigentes; filtra por correo o nombre, o por rol (ADMINISTRADOR, PERSONAL_FEDER, CLIENTE o PRODUCTOR) | ADMINISTRADOR | no |
| `DESUSU` | Desactiva una cuenta (baja lógica); nunca la del último administrador | ADMINISTRADOR | sí |
| `ACTUSU` | Reactiva una cuenta desactivada que ya estuvo activa | ADMINISTRADOR | sí |
| `INVUSU` | Invita a una persona como PERSONAL_FEDER o ADMINISTRADOR y le envía la invitación por correo; queda PENDIENTE hasta que acepte | ADMINISTRADOR | sí |
| `ACEINV` | La persona invitada acepta su invitación, por su número, enviando el comando desde el correo invitado | Público | sí |
| `RECINV` | La persona invitada rechaza su invitación, por su número, desde el correo invitado | Público | sí |
| `CANINV` | Cancela una invitación PENDIENTE y avisa por correo a la persona invitada | ADMINISTRADOR | sí |
| `REVROL` | Revoca una concesión VIGENTE y avisa por correo al titular; al revocar PRODUCTOR el perfil queda inactivo | ADMINISTRADOR | sí |
| `LISROL` | Lista el historial de concesiones de roles de su cuenta (el ADMINISTRADOR, de cualquiera; "*" = de todas las cuentas) | Usuario registrado | no |
| `LISBIT` | Lista los últimos registros de la bitácora, de una entidad (USUARIO_ROL, PRODUCTOR...) o de todas | ADMINISTRADOR | no |
| `LISRLS` | Lista los roles del sistema con la cantidad de cuentas que lo tienen vigente; filtra por código o nombre | ADMINISTRADOR | no |

### REGCLI

Registro público como cliente con la dirección de correo del remitente; si los datos ya pertenecen a una cuenta, se avisa a esa cuenta en su dirección registrada

- Sintaxis: `REGCLI["ci","ci_complemento","nombres","apellidos","telefono","acepto_terminos"]`
- Ejemplo: `REGCLI["99000031","","Lucas","Pérez Vaca","71234567","true"]`
- Acceso: Público
- Parámetros:
  1. ci (texto)
  2. ci_complemento (texto, opcional)
  3. nombres (texto)
  4. apellidos (texto)
  5. telefono (texto)
  6. acepto_terminos (true o false)

### ACTCLI

Activa voluntariamente el rol CLIENTE en su propia cuenta aceptando los términos

- Sintaxis: `ACTCLI["acepto_terminos"]`
- Ejemplo: `ACTCLI["true"]`
- Acceso: Usuario registrado
- Parámetros:
  1. acepto_terminos (true o false)

### VERUSU

Muestra los datos de su cuenta (el ADMINISTRADOR puede consultar cualquiera)

- Sintaxis: `VERUSU["correo"]`
- Ejemplo: `VERUSU["maria.quispe@feder.example"]`
- Acceso: Usuario registrado
- Parámetros:
  1. correo (correo electrónico)

### MODUSU

Modifica sus nombres, apellidos o teléfono; deje en "" lo que no cambia

- Sintaxis: `MODUSU["nombres","apellidos","telefono"]`
- Ejemplo: `MODUSU["","","71112233"]`
- Acceso: Usuario registrado
- Parámetros:
  1. nombres (texto, opcional)
  2. apellidos (texto, opcional)
  3. telefono (texto, opcional)

### LISUSU

Lista las cuentas de usuario con sus roles vigentes; filtra por correo o nombre, o por rol (ADMINISTRADOR, PERSONAL_FEDER, CLIENTE o PRODUCTOR)

- Sintaxis: `LISUSU["filtro"]`
- Ejemplo: `LISUSU["*"]`
- Acceso: ADMINISTRADOR
- Parámetros:
  1. filtro (texto, "*" = todos)

### DESUSU

Desactiva una cuenta (baja lógica); nunca la del último administrador

- Sintaxis: `DESUSU["correo","motivo"]`
- Ejemplo: `DESUSU["silvia.anez@feder.example","Cuenta duplicada informada por la titular"]`
- Acceso: ADMINISTRADOR
- Parámetros:
  1. correo (correo electrónico)
  2. motivo (texto)

### ACTUSU

Reactiva una cuenta desactivada que ya estuvo activa

- Sintaxis: `ACTUSU["correo","motivo"]`
- Ejemplo: `ACTUSU["silvia.anez@feder.example","La titular aclaró la situación de su cuenta"]`
- Acceso: ADMINISTRADOR
- Parámetros:
  1. correo (correo electrónico)
  2. motivo (texto)

### INVUSU

Invita a una persona como PERSONAL_FEDER o ADMINISTRADOR y le envía la invitación por correo; queda PENDIENTE hasta que acepte

- Sintaxis: `INVUSU["correo","ci","ci_complemento","nombres","apellidos","telefono","rol","motivo"]`
- Ejemplo: `INVUSU["ventas.ana.rivero@feder.example","99000020","","Ana","Rivero Paz","70000120","PERSONAL_FEDER","Incorporación como auxiliar de ventas"]`
- Acceso: ADMINISTRADOR
- Parámetros:
  1. correo (correo electrónico)
  2. ci (texto)
  3. ci_complemento (texto, opcional)
  4. nombres (texto)
  5. apellidos (texto)
  6. telefono (texto)
  7. rol (valor de la lista: ADMINISTRADOR\|PERSONAL_FEDER\|PRODUCTOR\|CLIENTE)
  8. motivo (texto)

### ACEINV

La persona invitada acepta su invitación, por su número, enviando el comando desde el correo invitado

- Sintaxis: `ACEINV["invitacion"]`
- Ejemplo: `ACEINV["6"]`
- Acceso: Público
- Parámetros:
  1. invitacion (número entero)

### RECINV

La persona invitada rechaza su invitación, por su número, desde el correo invitado

- Sintaxis: `RECINV["invitacion","motivo"]`
- Ejemplo: `RECINV["6","No continuaré el proceso de incorporación"]`
- Acceso: Público
- Parámetros:
  1. invitacion (número entero)
  2. motivo (texto)

### CANINV

Cancela una invitación PENDIENTE y avisa por correo a la persona invitada

- Sintaxis: `CANINV["correo","rol","motivo"]`
- Ejemplo: `CANINV["logistica.lucia.fernandez@feder.example","PERSONAL_FEDER","El puesto ya fue cubierto"]`
- Acceso: ADMINISTRADOR
- Parámetros:
  1. correo (correo electrónico)
  2. rol (valor de la lista: ADMINISTRADOR\|PERSONAL_FEDER\|PRODUCTOR\|CLIENTE)
  3. motivo (texto)

### REVROL

Revoca una concesión VIGENTE y avisa por correo al titular; al revocar PRODUCTOR el perfil queda inactivo

- Sintaxis: `REVROL["correo","rol","motivo"]`
- Ejemplo: `REVROL["ventas.jorge.suarez@feder.example","PERSONAL_FEDER","Finalización de su vínculo laboral"]`
- Acceso: ADMINISTRADOR
- Parámetros:
  1. correo (correo electrónico)
  2. rol (valor de la lista: ADMINISTRADOR\|PERSONAL_FEDER\|PRODUCTOR\|CLIENTE)
  3. motivo (texto)

### LISROL

Lista el historial de concesiones de roles de su cuenta (el ADMINISTRADOR, de cualquiera; "*" = de todas las cuentas)

- Sintaxis: `LISROL["correo"]`
- Ejemplo: `LISROL["ventas.jorge.suarez@feder.example"]`
- Acceso: Usuario registrado
- Parámetros:
  1. correo (correo electrónico, "*" = todos)

### LISBIT

Lista los últimos registros de la bitácora, de una entidad (USUARIO_ROL, PRODUCTOR...) o de todas

- Sintaxis: `LISBIT["entidad","limite"]`
- Ejemplo: `LISBIT["USUARIO_ROL","20"]`
- Acceso: ADMINISTRADOR
- Parámetros:
  1. entidad (texto, "*" = todos)
  2. limite (número entero, "*" = todos)

### LISRLS

Lista los roles del sistema con la cantidad de cuentas que lo tienen vigente; filtra por código o nombre

- Sintaxis: `LISRLS["filtro"]`
- Ejemplo: `LISRLS["*"]`
- Acceso: ADMINISTRADOR
- Parámetros:
  1. filtro (texto, "*" = todos)

## CU2 - Gestión de Productores y Comunidades Agrícolas

| Comando | Descripción | Acceso | Confirmación |
|---|---|---|---|
| `LISCOM` | Lista las comunidades agrícolas (productores y clientes ven solo las activas) | ADMINISTRADOR, CLIENTE, PERSONAL_FEDER, PRODUCTOR | no |
| `VERCOM` | Muestra una comunidad agrícola por su número; el personal ve también sus productores afiliados (productores y clientes, solo las activas) | ADMINISTRADOR, CLIENTE, PERSONAL_FEDER, PRODUCTOR | no |
| `INSCOM` | Registra una comunidad agrícola; departamento vacío = Santa Cruz | ADMINISTRADOR, PERSONAL_FEDER | sí |
| `MODCOM` | Modifica una comunidad; deje en "" los datos que no cambian | ADMINISTRADOR, PERSONAL_FEDER | sí |
| `DESCOM` | Desactiva una comunidad (baja lógica); sus productores conservan su afiliación | ADMINISTRADOR | sí |
| `ACTCOM` | Reactiva una comunidad desactivada | ADMINISTRADOR | sí |
| `SOLPRD` | Quiero ser productor de FEDER: presenta (o vuelve a presentar) su solicitud de alta | CLIENTE | sí |
| `LISPRD` | Lista los productores y solicitudes, opcionalmente por estado de la solicitud | ADMINISTRADOR, PERSONAL_FEDER | no |
| `VERPRD` | Muestra un perfil de productor por número o código; "*" muestra el suyo | Usuario registrado | no |
| `APRPRD` | Aprueba una solicitud PENDIENTE, concede el rol PRODUCTOR y avisa por correo al titular | ADMINISTRADOR | sí |
| `RECPRD` | Rechaza una solicitud PENDIENTE con su motivo, que se envía por correo al titular | ADMINISTRADOR | sí |
| `REHPRD` | Rehabilita un perfil APROBADA cuyo rol PRODUCTOR fue revocado y avisa por correo al titular | ADMINISTRADOR | sí |
| `DESPRD` | Da de baja a un productor por número o código: revoca su rol PRODUCTOR (igual que REVROL), su perfil queda inactivo y se avisa por correo al titular; se revierte con REHPRD | ADMINISTRADOR | sí |
| `CAMPRD` | Cambia la comunidad actual de un productor sin alterar el origen de su producción registrada | ADMINISTRADOR | sí |
| `MODPRD` | El productor actualiza la ubicación general de su actividad | PRODUCTOR | sí |

### LISCOM

Lista las comunidades agrícolas (productores y clientes ven solo las activas)

- Sintaxis: `LISCOM["filtro"]`
- Ejemplo: `LISCOM["*"]`
- Acceso: ADMINISTRADOR, CLIENTE, PERSONAL_FEDER, PRODUCTOR
- Parámetros:
  1. filtro (texto, "*" = todos)

### VERCOM

Muestra una comunidad agrícola por su número; el personal ve también sus productores afiliados (productores y clientes, solo las activas)

- Sintaxis: `VERCOM["comunidad"]`
- Ejemplo: `VERCOM["1"]`
- Acceso: ADMINISTRADOR, CLIENTE, PERSONAL_FEDER, PRODUCTOR
- Parámetros:
  1. comunidad (número entero)

### INSCOM

Registra una comunidad agrícola; departamento vacío = Santa Cruz

- Sintaxis: `INSCOM["nombre","municipio","departamento","descripcion"]`
- Ejemplo: `INSCOM["Comunidad San Antonio del Lomerío","San Antonio de Lomerío","","Artesanas del jabón de totaí"]`
- Acceso: ADMINISTRADOR, PERSONAL_FEDER
- Parámetros:
  1. nombre (texto)
  2. municipio (texto)
  3. departamento (texto, opcional)
  4. descripcion (texto, opcional)

### MODCOM

Modifica una comunidad; deje en "" los datos que no cambian

- Sintaxis: `MODCOM["comunidad","nombre","municipio","departamento","descripcion"]`
- Ejemplo: `MODCOM["2","","","","Apicultores organizados alrededor de un apiario comunitario"]`
- Acceso: ADMINISTRADOR, PERSONAL_FEDER
- Parámetros:
  1. comunidad (número entero)
  2. nombre (texto, opcional)
  3. municipio (texto, opcional)
  4. departamento (texto, opcional)
  5. descripcion (texto, opcional)

### DESCOM

Desactiva una comunidad (baja lógica); sus productores conservan su afiliación

- Sintaxis: `DESCOM["comunidad","motivo"]`
- Ejemplo: `DESCOM["3","La comunidad se fusionó con otra vecina"]`
- Acceso: ADMINISTRADOR
- Parámetros:
  1. comunidad (número entero)
  2. motivo (texto)

### ACTCOM

Reactiva una comunidad desactivada

- Sintaxis: `ACTCOM["comunidad","motivo"]`
- Ejemplo: `ACTCOM["4","Se afiliaron nuevas familias productoras"]`
- Acceso: ADMINISTRADOR
- Parámetros:
  1. comunidad (número entero)
  2. motivo (texto)

### SOLPRD

Quiero ser productor de FEDER: presenta (o vuelve a presentar) su solicitud de alta

- Sintaxis: `SOLPRD["comunidad","ubicacion_actividad"]`
- Ejemplo: `SOLPRD["2","Colmenas familiares junto al río, zona sur de la comunidad"]`
- Acceso: CLIENTE
- Parámetros:
  1. comunidad (número entero)
  2. ubicacion_actividad (texto)

### LISPRD

Lista los productores y solicitudes, opcionalmente por estado de la solicitud

- Sintaxis: `LISPRD["estado"]`
- Ejemplo: `LISPRD["PENDIENTE"]`
- Acceso: ADMINISTRADOR, PERSONAL_FEDER
- Parámetros:
  1. estado (valor de la lista: PENDIENTE\|APROBADA\|RECHAZADA, "*" = todos)

### VERPRD

Muestra un perfil de productor por número o código; "*" muestra el suyo

- Sintaxis: `VERPRD["productor"]`
- Ejemplo: `VERPRD["PROD-0001"]`
- Acceso: Usuario registrado
- Parámetros:
  1. productor (texto, "*" = todos)

### APRPRD

Aprueba una solicitud PENDIENTE, concede el rol PRODUCTOR y avisa por correo al titular

- Sintaxis: `APRPRD["productor","justificacion"]`
- Ejemplo: `APRPRD["4","Identidad verificada con el documento y colmenas comprobadas en la visita"]`
- Acceso: ADMINISTRADOR
- Parámetros:
  1. productor (número entero)
  2. justificacion (texto)

### RECPRD

Rechaza una solicitud PENDIENTE con su motivo, que se envía por correo al titular

- Sintaxis: `RECPRD["productor","motivo"]`
- Ejemplo: `RECPRD["4","No se pudo verificar la actividad declarada; corrija sus datos y vuelva a solicitar"]`
- Acceso: ADMINISTRADOR
- Parámetros:
  1. productor (número entero)
  2. motivo (texto)

### REHPRD

Rehabilita un perfil APROBADA cuyo rol PRODUCTOR fue revocado y avisa por correo al titular

- Sintaxis: `REHPRD["productor","motivo"]`
- Ejemplo: `REHPRD["1","Se resolvió la causa de la revocación tras la revisión"]`
- Acceso: ADMINISTRADOR
- Parámetros:
  1. productor (número entero)
  2. motivo (texto)

### DESPRD

Da de baja a un productor por número o código: revoca su rol PRODUCTOR (igual que REVROL), su perfil queda inactivo y se avisa por correo al titular; se revierte con REHPRD

- Sintaxis: `DESPRD["productor","motivo"]`
- Ejemplo: `DESPRD["PROD-0001","Dejó de producir y pidió la baja de su perfil"]`
- Acceso: ADMINISTRADOR
- Parámetros:
  1. productor (texto)
  2. motivo (texto)

### CAMPRD

Cambia la comunidad actual de un productor sin alterar el origen de su producción registrada

- Sintaxis: `CAMPRD["productor","comunidad","motivo"]`
- Ejemplo: `CAMPRD["2","3","El productor trasladó su apiario a la comunidad El Palmar"]`
- Acceso: ADMINISTRADOR
- Parámetros:
  1. productor (número entero)
  2. comunidad (número entero)
  3. motivo (texto)

### MODPRD

El productor actualiza la ubicación general de su actividad

- Sintaxis: `MODPRD["ubicacion_actividad"]`
- Ejemplo: `MODPRD["Taller familiar de extracción de aceite, zona norte de la comunidad"]`
- Acceso: PRODUCTOR
- Parámetros:
  1. ubicacion_actividad (texto)

## CU3 - Producción Agrícola, Asignación de Lotes y Trazabilidad de Origen

| Comando | Descripción | Acceso | Confirmación |
|---|---|---|---|
| `INSPCN` | Registra una producción PLANIFICADA propia; estimaciones y ubicación solo si se conocen | PRODUCTOR | sí |
| `INSTER` | Registra un producto ya elaborado: producción TERMINADA con su primer lote; comunidad vacía = la suya; fecha de inicio y observación solo si se conocen | PRODUCTOR | sí |
| `VALORI` | Valida, antes del registro, que un productor elaboró en otra comunidad en la fecha indicada | ADMINISTRADOR | sí |
| `ESTPCN` | Cambia el estado de su producción: EN_CURSO, TERMINADA o CANCELADA (con motivo) | PRODUCTOR | sí |
| `MODPCN` | Modifica fechas, estimación, ubicación u observación de su producción; "" = sin cambio | PRODUCTOR | sí |
| `CORPCN` | Corrige con motivo la ubicación de elaboración o la instantánea de origen de una producción | ADMINISTRADOR | sí |
| `INSLOT` | Asigna un lote de origen a su producción EN_CURSO o TERMINADA | PRODUCTOR | sí |
| `MODLOT` | Corrige un lote; tras su compra, elaboración, vencimiento y cantidad producida solo los corrige el ADMINISTRADOR | ADMINISTRADOR, PRODUCTOR | sí |
| `RETLOT` | Retira de la oferta a FEDER un lote DISPONIBLE propio | PRODUCTOR | sí |
| `LISPCN` | Lista producciones (el productor ve solo las suyas), opcionalmente por estado | ADMINISTRADOR, PERSONAL_FEDER, PRODUCTOR | no |
| `VERPCN` | Muestra una producción con sus lotes y su balance de cantidades | ADMINISTRADOR, PERSONAL_FEDER, PRODUCTOR | no |
| `LISLOT` | Lista lotes de origen (el productor ve solo los suyos), de una producción o de todas | ADMINISTRADOR, PERSONAL_FEDER, PRODUCTOR | no |
| `TRZLOT` | Trazabilidad de origen de un lote: producto, producción y comunidad de procedencia | Público | no |

### INSPCN

Registra una producción PLANIFICADA propia; estimaciones y ubicación solo si se conocen

- Sintaxis: `INSPCN["producto","fecha_inicio","fecha_disponibilidad_estimada","cantidad_estimada","ubicacion_produccion","observacion"]`
- Ejemplo: `INSPCN["FED-ACE-100","2026-12-01","2027-01-15","100","Taller familiar de extracción de aceite",""]`
- Acceso: PRODUCTOR
- Parámetros:
  1. producto (código)
  2. fecha_inicio (fecha AAAA-MM-DD)
  3. fecha_disponibilidad_estimada (fecha AAAA-MM-DD, opcional)
  4. cantidad_estimada (número decimal, opcional)
  5. ubicacion_produccion (texto, opcional)
  6. observacion (texto, opcional)

### INSTER

Registra un producto ya elaborado: producción TERMINADA con su primer lote; comunidad vacía = la suya; fecha de inicio y observación solo si se conocen

- Sintaxis: `INSTER["producto","fecha_elaboracion","fecha_vencimiento","cantidad_producida","cantidad_ofrecida","precio_ofrecido","ubicacion_produccion","comunidad_origen","fecha_inicio","observacion"]`
- Ejemplo: `INSTER["FED-JAB-090","2026-11-10","","200","150","9.50","Taller familiar de extracción de aceite","","2026-11-01","Jabón de aceite de totaí curado durante nueve días"]`
- Acceso: PRODUCTOR
- Parámetros:
  1. producto (código)
  2. fecha_elaboracion (fecha AAAA-MM-DD, opcional)
  3. fecha_vencimiento (fecha AAAA-MM-DD, opcional)
  4. cantidad_producida (número decimal)
  5. cantidad_ofrecida (número decimal)
  6. precio_ofrecido (número decimal, opcional)
  7. ubicacion_produccion (texto, opcional)
  8. comunidad_origen (número entero, opcional)
  9. fecha_inicio (fecha AAAA-MM-DD, opcional)
  10. observacion (texto, opcional)

### VALORI

Valida, antes del registro, que un productor elaboró en otra comunidad en la fecha indicada

- Sintaxis: `VALORI["productor","comunidad","fecha_elaboracion","motivo","evidencia"]`
- Ejemplo: `VALORI["2","1","2026-10-01","Trabajó en el taller de Totaizal hasta octubre","Acta comunal 14 del 2026-09-30"]`
- Acceso: ADMINISTRADOR
- Parámetros:
  1. productor (número entero)
  2. comunidad (número entero)
  3. fecha_elaboracion (fecha AAAA-MM-DD)
  4. motivo (texto)
  5. evidencia (texto)

### ESTPCN

Cambia el estado de su producción: EN_CURSO, TERMINADA o CANCELADA (con motivo)

- Sintaxis: `ESTPCN["produccion","estado","motivo"]`
- Ejemplo: `ESTPCN["PRD-2026-012","EN_CURSO",""]`
- Acceso: PRODUCTOR
- Parámetros:
  1. produccion (código)
  2. estado (valor de la lista: PLANIFICADA\|EN_CURSO\|TERMINADA\|CANCELADA)
  3. motivo (texto, opcional)

### MODPCN

Modifica fechas, estimación, ubicación u observación de su producción; "" = sin cambio

- Sintaxis: `MODPCN["produccion","fecha_inicio","fecha_disponibilidad_estimada","cantidad_estimada","ubicacion_produccion","observacion"]`
- Ejemplo: `MODPCN["PRD-2026-011","","2026-12-15","","","La extracción se retrasó por las lluvias"]`
- Acceso: PRODUCTOR
- Parámetros:
  1. produccion (código)
  2. fecha_inicio (fecha AAAA-MM-DD, opcional)
  3. fecha_disponibilidad_estimada (fecha AAAA-MM-DD, opcional)
  4. cantidad_estimada (número decimal, opcional)
  5. ubicacion_produccion (texto, opcional)
  6. observacion (texto, opcional)

### CORPCN

Corrige con motivo la ubicación de elaboración o la instantánea de origen de una producción

- Sintaxis: `CORPCN["produccion","campo","valor","motivo"]`
- Ejemplo: `CORPCN["PRD-2026-003","UBICACION_PRODUCCION","Sala de envasado nueva del apiario","Error al registrar la sala"]`
- Acceso: ADMINISTRADOR
- Parámetros:
  1. produccion (código)
  2. campo (valor de la lista: UBICACION_PRODUCCION\|NOMBRE_COMUNIDAD_ORIGEN\|MUNICIPIO_ORIGEN\|DEPARTAMENTO_ORIGEN)
  3. valor (texto)
  4. motivo (texto)

### INSLOT

Asigna un lote de origen a su producción EN_CURSO o TERMINADA

- Sintaxis: `INSLOT["produccion","fecha_elaboracion","fecha_vencimiento","cantidad_producida","cantidad_ofrecida","precio_ofrecido"]`
- Ejemplo: `INSLOT["PRD-2026-011","2026-11-12","2027-11-12","30","25","60.00"]`
- Acceso: PRODUCTOR
- Parámetros:
  1. produccion (código)
  2. fecha_elaboracion (fecha AAAA-MM-DD, opcional)
  3. fecha_vencimiento (fecha AAAA-MM-DD, opcional)
  4. cantidad_producida (número decimal)
  5. cantidad_ofrecida (número decimal)
  6. precio_ofrecido (número decimal, opcional)

### MODLOT

Corrige un lote; tras su compra, elaboración, vencimiento y cantidad producida solo los corrige el ADMINISTRADOR

- Sintaxis: `MODLOT["lote","fecha_elaboracion","fecha_vencimiento","cantidad_producida","cantidad_ofrecida","precio_ofrecido","motivo"]`
- Ejemplo: `MODLOT["LP-2026-011","","","","35","","Parte del lote se reservó para una feria"]`
- Acceso: ADMINISTRADOR, PRODUCTOR
- Parámetros:
  1. lote (código)
  2. fecha_elaboracion (fecha AAAA-MM-DD, opcional)
  3. fecha_vencimiento (fecha AAAA-MM-DD, opcional)
  4. cantidad_producida (número decimal, opcional)
  5. cantidad_ofrecida (número decimal, opcional)
  6. precio_ofrecido (número decimal, opcional)
  7. motivo (texto, opcional)

### RETLOT

Retira de la oferta a FEDER un lote DISPONIBLE propio

- Sintaxis: `RETLOT["lote","motivo"]`
- Ejemplo: `RETLOT["LP-2026-002","El lote se destinó a una feria local"]`
- Acceso: PRODUCTOR
- Parámetros:
  1. lote (código)
  2. motivo (texto)

### LISPCN

Lista producciones (el productor ve solo las suyas), opcionalmente por estado

- Sintaxis: `LISPCN["estado"]`
- Ejemplo: `LISPCN["*"]`
- Acceso: ADMINISTRADOR, PERSONAL_FEDER, PRODUCTOR
- Parámetros:
  1. estado (valor de la lista: PLANIFICADA\|EN_CURSO\|TERMINADA\|CANCELADA, "*" = todos)

### VERPCN

Muestra una producción con sus lotes y su balance de cantidades

- Sintaxis: `VERPCN["produccion"]`
- Ejemplo: `VERPCN["PRD-2026-011"]`
- Acceso: ADMINISTRADOR, PERSONAL_FEDER, PRODUCTOR
- Parámetros:
  1. produccion (código)

### LISLOT

Lista lotes de origen (el productor ve solo los suyos), de una producción o de todas

- Sintaxis: `LISLOT["produccion"]`
- Ejemplo: `LISLOT["PRD-2026-005"]`
- Acceso: ADMINISTRADOR, PERSONAL_FEDER, PRODUCTOR
- Parámetros:
  1. produccion (código, "*" = todos)

### TRZLOT

Trazabilidad de origen de un lote: producto, producción y comunidad de procedencia

- Sintaxis: `TRZLOT["lote"]`
- Ejemplo: `TRZLOT["LP-2026-001"]`
- Acceso: Público
- Parámetros:
  1. lote (código)

## CU4 - Catálogo Unificado, Inventarios, Alertas y Control de Concurrencia

| Comando | Descripción | Acceso | Confirmación |
|---|---|---|---|
| `LISCAT` | Lista las categorías del catálogo (el personal ve también las inactivas) | Público | no |
| `VERCAT` | Muestra una categoría del catálogo por su nombre, con la cantidad de productos activos (el personal ve también las inactivas) | Público | no |
| `INSCAT` | Registra una categoría del catálogo | ADMINISTRADOR, PERSONAL_FEDER | sí |
| `MODCAT` | Modifica el nombre o la descripción de una categoría (vacío = sin cambio) | ADMINISTRADOR, PERSONAL_FEDER | sí |
| `DESCAT` | Desactiva una categoría sin productos activos | ADMINISTRADOR, PERSONAL_FEDER | sí |
| `ACTCAT` | Vuelve a activar una categoría | ADMINISTRADOR, PERSONAL_FEDER | sí |
| `LISPRO` | Lista los productos del catálogo con su stock vendible (filtro por código, nombre o categoría) | Público | no |
| `VERPRO` | Muestra el detalle de un producto y su stock vendible | Público | no |
| `INSPRO` | Registra un producto en el catálogo | ADMINISTRADOR, PERSONAL_FEDER | sí |
| `MODPRO` | Modifica los datos de un producto (vacío = sin cambio; el precio se cambia con PREPRO) | ADMINISTRADOR, PERSONAL_FEDER | sí |
| `PREPRO` | Cambia el precio de venta de un producto (queda en la bitácora como CAMBIO_PRECIO) | ADMINISTRADOR, PERSONAL_FEDER | sí |
| `DESPRO` | Desactiva un producto: deja de publicarse y de venderse | ADMINISTRADOR, PERSONAL_FEDER | sí |
| `ACTPRO` | Vuelve a activar un producto del catálogo | ADMINISTRADOR, PERSONAL_FEDER | sí |
| `INSCMP` | Registra una compra a un productor con su primera línea (precio vacío = precio ofrecido) | ADMINISTRADOR, PERSONAL_FEDER | sí |
| `ADDCMP` | Agrega una línea a una compra REGISTRADA | ADMINISTRADOR, PERSONAL_FEDER | sí |
| `RECCMP` | Registra la recepción física de una línea: aceptado (entra al inventario) y rechazado, con clave única | ADMINISTRADOR, PERSONAL_FEDER | sí |
| `CANCMP` | Cancela cantidad pendiente de una línea de compra ("*" = todo lo pendiente), con motivo | ADMINISTRADOR, PERSONAL_FEDER | sí |
| `ANUREC` | Corrige una aceptación registrada de más (ANULACION_RECEPCION); opcionalmente la registra como rechazada | ADMINISTRADOR, PERSONAL_FEDER | sí |
| `PRECMP` | Corrige el precio pactado de una línea que todavía no tuvo mercancía aceptada | ADMINISTRADOR, PERSONAL_FEDER | sí |
| `PAGCMP` | Registra el pago al productor de una compra RECIBIDA (se paga lo aceptado). En la semilla, CMP-2026-003 queda RECIBIDA después del ejemplo de CANCMP | ADMINISTRADOR, PERSONAL_FEDER | sí |
| `RECPRE` | Documenta en la bitácora una diferencia de precio descubierta después de la aceptación o del pago (RECTIFICACION_PRECIO); no cambia el precio histórico | ADMINISTRADOR | sí |
| `LISCMP` | Lista las compras (un productor ve solo las suyas) | ADMINISTRADOR, PERSONAL_FEDER, PRODUCTOR | no |
| `VERCMP` | Muestra una compra con sus líneas (un productor solo ve sus compras) | ADMINISTRADOR, PERSONAL_FEDER, PRODUCTOR | no |
| `LISSTK` | Muestra por producto la existencia física, lo reservado, el stock vendible y la alerta | ADMINISTRADOR, PERSONAL_FEDER | no |
| `ALESTK` | Lista los productos activos agotados o bajo su stock mínimo | ADMINISTRADOR, PERSONAL_FEDER | no |
| `LISLTF` | Lista los lotes de FEDER con saldo, reservado, estado, ubicación y vencimiento de origen | ADMINISTRADOR, PERSONAL_FEDER | no |
| `LISMOV` | Muestra los movimientos de inventario de un lote de FEDER ("*" = de todos los lotes, del más reciente al más antiguo) | ADMINISTRADOR, PERSONAL_FEDER | no |
| `AJUSTK` | Registra un ajuste o una baja de un lote con motivo (AJUSTE_POSITIVO solo ADMINISTRADOR, con evidencia) | ADMINISTRADOR, PERSONAL_FEDER | sí |
| `LISVEN` | Lista los lotes con saldo que vencen en los próximos N días y los ya vencidos pendientes de baja ("*" = todos los lotes con saldo, por vencimiento) | ADMINISTRADOR, PERSONAL_FEDER | no |
| `BLQLTF` | Bloquea un lote de FEDER (mercancía dañada o error de origen): libera sus reservas y las reasigna con FEFO | ADMINISTRADOR, PERSONAL_FEDER | sí |
| `VENLTF` | Proceso diario: pasa a VENCIDO los lotes cuyo origen venció, libera sus reservas y las reasigna con FEFO | ADMINISTRADOR, PERSONAL_FEDER | sí |
| `LISDCM` | Lista las líneas de las compras a productores con lo recibido, rechazado, cancelado y pendiente ("*" = de todas las compras) | ADMINISTRADOR, PERSONAL_FEDER | no |

### LISCAT

Lista las categorías del catálogo (el personal ve también las inactivas)

- Sintaxis: `LISCAT["filtro"]`
- Ejemplo: `LISCAT["*"]`
- Acceso: Público
- Parámetros:
  1. filtro (texto, "*" = todos)

### VERCAT

Muestra una categoría del catálogo por su nombre, con la cantidad de productos activos (el personal ve también las inactivas)

- Sintaxis: `VERCAT["nombre"]`
- Ejemplo: `VERCAT["Miel y derivados"]`
- Acceso: Público
- Parámetros:
  1. nombre (texto)

### INSCAT

Registra una categoría del catálogo

- Sintaxis: `INSCAT["nombre","descripcion"]`
- Ejemplo: `INSCAT["Mieles","Productos apícolas"]`
- Acceso: ADMINISTRADOR, PERSONAL_FEDER
- Parámetros:
  1. nombre (texto)
  2. descripcion (texto, opcional)

### MODCAT

Modifica el nombre o la descripción de una categoría (vacío = sin cambio)

- Sintaxis: `MODCAT["nombre_actual","nuevo_nombre","descripcion"]`
- Ejemplo: `MODCAT["Harinas","","Harinas de frutos, granos y tubérculos de la región"]`
- Acceso: ADMINISTRADOR, PERSONAL_FEDER
- Parámetros:
  1. nombre_actual (texto)
  2. nuevo_nombre (texto, opcional)
  3. descripcion (texto, opcional)

### DESCAT

Desactiva una categoría sin productos activos

- Sintaxis: `DESCAT["nombre","motivo"]`
- Ejemplo: `DESCAT["Bebidas artesanales","Línea de bebidas suspendida"]`
- Acceso: ADMINISTRADOR, PERSONAL_FEDER
- Parámetros:
  1. nombre (texto)
  2. motivo (texto)

### ACTCAT

Vuelve a activar una categoría

- Sintaxis: `ACTCAT["nombre","motivo"]`
- Ejemplo: `ACTCAT["Bebidas artesanales","Se incorpora la línea de bebidas"]`
- Acceso: ADMINISTRADOR, PERSONAL_FEDER
- Parámetros:
  1. nombre (texto)
  2. motivo (texto)

### LISPRO

Lista los productos del catálogo con su stock vendible (filtro por código, nombre o categoría)

- Sintaxis: `LISPRO["filtro"]`
- Ejemplo: `LISPRO["miel"]`
- Acceso: Público
- Parámetros:
  1. filtro (texto, "*" = todos)

### VERPRO

Muestra el detalle de un producto y su stock vendible

- Sintaxis: `VERPRO["codigo"]`
- Ejemplo: `VERPRO["FED-ACE-020"]`
- Acceso: Público
- Parámetros:
  1. codigo (código)

### INSPRO

Registra un producto en el catálogo

- Sintaxis: `INSPRO["codigo","nombre","categoria","presentacion","unidad_medida","precio_venta","stock_minimo","permite_encargo","descripcion","imagen_url"]`
- Ejemplo: `INSPRO["FED-MIE-250","Miel de abeja 250 g","Miel y derivados","Frasco de vidrio de 250 g","UNIDAD","25.00","10","true","Miel multifloral",""]`
- Acceso: ADMINISTRADOR, PERSONAL_FEDER
- Parámetros:
  1. codigo (código)
  2. nombre (texto)
  3. categoria (texto)
  4. presentacion (texto)
  5. unidad_medida (valor de la lista: KILOGRAMO\|LIBRA\|ARROBA\|LITRO\|UNIDAD)
  6. precio_venta (número decimal)
  7. stock_minimo (número decimal)
  8. permite_encargo (true o false)
  9. descripcion (texto, opcional)
  10. imagen_url (texto, opcional)

### MODPRO

Modifica los datos de un producto (vacío = sin cambio; el precio se cambia con PREPRO)

- Sintaxis: `MODPRO["codigo","nombre","categoria","presentacion","unidad_medida","stock_minimo","permite_encargo","descripcion","imagen_url"]`
- Ejemplo: `MODPRO["FED-JAB-090","","","","","40","","",""]`
- Acceso: ADMINISTRADOR, PERSONAL_FEDER
- Parámetros:
  1. codigo (código)
  2. nombre (texto, opcional)
  3. categoria (texto, opcional)
  4. presentacion (texto, opcional)
  5. unidad_medida (valor de la lista: KILOGRAMO\|LIBRA\|ARROBA\|LITRO\|UNIDAD, opcional)
  6. stock_minimo (número decimal, opcional)
  7. permite_encargo (true o false, opcional)
  8. descripcion (texto, opcional)
  9. imagen_url (texto, opcional)

### PREPRO

Cambia el precio de venta de un producto (queda en la bitácora como CAMBIO_PRECIO)

- Sintaxis: `PREPRO["codigo","precio_venta","motivo"]`
- Ejemplo: `PREPRO["FED-ACE-020","26.00","Actualización de precios de fin de año"]`
- Acceso: ADMINISTRADOR, PERSONAL_FEDER
- Parámetros:
  1. codigo (código)
  2. precio_venta (número decimal)
  3. motivo (texto)

### DESPRO

Desactiva un producto: deja de publicarse y de venderse

- Sintaxis: `DESPRO["codigo","motivo"]`
- Ejemplo: `DESPRO["FED-MER-GUA","Fuera de temporada"]`
- Acceso: ADMINISTRADOR, PERSONAL_FEDER
- Parámetros:
  1. codigo (código)
  2. motivo (texto)

### ACTPRO

Vuelve a activar un producto del catálogo

- Sintaxis: `ACTPRO["codigo","motivo"]`
- Ejemplo: `ACTPRO["FED-MER-MAN","Inicio de la cosecha de mango"]`
- Acceso: ADMINISTRADOR, PERSONAL_FEDER
- Parámetros:
  1. codigo (código)
  2. motivo (texto)

### INSCMP

Registra una compra a un productor con su primera línea (precio vacío = precio ofrecido)

- Sintaxis: `INSCMP["codigo_productor","codigo_lote_origen","cantidad","precio_unitario","observacion"]`
- Ejemplo: `INSCMP["PROD-0001","LP-2026-002","20","9.00","Jabones para la campaña de diciembre"]`
- Acceso: ADMINISTRADOR, PERSONAL_FEDER
- Parámetros:
  1. codigo_productor (código)
  2. codigo_lote_origen (código)
  3. cantidad (número decimal)
  4. precio_unitario (número decimal, opcional)
  5. observacion (texto, opcional)

### ADDCMP

Agrega una línea a una compra REGISTRADA

- Sintaxis: `ADDCMP["codigo_compra","codigo_lote_origen","cantidad","precio_unitario"]`
- Ejemplo: `ADDCMP["CMP-2026-005","LP-2026-001","30","15.00"]`
- Acceso: ADMINISTRADOR, PERSONAL_FEDER
- Parámetros:
  1. codigo_compra (código)
  2. codigo_lote_origen (código)
  3. cantidad (número decimal)
  4. precio_unitario (número decimal, opcional)

### RECCMP

Registra la recepción física de una línea: aceptado (entra al inventario) y rechazado, con clave única

- Sintaxis: `RECCMP["codigo_compra","codigo_lote_origen","cantidad_aceptada","cantidad_rechazada","ubicacion","motivo","documento_entrega","clave_operacion"]`
- Ejemplo: `RECCMP["CMP-2026-005","LP-2026-011","28","2","Estante A-03","Dos botellas con el sello roto","NE-MQ-0002","REC-CMP005-LP011-01"]`
- Acceso: ADMINISTRADOR, PERSONAL_FEDER
- Parámetros:
  1. codigo_compra (código)
  2. codigo_lote_origen (código)
  3. cantidad_aceptada (número decimal)
  4. cantidad_rechazada (número decimal)
  5. ubicacion (texto, opcional)
  6. motivo (texto, opcional)
  7. documento_entrega (texto, opcional)
  8. clave_operacion (código)

### CANCMP

Cancela cantidad pendiente de una línea de compra ("*" = todo lo pendiente), con motivo

- Sintaxis: `CANCMP["codigo_compra","codigo_lote_origen","cantidad","motivo","clave_operacion"]`
- Ejemplo: `CANCMP["CMP-2026-003","LP-2026-008","*","La productora no entregará las tabletas restantes","CAN-CMP003-LP008-01"]`
- Acceso: ADMINISTRADOR, PERSONAL_FEDER
- Parámetros:
  1. codigo_compra (código)
  2. codigo_lote_origen (código)
  3. cantidad (número decimal, "*" = todos)
  4. motivo (texto)
  5. clave_operacion (código)

### ANUREC

Corrige una aceptación registrada de más (ANULACION_RECEPCION); opcionalmente la registra como rechazada

- Sintaxis: `ANUREC["codigo_compra","codigo_lote_origen","cantidad","registrar_como_rechazo","motivo","clave_operacion"]`
- Ejemplo: `ANUREC["CMP-2026-003","LP-2026-008","2","false","Se contaron dos tabletas de más","ANU-CMP003-LP008-01"]`
- Acceso: ADMINISTRADOR, PERSONAL_FEDER
- Parámetros:
  1. codigo_compra (código)
  2. codigo_lote_origen (código)
  3. cantidad (número decimal)
  4. registrar_como_rechazo (true o false)
  5. motivo (texto)
  6. clave_operacion (código)

### PRECMP

Corrige el precio pactado de una línea que todavía no tuvo mercancía aceptada

- Sintaxis: `PRECMP["codigo_compra","codigo_lote_origen","precio_unitario","motivo"]`
- Ejemplo: `PRECMP["CMP-2026-005","LP-2026-011","58.00","Precio acordado por escrito con la productora"]`
- Acceso: ADMINISTRADOR, PERSONAL_FEDER
- Parámetros:
  1. codigo_compra (código)
  2. codigo_lote_origen (código)
  3. precio_unitario (número decimal)
  4. motivo (texto)

### PAGCMP

Registra el pago al productor de una compra RECIBIDA (se paga lo aceptado). En la semilla, CMP-2026-003 queda RECIBIDA después del ejemplo de CANCMP

- Sintaxis: `PAGCMP["codigo_compra"]`
- Ejemplo: `PAGCMP["CMP-2026-003"]`
- Acceso: ADMINISTRADOR, PERSONAL_FEDER
- Parámetros:
  1. codigo_compra (código)

### RECPRE

Documenta en la bitácora una diferencia de precio descubierta después de la aceptación o del pago (RECTIFICACION_PRECIO); no cambia el precio histórico

- Sintaxis: `RECPRE["codigo_compra","codigo_lote_origen","precio_correcto","cantidad_afectada","motivo"]`
- Ejemplo: `RECPRE["CMP-2026-003","LP-2026-008","16.00","80","El acta de compra fijó 16.00 Bs por tableta"]`
- Acceso: ADMINISTRADOR
- Parámetros:
  1. codigo_compra (código)
  2. codigo_lote_origen (código)
  3. precio_correcto (número decimal)
  4. cantidad_afectada (número decimal)
  5. motivo (texto)

### LISCMP

Lista las compras (un productor ve solo las suyas)

- Sintaxis: `LISCMP["estado"]`
- Ejemplo: `LISCMP["*"]`
- Acceso: ADMINISTRADOR, PERSONAL_FEDER, PRODUCTOR
- Parámetros:
  1. estado (valor de la lista: REGISTRADA\|RECIBIDA_PARCIAL\|RECIBIDA\|CANCELADA, "*" = todos)

### VERCMP

Muestra una compra con sus líneas (un productor solo ve sus compras)

- Sintaxis: `VERCMP["codigo_compra"]`
- Ejemplo: `VERCMP["CMP-2026-003"]`
- Acceso: ADMINISTRADOR, PERSONAL_FEDER, PRODUCTOR
- Parámetros:
  1. codigo_compra (código)

### LISSTK

Muestra por producto la existencia física, lo reservado, el stock vendible y la alerta

- Sintaxis: `LISSTK["filtro"]`
- Ejemplo: `LISSTK["*"]`
- Acceso: ADMINISTRADOR, PERSONAL_FEDER
- Parámetros:
  1. filtro (texto, "*" = todos)

### ALESTK

Lista los productos activos agotados o bajo su stock mínimo

- Sintaxis: `ALESTK["filtro"]`
- Ejemplo: `ALESTK["*"]`
- Acceso: ADMINISTRADOR, PERSONAL_FEDER
- Parámetros:
  1. filtro (texto, "*" = todos)

### LISLTF

Lista los lotes de FEDER con saldo, reservado, estado, ubicación y vencimiento de origen

- Sintaxis: `LISLTF["filtro"]`
- Ejemplo: `LISLTF["FED-MIE"]`
- Acceso: ADMINISTRADOR, PERSONAL_FEDER
- Parámetros:
  1. filtro (texto, "*" = todos)

### LISMOV

Muestra los movimientos de inventario de un lote de FEDER ("*" = de todos los lotes, del más reciente al más antiguo)

- Sintaxis: `LISMOV["codigo_lote"]`
- Ejemplo: `LISMOV["LF-2026-001"]`
- Acceso: ADMINISTRADOR, PERSONAL_FEDER
- Parámetros:
  1. codigo_lote (código, "*" = todos)

### AJUSTK

Registra un ajuste o una baja de un lote con motivo (AJUSTE_POSITIVO solo ADMINISTRADOR, con evidencia)

- Sintaxis: `AJUSTK["codigo_lote","tipo","cantidad","motivo","clave_operacion","evidencia"]`
- Ejemplo: `AJUSTK["LF-2026-001","BAJA_DANADO","2","Dos botellas rotas al reordenar el estante","BAJA-LF001-20261115",""]`
- Acceso: ADMINISTRADOR, PERSONAL_FEDER
- Parámetros:
  1. codigo_lote (código)
  2. tipo (valor de la lista: ENTRADA_COMPRA\|ANULACION_RECEPCION\|SALIDA_VENTA\|ENTRADA_RETORNO_ENTREGA\|ENTRADA_DEVOLUCION\|SALIDA_REPOSICION\|AJUSTE_POSITIVO\|AJUSTE_NEGATIVO\|BAJA_VENCIDO\|BAJA_DANADO\|BAJA_DEFECTUOSO)
  3. cantidad (número decimal)
  4. motivo (texto)
  5. clave_operacion (código)
  6. evidencia (texto, opcional)

### LISVEN

Lista los lotes con saldo que vencen en los próximos N días y los ya vencidos pendientes de baja ("*" = todos los lotes con saldo, por vencimiento)

- Sintaxis: `LISVEN["dias"]`
- Ejemplo: `LISVEN["30"]`
- Acceso: ADMINISTRADOR, PERSONAL_FEDER
- Parámetros:
  1. dias (número entero, "*" = todos)

### BLQLTF

Bloquea un lote de FEDER (mercancía dañada o error de origen): libera sus reservas y las reasigna con FEFO

- Sintaxis: `BLQLTF["codigo_lote","motivo"]`
- Ejemplo: `BLQLTF["LF-2026-008","Humedad en las bolsas detectada en la revisión del almacén"]`
- Acceso: ADMINISTRADOR, PERSONAL_FEDER
- Parámetros:
  1. codigo_lote (código)
  2. motivo (texto)

### VENLTF

Proceso diario: pasa a VENCIDO los lotes cuyo origen venció, libera sus reservas y las reasigna con FEFO

- Sintaxis: `VENLTF["filtro"]`
- Ejemplo: `VENLTF["*"]`
- Acceso: ADMINISTRADOR, PERSONAL_FEDER
- Parámetros:
  1. filtro (texto, "*" = todos)

### LISDCM

Lista las líneas de las compras a productores con lo recibido, rechazado, cancelado y pendiente ("*" = de todas las compras)

- Sintaxis: `LISDCM["compra"]`
- Ejemplo: `LISDCM["*"]`
- Acceso: ADMINISTRADOR, PERSONAL_FEDER
- Parámetros:
  1. compra (código, "*" = todos)

## CU5 - Comercio Electrónico, Carrito de Compras, Ventas Inmediatas y Encargos

| Comando | Descripción | Acceso | Confirmación |
|---|---|---|---|
| `AGRCAR` | Agrega un producto al carrito (si ya está, suma la cantidad). El carrito no reserva stock | CLIENTE | sí |
| `QUICAR` | Quita una cantidad de un producto del carrito ("*" = quitar el producto) | CLIENTE | sí |
| `VERCAR` | Muestra su carrito ("*") o solo los productos indicados, separados por comas, con precios vigentes, stock vendible y el subtotal que debe indicar en PEDINM o PEDENC | CLIENTE | no |
| `VACCAR` | Vacía su carrito | CLIENTE | sí |
| `PEDINM` | Confirma su carrito como pedido inmediato: reserva el stock al instante (productos "*" = todo el carrito) | CLIENTE | sí |
| `PEDENC` | Confirma productos del carrito como encargo (sin stock suficiente y que acepten encargo). El ejemplo supone AGRCAR["FED-ACE-100","6"] y el subtotal que muestra VERCAR | CLIENTE | sí |
| `LISPED` | Lista pedidos y encargos (el cliente ve los suyos; el personal, todos) | ADMINISTRADOR, CLIENTE, PERSONAL_FEDER | no |
| `VERPED` | Muestra un pedido con sus líneas, su entrega y su historial | ADMINISTRADOR, CLIENTE, PERSONAL_FEDER | no |
| `CANPED` | Cancela un pedido mientras no esté despachado (libera sus reservas); el motivo es obligatorio | ADMINISTRADOR, CLIENTE, PERSONAL_FEDER | sí |
| `ACEENC` | Acepta un encargo PENDIENTE con su fecha estimada y, opcionalmente, la producción que lo respalda. La semilla no trae encargos PENDIENTE: el ejemplo usa el código que devuelve el ejemplo de PEDENC (consúltelo con LISPED["PENDIENTE"]) | ADMINISTRADOR, PERSONAL_FEDER | sí |
| `FECENC` | Actualiza la fecha estimada de disponibilidad de un encargo aceptado | ADMINISTRADOR, PERSONAL_FEDER | sí |
| `VINENC` | Vincula la producción que respalda una línea de un encargo PENDIENTE o ESPERANDO_ABASTECIMIENTO (la línea es la del producto de la producción) | ADMINISTRADOR, PERSONAL_FEDER | sí |
| `ABAPED` | Abastece un encargo en espera (o un pedido confirmado sin cobertura completa) con el stock vendible de FEDER | ADMINISTRADOR, PERSONAL_FEDER | sí |
| `EXPRES` | Expira las reservas de pedidos inmediatos pendientes cuyo plazo de pago venció y los cancela. Nunca rechaza un pago QR o TARJETA: un pedido con uno PENDIENTE no se cancela y se informa para conciliarlo con CONPAG o RECPAG | ADMINISTRADOR, PERSONAL_FEDER | sí |
| `LISCAR` | Lista los productos en los carritos de los clientes con su precio vigente ("*" = de todos los clientes) | ADMINISTRADOR, PERSONAL_FEDER | no |
| `LISDPE` | Lista las líneas de los pedidos: producto, cantidad, precio y subtotal ("*" = de todos los pedidos) | ADMINISTRADOR, PERSONAL_FEDER | no |
| `LISRES` | Lista las reservas de stock por lote de FEDER y su estado ("*" = de todos los pedidos) | ADMINISTRADOR, PERSONAL_FEDER | no |
| `LISHIS` | Lista los cambios de estado de los pedidos, del más reciente al más antiguo ("*" = de todos los pedidos) | ADMINISTRADOR, PERSONAL_FEDER | no |

### AGRCAR

Agrega un producto al carrito (si ya está, suma la cantidad). El carrito no reserva stock

- Sintaxis: `AGRCAR["codigo_producto","cantidad"]`
- Ejemplo: `AGRCAR["FED-ACE-020","2"]`
- Acceso: CLIENTE
- Parámetros:
  1. codigo_producto (código)
  2. cantidad (número decimal)

### QUICAR

Quita una cantidad de un producto del carrito ("*" = quitar el producto)

- Sintaxis: `QUICAR["codigo_producto","cantidad"]`
- Ejemplo: `QUICAR["FED-ACE-020","*"]`
- Acceso: CLIENTE
- Parámetros:
  1. codigo_producto (código)
  2. cantidad (número decimal, "*" = todos)

### VERCAR

Muestra su carrito ("*") o solo los productos indicados, separados por comas, con precios vigentes, stock vendible y el subtotal que debe indicar en PEDINM o PEDENC

- Sintaxis: `VERCAR["productos"]`
- Ejemplo: `VERCAR["*"]`
- Acceso: CLIENTE
- Parámetros:
  1. productos (texto, "*" = todos)

### VACCAR

Vacía su carrito

- Sintaxis: `VACCAR["filtro"]`
- Ejemplo: `VACCAR["*"]`
- Acceso: CLIENTE
- Parámetros:
  1. filtro (texto, "*" = todos)

### PEDINM

Confirma su carrito como pedido inmediato: reserva el stock al instante (productos "*" = todo el carrito)

- Sintaxis: `PEDINM["modalidad","direccion","referencia","nombre_receptor","telefono_contacto","productos","subtotal_esperado","observacion"]`
- Ejemplo: `PEDINM["DELIVERY","Barrio Las Palmeras, calle Los Mangales N.º 301","Edificio de tres pisos, depto. 2B","Andrés Molina Soliz","70000114","*","84.00","Entregar por la tarde"]`
- Acceso: CLIENTE
- Parámetros:
  1. modalidad (valor de la lista: RECOJO\|DELIVERY)
  2. direccion (texto, opcional)
  3. referencia (texto, opcional)
  4. nombre_receptor (texto)
  5. telefono_contacto (texto)
  6. productos (texto, "*" = todos)
  7. subtotal_esperado (número decimal)
  8. observacion (texto, opcional)

### PEDENC

Confirma productos del carrito como encargo (sin stock suficiente y que acepten encargo). El ejemplo supone AGRCAR["FED-ACE-100","6"] y el subtotal que muestra VERCAR

- Sintaxis: `PEDENC["modalidad","direccion","referencia","nombre_receptor","telefono_contacto","productos","subtotal_esperado","observacion"]`
- Ejemplo: `PEDENC["RECOJO","","","Daniela Flores Justiniano","70000111","FED-ACE-100","540.00","Para regalos de fin de año"]`
- Acceso: CLIENTE
- Parámetros:
  1. modalidad (valor de la lista: RECOJO\|DELIVERY)
  2. direccion (texto, opcional)
  3. referencia (texto, opcional)
  4. nombre_receptor (texto)
  5. telefono_contacto (texto)
  6. productos (texto, "*" = todos)
  7. subtotal_esperado (número decimal)
  8. observacion (texto, opcional)

### LISPED

Lista pedidos y encargos (el cliente ve los suyos; el personal, todos)

- Sintaxis: `LISPED["estado"]`
- Ejemplo: `LISPED["*"]`
- Acceso: ADMINISTRADOR, CLIENTE, PERSONAL_FEDER
- Parámetros:
  1. estado (valor de la lista: PENDIENTE\|ESPERANDO_ABASTECIMIENTO\|CONFIRMADO\|EN_PREPARACION\|DESPACHADO\|ENTREGADO\|CANCELADO, "*" = todos)

### VERPED

Muestra un pedido con sus líneas, su entrega y su historial

- Sintaxis: `VERPED["codigo_pedido"]`
- Ejemplo: `VERPED["PED-2026-0011"]`
- Acceso: ADMINISTRADOR, CLIENTE, PERSONAL_FEDER
- Parámetros:
  1. codigo_pedido (código)

### CANPED

Cancela un pedido mientras no esté despachado (libera sus reservas); el motivo es obligatorio

- Sintaxis: `CANPED["codigo_pedido","motivo"]`
- Ejemplo: `CANPED["PED-2026-0011","Ya no necesito los productos"]`
- Acceso: ADMINISTRADOR, CLIENTE, PERSONAL_FEDER
- Parámetros:
  1. codigo_pedido (código)
  2. motivo (texto)

### ACEENC

Acepta un encargo PENDIENTE con su fecha estimada y, opcionalmente, la producción que lo respalda. La semilla no trae encargos PENDIENTE: el ejemplo usa el código que devuelve el ejemplo de PEDENC (consúltelo con LISPED["PENDIENTE"])

- Sintaxis: `ACEENC["codigo_pedido","fecha_estimada","codigo_produccion","fundamento"]`
- Ejemplo: `ACEENC["PED-2026-0012","2026-12-20","","Producción PRD-2026-011 prevista para el 2026-12-05 más preparación"]`
- Acceso: ADMINISTRADOR, PERSONAL_FEDER
- Parámetros:
  1. codigo_pedido (código)
  2. fecha_estimada (fecha AAAA-MM-DD)
  3. codigo_produccion (código, opcional)
  4. fundamento (texto)

### FECENC

Actualiza la fecha estimada de disponibilidad de un encargo aceptado

- Sintaxis: `FECENC["codigo_pedido","fecha_estimada","motivo","fundamento"]`
- Ejemplo: `FECENC["PED-2026-0008","2026-12-19","La producción de respaldo se retrasó una semana","La productora informó por teléfono la nueva fecha de envasado"]`
- Acceso: ADMINISTRADOR, PERSONAL_FEDER
- Parámetros:
  1. codigo_pedido (código)
  2. fecha_estimada (fecha AAAA-MM-DD)
  3. motivo (texto)
  4. fundamento (texto)

### VINENC

Vincula la producción que respalda una línea de un encargo PENDIENTE o ESPERANDO_ABASTECIMIENTO (la línea es la del producto de la producción)

- Sintaxis: `VINENC["codigo_pedido","codigo_produccion"]`
- Ejemplo: `VINENC["PED-2026-0012","PRD-2026-011"]`
- Acceso: ADMINISTRADOR, PERSONAL_FEDER
- Parámetros:
  1. codigo_pedido (código)
  2. codigo_produccion (código)

### ABAPED

Abastece un encargo en espera (o un pedido confirmado sin cobertura completa) con el stock vendible de FEDER

- Sintaxis: `ABAPED["codigo_pedido"]`
- Ejemplo: `ABAPED["PED-2026-0008"]`
- Acceso: ADMINISTRADOR, PERSONAL_FEDER
- Parámetros:
  1. codigo_pedido (código)

### EXPRES

Expira las reservas de pedidos inmediatos pendientes cuyo plazo de pago venció y los cancela. Nunca rechaza un pago QR o TARJETA: un pedido con uno PENDIENTE no se cancela y se informa para conciliarlo con CONPAG o RECPAG

- Sintaxis: `EXPRES["filtro"]`
- Ejemplo: `EXPRES["*"]`
- Acceso: ADMINISTRADOR, PERSONAL_FEDER
- Parámetros:
  1. filtro (texto, "*" = todos)

### LISCAR

Lista los productos en los carritos de los clientes con su precio vigente ("*" = de todos los clientes)

- Sintaxis: `LISCAR["cliente"]`
- Ejemplo: `LISCAR["*"]`
- Acceso: ADMINISTRADOR, PERSONAL_FEDER
- Parámetros:
  1. cliente (correo electrónico, "*" = todos)

### LISDPE

Lista las líneas de los pedidos: producto, cantidad, precio y subtotal ("*" = de todos los pedidos)

- Sintaxis: `LISDPE["pedido"]`
- Ejemplo: `LISDPE["*"]`
- Acceso: ADMINISTRADOR, PERSONAL_FEDER
- Parámetros:
  1. pedido (código, "*" = todos)

### LISRES

Lista las reservas de stock por lote de FEDER y su estado ("*" = de todos los pedidos)

- Sintaxis: `LISRES["pedido"]`
- Ejemplo: `LISRES["*"]`
- Acceso: ADMINISTRADOR, PERSONAL_FEDER
- Parámetros:
  1. pedido (código, "*" = todos)

### LISHIS

Lista los cambios de estado de los pedidos, del más reciente al más antiguo ("*" = de todos los pedidos)

- Sintaxis: `LISHIS["pedido"]`
- Ejemplo: `LISHIS["*"]`
- Acceso: ADMINISTRADOR, PERSONAL_FEDER
- Parámetros:
  1. pedido (código, "*" = todos)

## CU6 - Procesamiento de Pedidos, Pagos Multicanal, Entregas y Notificaciones

| Comando | Descripción | Acceso | Confirmación |
|---|---|---|---|
| `REGPAG` | Registra un intento de pago de su pedido por QR o TARJETA (anticipo o saldo) o en EFECTIVO contra entrega (saldo completo). La clave es opcional (el prefijo PAG- lo reserva FEDER): reenviar la misma clave no duplica el pago | ADMINISTRADOR, CLIENTE, PERSONAL_FEDER | sí |
| `CONPAG` | Confirma un pago QR o TARJETA PENDIENTE tras verificar el cobro: referencia de la transacción e importe efectivamente cobrado (si no coincide, el pago sigue PENDIENTE). Si el pedido queda pagado por completo se emite su factura. Nadie verifica el pago de un pedido propio | ADMINISTRADOR, PERSONAL_FEDER | sí |
| `RECPAG` | Rechaza un pago PENDIENTE con un resultado definitivo sin cobro (rechazo, anulación o vencimiento del intento). El efectivo de un pedido ya aceptado contra entrega no se rechaza: se cobra al entregar | ADMINISTRADOR, PERSONAL_FEDER | sí |
| `LISPAG` | Lista los pagos de un pedido ("*" = todos; el cliente ve solo los de sus pedidos) | ADMINISTRADOR, CLIENTE, PERSONAL_FEDER | no |
| `FACPED` | Devuelve la factura de un pedido pagado por completo, que FEDER emite al completarse el pago; si un pedido pagado quedó sin factura, la emite (NIT/CI y razón social opcionales: por defecto, los del cliente). El ejemplo supone verificado el pago del ejemplo de CONPAG | ADMINISTRADOR, CLIENTE, PERSONAL_FEDER | sí |
| `ANUFAC` | Anula una factura cuando el procedimiento fiscal lo determina (la cancelación del pedido no la anula) | ADMINISTRADOR | sí |
| `VERFAC` | Muestra la factura de un pedido con sus líneas | ADMINISTRADOR, CLIENTE, PERSONAL_FEDER | no |
| `EJEREM` | Registra que un reembolso PENDIENTE se ejecutó (dinero devuelto), con su comprobante. El ejemplo supone cancelado el pedido PED-2026-0010, cuyo pago se reintegra | ADMINISTRADOR, PERSONAL_FEDER | sí |
| `FALREM` | Registra que un reembolso PENDIENTE falló; con "true" crea otro PENDIENTE que lo sustituye por el mismo monto, dentro de los límites (con "false", el sustituto se ordena después con SUSREM) | ADMINISTRADOR, PERSONAL_FEDER | sí |
| `SUSREM` | Ordena un reembolso nuevo que sustituye a uno FALLIDO (por ejemplo, cuando el cliente aporta otra cuenta), si no hay otro PENDIENTE por la misma causa y dentro de lo cobrado y no reintegrado. El ejemplo supone el reembolso del ejemplo de FALREM registrado con "false" | ADMINISTRADOR, PERSONAL_FEDER | sí |
| `LISREM` | Lista los reembolsos por estado (el cliente ve solo los de sus pedidos) | ADMINISTRADOR, CLIENTE, PERSONAL_FEDER | no |
| `CONPED` | Confirmación comercial de un pedido inmediato PENDIENTE: pagado por completo, o aceptado para efectivo contra entrega. El ejemplo supone rechazado el intento QR del pedido (RECPAG) y registrado REGPAG["PED-2026-0011","EFECTIVO","143.00",""] | ADMINISTRADOR, PERSONAL_FEDER | sí |
| `PREPED` | Pasa un pedido CONFIRMADO a EN_PREPARACION (todas sus líneas reservadas y el cobro resuelto) | ADMINISTRADOR, PERSONAL_FEDER | sí |
| `DESPED` | Despacha un pedido EN_PREPARACION: consume sus reservas (salida de inventario) y la entrega pasa a EN_CAMINO o LISTA_PARA_RECOJO. Fecha programada opcional. El ejemplo supone PREPED["PED-2026-0010"] | ADMINISTRADOR, PERSONAL_FEDER | sí |
| `ESTENT` | Registra el avance de una entrega despachada: FALLIDA, EN_CAMINO (reintento), RETORNADA (todo volvió; en recojo, no recogido) o NO_RECUPERADA (cierre de la búsqueda), con la observación | ADMINISTRADOR, PERSONAL_FEDER | sí |
| `RETENT` | Registra un retorno físico de mercancía de una entrega fallida, por reserva: recibido, apto (lo no apto se da de baja como DANADO o DEFECTUOSO), motivo, evidencia y clave opcional. Supone el ejemplo de ESTENT | ADMINISTRADOR, PERSONAL_FEDER | sí |
| `ENTPED` | Registra la entrega al cliente: el pedido pasa a ENTREGADO con el cobro completo (el efectivo contra entrega se confirma en este momento) | ADMINISTRADOR, PERSONAL_FEDER | sí |
| `LISENT` | Lista las entregas por estado (el cliente ve solo las de sus pedidos) | ADMINISTRADOR, CLIENTE, PERSONAL_FEDER | no |
| `VERENT` | Muestra la entrega de un pedido con lo despachado y lo retornado por reserva | ADMINISTRADOR, CLIENTE, PERSONAL_FEDER | no |
| `SOLDEV` | Solicita la devolución de unidades de un producto de su pedido ENTREGADO. El código es opcional: reenviar la misma solicitud con el mismo código devuelve la ya registrada (los códigos DEV-AAAA-NNNN los asigna FEDER) | ADMINISTRADOR, CLIENTE, PERSONAL_FEDER | sí |
| `RECDEV` | Registra la recepción física de la mercancía devuelta (una vez por devolución) | ADMINISTRADOR, PERSONAL_FEDER | sí |
| `INSDEV` | Registra la inspección de lo recibido: unidades aptas para volver a la venta (si ya está aprobada, las aptas aprobadas se reincorporan al stock). Supone el ejemplo de RECDEV | ADMINISTRADOR, PERSONAL_FEDER | sí |
| `LISDEV` | Lista las devoluciones por estado (el cliente ve solo las de sus pedidos) | ADMINISTRADOR, CLIENTE, PERSONAL_FEDER | no |
| `VERDEV` | Muestra una devolución por su código: estado, recepción, inspección, resolución y sus reembolsos (el cliente, solo las de sus pedidos; el personal ve también los movimientos de inventario) | ADMINISTRADOR, CLIENTE, PERSONAL_FEDER | no |
| `APRDEV` | Aprueba una devolución SOLICITADA con REPOSICION o REEMBOLSO y la cantidad aprobada; con REEMBOLSO fija el importe y ordena los reintegros | ADMINISTRADOR, PERSONAL_FEDER | sí |
| `RCHDEV` | Rechaza una devolución SOLICITADA con su motivo (lo recibido queda en custodia de FEDER) | ADMINISTRADOR, PERSONAL_FEDER | sí |
| `REPDEV` | Registra la entrega en FEDER de unidades de reposición de una devolución aprobada con REPOSICION (clave opcional). Supone APRDEV["DEV-2026-0003","REPOSICION","1",""] | ADMINISTRADOR, PERSONAL_FEDER | sí |
| `CERDEV` | Completa una devolución APROBADA cuando su reembolso o su reposición están completos y lo recibido está resuelto | ADMINISTRADOR, PERSONAL_FEDER | sí |
| `ANUDEV` | Anula una aprobación equivocada (sin reembolsos ordenados ni reposición entregada) y crea la devolución que la rectifica, SOLICITADA, para decidir de nuevo | ADMINISTRADOR, PERSONAL_FEDER | sí |
| `LISNOT` | Lista sus notificaciones, de la más reciente a la más antigua ("true" = solo las no leídas); el ADMINISTRADOR ve las de todos los usuarios | Usuario registrado | no |
| `LEENOT` | Marca como leída una notificación suya por su número ("*" = todas las no leídas) | Usuario registrado | sí |
| `LISFAC` | Lista las facturas emitidas, de la más reciente a la más antigua ("*" = todas, o VIGENTE / ANULADA) | ADMINISTRADOR, PERSONAL_FEDER | no |

### REGPAG

Registra un intento de pago de su pedido por QR o TARJETA (anticipo o saldo) o en EFECTIVO contra entrega (saldo completo). La clave es opcional (el prefijo PAG- lo reserva FEDER): reenviar la misma clave no duplica el pago

- Sintaxis: `REGPAG["codigo_pedido","metodo","monto","clave_idempotencia"]`
- Ejemplo: `REGPAG["PED-2026-0008","QR","300.00",""]`
- Acceso: ADMINISTRADOR, CLIENTE, PERSONAL_FEDER
- Parámetros:
  1. codigo_pedido (código)
  2. metodo (valor de la lista: QR\|TARJETA\|EFECTIVO)
  3. monto (número decimal)
  4. clave_idempotencia (texto, opcional)

### CONPAG

Confirma un pago QR o TARJETA PENDIENTE tras verificar el cobro: referencia de la transacción e importe efectivamente cobrado (si no coincide, el pago sigue PENDIENTE). Si el pedido queda pagado por completo se emite su factura. Nadie verifica el pago de un pedido propio

- Sintaxis: `CONPAG["clave_pago","referencia_externa","monto_cobrado"]`
- Ejemplo: `CONPAG["pago-ped0011-01","QR-20261115-000245","143.00"]`
- Acceso: ADMINISTRADOR, PERSONAL_FEDER
- Parámetros:
  1. clave_pago (texto)
  2. referencia_externa (texto)
  3. monto_cobrado (número decimal)

### RECPAG

Rechaza un pago PENDIENTE con un resultado definitivo sin cobro (rechazo, anulación o vencimiento del intento). El efectivo de un pedido ya aceptado contra entrega no se rechaza: se cobra al entregar

- Sintaxis: `RECPAG["clave_pago","motivo"]`
- Ejemplo: `RECPAG["pago-ped0011-01","El emisor informó que el código QR venció sin cobro"]`
- Acceso: ADMINISTRADOR, PERSONAL_FEDER
- Parámetros:
  1. clave_pago (texto)
  2. motivo (texto)

### LISPAG

Lista los pagos de un pedido ("*" = todos; el cliente ve solo los de sus pedidos)

- Sintaxis: `LISPAG["codigo_pedido"]`
- Ejemplo: `LISPAG["*"]`
- Acceso: ADMINISTRADOR, CLIENTE, PERSONAL_FEDER
- Parámetros:
  1. codigo_pedido (código, "*" = todos)

### FACPED

Devuelve la factura de un pedido pagado por completo, que FEDER emite al completarse el pago; si un pedido pagado quedó sin factura, la emite (NIT/CI y razón social opcionales: por defecto, los del cliente). El ejemplo supone verificado el pago del ejemplo de CONPAG

- Sintaxis: `FACPED["codigo_pedido","nit_ci","razon_social"]`
- Ejemplo: `FACPED["PED-2026-0011","99000014","Andrés Molina Soliz"]`
- Acceso: ADMINISTRADOR, CLIENTE, PERSONAL_FEDER
- Parámetros:
  1. codigo_pedido (código)
  2. nit_ci (texto, opcional)
  3. razon_social (texto, opcional)

### ANUFAC

Anula una factura cuando el procedimiento fiscal lo determina (la cancelación del pedido no la anula)

- Sintaxis: `ANUFAC["numero_factura","motivo"]`
- Ejemplo: `ANUFAC["FAC-2026-000008","Resolución SIN-2026-0042 del procedimiento de anulación fiscal"]`
- Acceso: ADMINISTRADOR
- Parámetros:
  1. numero_factura (código)
  2. motivo (texto)

### VERFAC

Muestra la factura de un pedido con sus líneas

- Sintaxis: `VERFAC["codigo_pedido"]`
- Ejemplo: `VERFAC["PED-2026-0010"]`
- Acceso: ADMINISTRADOR, CLIENTE, PERSONAL_FEDER
- Parámetros:
  1. codigo_pedido (código)

### EJEREM

Registra que un reembolso PENDIENTE se ejecutó (dinero devuelto), con su comprobante. El ejemplo supone cancelado el pedido PED-2026-0010, cuyo pago se reintegra

- Sintaxis: `EJEREM["clave_reembolso","comprobante"]`
- Ejemplo: `EJEREM["CANC-PED10-PAGO10","RQR-20261116-000077"]`
- Acceso: ADMINISTRADOR, PERSONAL_FEDER
- Parámetros:
  1. clave_reembolso (texto)
  2. comprobante (texto)

### FALREM

Registra que un reembolso PENDIENTE falló; con "true" crea otro PENDIENTE que lo sustituye por el mismo monto, dentro de los límites (con "false", el sustituto se ordena después con SUSREM)

- Sintaxis: `FALREM["clave_reembolso","motivo","sustituir"]`
- Ejemplo: `FALREM["CANC-PED10-PAGO10","La cuenta de destino rechazó la transferencia","true"]`
- Acceso: ADMINISTRADOR, PERSONAL_FEDER
- Parámetros:
  1. clave_reembolso (texto)
  2. motivo (texto)
  3. sustituir (true o false)

### SUSREM

Ordena un reembolso nuevo que sustituye a uno FALLIDO (por ejemplo, cuando el cliente aporta otra cuenta), si no hay otro PENDIENTE por la misma causa y dentro de lo cobrado y no reintegrado. El ejemplo supone el reembolso del ejemplo de FALREM registrado con "false"

- Sintaxis: `SUSREM["clave_reembolso_fallido"]`
- Ejemplo: `SUSREM["CANC-PED10-PAGO10"]`
- Acceso: ADMINISTRADOR, PERSONAL_FEDER
- Parámetros:
  1. clave_reembolso_fallido (texto)

### LISREM

Lista los reembolsos por estado (el cliente ve solo los de sus pedidos)

- Sintaxis: `LISREM["estado"]`
- Ejemplo: `LISREM["*"]`
- Acceso: ADMINISTRADOR, CLIENTE, PERSONAL_FEDER
- Parámetros:
  1. estado (valor de la lista: PENDIENTE\|EJECUTADO\|FALLIDO, "*" = todos)

### CONPED

Confirmación comercial de un pedido inmediato PENDIENTE: pagado por completo, o aceptado para efectivo contra entrega. El ejemplo supone rechazado el intento QR del pedido (RECPAG) y registrado REGPAG["PED-2026-0011","EFECTIVO","143.00",""]

- Sintaxis: `CONPED["codigo_pedido","comentario"]`
- Ejemplo: `CONPED["PED-2026-0011","Aceptado para pago en efectivo contra entrega"]`
- Acceso: ADMINISTRADOR, PERSONAL_FEDER
- Parámetros:
  1. codigo_pedido (código)
  2. comentario (texto, opcional)

### PREPED

Pasa un pedido CONFIRMADO a EN_PREPARACION (todas sus líneas reservadas y el cobro resuelto)

- Sintaxis: `PREPED["codigo_pedido"]`
- Ejemplo: `PREPED["PED-2026-0010"]`
- Acceso: ADMINISTRADOR, PERSONAL_FEDER
- Parámetros:
  1. codigo_pedido (código)

### DESPED

Despacha un pedido EN_PREPARACION: consume sus reservas (salida de inventario) y la entrega pasa a EN_CAMINO o LISTA_PARA_RECOJO. Fecha programada opcional. El ejemplo supone PREPED["PED-2026-0010"]

- Sintaxis: `DESPED["codigo_pedido","fecha_programada","observacion"]`
- Ejemplo: `DESPED["PED-2026-0010","2026-12-20 10:00","Retira la clienta en mostrador"]`
- Acceso: ADMINISTRADOR, PERSONAL_FEDER
- Parámetros:
  1. codigo_pedido (código)
  2. fecha_programada (fecha y hora AAAA-MM-DD HH:MM, opcional)
  3. observacion (texto, opcional)

### ESTENT

Registra el avance de una entrega despachada: FALLIDA, EN_CAMINO (reintento), RETORNADA (todo volvió; en recojo, no recogido) o NO_RECUPERADA (cierre de la búsqueda), con la observación

- Sintaxis: `ESTENT["codigo_pedido","estado","observacion"]`
- Ejemplo: `ESTENT["PED-2026-0009","FALLIDA","Nadie atendió en el domicilio; se dejó aviso"]`
- Acceso: ADMINISTRADOR, PERSONAL_FEDER
- Parámetros:
  1. codigo_pedido (código)
  2. estado (valor de la lista: PENDIENTE\|LISTA_PARA_RECOJO\|EN_CAMINO\|ENTREGADA\|FALLIDA\|RETORNADA\|NO_RECUPERADA\|CANCELADA)
  3. observacion (texto)

### RETENT

Registra un retorno físico de mercancía de una entrega fallida, por reserva: recibido, apto (lo no apto se da de baja como DANADO o DEFECTUOSO), motivo, evidencia y clave opcional. Supone el ejemplo de ESTENT

- Sintaxis: `RETENT["codigo_pedido","reserva","cantidad_recibida","cantidad_apta","tipo_baja","motivo","evidencia","clave_operacion"]`
- Ejemplo: `RETENT["PED-2026-0009","15","4","4","","Devuelto por el repartidor tras la entrega fallida","Guía de retorno GR-2026-0031","RET-PED0009-RES15-01"]`
- Acceso: ADMINISTRADOR, PERSONAL_FEDER
- Parámetros:
  1. codigo_pedido (código)
  2. reserva (número entero)
  3. cantidad_recibida (número decimal)
  4. cantidad_apta (número decimal)
  5. tipo_baja (valor de la lista: DANADO\|DEFECTUOSO, opcional)
  6. motivo (texto, opcional)
  7. evidencia (texto)
  8. clave_operacion (texto, opcional)

### ENTPED

Registra la entrega al cliente: el pedido pasa a ENTREGADO con el cobro completo (el efectivo contra entrega se confirma en este momento)

- Sintaxis: `ENTPED["codigo_pedido","observacion"]`
- Ejemplo: `ENTPED["PED-2026-0009","Recibió Marco Antelo en su domicilio"]`
- Acceso: ADMINISTRADOR, PERSONAL_FEDER
- Parámetros:
  1. codigo_pedido (código)
  2. observacion (texto, opcional)

### LISENT

Lista las entregas por estado (el cliente ve solo las de sus pedidos)

- Sintaxis: `LISENT["estado"]`
- Ejemplo: `LISENT["*"]`
- Acceso: ADMINISTRADOR, CLIENTE, PERSONAL_FEDER
- Parámetros:
  1. estado (valor de la lista: PENDIENTE\|LISTA_PARA_RECOJO\|EN_CAMINO\|ENTREGADA\|FALLIDA\|RETORNADA\|NO_RECUPERADA\|CANCELADA, "*" = todos)

### VERENT

Muestra la entrega de un pedido con lo despachado y lo retornado por reserva

- Sintaxis: `VERENT["codigo_pedido"]`
- Ejemplo: `VERENT["PED-2026-0009"]`
- Acceso: ADMINISTRADOR, CLIENTE, PERSONAL_FEDER
- Parámetros:
  1. codigo_pedido (código)

### SOLDEV

Solicita la devolución de unidades de un producto de su pedido ENTREGADO. El código es opcional: reenviar la misma solicitud con el mismo código devuelve la ya registrada (los códigos DEV-AAAA-NNNN los asigna FEDER)

- Sintaxis: `SOLDEV["codigo_pedido","codigo_producto","cantidad","motivo","codigo_devolucion"]`
- Ejemplo: `SOLDEV["PED-2026-0006","FED-CAF-250","1","Una bolsa llegó rota y con el café humedecido","DEV-AM-0006-01"]`
- Acceso: ADMINISTRADOR, CLIENTE, PERSONAL_FEDER
- Parámetros:
  1. codigo_pedido (código)
  2. codigo_producto (código)
  3. cantidad (número decimal)
  4. motivo (texto)
  5. codigo_devolucion (código, opcional)

### RECDEV

Registra la recepción física de la mercancía devuelta (una vez por devolución)

- Sintaxis: `RECDEV["codigo_devolucion","cantidad_recibida"]`
- Ejemplo: `RECDEV["DEV-2026-0003","1"]`
- Acceso: ADMINISTRADOR, PERSONAL_FEDER
- Parámetros:
  1. codigo_devolucion (código)
  2. cantidad_recibida (número decimal)

### INSDEV

Registra la inspección de lo recibido: unidades aptas para volver a la venta (si ya está aprobada, las aptas aprobadas se reincorporan al stock). Supone el ejemplo de RECDEV

- Sintaxis: `INSDEV["codigo_devolucion","cantidad_apta"]`
- Ejemplo: `INSDEV["DEV-2026-0003","1"]`
- Acceso: ADMINISTRADOR, PERSONAL_FEDER
- Parámetros:
  1. codigo_devolucion (código)
  2. cantidad_apta (número decimal)

### LISDEV

Lista las devoluciones por estado (el cliente ve solo las de sus pedidos)

- Sintaxis: `LISDEV["estado"]`
- Ejemplo: `LISDEV["*"]`
- Acceso: ADMINISTRADOR, CLIENTE, PERSONAL_FEDER
- Parámetros:
  1. estado (valor de la lista: SOLICITADA\|APROBADA\|RECHAZADA\|COMPLETADA\|ANULADA, "*" = todos)

### VERDEV

Muestra una devolución por su código: estado, recepción, inspección, resolución y sus reembolsos (el cliente, solo las de sus pedidos; el personal ve también los movimientos de inventario)

- Sintaxis: `VERDEV["devolucion"]`
- Ejemplo: `VERDEV["DEV-2026-0001"]`
- Acceso: ADMINISTRADOR, CLIENTE, PERSONAL_FEDER
- Parámetros:
  1. devolucion (código)

### APRDEV

Aprueba una devolución SOLICITADA con REPOSICION o REEMBOLSO y la cantidad aprobada; con REEMBOLSO fija el importe y ordena los reintegros

- Sintaxis: `APRDEV["codigo_devolucion","resolucion","cantidad_aprobada","comentario"]`
- Ejemplo: `APRDEV["DEV-2026-0003","REEMBOLSO","1","Tapa abollada verificada en la inspección"]`
- Acceso: ADMINISTRADOR, PERSONAL_FEDER
- Parámetros:
  1. codigo_devolucion (código)
  2. resolucion (valor de la lista: REPOSICION\|REEMBOLSO)
  3. cantidad_aprobada (número decimal)
  4. comentario (texto, opcional)

### RCHDEV

Rechaza una devolución SOLICITADA con su motivo (lo recibido queda en custodia de FEDER)

- Sintaxis: `RCHDEV["codigo_devolucion","motivo"]`
- Ejemplo: `RCHDEV["DEV-2026-0003","La abolladura no afecta el cierre hermético del frasco"]`
- Acceso: ADMINISTRADOR, PERSONAL_FEDER
- Parámetros:
  1. codigo_devolucion (código)
  2. motivo (texto)

### REPDEV

Registra la entrega en FEDER de unidades de reposición de una devolución aprobada con REPOSICION (clave opcional). Supone APRDEV["DEV-2026-0003","REPOSICION","1",""]

- Sintaxis: `REPDEV["codigo_devolucion","cantidad","clave_operacion"]`
- Ejemplo: `REPDEV["DEV-2026-0003","1",""]`
- Acceso: ADMINISTRADOR, PERSONAL_FEDER
- Parámetros:
  1. codigo_devolucion (código)
  2. cantidad (número decimal)
  3. clave_operacion (texto, opcional)

### CERDEV

Completa una devolución APROBADA cuando su reembolso o su reposición están completos y lo recibido está resuelto

- Sintaxis: `CERDEV["codigo_devolucion"]`
- Ejemplo: `CERDEV["DEV-2026-0003"]`
- Acceso: ADMINISTRADOR, PERSONAL_FEDER
- Parámetros:
  1. codigo_devolucion (código)

### ANUDEV

Anula una aprobación equivocada (sin reembolsos ordenados ni reposición entregada) y crea la devolución que la rectifica, SOLICITADA, para decidir de nuevo

- Sintaxis: `ANUDEV["codigo_devolucion","motivo"]`
- Ejemplo: `ANUDEV["DEV-2026-0003","Se aprobó por error una reposición: el cliente pidió reembolso"]`
- Acceso: ADMINISTRADOR, PERSONAL_FEDER
- Parámetros:
  1. codigo_devolucion (código)
  2. motivo (texto)

### LISNOT

Lista sus notificaciones, de la más reciente a la más antigua ("true" = solo las no leídas); el ADMINISTRADOR ve las de todos los usuarios

- Sintaxis: `LISNOT["solo_no_leidas"]`
- Ejemplo: `LISNOT["*"]`
- Acceso: Usuario registrado
- Parámetros:
  1. solo_no_leidas (true o false, "*" = todos)

### LEENOT

Marca como leída una notificación suya por su número ("*" = todas las no leídas)

- Sintaxis: `LEENOT["notificacion"]`
- Ejemplo: `LEENOT["*"]`
- Acceso: Usuario registrado
- Parámetros:
  1. notificacion (número entero, "*" = todos)

### LISFAC

Lista las facturas emitidas, de la más reciente a la más antigua ("*" = todas, o VIGENTE / ANULADA)

- Sintaxis: `LISFAC["estado"]`
- Ejemplo: `LISFAC["*"]`
- Acceso: ADMINISTRADOR, PERSONAL_FEDER
- Parámetros:
  1. estado (valor de la lista: VIGENTE\|ANULADA, "*" = todos)

## CU7 - Indicadores Financieros

| Comando | Descripción | Acceso | Confirmación |
|---|---|---|---|
| `INDING` | Ingresos reconocidos del período, con desglose por producto y categoría | ADMINISTRADOR | no |
| `INDCOS` | Costo de ventas, costo de posventa y mermas informativas del período | ADMINISTRADOR | no |
| `INDMAR` | Margen bruto comercial del período y margen porcentual | ADMINISTRADOR | no |
| `INDANT` | Anticipos pendientes a una fecha de corte | ADMINISTRADOR | no |
| `INDINV` | Valor del inventario de FEDER a costo a una fecha de corte | ADMINISTRADOR | no |
| `INDCOB` | Cobros confirmados del período por método de pago | ADMINISTRADOR | no |
| `INDREI` | Reintegros ejecutados del período por método y motivo | ADMINISTRADOR | no |
| `INDCMP` | Costo de las compras aceptadas del período por productor y origen | ADMINISTRADOR | no |
| `INDCNR` | Costo no recuperado pendiente de cancelación a una fecha de corte | ADMINISTRADOR | no |
| `INDRES` | Resumen de todos los indicadores financieros del período | ADMINISTRADOR | no |
| `INDSER` | Serie mensual de los indicadores financieros del período (hasta 24 meses) | ADMINISTRADOR | no |
| `INDCON` | Conciliaciones de control de caja e inventario a una fecha de corte | ADMINISTRADOR | no |

### INDING

Ingresos reconocidos del período, con desglose por producto y categoría

- Sintaxis: `INDING["desde","hasta"]`
- Ejemplo: `INDING["2026-01-01","2026-12-31"]`
- Acceso: ADMINISTRADOR
- Parámetros:
  1. desde (fecha AAAA-MM-DD)
  2. hasta (fecha AAAA-MM-DD)

### INDCOS

Costo de ventas, costo de posventa y mermas informativas del período

- Sintaxis: `INDCOS["desde","hasta"]`
- Ejemplo: `INDCOS["2026-01-01","2026-12-31"]`
- Acceso: ADMINISTRADOR
- Parámetros:
  1. desde (fecha AAAA-MM-DD)
  2. hasta (fecha AAAA-MM-DD)

### INDMAR

Margen bruto comercial del período y margen porcentual

- Sintaxis: `INDMAR["desde","hasta"]`
- Ejemplo: `INDMAR["2026-01-01","2026-12-31"]`
- Acceso: ADMINISTRADOR
- Parámetros:
  1. desde (fecha AAAA-MM-DD)
  2. hasta (fecha AAAA-MM-DD)

### INDANT

Anticipos pendientes a una fecha de corte

- Sintaxis: `INDANT["fecha_corte"]`
- Ejemplo: `INDANT["2026-11-15"]`
- Acceso: ADMINISTRADOR
- Parámetros:
  1. fecha_corte (fecha AAAA-MM-DD)

### INDINV

Valor del inventario de FEDER a costo a una fecha de corte

- Sintaxis: `INDINV["fecha_corte"]`
- Ejemplo: `INDINV["2026-11-15"]`
- Acceso: ADMINISTRADOR
- Parámetros:
  1. fecha_corte (fecha AAAA-MM-DD)

### INDCOB

Cobros confirmados del período por método de pago

- Sintaxis: `INDCOB["desde","hasta"]`
- Ejemplo: `INDCOB["2026-01-01","2026-12-31"]`
- Acceso: ADMINISTRADOR
- Parámetros:
  1. desde (fecha AAAA-MM-DD)
  2. hasta (fecha AAAA-MM-DD)

### INDREI

Reintegros ejecutados del período por método y motivo

- Sintaxis: `INDREI["desde","hasta"]`
- Ejemplo: `INDREI["2026-01-01","2026-12-31"]`
- Acceso: ADMINISTRADOR
- Parámetros:
  1. desde (fecha AAAA-MM-DD)
  2. hasta (fecha AAAA-MM-DD)

### INDCMP

Costo de las compras aceptadas del período por productor y origen

- Sintaxis: `INDCMP["desde","hasta"]`
- Ejemplo: `INDCMP["2026-01-01","2026-12-31"]`
- Acceso: ADMINISTRADOR
- Parámetros:
  1. desde (fecha AAAA-MM-DD)
  2. hasta (fecha AAAA-MM-DD)

### INDCNR

Costo no recuperado pendiente de cancelación a una fecha de corte

- Sintaxis: `INDCNR["fecha_corte"]`
- Ejemplo: `INDCNR["2026-11-15"]`
- Acceso: ADMINISTRADOR
- Parámetros:
  1. fecha_corte (fecha AAAA-MM-DD)

### INDRES

Resumen de todos los indicadores financieros del período

- Sintaxis: `INDRES["desde","hasta"]`
- Ejemplo: `INDRES["2026-01-01","2026-12-31"]`
- Acceso: ADMINISTRADOR
- Parámetros:
  1. desde (fecha AAAA-MM-DD)
  2. hasta (fecha AAAA-MM-DD)

### INDSER

Serie mensual de los indicadores financieros del período (hasta 24 meses)

- Sintaxis: `INDSER["desde","hasta"]`
- Ejemplo: `INDSER["2026-01-01","2026-12-31"]`
- Acceso: ADMINISTRADOR
- Parámetros:
  1. desde (fecha AAAA-MM-DD)
  2. hasta (fecha AAAA-MM-DD)

### INDCON

Conciliaciones de control de caja e inventario a una fecha de corte

- Sintaxis: `INDCON["fecha_corte"]`
- Ejemplo: `INDCON["2026-11-15"]`
- Acceso: ADMINISTRADOR
- Parámetros:
  1. fecha_corte (fecha AAAA-MM-DD)

## CU8 - Reportes y Estadísticas

| Comando | Descripción | Acceso | Confirmación |
|---|---|---|---|
| `REPVEN` | Reporte de ventas y pedidos de un período, como PDF o EXCEL. Filtros: producto, categoria, estado, tipo, modalidad y metodo (este solo en los cobros) | ADMINISTRADOR, PERSONAL_FEDER | no |
| `REPINV` | Reporte de inventario de FEDER a una fecha de corte, como PDF o EXCEL. Filtros: producto, categoria, productor, comunidad, estado del lote y desde/hasta de los movimientos | ADMINISTRADOR, PERSONAL_FEDER | no |
| `REPTRZ` | Trazabilidad de un pedido hasta la comunidad de origen, como PDF o EXCEL | ADMINISTRADOR, PERSONAL_FEDER | no |
| `REPABA` | Reporte de abastecimiento: compras, origen y productores, como PDF o EXCEL. Filtros: producto, categoria, productor, comunidad y estado de la compra | ADMINISTRADOR, PERSONAL_FEDER | no |

### REPVEN

Reporte de ventas y pedidos de un período, como PDF o EXCEL. Filtros: producto, categoria, estado, tipo, modalidad y metodo (este solo en los cobros)

- Sintaxis: `REPVEN["desde","hasta","formato","filtros"]`
- Ejemplo: `REPVEN["2026-01-01","2026-12-31","PDF","*"]`
- Acceso: ADMINISTRADOR, PERSONAL_FEDER
- Parámetros:
  1. desde (fecha AAAA-MM-DD)
  2. hasta (fecha AAAA-MM-DD)
  3. formato (valor de la lista: PDF\|EXCEL)
  4. filtros (texto, "*" = todos)

### REPINV

Reporte de inventario de FEDER a una fecha de corte, como PDF o EXCEL. Filtros: producto, categoria, productor, comunidad, estado del lote y desde/hasta de los movimientos

- Sintaxis: `REPINV["fecha_corte","formato","filtros"]`
- Ejemplo: `REPINV["2026-11-15","EXCEL","*"]`
- Acceso: ADMINISTRADOR, PERSONAL_FEDER
- Parámetros:
  1. fecha_corte (fecha AAAA-MM-DD)
  2. formato (valor de la lista: PDF\|EXCEL)
  3. filtros (texto, "*" = todos)

### REPTRZ

Trazabilidad de un pedido hasta la comunidad de origen, como PDF o EXCEL

- Sintaxis: `REPTRZ["codigo_pedido","formato"]`
- Ejemplo: `REPTRZ["PED-2026-0002","PDF"]`
- Acceso: ADMINISTRADOR, PERSONAL_FEDER
- Parámetros:
  1. codigo_pedido (código)
  2. formato (valor de la lista: PDF\|EXCEL)

### REPABA

Reporte de abastecimiento: compras, origen y productores, como PDF o EXCEL. Filtros: producto, categoria, productor, comunidad y estado de la compra

- Sintaxis: `REPABA["desde","hasta","formato","filtros"]`
- Ejemplo: `REPABA["2026-01-01","2026-12-31","EXCEL","productor=PROD-0001"]`
- Acceso: ADMINISTRADOR, PERSONAL_FEDER
- Parámetros:
  1. desde (fecha AAAA-MM-DD)
  2. hasta (fecha AAAA-MM-DD)
  3. formato (valor de la lista: PDF\|EXCEL)
  4. filtros (texto, "*" = todos)

## Sistema

| Comando | Descripción | Acceso | Confirmación |
|---|---|---|---|
| `HELP` | Muestra esta guía de comandos, los comandos de un tema (por ejemplo CU4) o el detalle de uno | Público | no |

### HELP

Muestra esta guía de comandos, los comandos de un tema (por ejemplo CU4) o el detalle de uno

- Sintaxis: `HELP["tema"]`
- Ejemplo: `HELP["*"]`
- Acceso: Público
- Parámetros:
  1. tema (texto, "*" = todos)

