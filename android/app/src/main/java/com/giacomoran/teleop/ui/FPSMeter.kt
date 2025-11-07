package com.giacomoran.teleop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.ar.core.TrackingState

/**
 * ARCore Tracking Meter component that displays:
 * - ARCore updates per second (not rendering FPS)
 * - Real-time tracking status (TRACKING/PAUSED/STOPPED)
 * - Stall detection (when ARCore stops providing updates)
 *
 * This meter helps detect when ARCore stalls in tracking,
 * which is critical for teleoperation use cases.
 */
@Composable
fun FPSMeter(
    updatesPerSecond: Float,
    cameraTrackingState: TrackingState,
    isStalled: Boolean,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .background(
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
            )
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Updates per second display
                Text(
                    text = "${updatesPerSecond.toInt()} Hz",
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = when {
                        isStalled -> Color(0xFFF44336) // Red when stalled
                        updatesPerSecond >= 55f -> Color(0xFF4CAF50) // Green when good (close to 60)
                        updatesPerSecond >= 30f -> Color(0xFFFFC107) // Yellow when moderate
                        else -> Color(0xFFF44336) // Red when poor
                    }
                )

                // Tracking status indicator
                val trackingStatusText = when (cameraTrackingState) {
                    TrackingState.TRACKING -> "TRACKING"
                    TrackingState.PAUSED -> "PAUSED"
                    TrackingState.STOPPED -> "STOPPED"
                }

                Text(
                    text = trackingStatusText,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Medium,
                    color = when (cameraTrackingState) {
                        TrackingState.TRACKING -> Color(0xFF4CAF50) // Green
                        TrackingState.PAUSED -> Color(0xFFFFC107) // Yellow
                        TrackingState.STOPPED -> Color(0xFFF44336) // Red
                    }
                )

                // Stall indicator
                if (isStalled) {
                    Text(
                        text = "STALLED",
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFF44336)
                    )
                }
            }
        }
    }
}

