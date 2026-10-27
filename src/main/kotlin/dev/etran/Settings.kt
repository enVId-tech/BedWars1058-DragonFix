package dev.etran

import org.bukkit.configuration.file.FileConfiguration

enum class TargetStrategy { NEAREST, RANDOM, LOWEST_HEALTH }

/** What dragons go after. Several can be active at once; each new target rolls one of the selected modes. */
enum class TargetMode { RANDOM, HYBRID, BASE, MIDDLE, PLAYER, ALL }

/** Immutable snapshot of config.yml. A new one is built on every reload. */
class Settings(c: FileConfiguration) {
    // general
    val enabledArenas: Set<String> = c.getStringList("general.enabled-arenas").map { it.lowercase() }.toSet()
    val disabledArenas: Set<String> = c.getStringList("general.disabled-arenas").map { it.lowercase() }.toSet()
    val debug = c.getBoolean("general.debug", false)

    // spawning
    val replaceVanilla = c.getBoolean("spawning.replace-vanilla-dragons", true)
    val dragonsPerBase = c.getInt("spawning.dragons-per-base", 1).coerceAtLeast(0)
    val honorDragonBuff = c.getBoolean("spawning.honor-dragon-buff", true)
    val dragonBuffExtra = c.getInt("spawning.dragon-buff-extra-dragons", 1).coerceAtLeast(0)
    val extraDragons = c.getInt("spawning.extra-dragons", 0).coerceAtLeast(0)
    val spawnHeightAboveAvg = c.getDouble("spawning.height-above-base-average", 45.0)
    val worldHeightMargin = c.getInt("spawning.world-height-margin", 4)
    val spawnSpread = c.getDouble("spawning.spawn-spread", 6.0)
    val spawnStagger = c.getInt("spawning.spawn-stagger-ticks", 4).coerceAtLeast(0)
    val health = c.getDouble("spawning.health", 200.0).coerceAtLeast(1.0)
    val invulnerable = c.getBoolean("spawning.invulnerable", false)
    val removeOnGameEnd = c.getBoolean("spawning.remove-on-game-end", true)

    // flight
    val neverPerch = c.getBoolean("flight.never-perch", true)
    val speed = c.getDouble("flight.speed", 0.85)
    val chargeSpeed = c.getDouble("flight.charge-speed", 1.25)
    val turnRate = c.getDouble("flight.turn-rate", 14.0)
    val verticalSpeed = c.getDouble("flight.vertical-speed", 0.6)
    val cruiseHeight = c.getDouble("flight.cruise-height-above-base-average", 18.0)
    val raidHeight = c.getDouble("flight.raid-height-above-base-average", 2.0)
    val targetYOffset = c.getDouble("flight.target-y-offset", 1.0)
    val passThroughDistance = c.getDouble("flight.pass-through-distance", 14.0)
    val overshootDistance = c.getDouble("flight.overshoot-distance", 22.0)
    val yawOffset = c.getDouble("flight.yaw-offset", 180.0).toFloat()

    // aggression
    val huntRange = c.getDouble("aggression.hunt-range", 160.0)
    val retargetInterval = c.getInt("aggression.retarget-interval", 30).coerceAtLeast(1)
    val raidChance = c.getDouble("aggression.raid-chance", 0.45).coerceIn(0.0, 1.0)
    val raidWhenNoPlayers = c.getBoolean("aggression.raid-when-no-players", true)
    val raidDuration = c.getInt("aggression.raid-duration", 160).coerceAtLeast(20)
    val preferAliveBases = c.getBoolean("aggression.prefer-alive-team-bases", true)
    val ignoreSpectators = c.getBoolean("aggression.ignore-spectators", true)
    val protectOwnTeam = c.getBoolean("aggression.protect-own-team", false)
    val targetStrategy: TargetStrategy = runCatching {
        TargetStrategy.valueOf(c.getString("aggression.target-strategy", "NEAREST")!!.uppercase())
    }.getOrDefault(TargetStrategy.NEAREST)

    val targetModes: Set<TargetMode> = run {
        val raw = c.get("aggression.target-modes")
        val names: List<String> = when (raw) {
            is List<*> -> raw.map { it.toString() }
            is String -> raw.split(',', ' ', ';')
            else -> emptyList()
        }
        val set = names.mapNotNull { n -> runCatching { TargetMode.valueOf(n.trim().uppercase()) }.getOrNull() }.toSet()
        if (set.isEmpty()) setOf(TargetMode.ALL) else set
    }
    val randomMargin = c.getDouble("aggression.random-margin", 40.0).coerceAtLeast(0.0)
    val hybridPlayerChance = c.getDouble("aggression.hybrid-player-chance", 0.25).coerceIn(0.0, 1.0)
    val allMiddleChance = c.getDouble("aggression.all-middle-chance", 0.15).coerceIn(0.0, 1.0)
    val playerSwitchTicks = (c.getDouble("aggression.player-switch-seconds", 12.0) * 20).toInt().coerceAtLeast(20)
    val spreadOut = c.getBoolean("aggression.spread-out", true)

    // combat
    val contactDamage = c.getDouble("combat.contact-damage", 8.0)
    val hitRadiusH = c.getDouble("combat.hit-radius-horizontal", 7.0)
    val hitRadiusV = c.getDouble("combat.hit-radius-vertical", 4.5)
    val hitCooldown = c.getInt("combat.hit-cooldown", 20)
    val knockbackH = c.getDouble("combat.knockback-horizontal", 1.6)
    val knockbackV = c.getDouble("combat.knockback-vertical", 0.75)
    val fireTicks = c.getInt("combat.fire-ticks", 0)

    // map damage
    val mapDamage = c.getBoolean("map-damage.enabled", true)
    val breakRadiusH = c.getInt("map-damage.break-radius-horizontal", 4)
    val breakRadiusV = c.getInt("map-damage.break-radius-vertical", 3)
    val breakInterval = c.getInt("map-damage.interval-ticks", 2).coerceAtLeast(1)
    val maxBlocksPerTick = c.getInt("map-damage.max-blocks-per-tick", 120)
    val breakLiquids = c.getBoolean("map-damage.break-liquids", false)
    val maxStoredBlocks = c.getInt("map-damage.max-stored-blocks", 250000)
    val minY = c.getInt("map-damage.min-y", 1)
    val breakEffects = c.getBoolean("map-damage.break-effects", false)
    val protectedMaterials: Set<String> =
        c.getStringList("map-damage.protected-materials").map { it.uppercase() }.toSet()
    val respectBedwarsProtection = c.getBoolean("map-damage.respect-bedwars-protection", true)
    val protectBeds = c.getBoolean("map-damage.protect-beds", true)
    val protectGeneratorRadius = c.getDouble("map-damage.protect-generator-radius", 2.5)
    val protectShopRadius = c.getDouble("map-damage.protect-shop-radius", 2.5)
    val protectSpawnRadius = c.getDouble("map-damage.protect-spawn-radius", 0.0)
    val restoreOnGameEnd = c.getBoolean("map-damage.restore.on-game-end", true)
    val restoreOnDisable = c.getBoolean("map-damage.restore.on-disable", true)
    val restorePerTick = c.getInt("map-damage.restore.blocks-per-tick", 3000).coerceAtLeast(1)
    val restoreDelay = c.getInt("map-damage.restore.delay-ticks", 0).coerceAtLeast(0)
    val rescuePlayers = c.getBoolean("map-damage.restore.rescue-players", true)

    // entities
    val protectedEntityTypes: Set<String> =
        c.getStringList("entities.protected-types").map { it.uppercase() }.toSet()
    val freezeEntityTypes: Set<String> =
        c.getStringList("entities.freeze-types").map { it.uppercase() }.toSet()

    fun arenaAllowed(vararg names: String): Boolean {
        val lower = names.map { it.lowercase() }
        if (lower.any { it in disabledArenas }) return false
        return enabledArenas.isEmpty() || lower.any { it in enabledArenas }
    }
}
