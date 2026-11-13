package app.lyrify.ai.api

import app.lyrify.ai.AiModels
import app.lyrify.ai.AiResult
import app.lyrify.ai.AiStage
import app.lyrify.ai.AiStats
import app.lyrify.ai.RejectReason
import app.lyrify.lyrics.LrcProblem
import app.lyrify.models.ModelState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WireTest {
    private val stats = AiStats(
        audioSec = 200.5,
        vocalShare = 0.62,
        wordsPerMinute = 88.0,
        chunks = 7,
        transcribedSec = 140.0,
        segments = 31,
        lines = 29,
        stageMs = mapOf(AiStage.DECODE to 12L, AiStage.SEPARATE to 9_000L, AiStage.TRANSCRIBE to 30_000L),
    )

    @Test
    fun `every result goes over as JSON and comes back the same`() {
        val results = listOf(
            AiResult.Transcribed("[00:01.000]placeholder one\n", stats),
            AiResult.Aligned("[00:02.000]placeholder two\n", 0.87, stats),
            AiResult.Instrumental(3.5),
            AiResult.Rejected(RejectReason.LOW_AGREEMENT, 0.41),
            AiResult.Rejected(RejectReason.INVALID_LRC, null, LrcProblem.TOO_FEW_LINES),
            AiResult.Failed("ModelLoadException: whisper-npu could not be loaded", model = AiModels.WHISPER),
            AiResult.Failed("NpuUnavailableException: no NPU device", npu = true),
            AiResult.Failed("IllegalStateException"),
        )

        for (result in results) assertEquals(result, Wire.result(Wire.encode(result)))
    }

    @Test
    fun `a result the add-on names in a later version, or that is not JSON, is a failed run`() {
        val later = """{"type":"summarised","lrc":"[00:01.000]x"}"""

        assertEquals(AiResult.Failed("UnknownResult"), Wire.result(later))
        assertEquals(AiResult.Failed("UnknownResult"), Wire.result("not json"))
    }

    @Test
    fun `fields a later add-on adds are ignored`() {
        val text = """{"type":"instrumental","wordsPerMinute":2.0,"confidence":0.3}"""

        assertEquals(AiResult.Instrumental(2.0), Wire.result(text))
    }

    @Test
    fun `the status goes over with the model state and the pins`() {
        val states = listOf(
            AddonStatus(supported = false, models = null),
            AddonStatus(true, ModelState.Missing(0, 980), pins = mapOf(AiModels.MDX to "ab12"), runtime = "ONNX Runtime 1.30.0"),
            AddonStatus(true, ModelState.Downloading(400, 980, waiting = true)),
            AddonStatus(true, ModelState.NoRoom(10, 980, neededBytes = 1_800)),
            AddonStatus(true, ModelState.Ready(980)),
            AddonStatus(true, ModelState.Failed(5, 980)),
        )

        for (status in states) assertEquals(status, Wire.status(Wire.encode(status)))
    }

    @Test
    fun `a status that cannot be read is none`() {
        assertEquals(null, Wire.status("{"))
    }

    @Test
    fun `import replies go over, and one that cannot be read asks to try again`() {
        val replies = listOf(
            ImportReply(ImportReply.Outcome.IMPORTED),
            ImportReply(ImportReply.Outcome.MISMATCH, "sha256"),
            ImportReply(ImportReply.Outcome.REFUSED, "name"),
            ImportReply(ImportReply.Outcome.RETRY, "IOException"),
        )

        for (reply in replies) assertEquals(reply, Wire.reply(Wire.encode(reply)))
        assertEquals(ImportReply.Outcome.RETRY, Wire.reply("[]").outcome)
    }

    @Test
    fun `the permission is the add-on's own, so a debug and a release add-on never clash`() {
        assertEquals("app.lyrify.ai.permission.BIND_AI", AddonNames.permission("app.lyrify.ai"))
        assertEquals("app.lyrify.ai.debug.permission.BIND_AI", AddonNames.permission("app.lyrify.ai.debug"))
        assertTrue(AddonNames.SERVICE.startsWith("app.lyrify.ai."))
    }
}
