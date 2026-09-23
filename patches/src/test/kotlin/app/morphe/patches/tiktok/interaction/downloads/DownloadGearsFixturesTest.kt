package app.morphe.patches.tiktok.interaction.downloads

import app.morphe.Fixtures
import com.android.tools.smali.dexlib2.DexFileFactory
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where a chosen-quality download reads a video's renditions (QualitySelector.rawGears), held to
 * every retained fixture. Video.getRawBitRate returns the backing list whole on each build, and
 * that field is named one of the two names the fallback reads: bitRate on 46.2.3, bitRateList on
 * 47.0.3, where reading bitRate alone found nothing and every chosen quality fell back to
 * TikTok's own save. The separate audio renditions stay in bitRateAudio.
 */
class DownloadGearsFixturesTest {
    @Test
    fun `every fixture hands the whole rendition list to getRawBitRate`() {
        for (apk in Fixtures.apks()) {
            val container = DexFileFactory.loadDexContainer(apk, Opcodes.getDefault())
            val video = container.dexEntryNames.flatMap { entry ->
                container.getEntry(entry)!!.dexFile.classes.filter { it.type == VIDEO }
            }.first()
            val raw = video.methods.single { it.name == "getRawBitRate" && it.parameterTypes.isEmpty() }
            assertEquals("${apk.name}: getRawBitRate returns a List", LIST, raw.returnType)
            val instructions = raw.implementation!!.instructions.toList()
            assertEquals("${apk.name}: getRawBitRate is a plain field read", 2, instructions.size)
            val read = instructions[0]
            assertEquals("${apk.name}: the read", Opcode.IGET_OBJECT, read.opcode)
            val field = (read as ReferenceInstruction).reference as FieldReference
            assertTrue(
                "${apk.name}: a List field of Video named as rawGears reads it, got ${field.name}",
                field.definingClass == VIDEO && field.type == LIST && field.name in setOf("bitRate", "bitRateList"),
            )
            assertEquals("${apk.name}: returned as read", Opcode.RETURN_OBJECT, instructions[1].opcode)
            assertEquals(
                "${apk.name}: the value read is the value returned",
                (read as TwoRegisterInstruction).registerA,
                (instructions[1] as OneRegisterInstruction).registerA,
            )
            assertTrue(
                "${apk.name}: the audio renditions field",
                video.fields.any { it.name == "bitRateAudio" && it.type == LIST },
            )
            // QualitySelector.codec reads this and takes a missing one for H.264, so a rename
            // would bring ByteVC2 files back with every test green (refutation review 2026-09-23).
            val bitRate = container.dexEntryNames.flatMap { entry ->
                container.getEntry(entry)!!.dexFile.classes.filter { it.type == BIT_RATE }
            }.first()
            assertTrue(
                "${apk.name}: BitRate's int codec field isBytevc1",
                bitRate.fields.any { it.name == "isBytevc1" && it.type == "I" },
            )
            assertTrue(
                "${apk.name}: BitRate's isBytevc1() getter",
                bitRate.methods.any { it.name == "isBytevc1" && it.parameterTypes.isEmpty() && it.returnType == "I" },
            )
        }
    }

    private companion object {
        const val VIDEO = "Lcom/ss/android/ugc/aweme/feed/model/Video;"
        const val BIT_RATE = "Lcom/ss/android/ugc/aweme/feed/model/BitRate;"
        const val LIST = "Ljava/util/List;"
    }
}
