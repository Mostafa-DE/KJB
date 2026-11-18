package app.lyrify.ai

// The AI's own tests live with the AI in the add-on (ai-addon/src/test); KJB keeps this one fixture of theirs for the
// tests of what it still decodes itself (Tones) through the shared MixSource.


/**
 * A mix held whole, read as the decoder gives it: at most [piece] frames per read (a decoder hands out what it has),
 * then 0 at the end. [closed] tells whether the reader closed it. With [jumps], it [seek]s before its first read as a
 * decoder that can seek its file does; [handedOut] counts the frames read.
 */
class ArrayMix(
    private val left: FloatArray,
    private val right: FloatArray = left,
    private val piece: Int = Int.MAX_VALUE,
    private val jumps: Boolean = false,
) : MixSource {
    private var at = 0
    var closed = false
        private set
    var handedOut = 0L
        private set

    constructor(stereo: Array<FloatArray>, piece: Int = Int.MAX_VALUE) : this(stereo[0], stereo[1], piece)

    override val expectedFrames: Int get() = left.size

    override fun read(left: FloatArray, right: FloatArray, offset: Int, count: Int): Int {
        val n = minOf(count, piece, this.left.size - at)
        this.left.copyInto(left, offset, at, at + n)
        this.right.copyInto(right, offset, at, at + n)
        at += n
        handedOut += n
        return n
    }

    override fun seek(frame: Long): Boolean {
        if (!jumps || handedOut > 0) return false
        at = frame.coerceAtMost(left.size.toLong()).toInt()
        return true
    }

    override fun close() {
        closed = true
    }

    /** The largest absolute sample: the peak a spec that normalises needs up front. */
    fun peak(): Float = maxOf(left.maxOf { kotlin.math.abs(it) }, right.maxOf { kotlin.math.abs(it) })
}
