package dev.etran

import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.entity.EnderDragon
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.event.Cancellable
import org.bukkit.event.Event
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.CreatureSpawnEvent
import org.bukkit.event.entity.EntityChangeBlockEvent
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.EntityEvent
import org.bukkit.event.entity.EntityExplodeEvent
import org.bukkit.event.world.WorldUnloadEvent
import org.bukkit.plugin.EventExecutor
import org.bukkit.plugin.java.JavaPlugin

class DragonFixPlugin : JavaPlugin(), Listener {

    lateinit var settings: Settings
        private set
    lateinit var damageStore: DamageStore
        private set

    /** One session per arena world (key = world name). */
    private val sessions = HashMap<String, DragonSession>()

    /** /bw-dragonfix config ...: edit options in game; changes apply immediately and are saved to config.yml. */
    private val configCmd = ConfigCommand(this) { settings = Settings(config) }

    companion object {
        /** Metadata tag set on every dragon controlled by this plugin. */
        const val META = "dragonfix"
    }

    override fun onEnable() {
        if (!BedwarsHook.init()) {
            logger.severe("BedWars1058 (or a compatible fork) was not found - disabling.")
            server.pluginManager.disablePlugin(this)
            return
        }
        saveDefaultConfig()
        settings = Settings(config)
        damageStore = DamageStore(this)

        server.pluginManager.registerEvents(this, this)
        registerBedwarsEvents()
        registerPhaseGuard()
        logger.info("BedWars1058-DragonFix enabled (BedWars API: ${BedwarsHook.prefix}).")
    }

    override fun onDisable() {
        for (s in sessions.values) s.end()
        sessions.clear()
        if (::damageStore.isInitialized && ::settings.isInitialized) {
            if (settings.restoreOnDisable) damageStore.restoreAllNow(settings.rescuePlayers)
        }
    }

    // ------------------------------------------------------------------ session management

    fun endSession(arena: ArenaRef, restoreMap: Boolean) {
        sessions.remove(arena.worldName)?.end()
        if (restoreMap && settings.restoreOnGameEnd) {
            val w = Bukkit.getWorld(arena.worldName) ?: return
            damageStore.restore(
                w, settings.restorePerTick, settings.restoreDelay, instant = false, rescue = settings.rescuePlayers,
            )
        }
    }

    private fun isOurDragon(e: Entity?): Boolean {
        if (e == null || e !is EnderDragon) return false
        return sessions.values.any { e.uniqueId in it.dragonIds }
    }

    private fun sessionOf(e: Entity): DragonSession? =
        sessions.values.firstOrNull { e.uniqueId in it.dragonIds }

    // ------------------------------------------------------------------ sudden death detection

    /**
     * BedWars spawns plain dragons (one per team member-holding team, via world.spawnEntity) when
     * sudden death starts. The first one we see in an arena starts a session; BedWars' dragons are
     * removed (or adopted) one tick later. We do not cancel the spawn so BedWars' spawn loop,
     * which calls setPhase() on the returned entity, is left undisturbed.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onDragonSpawn(e: CreatureSpawnEvent) {
        val dragon = e.entity as? EnderDragon ?: return
        val world = dragon.world
        if (sessions[world.name]?.spawningOwn == true) return

        val arena = BedwarsHook.arenaOfWorld(world) ?: return
        if (!arena.isPlaying) return
        if (!settings.arenaAllowed(arena.arenaName, arena.worldName)) return

        var session = sessions[world.name]
        if (session == null) {
            val info = BedwarsHook.buildInfo(arena, settings) ?: return
            val created = DragonSession(this, info)
            sessions[world.name] = created
            session = created
            val replace = settings.replaceVanilla
            server.scheduler.runTask(this, Runnable {
                if (!created.ended) created.start(spawnOwn = replace)
            })
        }

        val s = session
        val replace = settings.replaceVanilla
        server.scheduler.runTask(this, Runnable {
            if (replace) {
                if (dragon.isValid) dragon.remove()
            } else {
                s.adopt(dragon)
            }
        })
    }

    @EventHandler
    fun onWorldUnload(e: WorldUnloadEvent) {
        sessions.remove(e.world.name)?.end()
        damageStore.forget(e.world.uid)
    }

    /** Registers a listener for an event class that is looked up by name (BedWars API / 1.9+ events). */
    @Suppress("UNCHECKED_CAST")
    private fun listen(className: String, priority: EventPriority, handler: (Event) -> Unit): Boolean {
        val cls = try {
            Class.forName(className)
        } catch (_: Throwable) {
            return false
        }
        server.pluginManager.registerEvent(
            cls as Class<out Event>,
            object : Listener {},
            priority,
            object : EventExecutor {
                override fun execute(listener: Listener, event: Event) {
                    if (!cls.isInstance(event)) return
                    try {
                        handler(event)
                    } catch (t: Throwable) {
                        logger.warning("Error handling ${cls.simpleName}: $t")
                    }
                }
            },
            this,
            false,
        )
        return true
    }

    /** Game end / restart: put the map back and remove the dragons. */
    private fun registerBedwarsEvents() {
        val base = "${BedwarsHook.prefix}.api.events.gameplay"
        val ok1 = listen("$base.GameStateChangeEvent", EventPriority.NORMAL) { ev ->
            val arena = Reflect.call(ev, "getArena")?.let { ArenaRef(it) } ?: return@listen
            val state = (Reflect.call(ev, "getNewState") as? Enum<*>)?.name ?: return@listen
            if (state.equals("restarting", true) || state.equals("waiting", true)) {
                endSession(arena, restoreMap = true)
            }
        }
        val ok2 = listen("$base.GameEndEvent", EventPriority.NORMAL) { ev ->
            val arena = Reflect.call(ev, "getArena")?.let { ArenaRef(it) } ?: return@listen
            endSession(arena, restoreMap = true)
        }
        if (!ok1 || !ok2) logger.warning("Could not hook BedWars game events; map restore falls back to arena state polling.")
    }

    // ------------------------------------------------------------------ dragon behaviour guards

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onDamage(e: EntityDamageByEntityEvent) {
        val damager = e.damager
        if (!isOurDragon(damager)) return
        val session = sessionOf(damager) ?: return
        val victim = e.entity
        if (victim is Player) {
            // Vanilla contact damage is replaced by our own (combat.contact-damage).
            if (!session.dealingDamage) e.isCancelled = true
        } else if (victim.type.name in settings.protectedEntityTypes) {
            e.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onAnyDamage(e: EntityDamageEvent) {
        if (settings.invulnerable && isOurDragon(e.entity)) e.isCancelled = true
    }

    /** Vanilla dragon block breaking would not be recorded for restore, so only our code breaks blocks. */
    @EventHandler(priority = EventPriority.HIGHEST)
    fun onExplode(e: EntityExplodeEvent) {
        if (isOurDragon(e.entity)) e.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onChangeBlock(e: EntityChangeBlockEvent) {
        if (isOurDragon(e.entity)) e.isCancelled = true
    }

    /** 1.9+: stop the vanilla AI from switching to landing / perching phases (the event does not exist on 1.8). */
    private fun registerPhaseGuard() {
        listen("org.bukkit.event.entity.EnderDragonChangePhaseEvent", EventPriority.HIGHEST) { ev ->
            if (!settings.neverPerch) return@listen
            val entity = (ev as? EntityEvent)?.entity ?: return@listen
            if (!isOurDragon(entity)) return@listen
            val newPhase = Reflect.call(ev, "getNewPhase") as? Enum<*>
            // HOVER is the "parked" phase we set ourselves; everything else is vanilla AI.
            if (newPhase?.name != "HOVER") (ev as Cancellable).isCancelled = true
        }
    }

    // ------------------------------------------------------------------ command

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        when (args.firstOrNull()?.lowercase()) {
            "reload" -> configCmd.reload(sender)
            "config" -> configCmd.handle(sender, args.drop(1), label)
            "status" -> {
                if (sessions.isEmpty()) sender.sendMessage("No arena is in sudden death.")
                for ((world, s) in sessions) {
                    val w = Bukkit.getWorld(world)
                    sender.sendMessage(
                        "$world: ${s.dragons.size} dragons, " +
                            "${if (w != null) damageStore.count(w) else 0} blocks broken, " +
                            "avg base Y ${"%.1f".format(s.info.avgBaseY)}, ${s.info.bases.size} bases",
                    )
                }
            }
            else -> sender.sendMessage("/$label <reload | status | config ...>")
        }
        return true
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        return when {
            args.size == 1 -> listOf("reload", "status", "config").filter { it.startsWith(args[0], ignoreCase = true) }
            args[0].equals("config", ignoreCase = true) -> configCmd.complete(args.drop(1))
            else -> emptyList()
        }
    }
}
