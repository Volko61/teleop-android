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
import com.giacomoran.teleop.ui.ControlPad
import com.giacomoran.teleop.ui.ControlPadOutput
import com.giacomoran.teleop.ui.StatusBar
import com.giacomoran.teleop.ui.theme.TeleopTheme
import com.giacomoran.teleop.util.ARCoreSessionManager
import com.giacomoran.teleop.util.WebSocketClient
import kotlinx.coroutines.delay

class ARActivity : ComponentActivity() {
    private lateinit var sessionManager: ARCoreSessionManager
    private var webSocketClient: WebSocketClient? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Get server IP and port from intent
        val serverIp = intent.getStringExtra("SERVER_IP") ?: "192.168.1.100"
        val serverPort = intent.getStringExtra("SERVER_PORT")?.toIntOrNull() ?: 4443

        // Initialize WebSocket client
        webSocketClient = WebSocketClient(serverIp, serverPort)
        webSocketClient?.connect()

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
                ARScreen(
                    sessionManager = sessionManager,
                    webSocketClient = webSocketClient
                )
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
        webSocketClient?.disconnect()
        sessionManager.stopSession()
    }
}

@Composable
fun ARScreen(
    sessionManager: ARCoreSessionManager,
    webSocketClient: WebSocketClient?
) {
    val poseState by sessionManager.poseState.collectAsState()
    val trackingState by sessionManager.trackingState.collectAsState()
    val connectionState by webSocketClient?.connectionState?.collectAsState()
        ?: remember { mutableStateOf(WebSocketClient.ConnectionState.Disconnected) }

    // Show tracking message for first 5 seconds
    var showTrackingMessage by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        delay(5000) // 5 seconds
        showTrackingMessage = false
    }

    // Handle control pad output and send to server
    var controlOutput by remember { mutableStateOf<ControlPadOutput?>(null) }

    // Send pose data when it updates
    LaunchedEffect(poseState) {
        if (poseState is ARCoreSessionManager.PoseState.Pose) {
            val pose = poseState as ARCoreSessionManager.PoseState.Pose
            webSocketClient?.sendPose(pose.position, pose.quaternion)
        }
    }

    // Send control data when it changes
    LaunchedEffect(controlOutput) {
        controlOutput?.let { control ->
            webSocketClient?.sendControl(control)
        }
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

            // Main content column
            Column(
                modifier = Modifier.fillMaxSize()
            ) {
                // Status bar at the top
                StatusBar(
                    poseState = poseState,
                    trackingState = trackingState,
                    connectionState = connectionState,
                    modifier = Modifier.fillMaxWidth()
                )

                // Tracking message overlay (temporary, shown for first 5 seconds)
                if (showTrackingMessage) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
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

                // Control pad - aligned to bottom with small margins
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f),
                    contentAlignment = Alignment.BottomCenter
                ) {
                    ControlPad(
                        onControlChange = { output ->
                            controlOutput = output
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .padding(horizontal = 8.dp, vertical = 8.dp)
                    )
                }
            }
        }
    }
}


