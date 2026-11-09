package com.haokai.addemo

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.haokai.addemo.config.ConfigRepository
import com.haokai.addemo.kiosk.KioskHelper
import com.haokai.addemo.media.MediaDirector
import com.haokai.addemo.session.SessionEvent
import com.haokai.addemo.session.SessionStateMachine
import com.haokai.addemo.session.SessionUiSnapshot
import com.haokai.addemo.kiosk.KioskScreen
import com.haokai.addemo.vision.VisionDebugSnapshot
import com.haokai.addemo.vision.VisionEngine
import com.haokai.addemo.voice.VoiceDebugSnapshot
import com.haokai.addemo.voice.VoiceEngine
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var configRepository: ConfigRepository
    private lateinit var mediaDirector: MediaDirector
    private lateinit var stateMachine: SessionStateMachine
    private lateinit var visionEngine: VisionEngine
    private lateinit var voiceEngine: VoiceEngine

    private var uiSnapshot by mutableStateOf(SessionUiSnapshot())
    private var visionDebug by mutableStateOf(VisionDebugSnapshot())
    private var voiceDebug by mutableStateOf(VoiceDebugSnapshot())
    private var surfacesBound = false
    private var cameraStarted = false
    private var pendingPreview: PreviewView? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        if (result[Manifest.permission.CAMERA] == true) {
            startVisionIfReady()
        }
        // Mic is started later when poster enables mic; permission just needed in advance.
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        KioskHelper.applyImmersive(this)

        configRepository = ConfigRepository(applicationContext)
        mediaDirector = MediaDirector(
            context = applicationContext,
            configRepository = configRepository,
            onMediaEnded = { stateMachine.onEvent(SessionEvent.MediaEnded) },
        )
        visionEngine = VisionEngine(
            context = applicationContext,
            configRepository = configRepository,
            onEvent = { stateMachine.onEvent(it) },
        )
        voiceEngine = VoiceEngine(
            context = applicationContext,
            configRepository = configRepository,
            media = mediaDirector,
            onAsrFinal = { text -> stateMachine.onAsrFinal(text) },
            onServerMediaPlaying = { playing -> stateMachine.setServerMediaPlaying(playing) },
        )
        stateMachine = SessionStateMachine(
            configRepository = configRepository,
            media = mediaDirector,
            audienceProvider = { visionEngine.currentAudience() },
            emotionProvider = { visionEngine.currentEmotion() },
            onSnapshot = { snap ->
                uiSnapshot = snap
                voiceEngine.setMicEnabled(snap.micEnabled)
            },
            onSessionIdle = {
                visionEngine.onSessionReturnedToIdle()
                voiceEngine.clearCaptions()
            },
        )
        lifecycleScope.launch {
            visionEngine.debug.collect { visionDebug = it }
        }
        lifecycleScope.launch {
            voiceEngine.debug.collect { voiceDebug = it }
        }

        val showDebug = configRepository.load().features.debug_overlay

        setContent {
            KioskScreen(
                snapshot = uiSnapshot,
                visionDebug = visionDebug,
                voiceDebug = voiceDebug,
                showDebug = showDebug,
                onBindSurfaces = { playerView, posterView, previewView ->
                    if (!surfacesBound) {
                        surfacesBound = true
                        mediaDirector.attach(playerView, posterView)
                        stateMachine.start()
                        pendingPreview = previewView
                        ensurePermissionsAndStart()
                    }
                },
            )
        }
    }

    private fun ensurePermissionsAndStart() {
        val need = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            need += Manifest.permission.CAMERA
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            need += Manifest.permission.RECORD_AUDIO
        }
        if (need.isEmpty()) {
            startVisionIfReady()
        } else {
            permissionLauncher.launch(need.toTypedArray())
            // If camera already granted but only mic requested, still start vision.
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED
            ) {
                startVisionIfReady()
            }
        }
    }

    private fun startVisionIfReady() {
        if (cameraStarted) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val preview = pendingPreview ?: return
        cameraStarted = true
        lifecycleScope.launch {
            visionEngine.start(this@MainActivity, preview)
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            KioskHelper.applyImmersive(this)
        }
    }

    override fun onDestroy() {
        voiceEngine.release()
        visionEngine.release()
        mediaDirector.release()
        super.onDestroy()
    }
}
