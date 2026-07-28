package com.moidefr.mesureapp.ar

import androidx.compose.ui.geometry.Offset
import com.google.ar.core.Anchor
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Frame
import com.google.ar.core.HitResult
import com.google.ar.core.Plane
import com.google.ar.core.Session
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

data class TapResult(val anchor: Anchor, val circleDetected: Boolean)

/**
 * Tap (screen coords) -> ROI around the tap in the camera image -> Hough circle -> circle's
 * lowest point -> AR hit-test against the tracked ground plane -> anchor.
 *
 * The ROI size is scaled against a coarse hit-test distance estimate so a far-away object (whose
 * apparent radius in pixels is smaller) doesn't get searched with a window sized for a near one.
 */
fun createAnchorFromTap(session: Session, frame: Frame, tap: Offset): TapResult? {
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

    val groundContactPoint = try {
        val image = frame.acquireCameraImage()
        try {
            detectGroundContactPoint(image, imagePoint[0], imagePoint[1], roiSize)
        } finally {
            image.close()
        }
    } catch (e: NotYetAvailableException) {
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
        viewPoint[0] = tap.x
        viewPoint[1] = tap.y
    }

    val anchor = groundPlaneHit(frame.hitTest(viewPoint[0], viewPoint[1]))?.createAnchorOrNull()
        ?: return null
    return TapResult(anchor, circleDetected = groundContactPoint != null)
}

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
