import androidx.compose.runtime.MonotonicFrameClock

class TestFrameClock : MonotonicFrameClock {
    private var timeNanos = 0L

    override suspend fun <R> withFrameNanos(onFrame: (frameTimeNanos: Long) -> R): R {
        timeNanos += 16_000_000L
        return onFrame(timeNanos)
    }
}
