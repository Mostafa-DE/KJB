package app.lyrify.cut

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.lyrify.ai.AudioTooLongException
import app.lyrify.ai.MixDecoder
import app.lyrify.ai.MixSource
import app.lyrify.data.SharedWrites
import app.lyrify.tags.TagLibFile
import app.lyrify.tones.AacToneEncoder
import app.lyrify.tones.FadeCurve
import app.lyrify.tones.ToneAudio
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * The cutter on the phone, with its real AAC encoders and TagLib: every quality the phone offers saves an M4A that
 * decodes to the cut's length, carries the fixture's tags and both its covers with "(cut)" in its title and without its
 * ReplayGain, and is about the size shown before saving; a cancelled save leaves nothing; a song past 20 minutes is read
 * to its end. The audio is a made-up sine or silence;
 * the song is the tag tests' made-up `northbound-tide.m4a`. Everything stays in the debug app's private storage.
 */
@RunWith(AndroidJUnit4::class)
class CutDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val assets = InstrumentationRegistry.getInstrumentation().context.assets
    private val root = File(context.filesDir, "cut-device-test")
    private val album = File(root, "Northbound")
    private val music = File(root, "Music")
    private val song = File(album, "northbound-tide.m4a")
    private val tags = TagLibFile(context.filesDir)

    /** [seconds] of a 440 Hz sine at half volume, read as the decoder gives it. */
    private class Sine(seconds: Int) : MixSource {
        private val total = seconds * ToneAudio.RATE
        private var at = 0
        override val expectedFrames: Int get() = total

        override fun read(left: FloatArray, right: FloatArray, offset: Int, count: Int): Int {
            val n = minOf(count, total - at)
            for (i in 0 until n) {
                val v = (0.5 * sin(2 * PI * 440 * (at + i) / ToneAudio.RATE)).toFloat()
                left[offset + i] = v
                right[offset + i] = v
            }
            at += n
            return n
        }
    }

    private fun files(onEncode: () -> Unit = {}) = CutFiles(
        writes = SharedWrites.ANYWHERE,
        work = File(root, "work"),
        tags = tags,
        openSong = { Sine(seconds = 30) },
        newEncoder = { fd, bitRate, codec -> AacToneEncoder(fd, bitRate, codec).also { onEncode() } },
        music = music,
        canWrite = { it.isDirectory },
        scan = {},
        log = {},
        io = Dispatchers.IO,
    )

    private fun request(quality: CutQuality, encoder: String?) = CutRequest(
        songPath = song.path,
        songTitle = "Northbound Tide",
        songArtist = null,
        songAlbum = null,
        range = CutRange(2_000, 22_000),
        fadeIn = Fade(1_000, FadeCurve.SMOOTH),
        fadeOut = Fade(3_000, FadeCurve.EVEN),
        quality = quality,
        encoder = encoder,
    )

    @Before
    fun setUp() {
        root.deleteRecursively()
        album.mkdirs()
        music.mkdirs()
        assets.open("tags/northbound-tide.m4a").use { input -> song.outputStream().use { input.copyTo(it) } }
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun everyQualityThePhoneOffersSavesATaggedM4aOfTheCutsLength() = runBlocking<Unit> {
        val offered = CutQuality.offered(CutEncoders.phone())
        assertTrue("this phone offers no AAC encoder for 44.1 kHz stereo", offered.isNotEmpty())
        // The fixture is in KJB's private storage here, so TagLib may read it directly.
        val original = requireNotNull(tags.read(song))
        for ((quality, encoder) in offered) {
            val result = files().save(request(quality, encoder)) {}
            val saved = (result as CutResult.Saved).file
            assertTrue("$quality", result.tagged)
            assertEquals(album, saved.parentFile)

            val read = requireNotNull(tags.read(saved))
            assertEquals(listOf("Northbound Tide (cut)"), read.fields["TITLE"])
            assertEquals(original.fields["ARTIST"], read.fields["ARTIST"])
            assertFalse(read.fields.keys.any { it.startsWith("REPLAYGAIN_") })
            assertEquals(original.pictures.map { it.data.toList() }, read.pictures.map { it.data.toList() })

            // 20 s at 44.1 kHz, give or take the encoder's priming and its last frame.
            val frames = MixDecoder(saved).use { decoder -> countFrames(decoder) }
            assertTrue("$quality: $frames frames", abs(frames - 20 * ToneAudio.RATE) <= 4_096)
            val estimate = CutSize.estimateBytes(20_000, quality, tagBytes = original.pictures.sumOf { it.data.size.toLong() })
            assertTrue("$quality: ${saved.length()} bytes, about $estimate", abs(saved.length() - estimate) < estimate * 0.15)
            saved.delete()
        }
    }

    @Test
    fun aCancelledSaveLeavesNoFile() = runBlocking<Unit> {
        var job: Job? = null
        val cutter = files(onEncode = { job?.cancel() })
        job = launch(Dispatchers.IO, start = CoroutineStart.LAZY) { cutter.save(request(CutQuality.DEFAULT, null)) {} }
        job.start()
        job.join()
        assertEquals(listOf(song.name), album.list().orEmpty().toList())
        assertEquals(emptyList<String>(), music.list().orEmpty().toList())
        assertEquals(emptyList<String>(), File(root, "work").list().orEmpty().toList())
    }

    @Test
    fun aSongPastTwentyMinutesIsReadToItsEndByTheCuttersDecoder() {
        // 21 minutes of silence as an 8 kHz mono WAV (20 MB, its data left as zeros).
        val long = File(album, "long-mix.wav")
        val rate = 8_000
        val dataBytes = 21 * 60 * rate * 2
        RandomAccessFile(long, "rw").use { file ->
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            header.put("RIFF".toByteArray()).putInt(36 + dataBytes).put("WAVE".toByteArray())
            header.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
            header.put("data".toByteArray()).putInt(dataBytes)
            file.write(header.array())
            file.setLength(44L + dataBytes)
        }
        val frames = CutFiles.decoder(long).use { decoder -> countFrames(decoder) }
        assertTrue("$frames frames", abs(frames - 21 * 60 * ToneAudio.RATE) <= 4_096)
        // The AI's decoder stops at 20 minutes.
        assertThrows(AudioTooLongException::class.java) { MixDecoder(long).use { decoder -> countFrames(decoder) } }
    }

    private fun countFrames(source: MixSource): Int {
        val left = FloatArray(ToneAudio.CHUNK_FRAMES)
        val right = FloatArray(ToneAudio.CHUNK_FRAMES)
        var total = 0
        while (true) {
            val n = source.read(left, right, 0, left.size)
            if (n == 0) return total
            total += n
        }
    }
}
