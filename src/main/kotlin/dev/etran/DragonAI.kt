package dev.etran

import org.bukkit.Location
import org.bukkit.World
import org.bukkit.entity.EnderDragon
import org.bukkit.entity.Player
import org.bukkit.util.Vector
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.sqrt

enum class Mode { HUNT, RAID, OVERSHOOT, WANDER, PATROL }

/** Which part of the map a dragon is assigned to; used to spread dragons evenly. */
enum class Zone { BASE, MIDDLE, SWEEP, PLAYER }

/** Reflection shims for EnderDragon API that only exists on 1.9+. */
object DragonCompat {
    private val phaseClass: Class<*>? = try {
        Class.forName("org.bukkit.entity.EnderDragon\$Phase")
    } catch (_: Throwable) {
        null
    }
    private val hover: Any? = phaseClass?.enumConstants?.firstOrNull { (it as Enum<*>).name == "HOVER" }
    private val setPhase = try {
        if (phaseClass != null) EnderDragon::class.java.getMethod("setPhase", phaseClass) else null
    } catch (_: Throwable) {
        null
    }

    /** Parks the vanilla AI in a phase that never moves or perches, so only our steering applies. */
    fun parkVanillaAi(d: EnderDragon) {
        if (setPhase != null && hover != null) {
            try {
                setPhase.invoke(d, hover)
            } catch (_: Throwable) {
            }
        }
    }
}

/**
 * Runtime state of one controlled dragon. The plugin owns the dragon's position: [pos] is
 * authoritative and the entity is teleported to it every tick, so vanilla movement (and
 * perching) can never take effect.
 */
class DragonAI(val dragon: EnderDragon, val base: BaseInfo?, startPos: Vector, startHeading: Vector) {
    var pos: Vector = startPos
    var heading: Vector = startHeading.clone().normalize()

    var mode = Mode.WANDER
    var targetPlayer: Player? = null
    var point: Vector? = null
    var raidBase: BaseInfo? = null
    var lastRaidBase: BaseInfo? = null

    var retargetIn = 0

    /** Zone assignment (see [Zone]); [zoneId] indexes the middle point / sweep cell. */
    var zone: Zone? = null
    var zoneId = -1
    var zoneCenter: Vector? = null
    var zoneRadius = 0.0
    var zoneTicksLeft = 0

    /** PLAYER / HYBRID hunting: stick to one player until this tick, then pick another at random. */
    var lockedTarget: Player? = null
    var lockedUntil = 0L
    var raidTicksLeft = 0
    var overshootTicksLeft = 0
    val lastHit = HashMap<java.util.UUID, Long>()

    /**
     * Turns [heading] toward [dest] by at most [turnRateDeg] degrees, then moves [speed] blocks.
     * Vertical movement is limited to [vertSpeed]. The result is clamped to the given bounds.
     */
    fun steer(
        dest: Vector, speed: Double, turnRateDeg: Double, vertSpeed: Double,
        minX: Double, maxX: Double, minY: Double, maxY: Double, minZ: Double, maxZ: Double,
    ) {
        val want = dest.clone().subtract(pos)
        if (want.lengthSquared() < 1.0e-6) return
        want.normalize()

        val dot = heading.dot(want).coerceIn(-1.0, 1.0)
        val angle = acos(dot)
        val maxTurn = Math.toRadians(turnRateDeg)
        if (angle <= maxTurn) {
            heading = want
        } else {
            val t = maxTurn / angle
            val blended = if (dot < -0.9999) {
                // Exactly opposite: pick any perpendicular so the dragon turns instead of stalling.
                Vector(-heading.z, 0.0, heading.x).normalize().multiply(t).add(heading.clone().multiply(1 - t))
            } else {
                heading.clone().multiply(1 - t).add(want.clone().multiply(t))
            }
            if (blended.lengthSquared() > 1.0e-9) heading = blended.normalize()
        }

        val v = heading.clone().multiply(speed)
        if (v.y > vertSpeed) v.y = vertSpeed
        if (v.y < -vertSpeed) v.y = -vertSpeed
        pos.add(v)

        pos.x = pos.x.coerceIn(minX, maxX)
        pos.y = pos.y.coerceIn(minY, maxY)
        pos.z = pos.z.coerceIn(minZ, maxZ)
    }

    /** Moves the real entity to [pos], facing the direction of travel. */
    fun applyToEntity(world: World, yawOffset: Float) {
        val hx = heading.x
        val hz = heading.z
        val horiz = sqrt(hx * hx + hz * hz)
        val yaw = if (horiz < 1.0e-6) dragon.location.yaw
        else (Math.toDegrees(atan2(-hx, hz)).toFloat() + yawOffset)
        val loc = Location(world, pos.x, pos.y, pos.z, yaw, 0f)
        dragon.teleport(loc)
        dragon.velocity = Vector(0, 0, 0)
    }
}
