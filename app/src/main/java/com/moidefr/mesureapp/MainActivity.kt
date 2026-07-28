package com.moidefr.mesureapp

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import com.google.ar.core.Anchor
import com.google.ar.core.Config
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import com.google.ar.core.Session
import com.google.ar.core.exceptions.NotYetAvailableException
import com.moidefr.mesureapp.ar.detectGroundContactPoint
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.ar.arcore.createAnchorOrNull
import io.github.sceneview.ar.arcore.firstByTypeOrNull
import io.github.sceneview.ar.node.AnchorNode
import io.github.sceneview.node.SphereNode
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberOnGestureListener
import org.opencv.android.OpenCVLoader

private const val TAG = "MesureApp"

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
                    ArTapScreen()
                }
            }
        }
    }
}

/**
 * Validates the tap -> Hough circle -> AR hit-test -> 3D anchor pipeline: every tap drops a
 * marker at the detected object's ground-contact point. No distance/color logic yet.
 */
@Composable
private fun ArTapScreen() {
    val engine = rememberEngine()
    val materialLoader = rememberMaterialLoader(engine)
    val markerMaterial = remember(materialLoader) {
        materialLoader.createColorInstance(Color.Yellow)
    }

    var pendingTap by remember { mutableStateOf<Offset?>(null) }
    val anchors = remember { mutableStateListOf<Anchor>() }

    ARSceneView(
        modifier = Modifier.fillMaxSize(),
        engine = engine,
        materialLoader = materialLoader,
        planeFindingMode = Config.PlaneFindingMode.HORIZONTAL,
        onGestureListener = rememberOnGestureListener(
            onSingleTapConfirmed = { motionEvent, _ ->
                pendingTap = Offset(motionEvent.x, motionEvent.y)
            },
        ),
        onSessionUpdated = { session, frame ->
            pendingTap?.let { tap ->
                pendingTap = null
                createAnchorFromTap(session, frame, tap)?.let { anchor ->
                    anchors.add(anchor)
                }
            }
        },
    ) {
        anchors.forEach { anchor ->
            AnchorNode(anchor = anchor) {
                SphereNode(radius = 0.03f, materialInstance = markerMaterial)
            }
        }
    }
}

/**
 * Tap (screen coords) -> ROI around the tap in the camera image -> Hough circle -> circle's
 * lowest point -> AR hit-test against the tracked ground plane -> anchor.
 */
private fun createAnchorFromTap(session: Session, frame: Frame, tap: Offset): Anchor? {
    val imagePoint = FloatArray(2)
    frame.transformCoordinates2d(
        Coordinates2d.VIEW,
        floatArrayOf(tap.x, tap.y),
        Coordinates2d.IMAGE_PIXELS,
        imagePoint,
    )

    val groundContactPoint = try {
        val image = frame.acquireCameraImage()
        try {
            detectGroundContactPoint(image, imagePoint[0], imagePoint[1])
        } finally {
            image.close()
        }
    } catch (e: NotYetAvailableException) {
        Log.w(TAG, "Camera image not yet available for tap processing", e)
        null
    }

    val viewPoint = FloatArray(2)
    if (groundContactPoint != null) {
        frame.transformCoordinates2d(
            Coordinates2d.IMAGE_PIXELS,
            floatArrayOf(groundContactPoint.x, groundContactPoint.y),
            Coordinates2d.VIEW,
            viewPoint,
        )
    } else {
        Log.d(TAG, "No circle detected around tap, falling back to the raw tap point")
        viewPoint[0] = tap.x
        viewPoint[1] = tap.y
    }

    return frame.hitTest(viewPoint[0], viewPoint[1])
        .firstByTypeOrNull(
            planeTypes = setOf(Plane.Type.HORIZONTAL_UPWARD_FACING),
            point = false,
            depthPoint = false,
            instantPlacementPoint = false,
        )
        ?.createAnchorOrNull()
}
