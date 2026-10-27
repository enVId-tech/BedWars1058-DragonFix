# DragonFix

Hypixel-style sudden-death dragons for BedWars1058 (and compatible forks such as BedWars2023),
Spigot/Paper 1.8 - 1.21.4, Java 8+.

## Build

```
gradle build          # jar ends up in build/libs/DragonFix-1.0.0.jar
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
  restored at game end / server stop.
* `/hxdragons reload`, `/hxdragons status` (permission `DragonFix.admin`).

See `config.yml` for every option.
