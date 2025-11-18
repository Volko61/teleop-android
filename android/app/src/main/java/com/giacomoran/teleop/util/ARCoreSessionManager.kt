package com.giacomoran.teleop.util

import android.app.Activity
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.util.Log
import com.google.ar.core.*
import com.google.ar.core.exceptions.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.EnumSet
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Manages ARCore session lifecycle and provides 6DoF pose tracking.
 *
 * This manager uses a headless GLSurfaceView to maintain an OpenGL context
 * for ARCore without rendering camera frames, optimized for teleoperation use cases.
 *
 * ARCore Configuration for Teleop:
 * - Focus mode set to AUTO for continuous tracking
 * - Update mode set to BLOCKING for consistent frame timing
 * - Plane finding disabled to reduce CPU usage (not needed for 6DoF tracking)
 * - Light estimation disabled to reduce computation overhead
 * - Cloud anchor disabled (not needed for local tracking)
 */
class ARCoreSessionManager(private val activity: Activity) {
    private val TAG = "ARCoreSessionManager"

    private var session: Session? = null
    private var glSurfaceView: GLSurfaceView? = null
    private var cameraTextureId: Int = 0

    // Session state tracking using sealed class (type-safe, prevents invalid states)
    private var sessionState: SessionState = SessionState.Stopped

    // Pose tracking state
    private val _poseState = MutableStateFlow<PoseState>(PoseState.Initializing)
    val poseState: StateFlow<PoseState> = _poseState

    // ARCore tracking updates tracker (tracks session.update() calls, not rendering FPS)
    private val trackingTracker = ARCoreTrackingTracker(
        expectedUpdateRateHz = 60f, // Request 60 FPS updates
        stallThresholdMs = 100f, // Consider stalled if gap > 100ms
        historySize = 60
    )
    val trackingState: StateFlow<ARCoreTrackingTracker.TrackingMetrics> = trackingTracker.trackingState

    /**
     * Represents the current session state
     * Using sealed class ensures type safety and prevents invalid state combinations
     */
    private sealed class SessionState {
        object Stopped : SessionState()
        object Initializing : SessionState() // Session created, configuring
        object Ready : SessionState() // Session configured, texture initialized, but not resumed
        object Running : SessionState() // Session resumed and actively processing frames
        object Paused : SessionState() // Session paused (can resume)
        data class Error(val message: String) : SessionState()

        // Helper properties for easy state checking
        val isRunning: Boolean get() = this is Running
        val isReady: Boolean get() = this is Ready || this is Running
        val isTextureInitialized: Boolean get() = this is Ready || this is Running || this is Paused
        val canResume: Boolean get() = this is Ready || this is Paused
        val canPause: Boolean get() = this is Running
    }

    /**
     * Represents the current pose state of the device
     */
    sealed class PoseState {
        object Initializing : PoseState()
        object Tracking : PoseState()
        data class Pose(val position: FloatArray, val quaternion: FloatArray) : PoseState()
        data class Error(val message: String) : PoseState()
    }


    /**
     * Initialize and start the ARCore session
     */
    fun startSession(): Boolean {
        return try {
            // Create ARCore session
            session = Session(activity).apply {
                // Configure for teleop use case:
                // - AUTO focus mode for continuous tracking
                // - BLOCKING update mode for consistent frame timing
                val config = Config(this).apply {
                    focusMode = Config.FocusMode.AUTO
                    updateMode = Config.UpdateMode.BLOCKING
                    // Disable features not needed for 6DoF tracking to reduce CPU usage
                    planeFindingMode = Config.PlaneFindingMode.DISABLED
                    lightEstimationMode = Config.LightEstimationMode.DISABLED
                    cloudAnchorMode = Config.CloudAnchorMode.DISABLED
                }
                configure(config)

                // Request 60 FPS camera config if device supports it (falls back to 30 FPS if not supported)
                try {
                    val filter = CameraConfigFilter(this).apply {
                        targetFps = EnumSet.of(CameraConfig.TargetFps.TARGET_FPS_60)
                    }
                    val cameraConfigList = getSupportedCameraConfigs(filter)
                    if (cameraConfigList.isNotEmpty()) {
                        cameraConfig = cameraConfigList[0]
                        Log.d(TAG, "Camera config set to 60 FPS")
                    } else {
                        Log.d(TAG, "60 FPS not supported, using default camera config")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to set 60 FPS camera config, using default", e)
                }
            }

            // Create GLSurfaceView for OpenGL context
            glSurfaceView = GLSurfaceView(activity).apply {
                setEGLContextClientVersion(2)
                setEGLConfigChooser(8, 8, 8, 8, 16, 0) // RGBA8888 with depth
                setRenderer(ARRenderer())
                renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
            }

            sessionState = SessionState.Initializing
            _poseState.value = PoseState.Tracking
            Log.d(TAG, "ARCore session started successfully")
            true
        } catch (e: UnavailableException) {
            Log.e(TAG, "ARCore unavailable", e)
            _poseState.value = PoseState.Error("ARCore unavailable: ${e.message}")
            false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start ARCore session", e)
            _poseState.value = PoseState.Error("Failed to start session: ${e.message}")
            false
        }
    }

    /**
     * Resume the ARCore session (call from Activity.onResume)
     * Note: Session will be automatically resumed when texture is ready in onSurfaceChanged()
     */
    fun resume() {
        if (!sessionState.canResume) {
            Log.d(TAG, "ARCore session cannot resume from state: $sessionState")
            return
        }

        try {
            glSurfaceView?.onResume()
            // Session resume will happen in onSurfaceChanged() after texture is set up
            // If texture is already initialized, resume now
            if (sessionState.isTextureInitialized && session != null) {
                session?.resume()
                sessionState = SessionState.Running
                Log.d(TAG, "ARCore session resumed")
            }
        } catch (e: CameraNotAvailableException) {
            Log.e(TAG, "Camera not available", e)
            sessionState = SessionState.Error("Camera not available: ${e.message}")
            _poseState.value = PoseState.Error("Camera not available: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to resume ARCore session", e)
            sessionState = SessionState.Error("Failed to resume: ${e.message}")
            _poseState.value = PoseState.Error("Failed to resume: ${e.message}")
        }
    }

    /**
     * Pause the ARCore session (call from Activity.onPause)
     */
    fun pause() {
        if (!sessionState.canPause) {
            Log.d(TAG, "ARCore session cannot pause from state: $sessionState")
            return
        }

        try {
            glSurfaceView?.onPause()
            session?.pause()
            sessionState = SessionState.Paused
            trackingTracker.reset() // Reset tracking when pausing
            Log.d(TAG, "ARCore session paused")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to pause ARCore session", e)
        }
    }

    /**
     * Stop and cleanup the ARCore session
     */
    fun stopSession() {
        sessionState = SessionState.Stopped
        try {
            glSurfaceView?.onPause()
            glSurfaceView = null

            // Clean up camera texture
            if (cameraTextureId != 0) {
                val textures = intArrayOf(cameraTextureId)
                GLES20.glDeleteTextures(1, textures, 0)
                cameraTextureId = 0
            }

            session?.close()
            session = null

            Log.d(TAG, "ARCore session stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping ARCore session", e)
        }
    }

    /**
     * Get the GLSurfaceView for embedding in Compose
     */
    fun getGLSurfaceView(): GLSurfaceView? = glSurfaceView

    /**
     * Inner renderer class that handles frame updates
     */
    private inner class ARRenderer : GLSurfaceView.Renderer {
        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            Log.d(TAG, "OpenGL surface created")
            // Reset to Initializing state when surface is recreated
            if (sessionState is SessionState.Ready || sessionState is SessionState.Running || sessionState is SessionState.Paused) {
                sessionState = SessionState.Initializing
            }
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            Log.d(TAG, "OpenGL surface changed: ${width}x${height}")

            // Initialize camera texture when surface is ready
            if (!sessionState.isTextureInitialized && session != null) {
                try {
                    // Generate OpenGL texture ID
                    val textures = IntArray(1)
                    GLES20.glGenTextures(1, textures, 0)
                    cameraTextureId = textures[0]

                    // Bind texture to GL_TEXTURE_EXTERNAL_OES target (required for ARCore camera)
                    // Note: GL_TEXTURE_EXTERNAL_OES = 0x8D65
                    GLES20.glBindTexture(0x8D65, cameraTextureId)

                    // Set texture parameters
                    GLES20.glTexParameteri(0x8D65, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
                    GLES20.glTexParameteri(0x8D65, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
                    GLES20.glTexParameteri(0x8D65, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
                    GLES20.glTexParameteri(0x8D65, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)

                    // Set camera texture name for ARCore (must be done before session.resume())
                    session?.setCameraTextureName(cameraTextureId)
                    sessionState = SessionState.Ready
                    Log.d(TAG, "Camera texture initialized: $cameraTextureId")

                    // Resume session now that texture is ready (Issue #1 & #4 fix)
                    // This ensures texture is set before session resume, following Google's pattern
                    if (sessionState.canResume && session != null) {
                        try {
                            session?.resume()
                            sessionState = SessionState.Running
                            Log.d(TAG, "ARCore session resumed after texture initialization")
                        } catch (e: CameraNotAvailableException) {
                            Log.e(TAG, "Camera not available when resuming session", e)
                            sessionState = SessionState.Error("Camera not available: ${e.message}")
                            _poseState.value = PoseState.Error("Camera not available: ${e.message}")
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to resume session after texture init", e)
                            sessionState = SessionState.Error("Failed to resume session: ${e.message}")
                            _poseState.value = PoseState.Error("Failed to resume session: ${e.message}")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to initialize camera texture", e)
                    sessionState = SessionState.Error("Failed to initialize texture: ${e.message}")
                }
            }
        }

        override fun onDrawFrame(gl: GL10?) {
            if (!sessionState.isRunning || session == null) return

            try {
                // Update ARCore session
                val frame = session?.update() ?: return

                // Get camera pose and tracking state
                val camera = frame.camera

                // Track ARCore updates per second (not rendering FPS)
                // This detects when ARCore stalls or stops providing updates
                trackingTracker.onARCoreUpdate(camera.trackingState)

                // Only process pose if tracking is stable
                if (camera.trackingState == TrackingState.TRACKING) {
                    val pose = camera.pose

                    // Extract position (translation)
                    val position = FloatArray(3)
                    pose.getTranslation(position, 0)

                    // Extract orientation (quaternion) X, Y, Z, W
                    val quaternion = FloatArray(4)
                    pose.getRotationQuaternion(quaternion, 0)

                    // Update pose state
                    _poseState.value = PoseState.Pose(position, quaternion)
                }

            } catch (e: CameraNotAvailableException) {
                Log.e(TAG, "Camera not available", e)
                _poseState.value = PoseState.Error("Camera not available: ${e.message}")
            } catch (e: Exception) {
                Log.e(TAG, "Error updating ARCore frame", e)
                // Don't update error state for every exception to avoid spam
            }
        }
    }
}

