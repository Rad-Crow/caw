package com.radcrow.caw.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.MediaActionSound
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Size
import android.view.HapticFeedbackConstants
import android.view.WindowManager
import android.widget.Toast
import android.widget.VideoView
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.TorchState
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recording
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.camera.view.video.AudioConfig
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.FlashAuto
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.radcrow.caw.Code
import com.radcrow.caw.app
import com.radcrow.caw.media.fmtDur
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors

// captures stay in the app's private cache and never reach the gallery
class CameraActivity : ComponentActivity() {
    private val video by lazy { intent.getBooleanExtra(EXTRA_VIDEO, false) }
    private val scan by lazy { intent.getBooleanExtra(EXTRA_SCAN, false) }
    private val limit by lazy { maxSeconds(app.repo.config.value.maxUploadMb) }
    private val cam by lazy { LifecycleCameraController(this) }
    private val reader = Executors.newSingleThreadExecutor()
    private var sound: MediaActionSound? = null
    private val made = mutableListOf<File>()
    private var kept: File? = null
    private var noticeJob: Job? = null

    private var started by mutableStateOf(false)
    private var review by mutableStateOf<File?>(null)
    private var recording by mutableStateOf<Recording?>(null)
    private var elapsed by mutableLongStateOf(0L)
    private var busy by mutableStateOf(false)
    private var front by mutableStateOf(false)
    private var canFlip by mutableStateOf(false)
    private var hasFlash by mutableStateOf(false)
    private var flash by mutableIntStateOf(ImageCapture.FLASH_MODE_OFF)
    private var torch by mutableStateOf(false)
    private var notice by mutableStateOf<String?>(null)
    private var problem by mutableStateOf<String?>(null)

    private val ask = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        val denied = res.filterValues { !it }.keys
        when {
            Manifest.permission.CAMERA in denied -> refuse("Camera permission required.")
            Manifest.permission.RECORD_AUDIO in denied -> refuse("Microphone permission required.")
            else -> start()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(SystemBarStyle.dark(android.graphics.Color.TRANSPARENT), SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        lifecycleScope.launch { app.locked.collect { if (it) finish() } }
        val needed = listOfNotNull(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO.takeIf { video })
        val missing = needed.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        when {
            missing.isEmpty() -> start()
            savedInstanceState == null -> ask.launch(missing.toTypedArray())
        }
        setContent {
            CawTheme(alwaysDark = true) {
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    val r = review
                    if (r != null) Review(r) else if (started || scan) Viewfinder()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (Build.VERSION.SDK_INT >= 33) setRecentsScreenshotEnabled(!app.prefs.appLock)
    }

    override fun onDestroy() {
        super.onDestroy()
        recording?.close()
        sound?.release()
        reader.shutdown()
        made.filter { it != kept }.forEach { it.delete() }
    }

    private fun start() {
        cam.setEnabledUseCases(
            when {
                scan -> CameraController.IMAGE_ANALYSIS
                video -> CameraController.VIDEO_CAPTURE
                else -> CameraController.IMAGE_CAPTURE
            },
        )
        if (video) cam.videoCaptureQualitySelector = QualitySelector.from(Quality.HD, FallbackStrategy.lowerQualityOrHigherThan(Quality.HD))
        if (scan) {
            cam.imageAnalysisResolutionSelector = ResolutionSelector.Builder()
                .setResolutionStrategy(ResolutionStrategy(Size(1280, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                .build()
            cam.setImageAnalysisAnalyzer(reader) { img ->
                val text = img.use(::readFrame)
                if (text != null) runOnUiThread { found(text) }
            }
        }
        cam.torchState.observe(this) {
            torch = it == TorchState.ON
            hasFlash = cam.cameraInfo?.hasFlashUnit() == true
        }
        cam.initializationFuture.addListener({
            if (isDestroyed) return@addListener
            val hasBack = runCatching { cam.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) }.getOrDefault(false)
            val hasFront = runCatching { cam.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) }.getOrDefault(false)
            if (!hasBack && !hasFront) {
                refuse("Could not open the camera.")
                return@addListener
            }
            canFlip = hasBack && hasFront
            if (!hasBack) {
                front = true
                cam.cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA
            }
            cam.bindToLifecycle(this)
            started = true
        }, ContextCompat.getMainExecutor(this))
    }

    private fun flip() {
        runCatching {
            cam.cameraSelector = if (front) CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA
            front = !front
        }.onFailure { toast("Could not switch the camera.") }
    }

    private fun toggleFlash() {
        if (video || scan) {
            cam.enableTorch(!torch)
            return
        }
        flash = when (flash) {
            ImageCapture.FLASH_MODE_OFF -> ImageCapture.FLASH_MODE_AUTO
            ImageCapture.FLASH_MODE_AUTO -> ImageCapture.FLASH_MODE_ON
            else -> ImageCapture.FLASH_MODE_OFF
        }
        cam.imageCaptureFlashMode = flash
    }

    private fun shutter(kind: Int) {
        if (Build.VERSION.SDK_INT < 33 || !MediaActionSound.mustPlayShutterSound()) return
        (sound ?: MediaActionSound().also { sound = it }).play(kind)
    }

    private fun shoot() {
        val file = newFile(".jpg")
        // the controller mirrors front camera photos unless this is set explicitly
        val meta = ImageCapture.Metadata().apply { isReversedHorizontal = false }
        busy = true
        hold(true)
        runCatching {
            cam.takePicture(
                ImageCapture.OutputFileOptions.Builder(file).setMetadata(meta).build(),
                ContextCompat.getMainExecutor(this),
                object : ImageCapture.OnImageSavedCallback {
                    override fun onCaptureStarted() = shutter(MediaActionSound.SHUTTER_CLICK)

                    override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                        done()
                        review = file
                    }

                    override fun onError(e: ImageCaptureException) = failed(file, "Could not take the photo.")
                },
            )
        }.onFailure { failed(file, "Could not take the photo.") }
    }

    @SuppressLint("MissingPermission")
    private fun record() {
        val file = newFile(".mp4")
        val audio = app.calls.active.value == null &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        elapsed = 0
        hold(true)
        runCatching {
            recording = cam.startRecording(
                FileOutputOptions.Builder(file).setDurationLimitMillis(limit * 1000).build(),
                AudioConfig.create(audio),
                ContextCompat.getMainExecutor(this),
            ) { e ->
                when (e) {
                    is VideoRecordEvent.Start -> shutter(MediaActionSound.START_VIDEO_RECORDING)
                    is VideoRecordEvent.Status -> elapsed = e.recordingStats.recordedDurationNanos / 1_000_000_000
                    is VideoRecordEvent.Finalize -> {
                        recording = null
                        shutter(MediaActionSound.STOP_VIDEO_RECORDING)
                        if (e.error in KEEP && file.length() > 0) {
                            done()
                            review = file
                        } else {
                            failed(file, "Could not record the video.")
                        }
                    }
                }
            }
        }.onFailure { failed(file, "Could not record the video.") }
    }

    private fun stop() {
        busy = true
        recording?.stop()
    }

    private fun failed(file: File, text: String) {
        done()
        file.delete()
        if (!isFinishing) toast(text)
    }

    // rotating the screen rebinds the camera, which ends a capture in progress
    private fun hold(on: Boolean) {
        requestedOrientation = if (on) ActivityInfo.SCREEN_ORIENTATION_LOCKED else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }

    private fun done() {
        busy = false
        hold(false)
    }

    private fun retake() {
        review?.delete()
        review = null
    }

    private fun use() {
        val f = review ?: return
        kept = f
        setResult(RESULT_OK, Intent().setData(FileProvider.getUriForFile(this, "$packageName.share", f)))
        finish()
    }

    private fun found(text: String) {
        if (isFinishing || isDestroyed) return
        val code = Code.find(text) ?: return say("That isn't a Caw invite.")
        window.decorView.performHapticFeedback(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY)
        setResult(RESULT_OK, Intent().putExtra(EXTRA_TEXT, code))
        finish()
    }

    private fun paste() {
        val clip = getSystemService(ClipboardManager::class.java)?.primaryClip
        val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
        if (text.isNullOrBlank()) say("Nothing to paste.") else found(text)
    }

    private fun say(text: String) {
        notice = text
        noticeJob?.cancel()
        noticeJob = lifecycleScope.launch {
            delay(3000)
            notice = null
        }
    }

    private fun newFile(ext: String) = File(app.media.captures.apply { mkdirs() }, "${UUID.randomUUID()}$ext").also { made += it }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()

    // the scanner stays open without a camera so an invite can still be pasted
    private fun refuse(text: String) {
        if (scan) {
            problem = text
            return
        }
        toast(text)
        finish()
    }

    // removing the preview unbinds the camera, so it is off during review
    @Composable
    private fun Viewfinder() {
        val call by app.calls.active.collectAsStateWithLifecycle()
        Box(Modifier.fillMaxSize()) {
            if (started) {
                AndroidView(
                    factory = { ctx ->
                        PreviewView(ctx).apply {
                            scaleType = if (scan) PreviewView.ScaleType.FILL_CENTER else PreviewView.ScaleType.FIT_CENTER
                            controller = cam
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                    onRelease = { it.controller = null },
                )
            }
            Box(Modifier.fillMaxSize().safeDrawingPadding().padding(8.dp)) {
                IconButton(onClick = ::finish, Modifier.align(Alignment.TopStart)) { Icon(Icons.Default.Close, "Close", tint = Color.White) }
                if (recording != null) {
                    Text(
                        "${fmtDur(elapsed)} / ${fmtDur(limit)}",
                        Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 12.dp)
                            .background(Color.Red, RoundedCornerShape(6.dp))
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                        color = Color.White,
                    )
                }
                if (hasFlash) {
                    IconButton(onClick = ::toggleFlash, Modifier.align(Alignment.TopEnd)) {
                        val light = video || scan
                        val on = if (light) torch else flash == ImageCapture.FLASH_MODE_ON
                        when {
                            on -> Icon(Icons.Default.FlashOn, "Flash on", tint = Color.White)
                            !light && flash == ImageCapture.FLASH_MODE_AUTO -> Icon(Icons.Default.FlashAuto, "Flash auto", tint = Color.White)
                            else -> Icon(Icons.Default.FlashOff, "Flash off", tint = Color.White)
                        }
                    }
                }
                if (video && call != null) {
                    Text(
                        "No sound during a call",
                        Modifier.align(Alignment.BottomCenter).padding(bottom = 112.dp),
                        color = Color.White,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
                if (scan) {
                    Guide(Modifier.align(Alignment.Center))
                    Button(
                        onClick = ::paste,
                        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color.Black.copy(alpha = 0.5f), contentColor = Color.White),
                    ) {
                        Icon(Icons.Default.ContentPaste, null, Modifier.size(18.dp))
                        Text("  Paste invite")
                    }
                } else {
                    Shutter(Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp))
                }
                if (canFlip && recording == null) {
                    IconButton(onClick = ::flip, Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 28.dp)) {
                        Icon(Icons.Default.Cameraswitch, "Switch camera", tint = Color.White)
                    }
                }
            }
        }
    }

    @Composable
    private fun Guide(modifier: Modifier) {
        Column(
            modifier.padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (problem == null) Box(Modifier.size(240.dp).border(3.dp, Color.White, RoundedCornerShape(16.dp)))
            Text(
                problem ?: notice ?: "Scan a Caw invite",
                Modifier.background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 4.dp),
                color = Color.White,
                textAlign = TextAlign.Center,
            )
        }
    }

    @Composable
    private fun Shutter(modifier: Modifier) {
        val rec = recording != null
        val label = when {
            !video -> "Take photo"
            rec -> "Stop recording"
            else -> "Start recording"
        }
        Box(
            modifier
                .size(72.dp)
                .clip(CircleShape)
                .border(4.dp, Color.White, CircleShape)
                .clickable(enabled = !busy, role = Role.Button) {
                    when {
                        !video -> shoot()
                        recording != null -> stop()
                        else -> record()
                    }
                }
                .semantics { contentDescription = label },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .size(if (rec) 26.dp else 56.dp)
                    .background(if (video) Color.Red else Color.White, if (rec) RoundedCornerShape(4.dp) else CircleShape),
            )
        }
    }

    @Composable
    private fun Review(file: File) {
        BackHandler { retake() }
        Box(Modifier.fillMaxSize()) {
            if (video) {
                var playing by remember(file) { mutableStateOf(true) }
                var ratio by remember(file) { mutableFloatStateOf(0f) }
                AndroidView(
                    factory = { ctx ->
                        VideoView(ctx).apply {
                            setOnPreparedListener {
                                it.isLooping = true
                                if (it.videoHeight > 0) ratio = it.videoWidth.toFloat() / it.videoHeight
                            }
                            setVideoPath(file.path)
                        }
                    },
                    modifier = Modifier.align(Alignment.Center).then(if (ratio > 0) Modifier.aspectRatio(ratio) else Modifier),
                    update = { if (playing) it.start() else it.pause() },
                    onRelease = { it.stopPlayback() },
                )
                Box(
                    Modifier
                        .fillMaxSize()
                        .clickable { playing = !playing }
                        .semantics { contentDescription = if (playing) "Pause video" else "Play video" },
                )
                if (!playing) {
                    Icon(
                        Icons.Default.PlayArrow,
                        null,
                        Modifier.align(Alignment.Center).size(64.dp).background(Color.Black.copy(alpha = 0.5f), CircleShape).padding(12.dp),
                        tint = Color.White,
                    )
                }
            } else {
                val photo by produceState<Bitmap?>(null, file) {
                    value = withContext(Dispatchers.IO) { runCatching { app.media.upright(Uri.fromFile(file), 2048) }.getOrNull() }
                }
                photo?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
            }
            Row(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().safeDrawingPadding().padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = ::retake, colors = ButtonDefaults.textButtonColors(contentColor = Color.White)) { Text("Retake") }
                Button(onClick = ::use) { Text(if (video) "Use video" else "Use photo") }
            }
        }
    }

    class Capture : ActivityResultContract<Boolean, Uri?>() {
        override fun createIntent(context: Context, input: Boolean) = Intent(context, CameraActivity::class.java).putExtra(EXTRA_VIDEO, input)

        override fun parseResult(resultCode: Int, intent: Intent?) = intent?.data?.takeIf { resultCode == Activity.RESULT_OK }
    }

    class Scan : ActivityResultContract<Unit, String?>() {
        override fun createIntent(context: Context, input: Unit) = Intent(context, CameraActivity::class.java).putExtra(EXTRA_SCAN, true)

        override fun parseResult(resultCode: Int, intent: Intent?) = intent?.getStringExtra(EXTRA_TEXT)?.takeIf { resultCode == Activity.RESULT_OK }
    }

    private companion object {
        const val EXTRA_VIDEO = "video"
        const val EXTRA_SCAN = "scan"
        const val EXTRA_TEXT = "text"
        val KEEP = setOf(
            VideoRecordEvent.Finalize.ERROR_NONE,
            VideoRecordEvent.Finalize.ERROR_DURATION_LIMIT_REACHED,
            VideoRecordEvent.Finalize.ERROR_SOURCE_INACTIVE,
        )
    }
}

// long enough to reach the attachment limit at the bitrate Media.prepare transcodes to, at most 10 minutes
private fun maxSeconds(mb: Long) = (mb * 1024 * 1024 * 8 / 1_700_000 / 15 * 15).coerceIn(15, 600)

private val QR_HINTS = mapOf(DecodeHintType.TRY_HARDER to true)

private fun readFrame(img: ImageProxy): String? {
    val y = img.planes[0]
    val data = ByteArray(y.buffer.remaining()).also { y.buffer.get(it) }
    return readQr(data, y.rowStride, img.width, img.height)
}

// QR codes decode at any rotation, so frames are used as the sensor delivers them
internal fun readQr(y: ByteArray, rowStride: Int, width: Int, height: Int): String? {
    val side = minOf(width, height)
    val src = PlanarYUVLuminanceSource(y, rowStride, height, (width - side) / 2, (height - side) / 2, side, side, false)
    return try {
        QRCodeReader().decode(BinaryBitmap(HybridBinarizer(src)), QR_HINTS).text
    } catch (e: ReaderException) {
        null
    }
}
