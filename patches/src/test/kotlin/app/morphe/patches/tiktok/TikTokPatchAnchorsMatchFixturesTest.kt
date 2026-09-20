package app.morphe.patches.tiktok

import app.morphe.patches.tiktok.feedfilter.countColdStartFeedItemListStores
import app.morphe.patches.tiktok.interaction.downloads.drawsCommentImageWatermark
import app.morphe.patches.tiktok.interaction.speed.playerManagerSpeedBoundary
import app.morphe.patches.tiktok.misc.settings.isSettingsComposeRowsMethod
import com.android.tools.smali.dexlib2.DexFileFactory
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The three instruction-level anchors that changed in TikTok 47.0.3 remain unique on every
 * retained universal APK. The assertions describe behavior the patches consume rather than R8
 * names or strings that can move into adjacent methods.
 */
class TikTokPatchAnchorsMatchFixturesTest {
    @Test
    fun `settings compose rows anchor is unique on every fixture`() {
        val apks = fixtures()
        assumeTrue("no TikTok fixture on this machine", apks.isNotEmpty())

        for (apk in apks.sortedByDescending { it.name }) {
            val matches = mutableListOf<Method>()
            val classMethods = mutableListOf<Method>()
            val container = DexFileFactory.loadDexContainer(apk, Opcodes.getDefault())
            for (entry in container.dexEntryNames) {
                for (classDef in container.getEntry(entry)!!.dexFile.classes) {
                    if (classDef.type.endsWith("/SettingsComposeRvmpFragment;")) {
                        classMethods += classDef.methods
                    }
                    for (method in classDef.methods) {
                        if (isSettingsComposeRowsMethod(method, classDef)) matches += method
                    }
                }
            }

            assertEquals(
                "${apk.name}: settings compose rows " +
                    matches.joinToString { it.anchorSignature() } +
                    "; class methods: " + classMethods.joinToString(" | ") { method ->
                        method.anchorSignature() + " calls=" + method.listReturningCalls().joinToString()
                    },
                1,
                matches.size,
            )
        }
    }

    @Test
    fun `watermark cache and playback anchors are unique on every fixture`() {
        val apks = fixtures()
        assumeTrue("no TikTok fixture on this machine", apks.isNotEmpty())

        for (apk in apks) {
            val watermark = mutableListOf<Method>()
            val goldenCache = mutableListOf<Method>()
            val offlineCache = mutableListOf<Method>()
            val speed = mutableListOf<Method>()

            val container = DexFileFactory.loadDexContainer(apk, Opcodes.getDefault())
            for (entry in container.dexEntryNames) {
                for (classDef in container.getEntry(entry)!!.dexFile.classes) {
                    for (method in classDef.methods) {
                        val strings = method.stringConstants()
                        if (
                            method.parameterTypes == listOf("Landroid/graphics/Bitmap;") &&
                            method.returnType == "V" &&
                            "[tiktok_logo]" in strings &&
                            method.drawsCommentImageWatermark()
                        ) {
                            watermark += method
                        }
                        val storeCount = method.countColdStartFeedItemListStores()
                        if (
                            method.parameterTypes.isEmpty() && method.returnType == "Z" &&
                            "processGoldenVideoHitCache hitCache , time cost " in strings &&
                            storeCount in 3..4
                        ) {
                            goldenCache += method
                        }
                        if (
                            method.parameterTypes.isEmpty() && method.returnType == "Z" &&
                            "processOfflineVideoHitCache error" in strings &&
                            storeCount in 1..4
                        ) {
                            offlineCache += method
                        }
                        if (
                            method.definingClass.endsWith("/feed/controller/PlayerController;") &&
                            method.parameterTypes == listOf("F") && method.returnType == "V" &&
                            "speed_begin" in strings && "begin_speed" in strings &&
                            method.playerManagerSpeedBoundary() != null
                        ) {
                            speed += method
                        }
                    }
                }
            }

            assertEquals("${apk.name}: comment watermark anchor", 1, watermark.size)
            assertEquals("${apk.name}: golden cold-cache anchor", 1, goldenCache.size)
            assertEquals("${apk.name}: offline cold-cache anchor", 1, offlineCache.size)
            assertEquals("${apk.name}: playback speed anchor", 1, speed.size)

            val coldMethods = (goldenCache + offlineCache).distinctBy { it.anchorSignature() }
            assertEquals(
                "${apk.name}: cold-cache FeedItemList stores",
                4,
                coldMethods.sumOf { it.countColdStartFeedItemListStores() },
            )
            val offlineMarkers = coldMethods.sumOf { method ->
                method.implementation?.instructions?.count { instruction ->
                    instruction.opcode == Opcode.SGET_OBJECT &&
                        (instruction as? ReferenceInstruction)?.reference.let { reference ->
                            reference is FieldReference &&
                                reference.name == "OFFLINE_MODE" &&
                                reference.type == reference.definingClass
                        }
                } ?: 0
            }
            assertEquals("${apk.name}: cold-cache OFFLINE_MODE marker", 1, offlineMarkers)
        }
    }

    private fun Method.stringConstants(): Set<String> =
        implementation?.instructions?.mapNotNull { instruction ->
            ((instruction as? ReferenceInstruction)?.reference as? StringReference)?.string
        }?.toSet() ?: emptySet()

    private fun Method.anchorSignature(): String =
        "$definingClass->$name${parameterTypes.joinToString("", "(", ")")}$returnType"

    private fun Method.listReturningCalls(): List<String> =
        implementation?.instructions?.mapNotNull { instruction ->
            if (instruction.opcode != Opcode.INVOKE_STATIC) return@mapNotNull null
            val reference = (instruction as? ReferenceInstruction)?.reference as? MethodReference
                ?: return@mapNotNull null
            reference.toString().takeIf {
                reference.returnType == "Ljava/util/List;" ||
                    "Ljava/util/Comparator;" in reference.parameterTypes ||
                    "Ljava/lang/Iterable;" in reference.parameterTypes
            }
        } ?: emptyList()

    private fun fixtures(): List<File> {
        val directory = File(System.getenv("HUSHFEED_FIXTURE_DIR") ?: "C:/_claude-backups/tiktok-fixture")
        if (!directory.isDirectory) return emptyList()
        return directory.listFiles()?.filter { file ->
            file.isFile && file.extension == "apk" &&
                file.name.contains(Regex("(46\\.[2789]\\.3|47\\.0\\.3)"))
        }?.sortedBy { it.name } ?: emptyList()
    }
}
