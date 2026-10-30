package dev.etran

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.entity.Player

/**
 * Everything that touches the BedWars API goes through this file, and it does so by reflection.
 * That keeps the plugin independent of the BedWars version it was built against and lets one jar
 * work with both BedWars1058 (`com.andrei1058.bedwars`) and its forks such as BedWars2023
 * (`com.tomkeuper.bedwars`), which share the same API shape.
 *
 * Method names below were checked against the BedWars1058 25.2 sources
 * (IArena, ITeam, IGenerator, BedWars.ArenaUtil, GameStateChangeEvent, GameEndEvent).
 */
object Reflect {
    private fun assignable(param: Class<*>, arg: Any?): Boolean {
        if (arg == null) return !param.isPrimitive
        if (param.isInstance(arg)) return true
        return when (param) {
            java.lang.Integer.TYPE -> arg is Int
            java.lang.Boolean.TYPE -> arg is Boolean
            java.lang.Double.TYPE -> arg is Double
            java.lang.Long.TYPE -> arg is Long
            else -> false
        }
    }

    /** Calls the public method [method] of [target] whose parameters accept [args]; null on any failure. */
    fun call(target: Any, method: String, vararg args: Any?): Any? {
        return try {
            val m = target.javaClass.methods.firstOrNull { m ->
                m.name == method && m.parameterTypes.size == args.size &&
                    m.parameterTypes.indices.all { assignable(m.parameterTypes[it], args[it]) }
            } ?: return null
            m.isAccessible = true
            m.invoke(target, *args)
        } catch (_: Throwable) {
            null
        }
    }

    fun list(target: Any, method: String, vararg args: Any?): List<Any> =
        (call(target, method, *args) as? Collection<*>)?.filterNotNull() ?: emptyList()
}

class TeamRef(val raw: Any) {
    val name: String get() = Reflect.call(raw, "getName") as? String ?: "team"
    val spawn: Location? get() = Reflect.call(raw, "getSpawn") as? Location
    val bed: Location? get() = Reflect.call(raw, "getBed") as? Location
    val shop: Location? get() = Reflect.call(raw, "getShop") as? Location
    val upgrades: Location? get() = Reflect.call(raw, "getTeamUpgrades") as? Location
    val members: List<Player> get() = Reflect.list(raw, "getMembers").filterIsInstance<Player>()
    val generators: List<Any> get() = Reflect.list(raw, "getGenerators")

    /** Dragons this team gets at sudden death (1 by default, 2 with the Dragon Buff upgrade). */
    val dragons: Int get() = (Reflect.call(raw, "getDragons") as? Number)?.toInt() ?: 1

    fun isMember(p: Player): Boolean = Reflect.call(raw, "isMember", p) as? Boolean ?: false
}

class ArenaRef(val raw: Any) {
    val worldName: String get() = Reflect.call(raw, "getWorldName") as? String ?: ""
    val arenaName: String get() = Reflect.call(raw, "getArenaName") as? String ?: worldName
    val statusName: String get() = (Reflect.call(raw, "getStatus") as? Enum<*>)?.name ?: ""
    val world: World? get() = Reflect.call(raw, "getWorld") as? World
    val teams: List<TeamRef> get() = Reflect.list(raw, "getTeams").map { TeamRef(it) }
    val players: List<Player> get() = Reflect.list(raw, "getPlayers").filterIsInstance<Player>()
    val oreGenerators: List<Any> get() = Reflect.list(raw, "getOreGenerators")

    val isPlaying: Boolean get() = statusName.equals("playing", ignoreCase = true)

    fun isPlayer(p: Player): Boolean = Reflect.call(raw, "isPlayer", p) as? Boolean ?: false
    fun isSpectator(p: Player): Boolean = Reflect.call(raw, "isSpectator", p) as? Boolean ?: false

    /** BedWars' own build protection (protected regions, generator radius, outside the border). */
    fun isProtected(l: Location): Boolean = Reflect.call(raw, "isProtected", l) as? Boolean ?: false

    override fun equals(other: Any?) = other is ArenaRef && other.raw === raw
    override fun hashCode() = System.identityHashCode(raw)
}

/** One base (= one configured team island) of an arena. */
class BaseInfo(
    val team: TeamRef,
    val name: String,
    val spawn: Location,
    val bed: Location?,
    /** Extra dragons this base's team earned (Dragon Buff upgrade). */
    val buffExtra: Int,
) {
    fun alivePlayers(): List<Player> = team.members.filter { it.isOnline && !it.isDead }
}

/** Snapshot of everything the dragon AI needs to know about an arena at sudden death. */
class ArenaInfo(
    val arena: ArenaRef,
    val world: World,
    val bases: List<BaseInfo>,
    /** Average Y of all bases: the level dragons descend to. */
    val avgBaseY: Double,
    val centerX: Double,
    val centerZ: Double,
    /** Spheres (x, y, z, radius) that dragons may never break blocks in. */
    val protectedSpheres: List<DoubleArray>,
    /** Arena-wide (diamond / emerald) generator positions: the middle islands. */
    val middlePoints: List<org.bukkit.util.Vector>,
)

object BedwarsHook {
    private val PREFIXES = listOf("com.andrei1058.bedwars", "com.tomkeuper.bedwars")

    /** Package prefix of the BedWars API that was found, e.g. "com.andrei1058.bedwars". */
    lateinit var prefix: String
        private set
    private lateinit var api: Any

    fun init(): Boolean {
        for (p in PREFIXES) {
            val cls = try {
                Class.forName("$p.api.BedWars")
            } catch (_: Throwable) {
                continue
            }
            val reg = Bukkit.getServicesManager().getRegistration(cls) ?: continue
            prefix = p
            api = reg.provider as Any
            return true
        }
        return false
    }

    private fun arenaUtil(): Any? = Reflect.call(api, "getArenaUtil")

    /** BedWars uses the (copied) arena world's name as the arena identifier. */
    fun arenaOfWorld(world: World): ArenaRef? {
        val util = arenaUtil() ?: return null
        return Reflect.call(util, "getArenaByIdentifier", world.name)?.let { ArenaRef(it) }
    }

    fun isParticipant(arena: ArenaRef, p: Player): Boolean = arena.isPlayer(p) && !arena.isSpectator(p)

    fun participants(arena: ArenaRef): List<Player> =
        arena.players.filter { it.isOnline && !it.isDead && isParticipant(arena, it) }

    fun buildInfo(arena: ArenaRef, s: Settings): ArenaInfo? {
        val world = arena.world ?: return null
        val bases = ArrayList<BaseInfo>()
        for (team in arena.teams) {
            val spawn = team.spawn ?: continue
            val buff = (team.dragons - 1).coerceAtLeast(0)
            bases += BaseInfo(team, team.name, spawn.clone(), team.bed?.clone(), buff)
        }
        if (bases.isEmpty()) return null

        // Average Y of the map, derived from where the bases really are (spawns + beds).
        val ys = ArrayList<Double>()
        for (b in bases) {
            ys += b.spawn.y
            b.bed?.let { ys += it.y }
        }
        val avgY = ys.average()
        val cx = bases.map { it.spawn.x }.average()
        val cz = bases.map { it.spawn.z }.average()

        val spheres = ArrayList<DoubleArray>()
        fun addSphere(l: Location?, r: Double) {
            if (l != null && r > 0.0) spheres += doubleArrayOf(l.x, l.y, l.z, r)
        }

        // Generators: arena-wide (diamond / emerald) and per team (iron / gold).
        // By default only a small area around each generator / shop is kept (so they keep working);
        // terrain around bases and generators is protected only with map-damage.protect-bases-and-generators.
        val full = s.protectBasesAndGenerators
        val genR = if (full) s.protectGeneratorRadius else s.functionalRadius
        val shopR = if (full) s.protectShopRadius else s.functionalRadius
        val gens = ArrayList<Any>(arena.oreGenerators)
        for (b in bases) gens.addAll(b.team.generators)
        for (g in gens) addSphere(Reflect.call(g, "getLocation") as? Location, genR)

        // Shops and team upgrade NPCs, plus optional spawn / base protection.
        for (b in bases) {
            addSphere(b.team.shop, shopR)
            addSphere(b.team.upgrades, shopR)
            if (full) addSphere(b.spawn, maxOf(s.protectSpawnRadius, s.protectBaseRadius))
        }
        val middle = ArrayList<org.bukkit.util.Vector>()
        for (g in arena.oreGenerators) {
            val l = Reflect.call(g, "getLocation") as? Location ?: continue
            middle += org.bukkit.util.Vector(l.x, l.y, l.z)
        }
        return ArenaInfo(arena, world, bases, avgY, cx, cz, spheres, middle)
    }
}
