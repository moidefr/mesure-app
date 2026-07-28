package com.moidefr.mesureapp

import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.media.ImageReader
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.google.ar.core.Anchor
import com.google.ar.core.Config
import com.moidefr.mesureapp.ar.createAnchorFromTap
import com.moidefr.mesureapp.ar.distanceBetween
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

/**
 * First tap sets the target (cochonnet); every following tap adds a compared object (boule /
 * palet). Each compared object is colored on a green (closest to target) -> red (farthest)
 * gradient and labeled with its distance to the target, in cm.
 */
@Composable
private fun ArMeasureScreen() {
    val context = LocalContext.current
    val engine = rememberEngine()
    val materialLoader = rememberMaterialLoader(engine)

    var targetAnchor by remember { mutableStateOf<Anchor?>(null) }
    val comparedAnchors = remember { mutableStateListOf<Anchor>() }
    var pendingTap by remember { mutableStateOf<Offset?>(null) }
    var distancesCm by remember { mutableStateOf<List<Float>>(emptyList()) }

    var boxSizePx by remember { mutableStateOf(IntSize.Zero) }
    var frozenBitmap by remember { mutableStateOf<Bitmap?>(null) }
    val surfaceMirrorer = rememberSurfaceMirrorer()

    fun resetMeasurement() {
        targetAnchor?.detach()
        comparedAnchors.forEach { it.detach() }
        targetAnchor = null
        comparedAnchors.clear()
        distancesCm = emptyList()
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
            onGestureListener = rememberOnGestureListener(
                onSingleTapConfirmed = { motionEvent, _ ->
                    pendingTap = Offset(motionEvent.x, motionEvent.y)
                },
            ),
            onSessionUpdated = { session, frame ->
                pendingTap?.let { tap ->
                    pendingTap = null
                    val result = createAnchorFromTap(session, frame, tap)
                    if (result != null) {
                        if (!result.circleDetected) {
                            Toast.makeText(
                                context,
                                "Objet non détecté, point approximatif utilisé",
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                        val currentTarget = targetAnchor
                        if (currentTarget == null) {
                            targetAnchor = result.anchor
                        } else {
                            comparedAnchors.add(result.anchor)
                        }
                    }
                }

                val target = targetAnchor
                distancesCm = if (target != null) {
                    comparedAnchors.map { distanceBetween(it, target) * 100f }
                } else {
                    emptyList()
                }
            },
        ) {
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

        frozenBitmap?.let { bitmap ->
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
            )
        }

        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 32.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
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
    }
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
