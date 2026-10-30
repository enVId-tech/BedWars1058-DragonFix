# BedWars1058-DragonFix

Hypixel-style sudden-death dragons for BedWars1058 (and compatible forks such as BedWars2023),
Spigot/Paper 1.8 - 1.21.4, Java 8+.

## Build

```
gradle build          # jar ends up in build/libs/BedWars1058-DragonFix-1.0.0.jar
```

The only compile dependency is the Spigot **1.8.8** API. The BedWars API is used through reflection
(`BedwarsHook.kt`), so no BedWars jar is needed to build and the same jar works with both the
`com.andrei1058.bedwars` and `com.tomkeuper.bedwars` API packages. 1.9+ features (dragon phases)
are reflective too.

## What it does

* On sudden death BedWars spawns its own dragons (one per team x Dragon Buff). The first one in an
  arena starts a session; BedWars' dragons are removed one tick later and replaced by this plugin's.
* Dragon count = bases in the map x `spawning.dragons-per-base` (+ Dragon Buff extras).
* Dragons spawn `height-above-base-average` above the average Y of all bases, then dive to base level.
* Dragons never perch (vanilla AI is parked, the plugin owns the dragon's position).
* Dragons hunt players and raid bases, breaking any block except protected ones (BedWars' own
  protected regions / generator radius, shops, beds, barriers, ...). Every broken block is stored and
  restored at game end / server stop. If a restored block lands inside a player they are moved up to the next free block (`map-damage.restore.rescue-players`).
* `/bw-dragonfix reload`, `/bw-dragonfix status` (permission `dragonfix.admin`).
* `/bw-dragonfix config <list [section] | get <path> | set <path> <value> | add <path> <value> | remove <path> <value> | reload>`
  edits options live (e.g. `/bw-dragonfix config set map-damage.enabled false`); changes apply immediately and are saved to config.yml with comments kept.

See `config.yml` for every option.

## Target modes
`aggression.target-modes` is a list; pick one or several (each new target rolls one of the selected modes):
`RANDOM` (random points across the map), `HYBRID` (mostly random, sometimes a player), `BASE` (bases only),
`MIDDLE` (middle islands / generators only), `PLAYER` (players only, one random player at a time, switching every
`player-switch-seconds`), `ALL` (players, bases and middle - the default). `aggression.spread-out` sends dragons
to the bases with the fewest dragons so they do not pile on one base.
In game: `/bw-dragonfix config add aggression.target-modes BASE`, `/bw-dragonfix config remove aggression.target-modes ALL`.

## Even spread, altitude and base protection
* In `ALL` mode dragons are balanced over bases, middle islands, other map areas and players
  (`aggression.balance.*-weight`), and they patrol each area for `zone-duration-seconds`.
* Waypoints roll a height (`flight.low-pass-chance`, `flight.high-pass-chance`): low passes dive to the terrain, so the
  whole map gets carved, not one layer.
* `map-damage.protect-bases-and-generators` (default `false`) protects the terrain around bases and generators.
  Shops, upgrade NPCs and generators keep working either way (`functional-protect-radius`).

## Developer note
This plugin is coded with AI assistance and architected completely by me. Regardless, use at your own risk.

## License
This plugin is under the MIT License, check the [License](LICENSE) file for more details.