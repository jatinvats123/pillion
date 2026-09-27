package app.pillion.ui

import android.media.AudioManager
import android.net.Uri
import android.widget.VideoView
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.viewinterop.AndroidView
import app.pillion.R
import kotlinx.coroutines.delay

/**
 * The 2 s intro (`res/raw/pillion_intro.mp4`) over the app on a cold launch. The app composes and
 * starts underneath at the same time, so nothing waits for it; a tap skips it. The video starts
 * and ends on black, so it sits on black and that fades out after the last frame. It never takes
 * audio focus (the rider's music keeps playing) and is silent when the phone is on silent/vibrate.
 */
@Composable
fun IntroVideo(onDone: () -> Unit) {
    var playing by remember { mutableStateOf(true) }
    var visible by remember { mutableStateOf(true) }
    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(FADE_MS),
        finishedListener = { if (!visible) onDone() },
        label = "intro",
    )
    val finish = {
        playing = false
        visible = false
    }
    // Never in the way for long, whatever the player does (a slow decoder, the app sent to the back).
    LaunchedEffect(Unit) {
        delay(MAX_MS)
        finish()
    }
    val description = stringResource(R.string.intro_description)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer { this.alpha = alpha }
            .background(Color.Black)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClickLabel = stringResource(R.string.intro_skip),
                onClick = finish,
            )
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        if (playing) {
            AndroidView(
                factory = { context ->
                    VideoView(context).apply {
                        setAudioFocusRequest(AudioManager.AUDIOFOCUS_NONE)
                        val silent = context.getSystemService(AudioManager::class.java).ringerMode != AudioManager.RINGER_MODE_NORMAL
                        setOnPreparedListener { player ->
                            if (silent) player.setVolume(0f, 0f)
                            start()
                        }
                        setOnCompletionListener { finish() }
                        setOnErrorListener { _, _, _ ->
                            finish()
                            true
                        }
                        setVideoURI(Uri.parse("android.resource://${context.packageName}/${R.raw.pillion_intro}"))
                    }
                },
                onRelease = { it.stopPlayback() },
            )
        }
    }
}

private const val FADE_MS = 250
/** The video is 2 s; allow for the player starting up. */
private const val MAX_MS = 3_500L
