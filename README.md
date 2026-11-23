# BastionMCCombat v1.0.1

**Código fuente para Purpur/Paper 1.21.11, Java 21.** Compilar con `gradle build`, o subir el contenido del ZIP a la raíz de GitHub y ejecutar Actions. El JAR estará en `build/libs/`.

## Funciones incluidas
- Etiquetado PvP de 20 segundos por daño directo y proyectiles; se reinicia con cada golpe. Admite combates grupales.
- ActionBar durante combate y mensaje final durante tres segundos, configurables en `messages.yml`.
- Bloqueo de comandos de escape/ventaja con alias `plugin:comando`, teletransportes, /fly y CHORUS_FRUIT.
- Perlas con cooldown de 20 segundos por defecto (configurable); zonas con cooldown específico por nombre de arena en `ender-pearls.zone-cooldowns`.
- `/combat setarena <nombre> 1` y `2`, `/combat delarena`, `/combat arenas`, `/combat reload`, `/combat add/remove/status <jugador>`.
- Bloqueo de salida de arenas marcadas por esquinas (solo si estás marcado en combate).
- Zonas seguras declaradas en `protected-zones` y regiones `pvp deny` de WorldGuard 7 (integración opcional).
- Si un jugador se desconecta marcado, muere normalmente **solo si la gamerule `keepInventory` es false**. Si es true, no recibe penalización. La caída de objetos depende de la muerte estándar y otros plugins del servidor.
- Si muere, pierde el estado. Los demás combatientes no pierden sus temporizadores.
- `arenas.yml` se guarda automáticamente, `messages.yml` y `config.yml` se pueden recargar.
- Colores legacy `&` y `&#RRGGBB`, incluidos degradados por carácter, en mensajes.

## Pendiente importante: BastionMCProtections
**No existe aún una integración real con las protecciones dinámicas de BastionMCProtections ni un bloqueo de su flag PvP.** Para implementarlo sin falsos positivos, el código de BastionMCProtections debe ofrecer una API o eventos de gestión de flags. No basta con instalar este JAR: **las bases que crea ese plugin NO están protegidas automáticamente del ingreso en combate.** Se puede usar temporalmente `protected-zones` para áreas conocidas. Tampoco detectamos automáticamente la futura Mina PvP; el cooldown 100s está reservado como parámetro para esa integración. No pongas este proyecto en producción hasta completar esa conexión si necesitas esas garantías.

## Nota de seguridad
La sanción por logout se implementa con la muerte normal al ejecutarse `PlayerQuitEvent`; hay que probar la interacción con plugins de muerte, keepInventory por-permisos, inventarios guardados y reinicios en el servidor de pruebas. No se manipulan manualmente inventarios para evitar duplicaciones.

## Permisos
`bastioncombat.admin.reload`, `.status`, `.add`, `.remove`, `.arenas` y `bastioncombat.admin.*` (OP por defecto), `bastioncombat.bypass` (desactivado por defecto). Los rangos adicionales se conceden con LuckPerms.

## Ejemplo cooldown específico
```yaml
ender-pearls:
  default-cooldown-seconds: 20
  zone-cooldowns:
    minapvp: 100
```
Se aplicará cuando una arena llamada `minapvp` esté configurada mediante `/combat setarena`.

## Instalación
1. Compila con GitHub Actions.
2. Instala el JAR en `plugins/` y reinicia.
3. Configura mundos, bloqueos y arenas.
4. Prueba en un servidor de desarrollo los casos de límites de arena, caída de objetos y `keepInventory`.

**No verificado aún mediante compilación de Gradle ni prueba en un servidor activo.**


## v1.0.1 - Corrección arenas y cristal rojo
- `spawn` ya no se excluye del PvP: la v1.0.0 lo excluía aunque contenía Mina PvP.
- Migración al iniciar: si el archivo existente solo excluía `spawn`, se corrige automáticamente.
- `/combat setarena` usa límites horizontales X/Z con altura completa por defecto.
- En combate, al intentar cruzar un límite prohibido, cancela el movimiento y muestra una pared temporal de cristal rojo **solo al jugador**, sin colocar bloques en el mapa.
- `barrier` y `arenas.full-height` son configurables en `config.yml`. Copia estas secciones a tu config existente o usa `/combat reload` tras añadirlas.
- Conserva `plugins/BastionMCCombat/arenas.yml`, que guarda los límites existentes.
- Los bloques falsos no son una barrera física: el bloqueo real se realiza con el evento de movimiento.
- La protección dinámica de BastionMCProtections continúa pendiente de integración.

## Aclaración sobre el mundo de la mina
El mundo del usuario se llama exactamente `Void` (V mayúscula), no `spawn`. `minapvp` es el ID de la región/arena, no el nombre del mundo. En `combat.disabled-worlds` no debe estar `Void`. Usa `/combat delarena minapvp` y `/combat setarena minapvp 1` y `2` dentro de `Void` si las esquinas se guardaron previamente en otro mundo. `combat.enabled-worlds: []` deja activo combate en los mundos PvP sin excluir Survival.


## v1.0.2 – paneles rojos
- Barrera visual con `RED_STAINED_GLASS_PANE` en la frontera del área PvP.
- Reenvío de paneles al cliente después de cancelar el movimiento para evitar que se oculten instantáneamente.
- Ningún bloque real del mundo es cambiado.
- Si conservas `plugins/BastionMCCombat/config.yml`, actualiza manualmente `barrier.material: RED_STAINED_GLASS_PANE` (o usa `/combat reload`).
- El bloqueo de salida de arenas se conserva.
- **El retraso de PvP tras reaparecer no se altera**: no se ha confirmado qué sistema produce los 3–5 segundos de inmunidad. Comprueba el mundo y región de reaparición y cualquier plugin de spawn protection antes de retirarlo.
