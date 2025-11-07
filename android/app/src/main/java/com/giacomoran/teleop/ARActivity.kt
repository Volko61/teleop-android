package com.giacomoran.teleop

import android.opengl.GLSurfaceView
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.giacomoran.teleop.ui.FPSMeter
import com.giacomoran.teleop.ui.theme.TeleopTheme
import com.giacomoran.teleop.util.ARCoreSessionManager
import kotlinx.coroutines.delay

class ARActivity : ComponentActivity() {
    private lateinit var sessionManager: ARCoreSessionManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Initialize ARCore session manager
        sessionManager = ARCoreSessionManager(this)
        val started = sessionManager.startSession()

        if (!started) {
            // Handle error - could show error screen
            finish()
            return
        }

        setContent {
            TeleopTheme {
                ARScreen(sessionManager = sessionManager)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        sessionManager.resume()
    }

    override fun onPause() {
        super.onPause()
        sessionManager.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        sessionManager.stopSession()
    }
}

@Composable
fun ARScreen(sessionManager: ARCoreSessionManager) {
    val poseState by sessionManager.poseState.collectAsState()
    val trackingState by sessionManager.trackingState.collectAsState()

    // Show tracking message for first 5 seconds
    var showTrackingMessage by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        delay(5000) // 5 seconds
        showTrackingMessage = false
    }

    Scaffold(
        modifier = Modifier.fillMaxSize()
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Hidden GLSurfaceView for ARCore context (offscreen, just maintains OpenGL context)
            AndroidView(
                factory = { context ->
                    (sessionManager.getGLSurfaceView() ?: GLSurfaceView(context)).apply {
                        // Make it invisible since we only need the OpenGL context
                        alpha = 0f
                    }
                },
                modifier = Modifier.fillMaxSize()
            )

            // ARCore Tracking Meter centered on screen
            FPSMeter(
                updatesPerSecond = trackingState.updatesPerSecond,
                cameraTrackingState = trackingState.cameraTrackingState,
                isStalled = trackingState.isStalled,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(16.dp)
            )

            // Tracking message overlay
            if (showTrackingMessage) {
                Card(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Text(
                        text = "Slowly move your phone around to improve tracking",
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }

            // Pose information display
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "6DoF Tracking",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )

                        when (val state = poseState) {
                            is ARCoreSessionManager.PoseState.Initializing -> {
                                Text(
                                    text = "Initializing tracking...",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            is ARCoreSessionManager.PoseState.Tracking -> {
                                Text(
                                    text = "Tracking...",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            is ARCoreSessionManager.PoseState.Pose -> {
                                // Position (x, y, z)
                                Text(
                                    text = "Position:",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = "  X: ${formatFloat(state.position[0])}\n" +
                                           "  Y: ${formatFloat(state.position[1])}\n" +
                                           "  Z: ${formatFloat(state.position[2])}",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurface
                                )

                                Spacer(modifier = Modifier.height(4.dp))

                                // Orientation (quaternion: w, x, y, z)
                                Text(
                                    text = "Orientation (Quaternion):",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = "  W: ${formatFloat(state.quaternion[0])}\n" +
                                           "  X: ${formatFloat(state.quaternion[1])}\n" +
                                           "  Y: ${formatFloat(state.quaternion[2])}\n" +
                                           "  Z: ${formatFloat(state.quaternion[3])}",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                            is ARCoreSessionManager.PoseState.Error -> {
                                Text(
                                    text = "Error: ${state.message}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Format float to 3 decimal places for display
 */
private fun formatFloat(value: Float): String {
    return String.format("%.3f", value)
}

