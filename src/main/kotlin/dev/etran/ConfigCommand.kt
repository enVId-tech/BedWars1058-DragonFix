package dev.etran

import org.bukkit.command.CommandSender
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.plugin.java.JavaPlugin
import java.io.File

/**
 * In-game config editing: list / get / set / add / remove / reload.
 *
 * Changes apply immediately (via [onChanged]) and are written back to config.yml by editing the file's
 * text, so the comments in the file survive on every server version.
 */
class ConfigCommand(private val plugin: JavaPlugin, private val onChanged: () -> Unit) {
    private val cfg get() = plugin.config

    fun handle(s: CommandSender, args: List<String>, label: String) {
        when (args.firstOrNull()?.lowercase()) {
            "list" -> list(s, args.getOrNull(1))
            "get" -> {
                val path = args.getOrNull(1) ?: return usage(s, label)
                list(s, path)
            }
            "set" -> {
                val path = args.getOrNull(1) ?: return usage(s, label)
                if (args.size < 3) return usage(s, label)
                set(s, path, args.drop(2).joinToString(" "))
            }
            "add", "remove" -> {
                val path = args.getOrNull(1) ?: return usage(s, label)
                if (args.size < 3) return usage(s, label)
                editList(s, path, args.drop(2).joinToString(" "), add = args[0].equals("add", true))
            }
            "reload" -> reload(s)
            else -> usage(s, label)
        }
    }

    fun reload(s: CommandSender) {
        plugin.reloadConfig()
        onChanged()
        s.sendMessage("§aConfig reloaded from disk.")
    }

    private fun usage(s: CommandSender, label: String) {
        s.sendMessage("§e/$label config §7<list [section] | get <path> | set <path> <value> | add <path> <value> | remove <path> <value> | reload>")
    }

    // ------------------------------------------------------------------ read

    private fun show(v: Any?): String {
        val text = if (v is List<*>) v.joinToString(", ", "[", "]") else v.toString()
        return if (text.length > 120) text.take(117) + "..." else text
    }

    private fun list(s: CommandSender, path: String?) {
        if (path == null) {
            for (k in cfg.getKeys(false)) {
                val v = cfg.get(k)
                if (v is ConfigurationSection) s.sendMessage("§e$k §7(${leaves(v).size} options) - config list $k")
                else s.sendMessage("§e$k §7= §f${show(v)}")
            }
            return
        }
        val v = cfg.get(path)
        when {
            v == null -> s.sendMessage("§cUnknown option or section: $path")
            v is ConfigurationSection -> for (k in leaves(v)) s.sendMessage("§e$path.$k §7= §f${show(cfg.get("$path.$k"))}")
            else -> s.sendMessage("§e$path §7= §f${show(v)}")
        }
    }

    private fun leaves(sec: ConfigurationSection): List<String> =
        sec.getKeys(true).filter { sec.get(it) !is ConfigurationSection }

    // ------------------------------------------------------------------ write

    private fun set(s: CommandSender, path: String, raw: String) {
        val current = cfg.get(path)
        if (current == null) return s.sendMessage("§cUnknown option: $path §7(use config list)")
        if (current is ConfigurationSection) return s.sendMessage("§c$path is a section; use: config list $path")
        if (current is List<*>) return s.sendMessage("§c$path is a list; use config add / config remove.")
        val ref = cfg.defaults?.get(path) ?: current
        val value: Any? = when (ref) {
            is Boolean -> when (raw.lowercase()) {
                "true", "on", "yes" -> true
                "false", "off", "no" -> false
                else -> null
            }
            is Int -> raw.toIntOrNull()
            is Long -> raw.toLongOrNull()
            is Number -> raw.toDoubleOrNull()
            else -> raw
        }
        if (value == null) return s.sendMessage("§c'$raw' is not a valid ${typeName(ref)} for $path.")
        cfg.set(path, value)
        applyAndSave(s, path, value)
    }

    private fun editList(s: CommandSender, path: String, value: String, add: Boolean) {
        val current = cfg.get(path)
        if (current !is List<*>) return s.sendMessage("§c$path is not a list.")
        val items = cfg.getStringList(path).toMutableList()
        if (add) {
            if (items.any { it.equals(value, true) }) return s.sendMessage("§c$value is already in $path.")
            items += value
        } else if (!items.removeIf { it.equals(value, true) }) {
            return s.sendMessage("§c$value is not in $path.")
        }
        cfg.set(path, items)
        applyAndSave(s, path, items)
    }

    private fun typeName(ref: Any?) = when (ref) {
        is Boolean -> "true/false"
        is Int, is Long -> "whole number"
        is Number -> "number"
        else -> "value"
    }

    private fun applyAndSave(s: CommandSender, path: String, value: Any) {
        onChanged()
        val saved = persist(path, value)
        s.sendMessage("§a$path §7= §f${show(value)}" + if (saved) "" else " §7(applied; could not edit the file in place, saved the whole config instead)")
    }

    private fun persist(path: String, value: Any): Boolean {
        try {
            val file = File(plugin.dataFolder, "config.yml")
            if (!file.exists()) {
                plugin.saveConfig()
                return true
            }
            val lines = file.readLines().toMutableList()
            if (!YamlText.write(lines, path, value)) {
                plugin.saveConfig() // key missing from the file: fall back (comments may be lost on old servers)
                return false
            }
            file.writeText(lines.joinToString("\n") + "\n")
            return true
        } catch (t: Throwable) {
            plugin.logger.warning("Could not save config.yml: $t")
            return false
        }
    }

    // ------------------------------------------------------------------ tab completion

    fun complete(args: List<String>): List<String> {
        fun filter(opts: Collection<String>, prefix: String) = opts.filter { it.startsWith(prefix, ignoreCase = true) }
        val sub = args.firstOrNull()?.lowercase() ?: return emptyList()
        return when {
            args.size == 1 -> filter(listOf("list", "get", "set", "add", "remove", "reload"), args[0])
            args.size == 2 -> {
                val all = cfg.getKeys(true)
                val sections = all.filter { cfg.get(it) is ConfigurationSection }
                val options = all.filter { cfg.get(it) !is ConfigurationSection }
                val lists = options.filter { cfg.get(it) is List<*> }
                filter(
                    when (sub) {
                        "list" -> sections
                        "get" -> all
                        "set" -> options.filter { cfg.get(it) !is List<*> }
                        "add", "remove" -> lists
                        else -> emptyList()
                    },
                    args[1],
                )
            }
            args.size == 3 && sub == "set" -> {
                val ref = cfg.defaults?.get(args[1]) ?: cfg.get(args[1])
                if (ref is Boolean) filter(listOf("true", "false"), args[2])
                else if (args[1].endsWith("strategy")) filter(listOf("NEAREST", "RANDOM", "LOWEST_HEALTH"), args[2])
                else listOfNotNull(cfg.get(args[1])?.toString()).let { filter(it, args[2]) }
            }
            args.size == 3 && sub == "add" && args[1].endsWith("target-modes") ->
                filter(listOf("RANDOM", "HYBRID", "BASE", "MIDDLE", "PLAYER", "ALL"), args[2])
            args.size == 3 && sub == "remove" -> filter(cfg.getStringList(args[1]), args[2])
            else -> emptyList()
        }
    }
}

/** Minimal text-level editor for config.yml: changes one value and leaves every comment untouched. */
internal object YamlText {
    private val keyRe = Regex("^(\\s*)([A-Za-z0-9_\\-]+):\\s*(.*)$")

    private fun locate(lines: List<String>, path: String): Int {
        val stack = ArrayList<Pair<Int, String>>()
        for ((i, l) in lines.withIndex()) {
            val t = l.trimStart()
            if (t.isEmpty() || t.startsWith("#") || t.startsWith("-")) continue
            val m = keyRe.matchEntire(l) ?: continue
            val indent = m.groupValues[1].length
            while (stack.isNotEmpty() && stack.last().first >= indent) stack.removeAt(stack.lastIndex)
            stack.add(indent to m.groupValues[2])
            if (stack.joinToString(".") { it.second } == path) return i
        }
        return -1
    }

    private fun fmt(v: Any?): String {
        val text = v.toString()
        if (v !is String) return text
        val plain = Regex("[A-Za-z0-9_.\\-]+").matches(text)
        val ambiguous = text.lowercase() in setOf("true", "false", "yes", "no", "on", "off", "null", "~") ||
            text.toDoubleOrNull() != null
        return if (plain && !ambiguous) text else "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    }

    /** Returns false if [path] is not present in the file's text. */
    fun write(lines: MutableList<String>, path: String, value: Any): Boolean {
        val i = locate(lines, path)
        if (i < 0) return false
        val m = keyRe.matchEntire(lines[i]) ?: return false
        val indent = m.groupValues[1]
        val key = m.groupValues[2]
        if (value is List<*>) {
            var end = i + 1
            while (end < lines.size && lines[end].trimStart().startsWith("- ")) end++
            repeat(end - i - 1) { lines.removeAt(i + 1) }
            if (value.isEmpty()) {
                lines[i] = "$indent$key: []"
            } else {
                lines[i] = "$indent$key:"
                value.forEachIndexed { n, v -> lines.add(i + 1 + n, "$indent  - ${fmt(v)}") }
            }
        } else {
            lines[i] = "$indent$key: ${fmt(value)}"
        }
        return true
    }
}
