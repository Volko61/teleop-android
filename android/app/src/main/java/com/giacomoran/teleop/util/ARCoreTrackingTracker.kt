package com.giacomoran.teleop.util

import com.google.ar.core.TrackingState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Tracks ARCore tracking updates per second and detects tracking stalls.
 *
 * This tracker monitors:
 * - ARCore frame updates per second (not rendering FPS)
 * - Camera tracking state (TRACKING, PAUSED, STOPPED)
 * - Detection of tracking stalls (gaps in updates)
 * - Frame timing to identify dropped/slow updates
 *
 * Use this to monitor whether ARCore is actively tracking the device in real-time.
 */
class ARCoreTrackingTracker(
    private val expectedUpdateRateHz: Float = 30f, // Expected ARCore update rate (30 or 60 FPS)
    private val stallThresholdMs: Float = 100f, // Consider stalled if gap > 100ms
    private val historySize: Int = 60
) {
    private val expectedFrameTimeMs = 1000f / expectedUpdateRateHz

    private val _trackingState = MutableStateFlow(TrackingMetrics())
    val trackingState: StateFlow<TrackingMetrics> = _trackingState

    private var lastUpdateTimeNs: Long = 0
    private val updateIntervalsMs = ArrayDeque<Float>(historySize)

    /**
     * Call this when session.update() successfully returns a frame.
     * This tracks ARCore tracking updates, not rendering FPS.
     *
     * @param cameraTrackingState The tracking state from frame.camera.trackingState
     */
    fun onARCoreUpdate(cameraTrackingState: TrackingState) {
        val currentTimeNs = System.nanoTime()

        if (lastUpdateTimeNs > 0) {
            val intervalNs = currentTimeNs - lastUpdateTimeNs
            val intervalMs = intervalNs / 1_000_000f // Convert nanoseconds to milliseconds

            // Add to history (maintains fixed size)
            updateIntervalsMs.addLast(intervalMs)
            if (updateIntervalsMs.size > historySize) {
                updateIntervalsMs.removeFirst()
            }

            // Calculate updates per second from recent intervals
            val avgInterval = updateIntervalsMs.average().toFloat()
            val updatesPerSecond = if (avgInterval > 0) 1000f / avgInterval else 0f

            // Detect stall: if this interval is much longer than expected
            // This indicates ARCore stopped providing updates
            val isStalled = intervalMs > stallThresholdMs

            // Check if tracking is actively working
            val isTrackingActive = cameraTrackingState == TrackingState.TRACKING

            // Detect dropped/slow frames (interval > 1.5x expected frame time)
            val droppedFrames = updateIntervalsMs.map { interval ->
                interval > (expectedFrameTimeMs * 1.5f)
            }

            _trackingState.value = TrackingMetrics(
                updatesPerSecond = updatesPerSecond,
                isTrackingActive = isTrackingActive,
                cameraTrackingState = cameraTrackingState,
                isStalled = isStalled,
                droppedFrames = droppedFrames
            )
        }

        lastUpdateTimeNs = currentTimeNs
    }

    /**
     * Reset tracking (useful when pausing/resuming)
     */
    fun reset() {
        lastUpdateTimeNs = 0
        updateIntervalsMs.clear()
        _trackingState.value = TrackingMetrics()
    }

    /**
     * ARCore tracking metrics
     */
    data class TrackingMetrics(
        val updatesPerSecond: Float = 0f, // ARCore updates per second
        val isTrackingActive: Boolean = false, // True when camera.trackingState == TRACKING
        val cameraTrackingState: TrackingState = TrackingState.PAUSED, // Raw ARCore tracking state
        val isStalled: Boolean = false, // True when gap between updates > stallThresholdMs
        val droppedFrames: List<Boolean> = emptyList() // true = slow/dropped update, false = on time
    )
}

