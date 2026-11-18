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
 * to its end; a late start jumps there and lands on the frame a read from the start gives, in a WAV, a 48 kHz WAV and
 * an M4A, the saved cut and its fades too. The audio is a made-up sine, a sum of tones, or silence;
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

    /** [source] read from its start only, as a decoder that cannot jump. */
    private class FromStart(private val source: MixSource) : MixSource by source {
        override fun seek(frame: Long): Boolean = false
    }

    private fun files(onEncode: () -> Unit = {}, open: (File) -> MixSource = { Sine(seconds = 30) }) = CutFiles(
        writes = SharedWrites.ANYWHERE,
        work = File(root, "work"),
        tags = tags,
        openSong = open,
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

    @Test
    fun aLateStartInAWavLandsOnItsVeryFrame() {
        val rate = ToneAudio.RATE
        val song = wav(File(album, "long-mix.wav"), rate, seconds = 40)
        val at = 30L * rate + 123
        val (jumped, read) = CutFiles.decoder(song).use { decoder -> decoder.seek(at) to readFrames(decoder, 8_192) }
        assertTrue(jumped)
        for (i in read.indices) assertEquals("frame $i", ToneAudio.pcm16(tones(at + i, rate)) / 32768f, read[i], 1e-6f)
    }

    @Test
    fun aLateStartInA48kHzWavLandsWhereAReadFromTheStartDoes() {
        val song = wav(File(album, "long-mix-48.wav"), 48_000, seconds = 40)
        assertLandsAsFromTheStart(song, 30L * ToneAudio.RATE + 77)
    }

    @Test
    fun aLateStartInAnM4aLandsWhereAReadFromTheStartDoes() {
        val song = m4a(File(album, "long-mix.m4a"), seconds = 40)
        assertLandsAsFromTheStart(song, 30L * ToneAudio.RATE + 77)
    }

    @Test
    fun aLateCutOfAnM4aIsTheCutReadFromTheStartFadesAndAll() = runBlocking<Unit> {
        val song = m4a(File(album, "long-mix.m4a"), seconds = 40)
        val cut = request(CutQuality.DEFAULT, null).copy(songPath = song.path, range = CutRange(30_000, 34_000))
        val jumped = (files(open = { CutFiles.decoder(it) }).save(cut) {} as CutResult.Saved).file
        val read = (files(open = { FromStart(CutFiles.decoder(it)) }).save(cut) {} as CutResult.Saved).file
        val a = MixDecoder(read).use { decoder -> readFrames(decoder, 4 * ToneAudio.RATE) }
        val b = MixDecoder(jumped).use { decoder -> readFrames(decoder, 4 * ToneAudio.RATE) }
        val (lag, rms) = bestLag(a, b, range = 2_048)
        assertTrue("the jumped cut is $lag frames off", abs(lag) <= AAC_FRAME)
        assertTrue("the cuts differ by $rms RMS at their best", rms < 0.01)
    }

    /**
     * The first frames from [frame] of [song], jumped to and read to, agree within an AAC frame and nearly sample for
     * sample (a resampled song's jump may sit half a frame off the read's grid).
     */
    private fun assertLandsAsFromTheStart(song: File, frame: Long) {
        val (jumped, b) = CutFiles.decoder(song).use { decoder -> decoder.seek(frame) to readFrames(decoder, 16_384) }
        assertTrue(jumped)
        val a = FromStart(CutFiles.decoder(song)).use { decoder ->
            readFrames(decoder, frame.toInt())
            readFrames(decoder, 16_384)
        }
        val (lag, rms) = bestLag(a, b, range = 2_048)
        assertTrue("landed $lag frames off", abs(lag) <= AAC_FRAME)
        assertTrue("landed $lag frames off, differing by $rms RMS", rms < 0.01)
    }

    /** A sum of tones under 1 kHz with no common period near a second: frame [i] at [rate]. */
    private fun tones(i: Long, rate: Int): Float =
        TONES.sumOf { (hz, phase) -> 0.08 * sin(2 * PI * hz * i / rate + phase) }.toFloat()

    /** [seconds] of [tones] as a 16-bit stereo WAV at [rate]. */
    private fun wav(file: File, rate: Int, seconds: Int): File {
        val frames = seconds * rate
        RandomAccessFile(file, "rw").use { out ->
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            header.put("RIFF".toByteArray()).putInt(36 + frames * 4).put("WAVE".toByteArray())
            header.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(2).putInt(rate).putInt(rate * 4).putShort(4).putShort(16)
            header.put("data".toByteArray()).putInt(frames * 4)
            out.write(header.array())
            val piece = ByteBuffer.allocate(4_096 * 4).order(ByteOrder.LITTLE_ENDIAN)
            for (start in 0 until frames step 4_096) {
                piece.clear()
                for (i in start until minOf(frames, start + 4_096)) {
                    val v = ToneAudio.pcm16(tones(i.toLong(), rate))
                    piece.putShort(v).putShort(v)
                }
                out.write(piece.array(), 0, piece.position())
            }
        }
        return file
    }

    /** [seconds] of [tones] at 44.1 kHz as an M4A, by the phone's AAC encoder (as tones and cuts are made). */
    private fun m4a(file: File, seconds: Int): File {
        val frames = seconds * ToneAudio.RATE
        RandomAccessFile(file, "rw").use { out ->
            AacToneEncoder(out.fd).use { encoder ->
                val pcm = ShortArray(ToneAudio.CHUNK_FRAMES * 2)
                for (start in 0 until frames step ToneAudio.CHUNK_FRAMES) {
                    val n = minOf(ToneAudio.CHUNK_FRAMES, frames - start)
                    for (i in 0 until n) {
                        val v = ToneAudio.pcm16(tones((start + i).toLong(), ToneAudio.RATE))
                        pcm[2 * i] = v
                        pcm[2 * i + 1] = v
                    }
                    encoder.encode(pcm, n)
                }
                encoder.finish()
            }
        }
        return file
    }

    /** Up to [count] left-channel frames of [source]. */
    private fun readFrames(source: MixSource, count: Int): FloatArray {
        val out = FloatArray(count)
        val right = FloatArray(count)
        var at = 0
        while (at < count) {
            val n = source.read(out, right, at, count - at)
            if (n == 0) break
            at += n
        }
        return out.copyOf(at)
    }

    /** The shift of [b] against [a], within [range] frames either way, where they differ least, and that difference's RMS. */
    private fun bestLag(a: FloatArray, b: FloatArray, range: Int): Pair<Int, Double> {
        val n = minOf(a.size, b.size) - 2 * range
        var best = 0 to Double.MAX_VALUE
        for (lag in -range..range) {
            var sum = 0.0
            for (i in range until range + n) {
                val d = a[i + lag] - b[i]
                sum += d * d
            }
            val rms = kotlin.math.sqrt(sum / n)
            if (rms < best.second) best = lag to rms
        }
        return best
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

    private companion object {
        /** An AAC frame's frames: how far a jump may land from a read, at most. */
        const val AAC_FRAME: Int = 1_024

        /** The tones' frequencies (Hz) and phases. */
        val TONES: List<Pair<Double, Double>> = listOf(113.0 to 0.3, 197.0 to 1.1, 283.0 to 2.0, 419.0 to 0.7, 523.0 to 2.9, 661.0 to 1.6, 787.0 to 0.2, 953.0 to 2.4)
    }
}
