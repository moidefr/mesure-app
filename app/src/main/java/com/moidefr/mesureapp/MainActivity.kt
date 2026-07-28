package com.moidefr.mesureapp

import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.PointF
import android.media.ImageReader
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.google.ar.core.Anchor
import com.google.ar.core.Config
import com.moidefr.mesureapp.ar.DebugCircle
import com.moidefr.mesureapp.ar.createAnchorFromDetectedTap
import com.moidefr.mesureapp.ar.detectDebugCandidates
import com.moidefr.mesureapp.ar.detectTap
import com.moidefr.mesureapp.ar.distanceBetween
import com.moidefr.mesureapp.ar.hitTestDistance
import com.moidefr.mesureapp.ar.rgbaImageToBitmap
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.ar.node.AnchorNode
import io.github.sceneview.material.setColor
import io.github.sceneview.math.Position
import io.github.sceneview.node.SphereNode
import io.github.sceneview.node.TextNode
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberOnGestureListener
import io.github.sceneview.rememberSurfaceMirrorer
import org.opencv.android.OpenCVLoader
import kotlin.math.roundToInt
import kotlin.math.sqrt

private const val TAG = "MesureApp"

private val TargetColor = Color(0xFF2196F3)
private val ClosestColor = Color(0xFF2ECC71)
private val FarthestColor = Color(0xFFE74C3C)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!OpenCVLoader.initLocal()) {
            Log.e(TAG, "OpenCV initialization failed")
        }
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    ArMeasureScreen()
                }
            }
        }
    }
}

/** A tapped/detected object's position, kept both in camera-image pixels (for distance math,
 * stable regardless of screen rotation) and on-screen pixels (for drawing). */
private data class MeasuredPoint2D(val imagePoint: PointF, val screenPoint: Offset)

/**
 * 2D mode (default): objects are detected and shown as flat markers drawn directly on the camera
 * feed, in screen space. No 3D tracking, no billboarded labels — this is the right tool when the
 * camera looks almost straight down at the play area, which is how pétanque/palets are actually
 * measured. A single AR hit-test at the target gives the camera-to-ground depth, combined with
 * the camera's focal length to convert pixel distances to real centimeters.
 *
 * 3D mode (optional): the original ARCore anchor + 3D marker pipeline, better suited to a camera
 * held at an angle.
 *
 * In both modes: first tap sets the target (cochonnet), every following tap adds a compared
 * object (boule / palet), colored on a green (closest to target) -> red (farthest) gradient.
 */
@Composable
private fun ArMeasureScreen() {
    val context = LocalContext.current
    val engine = rememberEngine()
    val materialLoader = rememberMaterialLoader(engine)

    var use3DMode by remember { mutableStateOf(false) }

    // 3D mode state.
    var targetAnchor by remember { mutableStateOf<Anchor?>(null) }
    val comparedAnchors = remember { mutableStateListOf<Anchor>() }
    var distancesCm by remember { mutableStateOf<List<Float>>(emptyList()) }

    // 2D mode state.
    var target2D by remember { mutableStateOf<MeasuredPoint2D?>(null) }
    val compared2D = remember { mutableStateListOf<MeasuredPoint2D>() }
    var metersPerImagePixel by remember { mutableStateOf<Float?>(null) }
    var distances2DCm by remember { mutableStateOf<List<Float>>(emptyList()) }

    // Debug: shows every Hough candidate around a tap instead of just the one picked.
    var debugMode by remember { mutableStateOf(false) }
    val debugCircles = remember { mutableStateListOf<DebugCircle>() }

    var pendingTap by remember { mutableStateOf<Offset?>(null) }
    var boxSizePx by remember { mutableStateOf(IntSize.Zero) }
    var frozenBitmap by remember { mutableStateOf<Bitmap?>(null) }
    val surfaceMirrorer = rememberSurfaceMirrorer()

    fun resetMeasurement() {
        targetAnchor = null
        comparedAnchors.clear()
        distancesCm = emptyList()
        target2D = null
        compared2D.clear()
        metersPerImagePixel = null
        distances2DCm = emptyList()
        debugCircles.clear()
    }

    fun freezeFrame() {
        val size = boxSizePx
        if (size.width <= 0 || size.height <= 0) return
        val reader = ImageReader.newInstance(size.width, size.height, PixelFormat.RGBA_8888, 2)
        reader.setOnImageAvailableListener({ r ->
            r.acquireLatestImage()?.let { image ->
                frozenBitmap = rgbaImageToBitmap(image)
                image.close()
            }
            surfaceMirrorer.stopMirroring(r.surface)
            r.close()
        }, null)
        surfaceMirrorer.startMirroring(reader.surface, width = size.width, height = size.height)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { boxSizePx = it },
    ) {
        ARSceneView(
            modifier = Modifier.fillMaxSize(),
            engine = engine,
            materialLoader = materialLoader,
            surfaceMirrorer = surfaceMirrorer,
            planeFindingMode = Config.PlaneFindingMode.HORIZONTAL,
            planeRenderer = use3DMode,
            onGestureListener = rememberOnGestureListener(
                onSingleTapConfirmed = { motionEvent, _ ->
                    pendingTap = Offset(motionEvent.x, motionEvent.y)
                },
            ),
            onSessionUpdated = { _, frame ->
                pendingTap?.let { tap ->
                    pendingTap = null
                    if (debugMode) {
                        debugCircles.clear()
                        debugCircles.addAll(detectDebugCandidates(frame, tap))
                    } else {
                        val detected = detectTap(frame, tap)
                        if (!detected.circleDetected) {
                            Toast.makeText(
                                context,
                                "Objet non détecté, point approximatif utilisé",
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                        if (use3DMode) {
                            createAnchorFromDetectedTap(frame, detected)?.let { anchor ->
                                if (targetAnchor == null) {
                                    targetAnchor = anchor
                                } else {
                                    comparedAnchors.add(anchor)
                                }
                            }
                        } else {
                            if (metersPerImagePixel == null) {
                                hitTestDistance(frame, detected)?.let { depthMeters ->
                                    val focalLengthPx = frame.camera.imageIntrinsics.focalLength
                                    val avgFocalLengthPx = (focalLengthPx[0] + focalLengthPx[1]) / 2f
                                    metersPerImagePixel = depthMeters / avgFocalLengthPx
                                }
                            }
                            val point = MeasuredPoint2D(detected.imagePoint, detected.viewPoint)
                            if (target2D == null) {
                                target2D = point
                            } else {
                                compared2D.add(point)
                            }
                        }
                    }
                }

                val target = targetAnchor
                distancesCm = if (target != null) {
                    comparedAnchors.map { distanceBetween(it, target) * 100f }
                } else {
                    emptyList()
                }

                val target2d = target2D
                val scale = metersPerImagePixel
                distances2DCm = if (target2d != null && scale != null) {
                    compared2D.map { obj ->
                        val dx = obj.imagePoint.x - target2d.imagePoint.x
                        val dy = obj.imagePoint.y - target2d.imagePoint.y
                        sqrt(dx * dx + dy * dy) * scale * 100f
                    }
                } else {
                    emptyList()
                }
            },
        ) {
            if (use3DMode) {
                targetAnchor?.let { anchor ->
                    AnchorNode(anchor = anchor) {
                        val marker = remember(materialLoader) {
                            materialLoader.createColorInstance(TargetColor)
                        }
                        SphereNode(radius = 0.03f, materialInstance = marker)
                        TextNode(
                            text = "Cible",
                            position = Position(y = 0.08f),
                            textColor = TargetColor.toArgb(),
                        )
                    }
                }
                comparedAnchors.forEachIndexed { index, anchor ->
                    key(anchor) {
                        val distanceCm = distancesCm.getOrNull(index)
                        val color = distanceCm?.let { colorForRank(it, distancesCm) } ?: ClosestColor
                        AnchorNode(anchor = anchor) {
                            val marker = remember(materialLoader) {
                                materialLoader.createColorInstance(color)
                            }
                            SideEffect { marker.setColor(color) }
                            SphereNode(radius = 0.03f, materialInstance = marker)
                            TextNode(
                                text = buildString {
                                    append("Objet ${index + 1}")
                                    distanceCm?.let { append(" — %.0f cm".format(it)) }
                                },
                                position = Position(y = 0.08f),
                                textColor = color.toArgb(),
                            )
                        }
                    }
                }
            }
        }

        frozenBitmap?.let { bitmap ->
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
            )
        }

        if (!use3DMode) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                target2D?.let { drawCircle(color = TargetColor, radius = 26f, center = it.screenPoint) }
                compared2D.forEachIndexed { index, obj ->
                    val distanceCm = distances2DCm.getOrNull(index)
                    val color = distanceCm?.let { colorForRank(it, distances2DCm) } ?: ClosestColor
                    drawCircle(color = color, radius = 26f, center = obj.screenPoint)
                }
            }
            target2D?.let { PointLabel(text = "Cible", color = TargetColor, point = it.screenPoint) }
            compared2D.forEachIndexed { index, obj ->
                val distanceCm = distances2DCm.getOrNull(index)
                val color = distanceCm?.let { colorForRank(it, distances2DCm) } ?: ClosestColor
                PointLabel(
                    text = buildString {
                        append("Objet ${index + 1}")
                        distanceCm?.let { append(" — %.0f cm".format(it)) }
                    },
                    color = color,
                    point = obj.screenPoint,
                )
            }
        }

        if (debugCircles.isNotEmpty()) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                debugCircles.forEach { circle ->
                    drawCircle(
                        color = Color.Yellow,
                        radius = circle.radius,
                        center = circle.center,
                        style = Stroke(width = 4f),
                    )
                    drawCircle(color = Color.Yellow, radius = 6f, center = circle.center)
                }
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Button(
                onClick = {
                    use3DMode = !use3DMode
                    resetMeasurement()
                },
            ) {
                Text(if (use3DMode) "Mode : 3D" else "Mode : 2D")
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = {
                        if (frozenBitmap == null) freezeFrame() else frozenBitmap = null
                    },
                ) {
                    Text(if (frozenBitmap == null) "Figer" else "Reprendre")
                }
                Button(onClick = { resetMeasurement() }) {
                    Text("Réinitialiser")
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = {
                    debugMode = !debugMode
                    debugCircles.clear()
                },
            ) {
                Text(if (debugMode) "Détecter (actif)" else "Détecter")
            }
            if (debugMode) {
                Text(
                    text = "${debugCircles.size} cercle(s) trouvé(s) — tape un point pour scanner sa zone",
                    color = Color.Yellow,
                )
            }
        }
    }
}

/** Text label anchored near a raw screen-pixel point (top-left origin, no density conversion). */
@Composable
private fun PointLabel(text: String, color: Color, point: Offset) {
    Text(
        text = text,
        color = color,
        modifier = Modifier
            .offset { IntOffset((point.x + 24f).roundToInt(), (point.y - 24f).roundToInt()) }
            .background(Color.Black.copy(alpha = 0.6f))
            .padding(horizontal = 4.dp, vertical = 2.dp),
    )
}

/** Green for the closest compared object, red for the farthest, interpolated in between. */
private fun colorForRank(distanceCm: Float, allDistancesCm: List<Float>): Color {
    if (allDistancesCm.size <= 1) return ClosestColor
    val min = allDistancesCm.min()
    val max = allDistancesCm.max()
    if (max - min < 0.0001f) return ClosestColor
    val t = ((distanceCm - min) / (max - min)).coerceIn(0f, 1f)
    return lerp(ClosestColor, FarthestColor, t)
}
