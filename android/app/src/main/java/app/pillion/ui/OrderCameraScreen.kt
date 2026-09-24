package app.pillion.ui

import android.util.Size
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.pillion.R
import app.pillion.order.LoadedImage
import app.pillion.order.ScanSource
import app.pillion.ui.components.RoundIconButton
import app.pillion.ui.theme.Space
import app.pillion.ui.theme.Targets
import java.util.concurrent.Executors

/**
 * Camera scan (CameraX), for a printed bill or another phone's screen: the rider's own delivery
 * app is on this phone, so for it they share a screenshot instead. The photo is taken into memory,
 * never saved; [onCaptured] hands it to the order scanner.
 */
@Composable
fun OrderCameraRoute(onCaptured: (ScanSource, suspend () -> LoadedImage) -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val failed = stringResource(R.string.camera_failed)
    val controller = remember {
        LifecycleCameraController(context).apply {
            setEnabledUseCases(CameraController.IMAGE_CAPTURE)
            imageCaptureMode = ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY
            // ~5 MP: sharp enough for small print, bounded in memory whatever the sensor.
            imageCaptureResolutionSelector = ResolutionSelector.Builder()
                .setResolutionStrategy(ResolutionStrategy(Size(2560, 1920), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
                .build()
        }
    }
    val executor = remember { Executors.newSingleThreadExecutor() }
    DisposableEffect(lifecycleOwner) {
        controller.bindToLifecycle(lifecycleOwner)
        onDispose {
            controller.unbind()
            executor.shutdown()
        }
    }
    var capturing by remember { mutableStateOf(false) }
    BackHandler(onBack = onClose)

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { PreviewView(it).apply { this.controller = controller } },
            modifier = Modifier.fillMaxSize(),
        )
        Column(
            modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(20.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                stringResource(R.string.camera_hint),
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                modifier = Modifier
                    .background(Color.Black.copy(alpha = 0.6f), CircleShape)
                    .padding(horizontal = 18.dp, vertical = 10.dp)
                    .align(Alignment.CenterHorizontally),
            )
            // A camera's own layout: close on the left, one big round shutter in the middle.
            Box(Modifier.fillMaxWidth().padding(bottom = Space.m), contentAlignment = Alignment.Center) {
                RoundIconButton(
                    R.drawable.ic_close,
                    stringResource(R.string.cancel),
                    onClick = onClose,
                    size = Targets.rideSmall,
                    container = Color.Black.copy(alpha = 0.6f),
                    content = Color.White,
                    modifier = Modifier.align(Alignment.CenterStart),
                )
                val scanLabel = stringResource(R.string.camera_capture)
                Surface(
                    onClick = {
                        capturing = true
                        controller.takePicture(executor, object : ImageCapture.OnImageCapturedCallback() {
                            override fun onCaptureSuccess(image: ImageProxy) {
                                // Off the main thread: decode, then let go of the camera frame at once.
                                val loaded = image.use { LoadedImage(it.toBitmap(), it.imageInfo.rotationDegrees) }
                                ContextCompat.getMainExecutor(context).execute { onCaptured(ScanSource.Camera) { loaded } }
                            }

                            override fun onError(exception: ImageCaptureException) {
                                ContextCompat.getMainExecutor(context).execute {
                                    capturing = false
                                    Toast.makeText(context, failed, Toast.LENGTH_LONG).show()
                                }
                            }
                        })
                    },
                    enabled = !capturing,
                    shape = CircleShape,
                    color = Color.White.copy(alpha = if (capturing) 0.5f else 1f),
                    border = BorderStroke(4.dp, Color.Black.copy(alpha = 0.35f)),
                    modifier = Modifier
                        .size(Targets.ride)
                        .semantics { contentDescription = scanLabel },
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(painterResource(R.drawable.ic_document_scanner), contentDescription = null, tint = Color.Black, modifier = Modifier.size(34.dp))
                    }
                }
            }
        }
    }
}
