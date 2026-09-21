package app.morphe

import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.DexFileFactory
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.formats.ArrayPayload
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The view ids the extension looks up by name on the phone, held against the TikTok build the
 * bundle declares.
 *
 * <p>Those lookups happen at run time, so a renamed id used to surface as a switch that did nothing
 * on somebody's phone. Most of the names are the three character ones TikTok's build makes up, and
 * each build hands the same names out again, at nearly the same index, mostly to other views:
 * g6r is 0x7f0a2170 on both 46.9.3 and 47.0.3, the share sheet's on one and the like button's on
 * the other. Every name here exists in every retained build from 46.2.3 on, so a name that
 * resolves proves very little. What `view-id-anchors.txt` adds is an owner, a class with a real
 * name whose code loads the id. VideoDiggAssem loading g6r's id says g6r is still the like button,
 * and a build that hands g6r to something else fails here instead of on a phone.
 *
 * <p>The table has to list exactly the lookups the code makes, so it can't fall behind the code.
 * Older fixtures only report what they cover, since the bundle doesn't claim them.
 */
class RuntimeViewIdAnchorsTest {
    @Test
    fun `the anchor table lists exactly the view ids the extension looks up`() {
        val table = anchors().map { it.lookup }.toSet()
        val code = lookups()
        assertTrue("could not find any id lookup in the extension", code.isNotEmpty())
        val missing = code - table
        val stale = table - code
        assertTrue(
            "The extension looks these ids up and view-id-anchors.txt doesn't list them. Add a line " +
                "with the package and, where one exists, a class of the target that loads the id:\n" +
                missing.joinToString("\n"),
            missing.isEmpty(),
        )
        assertTrue(
            "view-id-anchors.txt lists lookups the extension no longer makes, or makes with other " +
                "names or in another order:\n" + stale.joinToString("\n"),
            stale.isEmpty(),
        )
    }

    /**
     * A group names one view. Its second name used to be the 46.x name of the same view, tried when
     * the current one found nothing, and on 47.0.3 each of those names some other view, which the
     * fallback then hid or read. A group may keep more than one name only with a reason in
     * [MORE_THAN_ONE_NAME], and that list only shrinks: a group that drops its extra names fails
     * here until its entry goes too.
     */
    @Test
    fun `a group looks up more than one name only for a reason the test records`() {
        val several = anchors().filter { it.names.size > 1 }.map { it.lookup }.toSortedSet()
        assertEquals(
            "Groups that try more than one name. On the target a second name is an older build's " +
                "name for the view, now some other view, so drop it; or record why the group needs " +
                "both in MORE_THAN_ONE_NAME. A recorded group that has one name now loses its entry.",
            MORE_THAN_ONE_NAME.keys.toSortedSet(),
            several,
        )
    }

    @Test
    fun `every anchor resolves on the declared target and its owner loads the id`() {
        val compatibility = AppCompatibilities.tiktok4703().single()
        val version = checkNotNull(compatibility.targets.single().version)
        val targets = Fixtures.files {
            it.extension == "apk" && (it.name.contains("_$version-") || it.name == "tiktok-$version.apk")
        }
        val anchors = anchors()
        for (apk in targets) {
            val coverage = coverage(apk, anchors, checkNotNull(compatibility.packageName))
            val failures = coverage.filter { it.state == State.BROKEN }.map { "${it.anchor.lookup}: ${it.detail}" }
            assertEquals("${apk.name} (the declared $version target)", emptyList<String>(), failures)
            println("${apk.name}: ${coverage.count { it.state == State.OWNED }} owners load their id; " +
                "${coverage.count { it.state == State.UNOWNED }} groups resolve with no owner to hold them to")
        }
    }

    /**
     * TikTok hands its short names out again on every build, mostly to other views: on 46.7.3 to
     * 46.9.3 no owner loads the id of any name its group lists, and only the ids with real names
     * (desc, title, view_rootview and the like) still hold.
     */
    @Test
    fun `older fixtures report which anchors they cover`() {
        val compatibility = AppCompatibilities.tiktok4703().single()
        val version = checkNotNull(compatibility.targets.single().version)
        val older = Fixtures.apks().filter { !it.name.contains("_$version-") && it.name != "tiktok-$version.apk" }
        val anchors = anchors()
        for (apk in older) {
            val coverage = coverage(apk, anchors, checkNotNull(compatibility.packageName))
            val broken = coverage.filter { it.state == State.BROKEN }
            println("${apk.name}: ${coverage.count { it.state == State.OWNED }} of " +
                "${anchors.count { it.owner != null }} owners load the id of the first name their group " +
                "defines; ${broken.size} groups don't hold")
            broken.forEach { println("  ${it.anchor.lookup}: ${it.detail}") }
        }
        if (older.isEmpty()) println("No fixture older than $version to report on.")
    }

    @Test
    fun `the resource table reader finds entries in every chunk and entry layout it reads`() {
        for ((flags, compact) in listOf(0 to false, SPARSE to false, OFFSET16 to false, 0 to true)) {
            val ids = ResourceIds.read(syntheticTable(flags, compact))
            assertEquals(
                "type chunk flags $flags, compact entries $compact",
                mapOf("com.example" to mapOf("first" to listOf(0x7f020000), "third" to listOf(0x7f020002))),
                ids,
            )
        }
    }

    private class Anchor(
        val lookup: String,
        val names: List<String>,
        val packageSuffix: String,
        /** The owner's class name, or null for none. */
        val owner: String?,
    )

    private enum class State { OWNED, UNOWNED, BROKEN }

    private class Coverage(val anchor: Anchor, val state: State, val detail: String)

    /**
     * Where each anchor stands on one APK: the first name it defines is the one the code will use
     * there, so that is the one its owner has to load.
     */
    private fun coverage(apk: File, anchors: List<Anchor>, appPackage: String): List<Coverage> {
        val tables = ResourceIds.read(apk)
        val loaded = literalsLoadedBy(apk, anchors.mapNotNull { it.owner }.map(::descriptor).toSet())
        return anchors.map { anchor ->
            val packageName = if (anchor.packageSuffix == "app") appPackage else "$appPackage.${anchor.packageSuffix}"
            val ids = tables[packageName].orEmpty()
            val used = anchor.names.firstOrNull { it in ids }
            val candidates = used?.let { ids.getValue(it) }.orEmpty()
            val owner = anchor.owner
            val literals = owner?.let { loaded[descriptor(it)] }
            when {
                used == null -> Coverage(anchor, State.BROKEN, "$packageName defines none of ${anchor.names}")
                // Two entries under one name: an invented short name that is also a real one. The
                // lookup on the phone lands on one of them, and which one is the platform's choice.
                candidates.size > 1 -> Coverage(anchor, State.BROKEN,
                    "$packageName gives $used ${candidates.size} ids, ${candidates.joinToString { hex(it) }}")
                owner == null -> Coverage(anchor, State.UNOWNED, "resolves as $used")
                literals == null -> Coverage(anchor, State.BROKEN, "there is no class $owner")
                candidates.single() in literals -> Coverage(anchor, State.OWNED, "$owner loads $used")
                else -> {
                    val others = anchor.names.filter { name -> ids[name].orEmpty().any { it in literals } }
                    Coverage(anchor, State.BROKEN,
                        "$owner doesn't load ${hex(candidates.single())}, the id of $used" +
                            if (others.isEmpty()) ", nor the id of any other name in the group"
                            else "; it loads the id of ${others.joinToString()}")
                }
            }
        }
    }

    private fun descriptor(className: String) = "L" + className.replace('.', '/') + ";"

    private fun hex(id: Int) = "0x%08x".format(id)

    /** Every literal each of [owners] loads in any of its methods, one dex file at a time. */
    private fun literalsLoadedBy(apk: File, owners: Set<String>): Map<String, Set<Int>> {
        val found = mutableMapOf<String, MutableSet<Int>>()
        if (owners.isEmpty()) return found
        val container = DexFileFactory.loadDexContainer(apk, Opcodes.getDefault())
        for (entry in container.dexEntryNames) {
            for (classDef in container.getEntry(entry)!!.dexFile.classes) {
                if (classDef.type !in owners) continue
                val literals = found.getOrPut(classDef.type) { mutableSetOf() }
                for (method in classDef.methods) {
                    for (instruction in method.implementation?.instructions ?: continue) {
                        when (instruction) {
                            is NarrowLiteralInstruction -> literals += instruction.narrowLiteral
                            is ArrayPayload -> instruction.arrayElements.forEach { literals += it.toInt() }
                        }
                    }
                }
            }
        }
        return found
    }

    private fun anchors(): List<Anchor> {
        val text = checkNotNull(javaClass.getResourceAsStream("/view-id-anchors.txt")) {
            "view-id-anchors.txt is missing from the test resources"
        }.bufferedReader().readText()
        val anchors = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.map { line ->
            val fields = line.split('|')
            assertEquals("a line of view-id-anchors.txt needs five fields: $line", 5, fields.size)
            val (source, group, names, packageSuffix, owner) = fields
            assertTrue("bad package in: $line", packageSuffix.matches(Regex("[a-z][a-z0-9_]*")))
            assertTrue("bad owner in: $line", owner == "-" || owner.matches(CLASS_NAME))
            Anchor(
                lookup = "$source|$group|$names",
                names = names.split(','),
                packageSuffix = packageSuffix,
                owner = owner.takeIf { it != "-" },
            )
        }.toList()
        val repeated = anchors.groupBy { it.lookup }.filterValues { it.size > 1 }.keys
        assertTrue("lines listed twice: $repeated", repeated.isEmpty())
        return anchors
    }

    /** `source|group|names` for every id the extension looks up by a name written into its code. */
    private fun lookups(): Set<String> {
        val repo = if (File("src/main/kotlin").isDirectory) File("..") else File(".")
        val root = File(repo, EXTENSION)
        assertTrue("could not find the extension sources from ${File(".").absolutePath}", root.isDirectory)
        val found = sortedSetOf<String>()
        root.walkTopDown().filter { it.extension == "java" }.forEach { file ->
            val text = file.readText()
            if (!LOOKS_UP_IDS.containsMatchIn(text)) return@forEach
            val source = file.relativeTo(root).invariantSeparatorsPath
            DECLARATION.findAll(text).forEach { match ->
                val (group, value) = match.destructured
                val names = LITERAL.findAll(value).map { it.groupValues[1] }.toList()
                assertTrue(
                    "$source declares $group as something other than plain string literals: $value",
                    names.isNotEmpty() && value.replace(LITERAL, "").trim('{', '}', ' ', ',', '\n', '\r', '\t').isEmpty(),
                )
                found += "$source|$group|${names.joinToString(",")}"
            }
            INLINE.findAll(text).forEach { found += "$source|inline|${it.groups[1]?.value ?: it.groups[2]!!.value}" }
        }
        return found
    }

    private fun syntheticTable(flags: Int, compact: Boolean): ByteBuffer {
        val out = ByteBuffer.allocate(4096).order(ByteOrder.LITTLE_ENDIAN)
        fun chunk(type: Int, headerSize: Int, body: () -> Unit) {
            val start = out.position()
            out.putShort(type.toShort()).putShort(headerSize.toShort()).putInt(0)
            body()
            while (out.position() % 4 != 0) out.put(0)
            out.putInt(start + 4, out.position() - start)
        }
        fun pool(strings: List<String>) = chunk(0x0001, 28) {
            val start = out.position() - 8
            out.putInt(strings.size).putInt(0).putInt(0x100).putInt(28 + 4 * strings.size).putInt(0)
            val encoded = strings.map { it.toByteArray(Charsets.UTF_8) }
            var offset = 0
            for (bytes in encoded) { out.putInt(offset); offset += bytes.size + 3 }
            check(out.position() - start == 28 + 4 * strings.size)
            for (bytes in encoded) { out.put(bytes.size.toByte()).put(bytes.size.toByte()).put(bytes).put(0) }
        }
        chunk(0x0002, 12) {
            out.putInt(1)
            pool(emptyList())
            chunk(0x0200, 288) {
                val start = out.position() - 8
                out.putInt(0x7f)
                "com.example".forEach { out.putChar(it) }
                repeat(128 - "com.example".length) { out.putChar(0.toChar()) }
                val offsets = out.position()
                out.putInt(0).putInt(0).putInt(0).putInt(0).putInt(0)
                out.putInt(offsets, out.position() - start)
                pool(listOf("attr", "id"))
                out.putInt(offsets + 8, out.position() - start)
                pool(listOf("first", "third"))
                // The entries: first at index 0, nothing at 1, third at 2, with key strings 0 and 1.
                chunk(0x0201, 20 + 4) {
                    val start = out.position() - 8
                    out.put(2).put(flags.toByte()).putShort(0)
                    val count = if (flags and SPARSE != 0) 2 else 3
                    out.putInt(count)
                    val entriesStart = out.position()
                    out.putInt(0)
                    out.putInt(4)
                    when {
                        flags and SPARSE != 0 -> out.putShort(0).putShort(0).putShort(2).putShort((8 / 4).toShort())
                        flags and OFFSET16 != 0 -> out.putShort(0).putShort(0xffff.toShort()).putShort((8 / 4).toShort()).putShort(0)
                        else -> out.putInt(0).putInt(-1).putInt(8)
                    }
                    out.putInt(entriesStart, out.position() - start)
                    for (key in 0..1) {
                        // A full entry is its size, its flags and a 32 bit key; a compact one puts
                        // a 16 bit key where the size goes and flags it.
                        if (compact) out.putShort(key.toShort()).putShort(0x0008).putInt(0)
                        else out.putShort(8).putShort(0).putInt(key)
                    }
                }
            }
        }
        out.flip()
        return out
    }

    private companion object {
        const val EXTENSION = "extensions/tiktok/src/main/java/app/morphe/extension/tiktok"
        const val SPARSE = 0x01
        const val OFFSET16 = 0x02

        /** A file that looks views up by resource name. */
        val LOOKS_UP_IDS = Regex("""ResourceIdCache|getIdentifier\([^;]*"id"""")

        /** A constant holding one or more names: `LIKE_BUTTON_IDS = {"g6r", "fws"}`, `NAME_ID = "title"`. */
        val DECLARATION = Regex(
            """static\s+final\s+String(?:\[])?\s+([A-Z][A-Z0-9_]*(?:_IDS?|_RESOURCE_NAMES?))\s*=\s*""" +
                """(?:new\s+String\[]\s*)?(\{[^}]*}|"[^"]*")\s*;"""
        )

        /** A name written straight into a lookup: `resolve(resources, PACKAGE, "view_rootview", false)`. */
        val INLINE = Regex(
            """\.resolve\(\s*(?:[^,;()]|\([^()]*\))+,\s*(?:[^,;()]|\([^()]*\))+,\s*"([^"]+)"|""" +
                """getIdentifier\(\s*"([^"]+)"\s*,\s*"id""""
        )

        val LITERAL = Regex(""""([^"]*)"""")
        val CLASS_NAME = Regex("""[a-z][A-Za-z0-9_]*(\.[A-Za-z_][A-Za-z0-9_$]*)+""")

        /** The groups that may look up more than one name, as `source|group|names`, and why. */
        val MORE_THAN_ONE_NAME = mapOf(
            "feed/VideoOverlayHider.java|VISUAL_SEARCH_IDS|fb,cn" to
                "two views of the target, each hidden on its own: the visual search layer and the " +
                "pill inside it",
            "blockauthor/FeedVisibility.java|HOME_TAB_RESOURCE_NAMES|omq,o1k" to
                "46.x fallback, left while FeedVisibility.java has another change open (2026-09-21)",
            "blockauthor/FeedVisibility.java|INBOX_TAB_RESOURCE_NAMES|omr,o1l" to
                "46.x fallback, left while FeedVisibility.java has another change open (2026-09-21)",
            "blockauthor/FeedVisibility.java|COMMENT_SHEET_RESOURCE_NAMES|pvp,p_5" to
                "46.x fallback, left while FeedVisibility.java has another change open (2026-09-21)",
            "blockauthor/FeedVisibility.java|COMMENT_TITLE_RESOURCE_NAMES|wk7,vjb" to
                "46.x fallback, left while FeedVisibility.java has another change open (2026-09-21)",
        )
    }
}

/**
 * The `id` entries of an APK's resource table, by package name and then entry name, with every id
 * a name has in the order of the entries. A name normally has one. TikTok 47.0.3 gives ten names
 * two or three, some because a made-up short name like `url` or `tv1` matches a real one. Only as
 * much of the format as that takes: the package chunks, their type and key string pools and the
 * type chunks for `id`, in the dense, sparse and 16 bit offset layouts, with full or compact entries.
 */
internal object ResourceIds {
    private const val TABLE = 0x0002
    private const val PACKAGE = 0x0200
    private const val TYPE = 0x0201
    private const val SPARSE = 0x01
    private const val OFFSET16 = 0x02
    private const val COMPACT = 0x0008

    fun read(apk: File): Map<String, Map<String, List<Int>>> {
        val bytes = ZipFile(apk).use { zip ->
            val entry = checkNotNull(zip.getEntry("resources.arsc")) { "${apk.name} has no resources.arsc" }
            zip.getInputStream(entry).use { it.readBytes() }
        }
        return read(ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN))
    }

    fun read(table: ByteBuffer): Map<String, Map<String, List<Int>>> {
        check(u16(table, 0) == TABLE) { "not a resource table" }
        val packages = linkedMapOf<String, MutableMap<String, MutableList<Int>>>()
        forEachChunk(table, u16(table, 2), table.limit()) { start, type ->
            if (type == PACKAGE) readPackage(table, start, packages)
        }
        return packages
    }

    private fun readPackage(table: ByteBuffer, start: Int, into: MutableMap<String, MutableMap<String, MutableList<Int>>>) {
        val headerSize = u16(table, start + 2)
        val packageId = table.getInt(start + 8)
        val name = buildString {
            for (i in 0 until 128) {
                val c = table.getChar(start + 12 + 2 * i)
                if (c == '\u0000') break
                append(c)
            }
        }
        val typeNames = StringPool(table, start + table.getInt(start + 268))
        val keys = StringPool(table, start + table.getInt(start + 276))
        val typeIdOffset = if (headerSize >= 288) table.getInt(start + 284) else 0
        val idType = (0 until typeNames.count).firstOrNull { typeNames[it] == "id" }?.let { it + 1 + typeIdOffset } ?: return
        val ids = into.getOrPut(name) { linkedMapOf() }
        forEachChunk(table, start + headerSize, start + table.getInt(start + 4)) { chunk, type ->
            if (type != TYPE || u8(table, chunk + 8) != idType) return@forEachChunk
            forEachEntry(table, chunk) { index, key ->
                val id = (packageId shl 24) or (idType shl 16) or index
                val sameName = ids.getOrPut(keys[key]) { mutableListOf() }
                // Another configuration's chunk repeats the entries; an id is listed once.
                if (id !in sameName) sameName += id
            }
        }
    }

    private fun forEachEntry(table: ByteBuffer, chunk: Int, each: (index: Int, key: Int) -> Unit) {
        val flags = u8(table, chunk + 9)
        val count = table.getInt(chunk + 12)
        val entries = chunk + table.getInt(chunk + 16)
        val offsets = chunk + u16(table, chunk + 2)
        for (i in 0 until count) {
            val index: Int
            val offset: Int
            when {
                flags and SPARSE != 0 -> {
                    index = u16(table, offsets + 4 * i)
                    offset = u16(table, offsets + 4 * i + 2) * 4
                }
                flags and OFFSET16 != 0 -> {
                    index = i
                    offset = u16(table, offsets + 2 * i).let { if (it == 0xffff) -1 else it * 4 }
                }
                else -> {
                    index = i
                    offset = table.getInt(offsets + 4 * i)
                }
            }
            if (offset == -1) continue
            val entry = entries + offset
            val key = if (u16(table, entry + 2) and COMPACT != 0) u16(table, entry) else table.getInt(entry + 4)
            each(index, key)
        }
    }

    private fun forEachChunk(table: ByteBuffer, from: Int, until: Int, each: (start: Int, type: Int) -> Unit) {
        var at = from
        while (at + 8 <= until) {
            val size = table.getInt(at + 4)
            check(size >= 8 && at + size <= until) { "a chunk at $at runs past its parent" }
            each(at, u16(table, at))
            at += size
        }
    }

    private class StringPool(private val table: ByteBuffer, start: Int) {
        val count = table.getInt(start + 8)
        private val utf8 = table.getInt(start + 16) and 0x100 != 0
        private val strings = start + table.getInt(start + 20)
        private val offsets = start + u16(table, start + 2)

        operator fun get(index: Int): String {
            check(index in 0 until count) { "string $index of $count" }
            var at = strings + table.getInt(offsets + 4 * index)
            if (utf8) {
                at += if (u8(table, at) and 0x80 != 0) 2 else 1
                var length = u8(table, at++)
                if (length and 0x80 != 0) length = ((length and 0x7f) shl 8) or u8(table, at++)
                val bytes = ByteArray(length)
                table.get(at, bytes)
                return String(bytes, Charsets.UTF_8)
            }
            var length = u16(table, at)
            at += 2
            if (length and 0x8000 != 0) {
                length = ((length and 0x7fff) shl 16) or u16(table, at)
                at += 2
            }
            return buildString { for (i in 0 until length) append(table.getChar(at + 2 * i)) }
        }
    }

    private fun u8(table: ByteBuffer, at: Int) = table.get(at).toInt() and 0xff
    private fun u16(table: ByteBuffer, at: Int) = table.getShort(at).toInt() and 0xffff
}
