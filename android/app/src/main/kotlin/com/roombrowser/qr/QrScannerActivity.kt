package com.roombrowser.qr

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.roombrowser.ui.common.RoomBrowserTheme
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Built-in QR scanner (spec section 38): CameraX preview + ZXing decoding.
 * The camera runs in the DEFAULT process (never in ':browser'), and the
 * result is returned via setResult().
 */
class QrScannerActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private var reader: MultiFormatReader? = null

    /**
     * Written on the analyzer thread, read there and on the main thread —
     * @Volatile so the "already delivered" short-circuit is actually visible
     * to the next frame instead of being cached in a register.
     */
    @Volatile
    private var delivered = false

    /**
     * The analyzer runs HERE, not on the main thread.
     *
     * ZXing's decode walks the whole luminance plane: at preview resolution
     * that is single-digit milliseconds on a fast phone and far more on a
     * slow one or on the CI emulator, every frame, on the thread that also
     * has to draw. The result was a scanner whose own close button stuttered
     * while it scanned. STRATEGY_KEEP_ONLY_LATEST means a slow decode drops
     * frames rather than queueing them, so one background thread is enough.
     */
    private val analyzerExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera() else {
                Toast.makeText(this, "Camera permission is required to scan QR codes", Toast.LENGTH_LONG).show()
                setResult(RESULT_CANCELED)
                finish()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Consistent edge-to-edge: the camera preview fills the screen
        // behind the (transparent) system bars — no UI element overlaps the
        // Back / Home / Recents buttons.
        enableEdgeToEdge()
        previewView = PreviewView(this)
        // Close affordance: a white X in a translucent scrim circle at the
        // top-end corner, inset-padded below the status bar and beside any
        // display cutout (landscape). It is the ONLY clickable element in
        // this screen, so nothing ever sits inside the system bar zones;
        // the camera preview below it is purely visual. The Compose overlay
        // only consumes touches that land on the button — everything else
        // falls through to the preview.
        val root = FrameLayout(this)
        root.addView(
            previewView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        root.addView(
            ComposeView(this).apply {
                setViewCompositionStrategy(
                    ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
                )
                // Themed like the rest of the app: RoomBrowserTheme is what
                // applies isAppearanceLightStatusBars/NavigationBars, so on a
                // light-system theme the clock and nav icons stay dark and
                // visible instead of vanishing over the camera preview.
                setContent {
                    RoomBrowserTheme { QrCloseOverlay(onClose = { closeScanner() }) }
                }
            },
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        setContentView(root)
        reader = MultiFormatReader().apply {
            setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE)))
        }
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    /** Close button — cancels the scan (no result) and finishes. */
    private fun closeScanner() {
        setResult(RESULT_CANCELED)
        finish()
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(analyzerExecutor, ::analyzeFrame)
            provider.unbindAll()
            runCatching {
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            }.onFailure {
                Toast.makeText(this, "Camera unavailable", Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun analyzeFrame(image: ImageProxy) {
        if (delivered) {
            image.close()
            return
        }
        // `image.close()` in a finally: an exception escaping the decode used
        // to leak the frame's buffer back to CameraX unreleased, and with
        // KEEP_ONLY_LATEST the pipeline then starves after a few frames —
        // a scanner that silently stops scanning.
        val result = try {
            runCatching { decode(image) }.getOrNull()
        } finally {
            image.close()
        }
        if (result == null) return
        // Analyzer thread → setResult/finish are main-thread-only API.
        // `delivered` is flipped here, not in the posted block, so the
        // frames already in flight behind this one short-circuit instead of
        // each posting their own finish().
        delivered = true
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            setResult(RESULT_OK, Intent().apply { putExtra(EXTRA_QR_TEXT, result) })
            finish()
        }
    }

    private fun decode(image: ImageProxy): String? {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        val source = PlanarYUVLuminanceSource(
            bytes,
            plane.rowStride,
            image.height,
            0,
            0,
            image.width.coerceAtMost(plane.rowStride),
            image.height,
            false
        )
        val bitmap = BinaryBitmap(HybridBinarizer(source))
        return reader?.decodeWithState(bitmap)?.let { qr ->
            reader?.reset()
            qr.text
        }
    }

    override fun onDestroy() {
        // Order matters. `delivered` first so a frame already running bails
        // out of the decode, then the executor is shut down (no new frame can
        // start), and only then is the reader released — MultiFormatReader is
        // not thread-safe, so resetting it while the analyzer thread was
        // inside decodeWithState was a real race.
        delivered = true
        analyzerExecutor.shutdown()
        reader?.reset()
        reader = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_QR_TEXT = "com.roombrowser.extra.QR_TEXT"
    }
}

/**
 * Fullscreen camera overlay holding only the close affordance. The button is
 * padded by the REAL system insets (status bar + display cutout, horizontal
 * sides) — never by guessed dp values — so it can never collide with the
 * status bar, a cutout, or the system Back / Home / Recents zone (it lives at
 * the top of the screen, far from the navigation bar).
 */
@Composable
private fun QrCloseOverlay(onClose: () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(
                WindowInsets.systemBars
                    .union(WindowInsets.displayCutout)
                    .only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
            )
            .padding(top = 8.dp, end = 8.dp)
    ) {
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .size(48.dp)
                .clip(CircleShape)
                .background(Color(0x66000000))
                .clickable(onClick = onClose)
                .semantics { contentDescription = "Close QR scanner" },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Filled.Close,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}
