package dev.etran

import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.entity.EnderDragon
import org.bukkit.entity.EntityType
import org.bukkit.entity.Player
import org.bukkit.metadata.FixedMetadataValue
import org.bukkit.scheduler.BukkitRunnable
import org.bukkit.scheduler.BukkitTask
import org.bukkit.util.Vector
import java.util.Random
import java.util.UUID
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Everything that happens in ONE arena between sudden death and game end. */
class DragonSession(private val plugin: DragonFixPlugin, val info: ArenaInfo) {
    private val s: Settings get() = plugin.settings
    private val store get() = plugin.damageStore
    private val world = info.world
    private val rnd = Random()

    val dragons = ArrayList<DragonAI>()
    val dragonIds = HashSet<UUID>()

    /** True while this session's own spawn call is running (so the spawn listener ignores it). */
    var spawningOwn = false

    /** True while we apply our own contact damage (vanilla contact damage is cancelled). */
    var dealingDamage = false
    var ended = false
        private set

    private var task: BukkitTask? = null
    private var tickCount = 0L
    private var players: List<Player> = emptyList()
    private val anchors = HashMap<UUID, Location>()

    private val pad = 160.0
    private val minX = info.bases.minOf { it.spawn.x } - pad
    private val maxX = info.bases.maxOf { it.spawn.x } + pad
    private val minZ = info.bases.minOf { it.spawn.z } - pad
    private val maxZ = info.bases.maxOf { it.spawn.z } + pad

    private var cache = HashMap<Material, Boolean>()
    private var cacheFor: Settings? = null
    private var capWarned = false

    // ------------------------------------------------------------------ lifecycle

    /** [spawnOwn] = false only adopts dragons passed to [adopt] (replace-vanilla-dragons: false). */
    fun start(spawnOwn: Boolean = true) {
        val st = s
        var delay = 0L
        for (base in if (spawnOwn) info.bases else emptyList()) {
            var count = st.dragonsPerBase
            if (st.honorDragonBuff && base.buffExtra > 0) count += st.dragonBuffExtra
            repeat(count) {
                later(delay) { spawnOwn(base) }
                delay += st.spawnStagger
            }
        }
        repeat(if (spawnOwn) st.extraDragons else 0) {
            later(delay) { spawnOwn(null) }
            delay += st.spawnStagger
        }
        task = object : BukkitRunnable() {
            override fun run() = tick()
        }.runTaskTimer(plugin, 1L, 1L)
    }

    fun end() {
        if (ended) return
        ended = true
        task?.cancel()
        for (ai in dragons) runCatching { if (ai.dragon.isValid) ai.dragon.remove() }
        dragons.clear()
        dragonIds.clear()
        anchors.clear()
    }

    private fun later(delay: Long, block: () -> Unit) {
        if (delay <= 0L) block()
        else object : BukkitRunnable() {
            override fun run() {
                if (!ended) block()
            }
        }.runTaskLater(plugin, delay)
    }

    // ------------------------------------------------------------------ spawning

    private fun spawnY(): Double {
        val ceiling = (world.maxHeight - s.worldHeightMargin).toDouble()
        val wanted = info.avgBaseY + s.spawnHeightAboveAvg
        return min(ceiling, max(wanted, info.avgBaseY + 10.0))
    }

    private fun spawnOwn(base: BaseInfo?) {
        if (ended) return
        val st = s
        val ox = base?.spawn?.x ?: info.centerX
        val oz = base?.spawn?.z ?: info.centerZ
        val x = ox + (rnd.nextDouble() * 2 - 1) * st.spawnSpread
        val z = oz + (rnd.nextDouble() * 2 - 1) * st.spawnSpread
        val loc = Location(world, x, spawnY(), z)

        spawningOwn = true
        val dragon = try {
            world.spawnEntity(loc, EntityType.ENDER_DRAGON) as? EnderDragon
        } catch (t: Throwable) {
            plugin.logger.warning("Could not spawn dragon: ${t.message}")
            null
        } finally {
            spawningOwn = false
        }
        if (dragon != null) register(dragon, base, loc.toVector())
    }

    /** Takes control of an existing dragon (used when replace-vanilla-dragons is off). */
    fun adopt(dragon: EnderDragon) {
        if (ended || dragon.uniqueId in dragonIds) return
        register(dragon, null, dragon.location.toVector())
    }

    private fun register(dragon: EnderDragon, base: BaseInfo?, at: Vector) {
        val st = s
        runCatching {
            dragon.maxHealth = st.health
            dragon.health = st.health
        }
        dragon.setMetadata(DragonFixPlugin.META, FixedMetadataValue(plugin, true))
        if (st.neverPerch) DragonCompat.parkVanillaAi(dragon)

        // Start by diving toward the middle of the map; the first retarget happens immediately.
        val toCenter = Vector(info.centerX - at.x, 0.0, info.centerZ - at.z)
        if (toCenter.lengthSquared() < 1.0) toCenter.setX(1.0)
        val heading = toCenter.normalize().setY(-0.35)
        val ai = DragonAI(dragon, base, at.clone(), heading)
        dragons += ai
        dragonIds += dragon.uniqueId
        if (st.debug) plugin.logger.info("Dragon ${dragon.uniqueId} spawned for ${base?.name ?: "map"} at y=${at.y}")
    }

    // ------------------------------------------------------------------ main loop

    private fun tick() {
        if (ended) return
        tickCount++
        if (!info.arena.isPlaying) {
            plugin.endSession(info.arena, restoreMap = true)
            return
        }
        if (tickCount % 5L == 1L) players = BedwarsHook.participants(info.arena)

        val it = dragons.iterator()
        while (it.hasNext()) {
            val ai = it.next()
            if (!ai.dragon.isValid || ai.dragon.isDead) {
                dragonIds.remove(ai.dragon.uniqueId)
                it.remove()
                continue
            }
            try {
                tickDragon(ai)
            } catch (t: Throwable) {
                plugin.logger.warning("Dragon tick failed: ${t}")
                if (s.debug) t.printStackTrace()
            }
        }
    }

    private fun tickDragon(ai: DragonAI) {
        val st = s
        if (st.neverPerch && tickCount % 40L == 0L) DragonCompat.parkVanillaAi(ai.dragon)

        // --- decide whether the current goal is still valid
        ai.retargetIn--
        when (ai.mode) {
            Mode.HUNT -> {
                val p = ai.targetPlayer
                if (p == null || !validTarget(ai, p)) ai.retargetIn = 0
            }
            Mode.RAID -> {
                ai.raidTicksLeft--
                if (ai.raidTicksLeft <= 0) ai.retargetIn = 0
            }
            Mode.OVERSHOOT -> {
                ai.overshootTicksLeft--
                if (ai.overshootTicksLeft <= 0) ai.retargetIn = 0
            }
            Mode.WANDER -> {}
        }
        if (ai.retargetIn <= 0) chooseTarget(ai)

        // --- work out where to fly and how fast
        var speed = st.speed
        var dest: Vector = ai.point ?: wanderPoint()
        when (ai.mode) {
            Mode.HUNT -> {
                val p = ai.targetPlayer
                if (p != null) {
                    dest = p.location.toVector().add(Vector(0.0, st.targetYOffset, 0.0))
                    val h = horizontal(ai.pos, dest)
                    speed = if (h < 90.0) st.chargeSpeed else st.speed
                    if (h < st.passThroughDistance) {
                        // Flew through the target: carry on past it, then come back for another pass.
                        val fwd = ai.heading.clone().setY(0.0)
                        if (fwd.lengthSquared() < 1.0e-6) fwd.setX(1.0)
                        fwd.normalize().multiply(st.overshootDistance)
                        ai.point = ai.pos.clone().add(fwd).setY(ai.pos.y + 4.0)
                        ai.mode = Mode.OVERSHOOT
                        ai.overshootTicksLeft = (st.overshootDistance / max(0.1, st.chargeSpeed)).toInt() + 12
                        ai.retargetIn = ai.overshootTicksLeft + 1
                        dest = ai.point!!
                    }
                }
            }
            Mode.RAID -> {
                val rb = ai.raidBase
                if (rb != null) {
                    var p = ai.point
                    if (p == null || horizontal(ai.pos, p) < 5.0) {
                        p = raidWaypoint(rb)
                        ai.point = p
                    }
                    dest = p
                    val far = horizontal(ai.pos, Vector(rb.spawn.x, 0.0, rb.spawn.z)) > 60.0
                    speed = if (far) st.chargeSpeed else st.speed
                }
            }
            Mode.OVERSHOOT -> {
                speed = st.chargeSpeed
                if (horizontal(ai.pos, dest) < 6.0) ai.retargetIn = 0
            }
            Mode.WANDER -> {
                if (horizontal(ai.pos, dest) < 8.0) ai.retargetIn = 0
            }
        }

        // --- move
        val maxY = (world.maxHeight - st.worldHeightMargin).toDouble()
        ai.steer(dest, speed, st.turnRate, st.verticalSpeed, minX, maxX, 2.0, maxY, minZ, maxZ)
        ai.applyToEntity(world, st.yawOffset)

        // --- effects on the world
        if (st.mapDamage && tickCount % st.breakInterval == 0L) breakBlocks(ai)
        combat(ai)
        if (tickCount % 3L == 0L) freezeNearby(ai)
    }

    // ------------------------------------------------------------------ targeting

    private fun validTarget(ai: DragonAI, p: Player): Boolean {
        if (!p.isOnline || p.isDead || p.world != world) return false
        if (s.ignoreSpectators && p.gameMode == GameMode.SPECTATOR) return false
        if (s.protectOwnTeam && ai.base?.team?.isMember(p) == true) return false
        return true
    }

    private fun chooseTarget(ai: DragonAI) {
        val st = s
        ai.retargetIn = st.retargetInterval
        ai.lastRaidBase = ai.raidBase ?: ai.lastRaidBase
        ai.raidBase = null
        ai.targetPlayer = null
        ai.point = null

        val candidates = players.filter { p ->
            validTarget(ai, p) && distance3(ai.pos, p) <= st.huntRange
        }
        val raid = if (candidates.isEmpty()) st.raidWhenNoPlayers else rnd.nextDouble() < st.raidChance
        if (raid) {
            val base = pickRaidBase(ai)
            if (base != null) {
                ai.mode = Mode.RAID
                ai.raidBase = base
                ai.raidTicksLeft = st.raidDuration
                ai.retargetIn = st.raidDuration + 5
                ai.point = raidWaypoint(base)
                return
            }
        }
        if (candidates.isNotEmpty()) {
            ai.mode = Mode.HUNT
            ai.targetPlayer = when (st.targetStrategy) {
                TargetStrategy.NEAREST -> candidates.minByOrNull { distance3(ai.pos, it) }
                TargetStrategy.RANDOM -> candidates[rnd.nextInt(candidates.size)]
                TargetStrategy.LOWEST_HEALTH -> candidates.minByOrNull { it.health }
            }
            return
        }
        ai.mode = Mode.WANDER
        ai.point = wanderPoint()
    }

    private fun pickRaidBase(ai: DragonAI): BaseInfo? {
        var pool = info.bases.filter { it !== ai.base }
        if (pool.isEmpty()) pool = info.bases
        if (s.preferAliveBases) {
            val alive = pool.filter { it.alivePlayers().isNotEmpty() }
            if (alive.isNotEmpty()) pool = alive
        }
        val fresh = pool.filter { it !== ai.lastRaidBase }
        if (fresh.isNotEmpty()) pool = fresh
        return if (pool.isEmpty()) null else pool[rnd.nextInt(pool.size)]
    }

    /** A point in the middle of a base, at the level of the bases (average Y of the map). */
    private fun raidWaypoint(b: BaseInfo): Vector = Vector(
        b.spawn.x + (rnd.nextDouble() * 2 - 1) * 12.0,
        info.avgBaseY + s.raidHeight,
        b.spawn.z + (rnd.nextDouble() * 2 - 1) * 12.0,
    )

    private fun wanderPoint(): Vector = Vector(
        info.centerX + (rnd.nextDouble() * 2 - 1) * 60.0,
        info.avgBaseY + s.cruiseHeight,
        info.centerZ + (rnd.nextDouble() * 2 - 1) * 60.0,
    )

    private fun horizontal(a: Vector, b: Vector): Double {
        val dx = a.x - b.x
        val dz = a.z - b.z
        return sqrt(dx * dx + dz * dz)
    }

    private fun distance3(a: Vector, p: Player): Double {
        val l = p.location
        val dx = a.x - l.x
        val dy = a.y - l.y
        val dz = a.z - l.z
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    // ------------------------------------------------------------------ combat

    private fun combat(ai: DragonAI) {
        val st = s
        for (p in players) {
            if (!validTarget(ai, p)) continue
            val l = p.location
            val dx = l.x - ai.pos.x
            val dz = l.z - ai.pos.z
            val dy = l.y + 0.9 - ai.pos.y
            if (dx * dx + dz * dz > st.hitRadiusH * st.hitRadiusH || abs(dy) > st.hitRadiusV) continue

            val last = ai.lastHit[p.uniqueId]
            if (last != null && tickCount - last < st.hitCooldown) continue
            ai.lastHit[p.uniqueId] = tickCount

            if (st.contactDamage > 0.0) {
                dealingDamage = true
                try {
                    p.damage(st.contactDamage, ai.dragon)
                } finally {
                    dealingDamage = false
                }
            }
            if (st.fireTicks > 0) p.fireTicks = st.fireTicks

            val kb = Vector(dx, 0.0, dz)
            if (kb.lengthSquared() < 0.01) kb.setX(ai.heading.z).setZ(-ai.heading.x)
            kb.normalize().multiply(st.knockbackH).setY(st.knockbackV)
            p.velocity = kb
        }
    }

    /** Keeps shops / holograms where they are while a dragon is close (wing pushes can't move them). */
    private fun freezeNearby(ai: DragonAI) {
        val st = s
        if (st.freezeEntityTypes.isEmpty()) return
        for (e in ai.dragon.getNearbyEntities(24.0, 24.0, 24.0)) {
            if (e is Player || e is EnderDragon) continue
            if (e.type.name !in st.freezeEntityTypes) continue
            val anchor = anchors.getOrPut(e.uniqueId) { e.location.clone() }
            if (e.location.distanceSquared(anchor) > 0.04) {
                e.teleport(anchor)
                e.velocity = Vector(0, 0, 0)
            }
        }
    }

    // ------------------------------------------------------------------ map damage

    private fun breakableMaterial(t: Material): Boolean {
        val st = s
        if (cacheFor !== st) {
            cache = HashMap()
            cacheFor = st
        }
        return cache.getOrPut(t) {
            val n = t.name
            when {
                n == "AIR" || n.endsWith("_AIR") -> false
                n in st.protectedMaterials -> false
                st.protectBeds && (n == "BED_BLOCK" || n == "BED" || n.endsWith("_BED")) -> false
                !st.breakLiquids && n in LIQUIDS -> false
                else -> true
            }
        }
    }

    private fun inProtectedSphere(x: Int, y: Int, z: Int): Boolean {
        val cx = x + 0.5
        val cy = y + 0.5
        val cz = z + 0.5
        for (sp in info.protectedSpheres) {
            val dx = cx - sp[0]
            val dy = cy - sp[1]
            val dz = cz - sp[2]
            if (dx * dx + dy * dy + dz * dz <= sp[3] * sp[3]) return true
        }
        return false
    }

    private fun breakBlocks(ai: DragonAI) {
        val st = s
        val bx = ai.pos.blockX
        val by = ai.pos.blockY
        val bz = ai.pos.blockZ
        var broken = 0
        for (x in bx - st.breakRadiusH..bx + st.breakRadiusH) {
            for (z in bz - st.breakRadiusH..bz + st.breakRadiusH) {
                if (!world.isChunkLoaded(x shr 4, z shr 4)) continue
                for (y in by - st.breakRadiusV..by + st.breakRadiusV) {
                    if (y < st.minY || y >= world.maxHeight) continue
                    val block = world.getBlockAt(x, y, z)
                    if (!breakableMaterial(block.type)) continue
                    if (inProtectedSphere(x, y, z)) continue
                    if (st.respectBedwarsProtection && info.arena.isProtected(block.location)) continue
                    if (!store.record(block, st.maxStoredBlocks)) {
                        if (!capWarned) {
                            capWarned = true
                            plugin.logger.warning(
                                "Arena ${info.arena.worldName}: max-stored-blocks reached, dragons stop breaking blocks.",
                            )
                        }
                        return
                    }
                    BlockOps.removeBlock(block)
                    if (++broken >= st.maxBlocksPerTick) return
                }
            }
        }
    }

    companion object {
        private val LIQUIDS = setOf("WATER", "STATIONARY_WATER", "LAVA", "STATIONARY_LAVA", "BUBBLE_COLUMN")
    }
}
