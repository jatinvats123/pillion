package app.pillion.order

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.util.Log
import app.pillion.BuildConfig
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.sqrt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

enum class ScanSource { Share, Gallery, Camera, TestImage, Manual }

enum class ScanFailure { Unreadable, NoOrderFound }

sealed interface ScanState {
    data object Idle : ScanState
    data object Reading : ScanState
    data class Review(val parsed: ParsedOrder, val source: ScanSource) : ScanState
    data class Failed(val reason: ScanFailure) : ScanState
}

/** An image in memory, and how far to turn it to be upright. */
class LoadedImage(val bitmap: Bitmap, val rotationDegrees: Int)

/**
 * Reads an order screen: on-device OCR (ML Kit, bundled model, works offline) → [OrderParser].
 * Process-wide, so a screenshot shared from the delivery app lands on the ride screen. The image
 * stays in memory and is dropped once read; only the parsed text moves on, to the rider's review.
 */
class OrderScanner {

    // The Devanagari recogniser also reads Latin script (its model set includes the Latin one):
    // English, Hinglish and Hindi screens in one pass.
    private val recognizer = TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null

    private val _state = MutableStateFlow<ScanState>(ScanState.Idle)
    val state: StateFlow<ScanState> = _state.asStateFlow()

    /** Reads the order in the image [load] returns. A new scan replaces one in progress. Main thread. */
    fun scan(source: ScanSource, load: suspend () -> LoadedImage) {
        job?.cancel()
        _state.value = ScanState.Reading
        job = scope.launch {
            val lines = try {
                val image = load()
                read(image)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w(TAG, "Couldn't read the image", error)
                _state.value = ScanState.Failed(ScanFailure.Unreadable)
                return@launch
            }
            val parsed = withContext(Dispatchers.Default) { OrderParser.parse(lines) }
            // Debug builds only: what OCR saw and what the parser made of it (customer data).
            if (BuildConfig.DEBUG) Log.d(TAG, "OCR ${lines.size} lines:\n${lines.joinToString("\n") { it.text }}\n→ $parsed")
            _state.value = if (parsed.isEmpty) ScanState.Failed(ScanFailure.NoOrderFound) else ScanState.Review(parsed, source)
        }
    }

    /** An empty card for the rider to type the order into. */
    fun enterManually() {
        job?.cancel()
        _state.value = ScanState.Review(OrderParser.parse(emptyList()), ScanSource.Manual)
    }

    fun dismiss() {
        job?.cancel()
        _state.value = ScanState.Idle
    }

    private suspend fun read(image: LoadedImage): List<OcrLine> {
        val text = recognizer.process(InputImage.fromBitmap(image.bitmap, image.rotationDegrees)).await()
        return text.textBlocks.flatMap { it.lines }.mapNotNull { line ->
            line.boundingBox?.let { OcrLine(line.text, it.left, it.top, it.right, it.bottom) }
        }
    }

    private companion object {
        const val TAG = "OrderScan"
    }
}

private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { continuation.resume(it) }
    addOnFailureListener { continuation.resumeWithException(it) }
    addOnCanceledListener { continuation.cancel() }
}

/** Plenty for OCR (a phone screenshot is ~2.5 MP), and bounded for photos and long screenshots. */
private const val MAX_PIXELS = 8_000_000L

/** A shared or picked image, decoded in memory (nothing is written to disk), shrunk to [MAX_PIXELS]. */
suspend fun loadOrderImage(context: Context, uri: Uri): LoadedImage = withContext(Dispatchers.IO) {
    val resolver = context.contentResolver
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        // ImageDecoder turns photos upright itself (EXIF).
        val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val pixels = info.size.width.toLong() * info.size.height
            if (pixels > MAX_PIXELS) {
                val scale = sqrt(MAX_PIXELS.toDouble() / pixels)
                decoder.setTargetSize((info.size.width * scale).toInt(), (info.size.height * scale).toInt())
            }
        }
        LoadedImage(bitmap, 0)
    } else {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outWidth.toLong() * bounds.outHeight / (sample * sample) > MAX_PIXELS) sample *= 2
        val bitmap = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: throw IOException("Unreadable image")
        val orientation = resolver.openInputStream(uri)?.use {
            ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }
        val rotation = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
        LoadedImage(bitmap, rotation)
    }
}
