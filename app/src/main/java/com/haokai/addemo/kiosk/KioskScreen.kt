package com.haokai.addemo.kiosk

import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.haokai.addemo.session.SessionUiSnapshot
import com.haokai.addemo.vision.VisionDebugSnapshot
import com.haokai.addemo.voice.VoiceDebugSnapshot

@Composable
fun KioskScreen(
    snapshot: SessionUiSnapshot,
    visionDebug: VisionDebugSnapshot,
    voiceDebug: VoiceDebugSnapshot,
    showDebug: Boolean,
    onBindSurfaces: (
        androidx.media3.ui.PlayerView,
        ImageView,
        PreviewView,
    ) -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                val root = FrameLayout(context).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                }
                // Hidden camera preview required by CameraX bind; sits under video/poster.
                val previewView = PreviewView(context).apply {
                    layoutParams = FrameLayout.LayoutParams(1, 1)
                    implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                    alpha = 0f
                }
                // 全限定 media3.ui，避免与 compose.ui 包名冲突导致 Modifier 无法解析
                val playerView = androidx.media3.ui.PlayerView(context).apply {
                    layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT,
                    )
                    useController = false
                    // Fit：等比完整展示，不裁切；比例不一致时居中留黑边
                    resizeMode =
                        androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
                }
                val posterView = ImageView(context).apply {
                    layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT,
                    )
                    // Fit：套餐/菜品海报完整可见，避免 CENTER_CROP 切掉边角信息
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    visibility = android.view.View.GONE
                }
                root.addView(previewView)
                root.addView(playerView)
                root.addView(posterView)
                onBindSurfaces(playerView, posterView, previewView)
                root
            },
        )

        AsrCaptionOverlay(
            voiceDebug = voiceDebug,
            micEnabled = snapshot.micEnabled,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 28.dp, vertical = 36.dp),
        )

        if (showDebug) {
            DebugPanel(
                snapshot = snapshot,
                visionDebug = visionDebug,
                voiceDebug = voiceDebug,
                panelModifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(12.dp),
            )
        }
    }
}

@Composable
private fun AsrCaptionOverlay(
    voiceDebug: VoiceDebugSnapshot,
    micEnabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val caption = voiceDebug.captionText
        .ifBlank { voiceDebug.partialText }
        .ifBlank { voiceDebug.lastFinal }
        .trim()
    // Idle / 关麦后不保留旧字幕
    val active = micEnabled || voiceDebug.listening
    if (!active) return

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = if (voiceDebug.listening) "正在聆听…" else "麦克风已开",
            color = Color(0xFFB8E986),
            fontSize = 14.sp,
        )
        if (caption.isNotEmpty()) {
            Text(
                text = caption,
                color = Color.White,
                fontSize = 28.sp,
                modifier = Modifier
                    .padding(top = 8.dp)
                    .fillMaxWidth()
                    .background(Color(0x99000000), RoundedCornerShape(12.dp))
                    .padding(horizontal = 20.dp, vertical = 14.dp),
            )
        }
    }
}

@Composable
private fun DebugPanel(
    snapshot: SessionUiSnapshot,
    visionDebug: VisionDebugSnapshot,
    voiceDebug: VoiceDebugSnapshot,
    panelModifier: Modifier = Modifier,
) {
    Column(
        modifier = panelModifier
            .fillMaxWidth()
            .background(Color(0xCC111111), RoundedCornerShape(12.dp))
            .padding(12.dp),
    ) {
        Text(
            text = "state=${snapshot.state}  mic=${snapshot.micEnabled} " +
                "block=${snapshot.blockingPlayback} leaveQ=${snapshot.pendingLeave}",
            color = Color.White,
            fontSize = 13.sp,
        )
        Text(
            text = "event=${snapshot.lastEvent}  hit=${snapshot.lastHit}  sid=${snapshot.sessionId}",
            color = Color(0xFFB0B0B0),
            fontSize = 12.sp,
        )
        Text(
            text = "vision: faces=${visionDebug.faceCount} primary=${visionDebug.hasPrimary} " +
                "aud=${visionDebug.audience} emo=${visionDebug.emotion} " +
                "dwell=${visionDebug.dwellMs}ms /5s=${visionDebug.enterMs} " +
                "eng=${visionDebug.engaged} cam=${visionDebug.cameraLabel}",
            color = Color(0xFF9AD0FF),
            fontSize = 11.sp,
        )
        Text(
            text = visionDebug.status,
            color = Color(0xFF888888),
            fontSize = 11.sp,
        )
        Text(
            text = "voice: ${voiceDebug.status} listen=${voiceDebug.listening} " +
                "cred=${voiceDebug.credentialsOk} aec=${voiceDebug.aecEnabled} " +
                "srv=${voiceDebug.serverIntent}",
            color = Color(0xFFB8E986),
            fontSize = 11.sp,
        )
        if (voiceDebug.lastIntent.isNotBlank()) {
            Text(
                text = "intent=${voiceDebug.lastIntent}",
                color = Color(0xFFB8E986),
                fontSize = 11.sp,
            )
        }
        if (voiceDebug.partialText.isNotBlank() || voiceDebug.lastFinal.isNotBlank() ||
            voiceDebug.captionText.isNotBlank()
        ) {
            Text(
                text = "asr=${voiceDebug.captionText.ifBlank {
                    "≈${voiceDebug.partialText.ifBlank { "-" }} | final=${voiceDebug.lastFinal.ifBlank { "-" }}"
                }}",
                color = Color(0xFFB8E986),
                fontSize = 11.sp,
            )
        }
        voiceDebug.error?.let {
            Text(text = it, color = Color(0xFFFF8A80), fontSize = 10.sp)
        }
    }
}
