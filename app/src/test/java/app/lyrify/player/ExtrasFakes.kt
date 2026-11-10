package app.lyrify.player

/** A player for the sleep timer and the extras: three songs by uid, a place, play and pause, speed and volume as set. */
class FakeExtrasPort : ExtrasPort {
    override var loaded: List<Long> = listOf(1, 2, 3)
    override var index: Int = 0
    override var positionMs: Long = 0
    override var playing: Boolean = true
    override var durationMs: Long = 200_000
    override var speed: Float = 1f
    var pitch: Float = 1f
    override var volume: Float = 1f
    val seeks = mutableListOf<Pair<Int, Long>>()

    override fun load(items: List<Projected>, index: Int, positionMs: Long) = Unit

    override fun apply(edit: Edit) = Unit

    override fun seek(index: Int, positionMs: Long) {
        this.index = index
        this.positionMs = positionMs
        seeks += index to positionMs
    }

    override fun setPlayWhenReady(play: Boolean) {
        playing = play
    }

    override fun setSpeed(speed: Float, pitch: Float) {
        this.speed = speed
        this.pitch = pitch
    }
}
