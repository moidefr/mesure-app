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
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * A palet breton is ~7cm across; this leaves enough margin either side for pétanque boules too,
 * while staying tight enough that a shadow edge or plank seam can no longer masquerade as the
 * object just because it happens to be roughly circular.
 */
private const val EXPECTED_OBJECT_RADIUS_METERS = 0.035f
private const val MIN_RADIUS_SCALE = 0.4f
private const val MAX_RADIUS_SCALE = 2.2f
private const val ROI_MARGIN_SCALE = 6f
private const val REFERENCE_DISTANCE_METERS = 1f
private const val MIN_ROI_SIZE = 120
private const val MAX_ROI_SIZE = 900

/**
 * [imagePoint] is in the camera image's own pixel coordinates (stable regardless of screen
 * rotation/orientation, used for real-world distance math); [viewPoint] is the same point
 * mapped to screen coordinates (used for hit-testing and on-screen drawing).
 */
data class DetectedTap(val imagePoint: PointF, val viewPoint: Offset, val circleDetected: Boolean)

private data class RoiPlan(val roiSize: Int, val minRadiusPx: Int, val maxRadiusPx: Int)

/**
 * Projects [EXPECTED_OBJECT_RADIUS_METERS] to pixels at the tap's estimated depth (pinhole camera
 * model: apparent radius = focalLengthPx * realRadiusMeters / depthMeters), then derives a ROI and
 * a min/max radius bound around that expected size.
 *
 * Sizing the ROI/radius bounds off a fraction of an arbitrary reference ROI (rather than the
 * object's actual expected apparent size) let large, low-detail regions — a shadow edge, a plank
 * seam — pass the circularity filter purely because the search window happened to be sized
 * generously; that showed up as a huge false "circle" spanning most of the ROI in testing.
 */
private fun planRoi(frame: Frame, tap: Offset): RoiPlan {
    val coarseDistance = groundPlaneHit(frame.hitTest(tap.x, tap.y))?.distance
        ?.coerceIn(0.2f, 5f) ?: REFERENCE_DISTANCE_METERS
    val focalLengthPx = frame.camera.imageIntrinsics.focalLength
    val avgFocalLengthPx = (focalLengthPx[0] + focalLengthPx[1]) / 2f
    val expectedRadiusPx = avgFocalLengthPx * EXPECTED_OBJECT_RADIUS_METERS / coarseDistance

    val roiSize = (expectedRadiusPx * ROI_MARGIN_SCALE * 2f)
        .roundToInt()
        .coerceIn(MIN_ROI_SIZE, MAX_ROI_SIZE)
    val minRadiusPx = (expectedRadiusPx * MIN_RADIUS_SCALE).roundToInt().coerceAtLeast(8)
    val maxRadiusPx = (expectedRadiusPx * MAX_RADIUS_SCALE).roundToInt().coerceAtLeast(minRadiusPx + 5)
    return RoiPlan(roiSize, minRadiusPx, maxRadiusPx)
}

/**
 * Tap (screen coords) -> ROI around the tap in the camera image -> circle detection -> circle's
 * center.
 *
 * The circle's center (not its bottom edge) is used as the object's point: the camera looks down
 * at the play area roughly from above, so the object's silhouette bottom does not approximate
 * its ground contact point the way it would from a shallow, eye-level viewing angle.
 */
fun detectTap(frame: Frame, tap: Offset): DetectedTap {
    val plan = planRoi(frame, tap)

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
            detectObjectCenter(image, imagePoint[0], imagePoint[1], plan.roiSize, plan.minRadiusPx, plan.maxRadiusPx)
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

/** A candidate detected object, in screen coordinates — for the debug "Détecter" overlay. */
data class DebugCircle(val center: Offset, val radius: Float)

/**
 * Debug helper: runs the same ROI + detection pass as [detectTap] around [tap], but returns every
 * candidate circle found instead of only the one closest to the tap. Use this to see what the
 * detector actually sees, rather than trusting the single pick silently.
 */
fun detectDebugCandidates(frame: Frame, tap: Offset): List<DebugCircle> {
    val plan = planRoi(frame, tap)

    val imagePoint = FloatArray(2)
    frame.transformCoordinates2d(
        Coordinates2d.VIEW,
        floatArrayOf(tap.x, tap.y),
        Coordinates2d.IMAGE_PIXELS,
        imagePoint,
    )

    val circles = try {
        val image = frame.acquireCameraImage()
        try {
            detectCirclesInRoi(image, imagePoint[0], imagePoint[1], plan.roiSize, plan.minRadiusPx, plan.maxRadiusPx)
        } finally {
            image.close()
        }
    } catch (e: NotYetAvailableException) {
        emptyList()
    }

    return circles.map { circle ->
        val centerView = FloatArray(2)
        frame.transformCoordinates2d(
            Coordinates2d.IMAGE_PIXELS,
            floatArrayOf(circle.centerX, circle.centerY),
            Coordinates2d.VIEW,
            centerView,
        )
        val edgeView = FloatArray(2)
        frame.transformCoordinates2d(
            Coordinates2d.IMAGE_PIXELS,
            floatArrayOf(circle.centerX + circle.radius, circle.centerY),
            Coordinates2d.VIEW,
            edgeView,
        )
        val viewRadius = hypot(
            (edgeView[0] - centerView[0]).toDouble(),
            (edgeView[1] - centerView[1]).toDouble(),
        ).toFloat()
        DebugCircle(Offset(centerView[0], centerView[1]), viewRadius)
    }
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
