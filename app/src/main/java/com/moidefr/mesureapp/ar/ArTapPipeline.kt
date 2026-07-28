package com.moidefr.mesureapp.ar

import android.graphics.PointF
import androidx.compose.ui.geometry.Offset
import com.google.ar.core.Anchor
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Frame
import com.google.ar.core.HitResult
import com.google.ar.core.Plane
import com.google.ar.core.exceptions.NotYetAvailableException
import io.github.sceneview.ar.arcore.createAnchorOrNull
import io.github.sceneview.ar.arcore.distance
import io.github.sceneview.ar.arcore.firstByTypeOrNull
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Reference apparent object size: at 1 m, a 400 px-wide ROI covers a pétanque ball comfortably. */
private const val REFERENCE_DISTANCE_METERS = 1f
private const val REFERENCE_ROI_SIZE = 400
private const val MIN_ROI_SIZE = 120
private const val MAX_ROI_SIZE = 900

/**
 * [imagePoint] is in the camera image's own pixel coordinates (stable regardless of screen
 * rotation/orientation, used for real-world distance math); [viewPoint] is the same point
 * mapped to screen coordinates (used for hit-testing and on-screen drawing).
 */
data class DetectedTap(val imagePoint: PointF, val viewPoint: Offset, val circleDetected: Boolean)

/**
 * Tap (screen coords) -> ROI around the tap in the camera image -> Hough circle -> circle's
 * center.
 *
 * The circle's center (not its bottom edge) is used as the object's point: the camera looks down
 * at the play area roughly from above, so the object's silhouette bottom does not approximate
 * its ground contact point the way it would from a shallow, eye-level viewing angle.
 *
 * The ROI size is scaled against a coarse hit-test distance estimate so a far-away object (whose
 * apparent radius in pixels is smaller) doesn't get searched with a window sized for a near one.
 */
fun detectTap(frame: Frame, tap: Offset): DetectedTap {
    val coarseDistance = groundPlaneHit(frame.hitTest(tap.x, tap.y))?.distance
        ?.coerceIn(0.2f, 5f) ?: REFERENCE_DISTANCE_METERS
    val roiSize = (REFERENCE_ROI_SIZE * (REFERENCE_DISTANCE_METERS / coarseDistance))
        .roundToInt()
        .coerceIn(MIN_ROI_SIZE, MAX_ROI_SIZE)

    val imagePoint = FloatArray(2)
    frame.transformCoordinates2d(
        Coordinates2d.VIEW,
        floatArrayOf(tap.x, tap.y),
        Coordinates2d.IMAGE_PIXELS,
        imagePoint,
    )

    val objectCenter = try {
        val image = frame.acquireCameraImage()
        try {
            detectObjectCenter(image, imagePoint[0], imagePoint[1], roiSize)
        } finally {
            image.close()
        }
    } catch (e: NotYetAvailableException) {
        null
    }
    val finalImagePoint = objectCenter ?: PointF(imagePoint[0], imagePoint[1])

    val viewPoint = FloatArray(2)
    frame.transformCoordinates2d(
        Coordinates2d.IMAGE_PIXELS,
        floatArrayOf(finalImagePoint.x, finalImagePoint.y),
        Coordinates2d.VIEW,
        viewPoint,
    )

    return DetectedTap(
        imagePoint = finalImagePoint,
        viewPoint = Offset(viewPoint[0], viewPoint[1]),
        circleDetected = objectCenter != null,
    )
}

/** AR hit-test against the tracked ground plane at the detected point -> 3D anchor. */
fun createAnchorFromDetectedTap(frame: Frame, detected: DetectedTap): Anchor? =
    groundPlaneHit(frame.hitTest(detected.viewPoint.x, detected.viewPoint.y))?.createAnchorOrNull()

/** Depth (meters, camera to hit point) at the detected point, used to scale pixel distances. */
fun hitTestDistance(frame: Frame, detected: DetectedTap): Float? =
    groundPlaneHit(frame.hitTest(detected.viewPoint.x, detected.viewPoint.y))?.distance

private fun groundPlaneHit(hits: List<HitResult>) = hits.firstByTypeOrNull(
    planeTypes = setOf(Plane.Type.HORIZONTAL_UPWARD_FACING),
    point = false,
    depthPoint = false,
    instantPlacementPoint = false,
)

/** Euclidean distance in meters between two anchors' current poses. */
fun distanceBetween(a: Anchor, b: Anchor): Float {
    val p1 = a.pose
    val p2 = b.pose
    val dx = p1.tx() - p2.tx()
    val dy = p1.ty() - p2.ty()
    val dz = p1.tz() - p2.tz()
    return sqrt(dx * dx + dy * dy + dz * dz)
}
