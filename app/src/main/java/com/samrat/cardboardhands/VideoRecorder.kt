package com.samrat.cardboardhands

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.view.Surface

/**
 * An H.264 video (with the microphone, when allowed) drawn by OpenGL into [surface], saved to
 * Movies/PhoneXR where the gallery and the Photos app find it.
 */
class VideoRecorder(private val context: Context, width: Int, height: Int) {
    val width = width / 16 * 16
    val height = height / 16 * 16
    private val recorder = MediaRecorder(context)
    private val uri: Uri
    private val descriptor: ParcelFileDescriptor
    val surface: Surface
    val startedAt = System.currentTimeMillis()

    init {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "PhoneXR_${System.currentTimeMillis()}.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/PhoneXR")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        uri = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: error("Не удалось создать видео")
        descriptor = context.contentResolver.openFileDescriptor(uri, "rw") ?: error("Нет доступа к видео")
        val sound = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        recorder.apply {
            if (sound) setAudioSource(MediaRecorder.AudioSource.MIC)
            setVideoSource(MediaRecorder.VideoSource.SURFACE)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setOutputFile(descriptor.fileDescriptor)
            setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            setVideoSize(this@VideoRecorder.width, this@VideoRecorder.height)
            setVideoFrameRate(30)
            setVideoEncodingBitRate(10_000_000)
            if (sound) {
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(128_000)
                setAudioSamplingRate(44_100)
            }
            prepare()
        }
        surface = recorder.surface
        recorder.start()
    }

    /** Ends the video; returns true when it was saved. */
    fun stop(): Boolean {
        val saved = runCatching { recorder.stop() }.isSuccess
        recorder.release()
        descriptor.close()
        if (saved) {
            context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
        } else {
            context.contentResolver.delete(uri, null, null)
        }
        return saved
    }
}
