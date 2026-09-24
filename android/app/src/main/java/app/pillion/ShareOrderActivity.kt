package app.pillion

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import app.pillion.order.ScanSource
import app.pillion.order.loadOrderImage
import kotlinx.coroutines.launch

/**
 * "Share → Pillion: scan order" from the delivery app. Reads the screenshot at once (the read
 * permission a share grants belongs to this activity), hands it to the order scanner and brings
 * the ride screen forward, where the rider checks the order. No window of its own.
 *
 * Not MainActivity itself: it would start a second copy inside the delivery app's task, with its
 * own ride ViewModel and voice session, in the middle of a ride.
 */
class ShareOrderActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = sharedImage(intent)
        if (savedInstanceState != null || uri == null) {
            finish()
            return
        }
        lifecycleScope.launch {
            val image = runCatching { loadOrderImage(this@ShareOrderActivity, uri) }
            pillion.scanner.scan(ScanSource.Share) { image.getOrThrow() }
            startActivity(
                Intent(this@ShareOrderActivity, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra(MainActivity.EXTRA_SHOW_ORDER, true)
            )
            finish()
        }
    }

    private fun sharedImage(intent: Intent): Uri? {
        if (intent.action != Intent.ACTION_SEND || intent.type?.startsWith("image/") != true) return null
        return IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            ?: intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
    }
}
