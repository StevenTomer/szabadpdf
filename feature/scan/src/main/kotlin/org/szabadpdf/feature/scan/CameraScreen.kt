package org.szabadpdf.feature.scan

import org.szabadpdf.core.ui.Tip
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.launch
import java.util.concurrent.Executors
import kotlin.math.max

private val OutlineColor = Color(0xFF4CAF50)

@Composable
internal fun CameraScreen(
    viewModel: ScanViewModel,
    onReview: () -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val state by viewModel.state.collectAsState()
    val live by viewModel.live.collectAsState()

    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            // A TextureView: drawn in the normal view hierarchy, so overlays and screenshots behave.
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var torch by remember { mutableStateOf(false) }
    val imageCapture = remember {
        ImageCapture.Builder()
            .setResolutionSelector(ratio4by3())
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()
    }
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    val flash = remember { Animatable(0f) }

    LaunchedEffect(lifecycleOwner) {
        val provider = ProcessCameraProvider.awaitInstance(context)
        val preview = Preview.Builder().setResolutionSelector(ratio4by3()).build()
            .also { it.surfaceProvider = previewView.surfaceProvider }
        val analysis = ImageAnalysis.Builder()
            .setResolutionSelector(ratio4by3())
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()
            .also { it.setAnalyzer(analysisExecutor, viewModel::liveFrame) }
        provider.unbindAll()
        camera = runCatching {
            provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis, imageCapture)
        }.getOrNull()
    }
    DisposableEffect(Unit) {
        onDispose {
            runCatching { ProcessCameraProvider.getInstance(context).get().unbindAll() }
            analysisExecutor.shutdown()
            viewModel.resetLive()
        }
    }

    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
        if (uris.isNotEmpty()) viewModel.import(uris)
    }

    fun shoot() {
        scope.launch {
            flash.snapTo(0.7f)
            flash.animateTo(0f, tween(250))
        }
        imageCapture.takePicture(
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    val rotation = image.imageInfo.rotationDegrees
                    val bitmap: Bitmap? = runCatching { image.toBitmap() }.getOrNull()
                    image.close()
                    if (bitmap != null) viewModel.capture(bitmap, rotation)
                }

                override fun onError(exception: ImageCaptureException) = Unit
            },
        )
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView({ previewView }, Modifier.fillMaxSize())

        // The detected page, drawn over the preview. The analysis frames have the preview's
        // proportions (both 4:3), and the preview fills the view, cropping the overflow.
        Canvas(Modifier.fillMaxSize()) {
            val outline = live ?: return@Canvas
            val corners = outline.corners ?: return@Canvas
            val scale = max(size.width / outline.frameWidth, size.height / outline.frameHeight)
            val dx = (size.width - outline.frameWidth * scale) / 2
            val dy = (size.height - outline.frameHeight * scale) / 2
            val points = corners.points.map { Offset(dx + it.x * outline.frameWidth * scale, dy + it.y * outline.frameHeight * scale) }
            val path = Path().apply {
                moveTo(points[0].x, points[0].y)
                points.drop(1).forEach { lineTo(it.x, it.y) }
                close()
            }
            drawPath(path, OutlineColor.copy(alpha = 0.25f))
            drawPath(path, OutlineColor, style = Stroke(3.dp.toPx()))
        }

        Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = flash.value)))

        Row(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Tip("Close scanner") {
                IconButton(onClick = onClose, colors = overlayButton()) { Icon(Icons.Filled.Close, "Close scanner") }
            }
            if (camera?.cameraInfo?.hasFlashUnit() == true) {
                IconButton(
                    onClick = {
                        torch = !torch
                        camera?.cameraControl?.enableTorch(torch)
                    },
                    colors = overlayButton(),
                ) { Icon(if (torch) Icons.Filled.FlashOn else Icons.Filled.FlashOff, if (torch) "Light off" else "Light on") }
            }
        }

        Row(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.45f))
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 20.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Tip("Import photos") {
                IconButton(
                    onClick = { gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    colors = overlayButton(),
                    modifier = Modifier.size(56.dp),
                ) { Icon(Icons.Filled.PhotoLibrary, "Import photos") }
            }

            Box(
                Modifier
                    .size(76.dp)
                    .border(4.dp, Color.White, CircleShape)
                    .padding(8.dp)
                    .clip(CircleShape)
                    .background(Color.White)
                    .clickable(enabled = camera != null, onClickLabel = "Take picture", onClick = ::shoot),
            )

            PagesButton(state, onReview)
        }
    }
}

/** The latest page's thumbnail with a page count; opens the review. */
@Composable
private fun PagesButton(state: ScanState, onReview: () -> Unit) {
    val last = state.pages.lastOrNull()
    Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) {
        if (last == null && state.processing == 0) return@Box
        BadgedBox(badge = { if (state.pages.isNotEmpty()) Badge { Text("${state.pages.size}") } }) {
            Box(
                Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.DarkGray)
                    .border(2.dp, Color.White, RoundedCornerShape(8.dp))
                    .clickable(enabled = state.pages.isNotEmpty(), onClickLabel = "Review pages", onClick = onReview),
                contentAlignment = Alignment.Center,
            ) {
                if (last != null) {
                    val thumb = rememberPageBitmap(last.result, last.version, 160)
                    thumb?.let { Image(it.asImageBitmap(), "Scanned pages", Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                }
                if (state.processing > 0) CircularProgressIndicator(Modifier.size(28.dp), color = Color.White, strokeWidth = 3.dp)
            }
        }
    }
}

@Composable
private fun overlayButton() = IconButtonDefaults.iconButtonColors(
    containerColor = Color.Black.copy(alpha = 0.35f),
    contentColor = Color.White,
)

private fun ratio4by3() = ResolutionSelector.Builder()
    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
    .build()
