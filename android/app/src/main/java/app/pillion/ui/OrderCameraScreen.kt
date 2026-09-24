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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.pillion.R
import app.pillion.order.LoadedImage
import app.pillion.order.ScanSource
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
                    .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 14.dp, vertical = 10.dp)
                    .align(Alignment.CenterHorizontally),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onClose, modifier = Modifier.weight(1f).height(72.dp)) {
                    Text(stringResource(R.string.cancel), color = Color.White, style = MaterialTheme.typography.titleMedium)
                }
                Button(
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
                    modifier = Modifier.weight(2f).height(72.dp),
                ) {
                    Text(stringResource(R.string.camera_capture), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
