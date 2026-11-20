package cn.lemonit.paperplane.features.servers

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import cn.lemonit.paperplane.ui.components.ButtonKind
import cn.lemonit.paperplane.ui.components.ButtonSize
import cn.lemonit.paperplane.ui.components.PaperButton
import cn.lemonit.paperplane.ui.components.Toasts
import cn.lemonit.paperplane.ui.i18n.tr
import cn.lemonit.paperplane.ui.icons.PaperIcon
import cn.lemonit.paperplane.ui.theme.PaperTheme
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.ResultPoint
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.journeyapps.barcodescanner.BarcodeCallback
import com.journeyapps.barcodescanner.BarcodeResult
import com.journeyapps.barcodescanner.BarcodeView
import com.journeyapps.barcodescanner.DefaultDecoderFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 取景框四周留白（占边长的比例）：只在框内识别，和画出来的角标一致 */
private const val FRAME_MARGIN = 0.12f

/**
 * 扫二维码（连接码）：正方形取景区 + 角标，下面是手电筒、相册。
 * 没有相机权限时显示申请按钮（被永久拒绝时去系统设置）。识别到文本回调 [onScanned]（同一段文本 2 秒内不重复回调）。
 */
@Composable
fun QrScanner(onScanned: (String) -> Unit, modifier: Modifier = Modifier, paused: Boolean = false) {
    val context = LocalContext.current
    val c = PaperTheme.colors
    val s = PaperTheme.spacing
    var granted by remember { mutableStateOf(hasCamera(context)) }
    var asked by rememberSaveable { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        granted = it
        asked = true
    }
    LaunchedEffect(Unit) { if (!granted && !asked) permission.launch(Manifest.permission.CAMERA) }
    val callback by rememberUpdatedState(onScanned)
    val scope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            val text = withContext(Dispatchers.Default) { decodeImage(context, uri) }
            if (text == null) Toasts.warning(tr("server.scan.notFound")) else callback(text)
        }
    }
    var torch by remember { mutableStateOf(false) }
    val hasFlash = remember { context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH) }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(s.lg), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(PaperTheme.radius.xl))
                .background(Color(0xFF111214)),
            contentAlignment = Alignment.Center,
        ) {
            if (granted) {
                CameraPreview(torch = torch, paused = paused, onText = { callback(it) })
                FrameCorners()
            } else {
                Column(
                    Modifier.padding(s.xxl),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(s.lg),
                ) {
                    PaperIcon("scan-line", size = PaperTheme.sizes.iconEmpty, tint = Color.White.copy(alpha = 0.7f))
                    Text(tr("server.scan.noPermission"), style = PaperTheme.typography.body, color = Color.White, textAlign = TextAlign.Center)
                    val permanentlyDenied = asked && (context as? Activity)?.shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) == false
                    if (permanentlyDenied) {
                        PaperButton(tr("server.scan.openSettings"), onClick = { openAppSettings(context) }, kind = ButtonKind.Primary)
                    } else {
                        PaperButton(tr("server.scan.retry"), onClick = { permission.launch(Manifest.permission.CAMERA) }, kind = ButtonKind.Primary)
                    }
                }
            }
        }
        Text(tr("server.scan.hint"), style = PaperTheme.typography.small, color = c.textSecondary, textAlign = TextAlign.Center)
        Row(horizontalArrangement = Arrangement.spacedBy(s.md)) {
            if (granted && hasFlash) {
                PaperButton(
                    tr("server.scan.torch"),
                    onClick = { torch = !torch },
                    icon = "flashlight",
                    kind = if (torch) ButtonKind.Primary else ButtonKind.Secondary,
                    size = ButtonSize.Small,
                )
            }
            PaperButton(
                tr("server.scan.album"),
                onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                icon = "image",
                size = ButtonSize.Small,
            )
        }
    }
}

@Composable
private fun CameraPreview(torch: Boolean, paused: Boolean, onText: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val handler by rememberUpdatedState(onText)
    val view = remember {
        BarcodeView(context).apply {
            decoderFactory = DefaultDecoderFactory(listOf(BarcodeFormat.QR_CODE))
            marginFraction = FRAME_MARGIN.toDouble()
        }
    }
    var last by remember { mutableStateOf("" to 0L) }
    DisposableEffect(view) {
        view.decodeContinuous(object : BarcodeCallback {
            override fun barcodeResult(result: BarcodeResult) {
                val text = result.text ?: return
                val now = System.currentTimeMillis()
                if (text == last.first && now - last.second < 2000) return
                last = text to now
                view.post { handler(text) }
            }

            override fun possibleResultPoints(resultPoints: MutableList<ResultPoint>) {}
        })
        onDispose { view.stopDecoding() }
    }
    DisposableEffect(lifecycle, paused) {
        val observer = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_RESUME -> if (!paused) view.resume()
                Lifecycle.Event.ON_PAUSE -> view.pause()
                else -> {}
            }
        }
        lifecycle.addObserver(observer)
        if (!paused && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) view.resume()
        if (paused) view.pause()
        onDispose {
            lifecycle.removeObserver(observer)
            view.pause()
        }
    }
    LaunchedEffect(torch) { runCatching { view.setTorch(torch) } }
    AndroidView(factory = { view }, modifier = Modifier.fillMaxSize())
}

/** 取景框四角 */
@Composable
private fun FrameCorners() {
    val accent = PaperTheme.colors.accent
    Canvas(Modifier.fillMaxSize()) {
        val m = size.minDimension * FRAME_MARGIN
        val l = m
        val t = m
        val r = size.width - m
        val b = size.height - m
        val len = size.minDimension * 0.09f
        val w = 3.dp.toPx()
        val radius = 10.dp.toPx()
        fun corner(x: Float, y: Float, dx: Float, dy: Float) {
            val p = Path().apply {
                moveTo(x, y + dy * len)
                lineTo(x, y + dy * radius)
                quadraticTo(x, y, x + dx * radius, y)
                lineTo(x + dx * len, y)
            }
            drawPath(p, Color.White, style = Stroke(width = w, cap = StrokeCap.Round))
        }
        corner(l, t, 1f, 1f)
        corner(r, t, -1f, 1f)
        corner(l, b, 1f, -1f)
        corner(r, b, -1f, -1f)
        drawLine(accent.copy(alpha = 0.8f), Offset(l + len, size.height / 2), Offset(r - len, size.height / 2), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
    }
}

private fun hasCamera(context: Context) =
    ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

private fun openAppSettings(context: Context) {
    context.startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}

/** 从相册图片里识别二维码（截图、照片都行）；识别不到返回 null */
private fun decodeImage(context: Context, uri: Uri): String? {
    val bitmap = loadBitmap(context, uri, 1600) ?: return null
    val w = bitmap.width
    val h = bitmap.height
    val pixels = IntArray(w * h)
    bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
    val source = RGBLuminanceSource(w, h, pixels)
    val hints = mapOf(DecodeHintType.TRY_HARDER to true, DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE))
    for (bin in listOf(HybridBinarizer(source), GlobalHistogramBinarizer(source))) {
        try {
            return QRCodeReader().decode(BinaryBitmap(bin), hints).text
        } catch (_: Exception) {
        }
    }
    // 深色背景上的反色二维码
    return try {
        QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source.invert())), hints).text
    } catch (_: Exception) {
        null
    }
}

private fun loadBitmap(context: Context, uri: Uri, maxSide: Int): Bitmap? = try {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    var sample = 1
    while (bounds.outWidth / sample > maxSide * 2 || bounds.outHeight / sample > maxSide * 2) sample *= 2
    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
} catch (_: Exception) {
    null
}
