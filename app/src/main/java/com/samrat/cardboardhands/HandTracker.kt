package com.samrat.cardboardhands

import android.content.Context
import android.graphics.Bitmap
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import com.samrat.orangehanding.FusionHands

/**
 * Hand tracking for all of PhoneXR, through the OrangeHanding library: MediaPipe hands, with
 * YOLO11-pose finding the wrists of hands MediaPipe missed.
 */
class HandTracker(
    context: Context,
    useGpu: Boolean = false,
    private val onResult: (HandLandmarkerResult) -> Unit
) : AutoCloseable {
    private val fusion = FusionHands(context, useGpu, yoloEveryMs = if (BuildConfig.LITE) 500L else 250L)

    fun detect(bitmap: Bitmap, timestampMs: Long, rotationDegrees: Int = 0) {
        onResult(fusion.detect(bitmap, timestampMs, rotationDegrees))
    }

    override fun close() = fusion.close()
}
