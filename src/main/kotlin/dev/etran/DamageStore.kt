package dev.etran

import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.block.BlockState
import org.bukkit.plugin.Plugin
import org.bukkit.scheduler.BukkitRunnable
import java.lang.reflect.Method
import java.util.UUID

/** Version-independent block removal (no physics, so nothing pops off as an item). */
object BlockOps {
    private val setTypePhysics: Method? = try {
        Block::class.java.getMethod("setType", Material::class.java, java.lang.Boolean.TYPE)
    } catch (_: Throwable) {
        null
    }
    private val setIdAndData: Method? = try {
        Block::class.java.getMethod(
            "setTypeIdAndData", Integer.TYPE, java.lang.Byte.TYPE, java.lang.Boolean.TYPE,
        )
    } catch (_: Throwable) {
        null
    }

    fun removeBlock(b: Block) {
        try {
            when {
                setTypePhysics != null -> setTypePhysics.invoke(b, Material.AIR, false)
                setIdAndData != null -> setIdAndData.invoke(b, 0, 0.toByte(), false)
                else -> b.type = Material.AIR
            }
        } catch (_: Throwable) {
            b.type = Material.AIR
        }
    }

    /** Packs a block position into one Long (26 bits x, 26 bits z, 12 bits y). */
    fun key(x: Int, y: Int, z: Int): Long =
        ((x.toLong() and 0x3FFFFFF) shl 38) or ((z.toLong() and 0x3FFFFFF) shl 12) or ((y + 2048).toLong() and 0xFFF)
}

/**
 * Remembers the original state of every block a dragon destroyed so it can be put back.
 * BlockState works on every version (1.8 material+data, 1.13+ BlockData, tile entities included).
 */
class DamageStore(private val plugin: Plugin) {
    private val saved = HashMap<UUID, LinkedHashMap<Long, BlockState>>()
    private val restoring = HashSet<UUID>()

    fun count(world: World): Int = saved[world.uid]?.size ?: 0

    fun hasDamage(world: World): Boolean = count(world) > 0

    /** Records [block]'s current state if this position has not been recorded yet. false = store is full. */
    fun record(block: Block, maxStored: Int): Boolean {
        val map = saved.getOrPut(block.world.uid) { LinkedHashMap() }
        val k = BlockOps.key(block.x, block.y, block.z)
        if (map.containsKey(k)) return true
        if (map.size >= maxStored) return false
        map[k] = block.state
        return true
    }

    fun isRecorded(block: Block): Boolean =
        saved[block.world.uid]?.containsKey(BlockOps.key(block.x, block.y, block.z)) == true

    /** Drop everything without restoring (the world is going away anyway). */
    fun forget(worldId: UUID) {
        saved.remove(worldId)
        restoring.remove(worldId)
    }

    /**
     * Restore all damage in [world]. With [instant] the whole thing happens synchronously
     * (used on shutdown); otherwise it is spread over ticks, [perTick] blocks at a time.
     */
    fun restore(
        world: World,
        perTick: Int,
        delayTicks: Int,
        instant: Boolean,
        rescue: Boolean = false,
        onDone: () -> Unit = {},
    ) {
        val id = world.uid
        val map = saved[id]
        if (map == null || map.isEmpty()) {
            saved.remove(id)
            onDone()
            return
        }
        if (instant) {
            for (st in map.values) applyState(st)
            saved.remove(id)
            if (rescue) rescuePlayers(world)
            onDone()
            return
        }
        if (!restoring.add(id)) return
        object : BukkitRunnable() {
            override fun run() {
                val w = Bukkit.getWorld(id)
                val m = saved[id]
                if (w == null || m == null) {
                    forget(id)
                    cancel()
                    return
                }
                val it = m.entries.iterator()
                var n = 0
                while (it.hasNext() && n < perTick) {
                    applyState(it.next().value)
                    it.remove()
                    n++
                }
                if (rescue) rescuePlayers(w)
                if (m.isEmpty()) {
                    saved.remove(id)
                    restoring.remove(id)
                    cancel()
                    onDone()
                }
            }
        }.runTaskTimer(plugin, delayTicks.toLong().coerceAtLeast(1L), 1L)
    }

    /** Synchronously restore every world (plugin disable). */
    fun restoreAllNow(rescue: Boolean = false) {
        for (id in saved.keys.toList()) {
            val w = Bukkit.getWorld(id)
            if (w != null) restore(w, Int.MAX_VALUE, 0, instant = true, rescue = rescue) else forget(id)
        }
    }

    // ------------------------------------------------------------------ player rescue

    private fun solid(b: Block): Boolean = b.type.isSolid

    private fun hazard(b: Block): Boolean = b.type.name.contains("LAVA") || b.type.name == "FIRE"

    /** A spot a player can stand: solid ground below, feet and head blocks free. */
    private fun standable(w: World, x: Int, y: Int, z: Int): Boolean {
        if (y < 1 || y + 1 >= w.maxHeight) return false
        val feet = w.getBlockAt(x, y, z)
        val head = w.getBlockAt(x, y + 1, z)
        val below = w.getBlockAt(x, y - 1, z)
        return !solid(feet) && !solid(head) && solid(below) && !hazard(feet) && !hazard(head) && !hazard(below)
    }

    /**
     * Restored blocks can appear inside a player. Anyone whose feet or head is now inside a solid block is moved
     * to the closest free standing spot straight above them (the next available block), falling back to the
     * nearest free spot around them when nothing above works.
     */
    private fun rescuePlayers(world: World) {
        for (p in world.players) {
            try {
                if (p.gameMode.name == "SPECTATOR" || (p.allowFlight && p.isFlying)) continue
                val loc = p.location
                val bx = loc.blockX
                val by = loc.blockY
                val bz = loc.blockZ
                if (!solid(world.getBlockAt(bx, by, bz)) && !solid(world.getBlockAt(bx, by + 1, bz))) continue

                var target: Int? = null
                var y = by
                while (y < world.maxHeight - 1) {
                    if (standable(world, bx, y, bz)) {
                        target = y
                        break
                    }
                    y++
                }
                var tx = bx
                var tz = bz
                if (target == null) {
                    // Sealed in from above (e.g. a roof): look at neighbouring columns, nearest first.
                    search@ for (r in 1..6) {
                        for (dx in -r..r) for (dz in -r..r) {
                            if (Math.abs(dx) != r && Math.abs(dz) != r) continue
                            for (yy in (by - 3)..(by + 3)) {
                                if (standable(world, bx + dx, yy, bz + dz)) {
                                    target = yy
                                    tx = bx + dx
                                    tz = bz + dz
                                    break@search
                                }
                            }
                        }
                    }
                }
                val ty = target ?: continue
                val dest = if (tx == bx && tz == bz) {
                    loc.clone().apply { this.y = ty.toDouble() }
                } else {
                    org.bukkit.Location(world, tx + 0.5, ty.toDouble(), tz + 0.5, loc.yaw, loc.pitch)
                }
                p.teleport(dest)
                p.velocity = org.bukkit.util.Vector(0, 0, 0)
                p.fallDistance = 0f
            } catch (t: Throwable) {
                plugin.logger.fine("Could not rescue ${p.name}: ${t.message}")
            }
        }
    }

    private fun applyState(st: BlockState) {
        try {
            st.update(true, false)
        } catch (t: Throwable) {
            plugin.logger.fine("Could not restore block at ${st.x},${st.y},${st.z}: ${t.message}")
        }
    }
}
