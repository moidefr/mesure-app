package com.moidefr.mesureapp.ar

import android.graphics.Bitmap
import android.media.Image

/** Converts a single RGBA_8888 [Image] (e.g. from an [android.media.ImageReader]) to a [Bitmap]. */
fun rgbaImageToBitmap(image: Image): Bitmap {
    val plane = image.planes[0]
    val buffer = plane.buffer
    val pixelStride = plane.pixelStride
    val rowStride = plane.rowStride
    val rowPadding = rowStride - pixelStride * image.width

    val bitmap = Bitmap.createBitmap(
        image.width + rowPadding / pixelStride,
        image.height,
        Bitmap.Config.ARGB_8888,
    )
    bitmap.copyPixelsFromBuffer(buffer)
    return if (rowPadding == 0) {
        bitmap
    } else {
        Bitmap.createBitmap(bitmap, 0, 0, image.width, image.height)
    }
}
