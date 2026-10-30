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
            Mode.PATROL -> {
                ai.zoneTicksLeft--
                if (ai.zoneTicksLeft <= 0) ai.retargetIn = 0
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
                    // A pass is over when the dragon actually hit the target, or when it missed and the target
                    // is now behind it. Merely getting close is not enough: the dragon keeps going until it connects.
                    val hitRecently = ai.lastHit[p.uniqueId]?.let { tickCount - it <= 3L } == true
                    val behind = ai.heading.x * (dest.x - ai.pos.x) + ai.heading.z * (dest.z - ai.pos.z) < 0.0
                    if (hitRecently || (h < st.passThroughDistance && behind)) {
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
            Mode.PATROL -> {
                var p = ai.point
                if (p == null || (horizontal(ai.pos, p) < 8.0 && abs(ai.pos.y - p.y) < 10.0)) {
                    p = zoneWaypoint(ai)
                    ai.point = p
                }
                dest = p
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
        val prevZone = ai.zone
        val prevId = ai.zoneId
        ai.zone = null

        // Keep chasing the same player until the switch period is over.
        val locked = ai.lockedTarget
        if (prevZone == Zone.PLAYER && locked != null && tickCount < ai.lockedUntil && validTarget(ai, locked)) {
            ai.zone = Zone.PLAYER
            ai.mode = Mode.HUNT
            ai.targetPlayer = locked
            ai.retargetIn = (ai.lockedUntil - tickCount).toInt().coerceAtLeast(1)
            return
        }

        val modes = st.targetModes
        val mode = if (TargetMode.ALL in modes) TargetMode.ALL else modes.toList()[rnd.nextInt(modes.size)]
        val inRange = players.filter { validTarget(ai, it) && distance3(ai.pos, it) <= st.huntRange }
        val anyone = players.filter { validTarget(ai, it) }

        when (mode) {
            TargetMode.RANDOM -> goRandom(ai)
            TargetMode.HYBRID -> {
                if (anyone.isNotEmpty() && rnd.nextDouble() < st.hybridPlayerChance) huntLocked(ai, anyone, true)
                else goRandom(ai)
            }
            TargetMode.BASE -> if (!goBase(ai)) goRandom(ai)
            TargetMode.MIDDLE -> goMiddle(ai, prevId)
            TargetMode.PLAYER -> if (anyone.isNotEmpty()) huntLocked(ai, anyone, true) else goRandom(ai)
            TargetMode.ALL -> goBalanced(ai, inRange, prevId)
        }
    }

    /**
     * ALL mode: every dragon joins the part of the map (bases / middle / other areas / players) that currently has
     * the fewest dragons relative to its weight, so dragons end up spread evenly instead of bunching up.
     */
    private fun goBalanced(ai: DragonAI, inRange: List<Player>, prevId: Int) {
        val st = s
        val cats = ArrayList<Pair<Zone, Double>>()
        if (st.baseWeight > 0.0 && info.bases.isNotEmpty()) cats += Pair(Zone.BASE, st.baseWeight)
        if (st.middleWeight > 0.0) cats += Pair(Zone.MIDDLE, st.middleWeight)
        if (st.sweepWeight > 0.0 && sweepCells.isNotEmpty()) cats += Pair(Zone.SWEEP, st.sweepWeight)
        if (st.playerWeight > 0.0 && inRange.isNotEmpty()) cats += Pair(Zone.PLAYER, st.playerWeight)
        if (cats.isEmpty()) {
            goRandom(ai)
            return
        }
        val scores = cats.map { (z, w) -> dragons.count { it !== ai && it.zone == z } / w }
        val best = scores.minOrNull() ?: 0.0
        val pool = cats.indices.filter { scores[it] <= best + 1.0e-9 }
        when (cats[pool[rnd.nextInt(pool.size)]].first) {
            Zone.BASE -> if (!goBase(ai)) goRandom(ai)
            Zone.MIDDLE -> goMiddle(ai, prevId)
            Zone.SWEEP -> goSweep(ai, prevId)
            Zone.PLAYER -> huntLocked(ai, inRange, false)
        }
    }

    /** Fly to random points across the whole map, one after another. */
    private fun goRandom(ai: DragonAI) {
        ai.mode = Mode.WANDER
        ai.point = randomMapPoint()
    }

    /** Chase one player for a while; after that period another one is chosen. */
    private fun huntLocked(ai: DragonAI, pool: List<Player>, randomPick: Boolean) {
        val st = s
        val target = if (randomPick) pool[rnd.nextInt(pool.size)] else when (st.targetStrategy) {
            TargetStrategy.NEAREST -> pool.minByOrNull { distance3(ai.pos, it) }
            TargetStrategy.RANDOM -> pool[rnd.nextInt(pool.size)]
            TargetStrategy.LOWEST_HEALTH -> pool.minByOrNull { it.health }
        } ?: pool[0]
        ai.lockedTarget = target
        ai.lockedUntil = tickCount + st.playerSwitchTicks
        ai.zone = Zone.PLAYER
        ai.mode = Mode.HUNT
        ai.targetPlayer = target
        ai.retargetIn = st.playerSwitchTicks
    }

    private fun goBase(ai: DragonAI): Boolean {
        val base = pickRaidBase(ai) ?: return false
        val st = s
        ai.zone = Zone.BASE
        ai.mode = Mode.RAID
        ai.raidBase = base
        ai.raidTicksLeft = st.raidDuration
        ai.retargetIn = st.raidDuration + 5
        ai.point = raidWaypoint(base)
        return true
    }

    /** Index of the least-crowded zone of [zone] among [count] candidates (ties random, avoiding [avoid]). */
    private fun leastLoaded(ai: DragonAI, zone: Zone, count: Int, avoid: Int): Int {
        val load = IntArray(count)
        for (o in dragons) if (o !== ai && o.zone == zone && o.zoneId in 0 until count) load[o.zoneId]++
        var ids = (0 until count).toList()
        if (ids.size > 1) ids = ids.filter { it != avoid }
        val least = ids.minOf { load[it] }
        val pool = ids.filter { load[it] == least }
        return pool[rnd.nextInt(pool.size)]
    }

    private fun startPatrol(ai: DragonAI, zone: Zone, id: Int, center: Vector, radius: Double) {
        ai.zone = zone
        ai.zoneId = id
        ai.zoneCenter = center
        ai.zoneRadius = radius
        ai.zoneTicksLeft = s.zoneDuration
        ai.retargetIn = s.zoneDuration + 5
        ai.mode = Mode.PATROL
        ai.point = zoneWaypoint(ai)
    }

    /** Middle islands and generators (falls back to the centre of the map when there are none). */
    private fun goMiddle(ai: DragonAI, avoid: Int) {
        val pts = info.middlePoints.ifEmpty { listOf(Vector(info.centerX, info.avgBaseY, info.centerZ)) }
        val id = leastLoaded(ai, Zone.MIDDLE, pts.size, avoid)
        startPatrol(ai, Zone.MIDDLE, id, pts[id], s.zoneRadius)
    }

    /** Other areas of the map: a grid of cells away from the bases and the middle. */
    private fun goSweep(ai: DragonAI, avoid: Int) {
        if (sweepCells.isEmpty()) {
            goRandom(ai)
            return
        }
        val id = leastLoaded(ai, Zone.SWEEP, sweepCells.size, avoid)
        startPatrol(ai, Zone.SWEEP, id, sweepCells[id], s.sweepCellSize / 2.0)
    }

    private val mapX0 get() = minX + pad - s.randomMargin
    private val mapX1 get() = maxX - pad + s.randomMargin
    private val mapZ0 get() = minZ + pad - s.randomMargin
    private val mapZ1 get() = maxZ - pad + s.randomMargin

    private val sweepCells: List<Vector> by lazy {
        val cs = s.sweepCellSize
        val out = ArrayList<Vector>()
        val nx = max(1, Math.ceil((mapX1 - mapX0) / cs).toInt())
        val nz = max(1, Math.ceil((mapZ1 - mapZ0) / cs).toInt())
        for (ix in 0 until nx) for (iz in 0 until nz) {
            val c = Vector(mapX0 + (ix + 0.5) * cs, info.avgBaseY, mapZ0 + (iz + 0.5) * cs)
            // The bases and the middle already have their own zones.
            if (info.bases.any { horizontal(Vector(it.spawn.x, 0.0, it.spawn.z), c) < cs * 0.6 }) continue
            if (info.middlePoints.any { horizontal(it, c) < cs * 0.6 }) continue
            // Skip empty void where the chunk is loaded and nothing is built.
            if (world.isChunkLoaded(c.x.toInt() shr 4, c.z.toInt() shr 4) && groundY(c.x, c.z) == null) continue
            out += c
        }
        out
    }

    /** Anywhere over the map (the area around all bases, plus a margin). */
    private fun randomMapPoint(): Vector {
        val x = mapX0 + rnd.nextDouble() * (mapX1 - mapX0)
        val z = mapZ0 + rnd.nextDouble() * (mapZ1 - mapZ0)
        return Vector(x, altitudeFor(x, z), z)
    }

    /** Surface height at x/z, or null when there is no terrain worth diving to (void / unloaded chunk). */
    private fun groundY(x: Double, z: Double): Double? {
        val bx = Math.floor(x).toInt()
        val bz = Math.floor(z).toInt()
        if (!world.isChunkLoaded(bx shr 4, bz shr 4)) return null
        val y = world.getHighestBlockYAt(bx, bz).toDouble()
        if (y <= 1.0 || y < info.avgBaseY - s.maxDiveDepth) return null
        return y
    }

    /**
     * Picks how high a waypoint is: low passes dive down to the terrain (so dragons carve the whole map, not just
     * one layer), high passes cruise above everything, the rest fly at base level.
     */
    private fun altitudeFor(x: Double, z: Double): Double {
        val st = s
        val r = rnd.nextDouble()
        val mid = info.avgBaseY + st.raidHeight + rnd.nextDouble() * 6.0
        return when {
            r < st.lowPassChance -> {
                val g = groundY(x, z)
                if (g == null) mid else max(2.0, g + st.lowPassOffset + rnd.nextDouble() * 3.0)
            }
            r < st.lowPassChance + st.highPassChance -> info.avgBaseY + st.cruiseHeight + rnd.nextDouble() * 10.0
            else -> mid
        }
    }

    private fun zoneWaypoint(ai: DragonAI): Vector {
        val c = ai.zoneCenter ?: return wanderPoint()
        val a = rnd.nextDouble() * Math.PI * 2
        val r = sqrt(rnd.nextDouble()) * ai.zoneRadius
        val x = c.x + Math.cos(a) * r
        val z = c.z + Math.sin(a) * r
        return Vector(x, altitudeFor(x, z), z)
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
        if (s.spreadOut) {
            // Keep dragons from piling on one base: prefer the bases with the fewest dragons already on them.
            val load = pool.associateWith { b -> dragons.count { it !== ai && it.raidBase === b } }
            val least = load.values.minOrNull() ?: 0
            pool = pool.filter { load[it] == least }
        }
        return if (pool.isEmpty()) null else pool[rnd.nextInt(pool.size)]
    }

    /** A point in a base; the height varies so dragons also dive through the lower parts of the base. */
    private fun raidWaypoint(b: BaseInfo): Vector {
        val x = b.spawn.x + (rnd.nextDouble() * 2 - 1) * 12.0
        val z = b.spawn.z + (rnd.nextDouble() * 2 - 1) * 12.0
        return Vector(x, altitudeFor(x, z), z)
    }

    private fun wanderPoint(): Vector {
        val x = info.centerX + (rnd.nextDouble() * 2 - 1) * 60.0
        val z = info.centerZ + (rnd.nextDouble() * 2 - 1) * 60.0
        return Vector(x, altitudeFor(x, z), z)
    }

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
            val dy = l.y + 0.9 - ai.pos.y
            // The dragon's head is ahead of its centre: a player is hit if they are close to either point.
            val hx = ai.heading.x
            val hz = ai.heading.z
            val hl = sqrt(hx * hx + hz * hz)
            val ox = if (hl > 1.0e-6) hx / hl * st.headOffset else 0.0
            val oz = if (hl > 1.0e-6) hz / hl * st.headOffset else 0.0
            val dxc = l.x - ai.pos.x
            val dzc = l.z - ai.pos.z
            val dxh = l.x - (ai.pos.x + ox)
            val dzh = l.z - (ai.pos.z + oz)
            val r2 = st.hitRadiusH * st.hitRadiusH
            if ((dxc * dxc + dzc * dzc > r2 && dxh * dxh + dzh * dzh > r2) || abs(dy) > st.hitRadiusV) continue
            val dx = dxc
            val dz = dzc

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
