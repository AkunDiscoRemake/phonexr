package com.samrat.cardboardhands

import android.graphics.Bitmap
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.opengl.Matrix
import com.samrat.orangehanding.FusionBody
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.opengles.GL10

/**
 * Draws the avatar mirror: a background (the camera, a colour or a place), the character in the
 * pose of the person in front of the camera, and a small camera picture with the skeleton the
 * tracker sees. While recording, every frame is drawn a second time into the video's surface.
 */
class AvatarRenderer : GLSurfaceView.Renderer {
    @Volatile var rig: AvatarRig? = null
    @Volatile var showSkeleton = true
    /** Frame only the head, arms and chest (the mirror's "upper body"), not the whole figure. */
    @Volatile var upperBody = false
    /** Still background; null shows the camera picture. */
    @Volatile private var backdrop: Bitmap? = null
    private var backdropFresh = false
    private val cameraLock = Any()
    private var camera: Bitmap? = null
    private var cameraFresh = false
    @Volatile private var pose: FusionBody.Pose? = null
    @Volatile private var poseFresh = false
    @Volatile private var sideways = 0f
    private var lastFrameNs = 0L
    private var lastPoseNs = 0L

    @Volatile private var pendingRecorder: VideoRecorder? = null
    @Volatile private var stopRequested: ((Boolean) -> Unit)? = null
    private var recorder: VideoRecorder? = null
    private var recordSurface: EGLSurface = EGL14.EGL_NO_SURFACE

    private var avatarProgram = 0
    private var flatProgram = 0
    private var lineProgram = 0
    private var cameraTexture = 0
    private var backdropTexture = 0
    private var hasCamera = false
    private var hasBackdrop = false
    private var cameraAspect = 16f / 9f
    private var backdropAspect = 1f
    private var textures = IntArray(0)
    private var uploaded: AvatarRig? = null
    private var uvBuffers = emptyList<FloatBuffer>()
    private var indexBuffers = emptyList<ShortBuffer>()
    private var positionBuffers = emptyList<FloatBuffer>()
    private var normalBuffers = emptyList<FloatBuffer>()
    var viewWidth = 1; private set
    var viewHeight = 1; private set
    private val projection = FloatArray(16)
    private val view = FloatArray(16)
    private val mvp = FloatArray(16)

    /** A new camera frame; the renderer keeps [bitmap] and recycles the one before it. */
    fun setCamera(bitmap: Bitmap) = synchronized(cameraLock) {
        val old = camera
        camera = bitmap
        cameraFresh = true
        if (old != null && old !== bitmap) old.recycle()
    }

    fun setBackdrop(bitmap: Bitmap?) {
        backdrop = bitmap
        backdropFresh = true
    }

    fun setPose(value: FusionBody.Pose?, sideways: Float) {
        pose = value
        this.sideways = sideways
        poseFresh = true
    }

    fun startRecording(recorder: VideoRecorder) { pendingRecorder = recorder }

    fun stopRecording(done: (Boolean) -> Unit) { stopRequested = done }

    val recording get() = recorder != null || pendingRecorder != null

    override fun onSurfaceCreated(gl: GL10?, config: javax.microedition.khronos.egl.EGLConfig?) {
        avatarProgram = CinemaRenderer.program(AVATAR_VERTEX, AVATAR_FRAGMENT)
        flatProgram = CinemaRenderer.program(FLAT_VERTEX, FLAT_FRAGMENT)
        lineProgram = CinemaRenderer.program(LINE_VERTEX, LINE_FRAGMENT)
        cameraTexture = newTexture()
        backdropTexture = newTexture()
        uploaded = null
        backdropFresh = true
        synchronized(cameraLock) { cameraFresh = camera != null }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewWidth = width
        viewHeight = height
    }

    override fun onDrawFrame(gl: GL10?) {
        val current = rig
        if (current != null && uploaded !== current) upload(current)
        if (backdropFresh) {
            backdropFresh = false
            val bitmap = backdrop
            hasBackdrop = bitmap != null && !bitmap.isRecycled
            if (hasBackdrop) {
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, backdropTexture)
                GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
                backdropAspect = bitmap!!.width.toFloat() / bitmap.height
            }
        }
        synchronized(cameraLock) {
            val bitmap = camera
            if (cameraFresh && bitmap != null && !bitmap.isRecycled) {
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, cameraTexture)
                GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
                cameraAspect = bitmap.width.toFloat() / bitmap.height
                hasCamera = true
            }
            cameraFresh = false
        }
        if (poseFresh && current != null) {
            poseFresh = false
            val body = pose
            // A frame or two without the person keeps the last pose; only a real absence rests.
            if (body != null) { current.pose(body, sideways); lastPoseNs = System.nanoTime() }
            else if (System.nanoTime() - lastPoseNs > 1_200_000_000L) current.rest()
        }
        // Glide toward the tracked pose every frame (about 60 ms to catch up), skin only when it moved.
        val now = System.nanoTime()
        val dt = if (lastFrameNs == 0L) .016f else ((now - lastFrameNs) / 1e9f).coerceIn(.001f, .1f)
        lastFrameNs = now
        if (current != null && uploaded === current && current.ease(1f - kotlin.math.exp(-dt * 16f))) {
            current.skin()
            for (i in positionBuffers.indices) {
                positionBuffers[i].position(0); positionBuffers[i].put(current.positions[i]).position(0)
                normalBuffers[i].position(0); normalBuffers[i].put(current.normals[i]).position(0)
            }
        }

        drawScene(viewWidth, viewHeight)
        record()
    }

    private fun record() {
        pendingRecorder?.let { next ->
            pendingRecorder = null
            recorder = next
            recordSurface = createRecordSurface(next)
        }
        val active = recorder ?: return
        val display = EGL14.eglGetCurrentDisplay()
        val context = EGL14.eglGetCurrentContext()
        val draw = EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW)
        val read = EGL14.eglGetCurrentSurface(EGL14.EGL_READ)
        if (recordSurface != EGL14.EGL_NO_SURFACE && EGL14.eglMakeCurrent(display, recordSurface, recordSurface, context)) {
            drawScene(active.width, active.height)
            EGLExt.eglPresentationTimeANDROID(display, recordSurface, System.nanoTime())
            EGL14.eglSwapBuffers(display, recordSurface)
            EGL14.eglMakeCurrent(display, draw, read, context)
        }
        stopRequested?.let { done ->
            stopRequested = null
            if (recordSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, recordSurface)
            recordSurface = EGL14.EGL_NO_SURFACE
            recorder = null
            Thread { done(active.stop()) }.start()
        }
    }

    private fun createRecordSurface(recorder: VideoRecorder): EGLSurface {
        val display = EGL14.eglGetCurrentDisplay()
        val context = EGL14.eglGetCurrentContext()
        val id = IntArray(1)
        EGL14.eglQueryContext(display, context, EGL14.EGL_CONFIG_ID, id, 0)
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        EGL14.eglChooseConfig(display, intArrayOf(EGL14.EGL_CONFIG_ID, id[0], EGL14.EGL_NONE), 0, configs, 0, 1, count, 0)
        val config = configs[0] ?: return EGL14.EGL_NO_SURFACE
        return EGL14.eglCreateWindowSurface(display, config, recorder.surface, intArrayOf(EGL14.EGL_NONE), 0)
    }

    private fun drawScene(width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        GLES20.glClearColor(.06f, .06f, .08f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        val aspect = width.toFloat() / height
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        when {
            hasBackdrop -> fill(backdropTexture, backdropAspect, aspect)
            hasCamera -> fill(cameraTexture, cameraAspect, aspect)
        }
        val current = uploaded
        if (current != null) drawAvatar(current, aspect)
        if (showSkeleton && hasCamera) drawPicture(aspect)
    }

    /** A picture covering the view (cropped, never stretched). */
    private fun fill(texture: Int, pictureAspect: Float, viewAspect: Float) {
        val (u0, u1, v0, v1) = if (viewAspect < pictureAspect) {
            val span = viewAspect / pictureAspect
            listOf(.5f - span / 2, .5f + span / 2, 0f, 1f)
        } else {
            val span = pictureAspect / viewAspect
            listOf(0f, 1f, .5f - span / 2, .5f + span / 2)
        }
        flat(texture, -1f, -1f, 1f, 1f, u0, v0, u1, v1)
    }

    private fun flat(texture: Int, x0: Float, y0: Float, x1: Float, y1: Float, u0: Float, v0: Float, u1: Float, v1: Float) {
        GLES20.glUseProgram(flatProgram)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(flatProgram, "uTexture"), 0)
        val data = floatArrayOf(x0, y0, u0, v1, x1, y0, u1, v1, x0, y1, u0, v0, x1, y1, u1, v0)
        val buffer = floats(data)
        val position = GLES20.glGetAttribLocation(flatProgram, "aPosition")
        val uv = GLES20.glGetAttribLocation(flatProgram, "aUv")
        buffer.position(0)
        GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 16, buffer)
        GLES20.glEnableVertexAttribArray(position)
        buffer.position(2)
        GLES20.glVertexAttribPointer(uv, 2, GLES20.GL_FLOAT, false, 16, buffer)
        GLES20.glEnableVertexAttribArray(uv)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun drawAvatar(rig: AvatarRig, aspect: Float) {
        // Frame the whole body, a little bigger in a tall view.
        val fov = 32f
        Matrix.perspectiveM(projection, 0, fov, aspect, .1f, 30f)
        val halfHeight = rig.height * (if (upperBody) .3f else .56f)
        val centre = rig.height * (if (upperBody) .74f else .5f)
        val fit = if (aspect < .8f) halfHeight / aspect * .8f else halfHeight
        val distance = fit / kotlin.math.tan(Math.toRadians(fov / 2.0)).toFloat()
        Matrix.setLookAtM(view, 0, 0f, centre, distance, 0f, centre, 0f, 0f, 1f, 0f)
        Matrix.multiplyMM(mvp, 0, projection, 0, view, 0)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthFunc(GLES20.GL_LEQUAL)
        GLES20.glClear(GLES20.GL_DEPTH_BUFFER_BIT)
        GLES20.glUseProgram(avatarProgram)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(avatarProgram, "uMvp"), 1, false, mvp, 0)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(avatarProgram, "uTexture"), 0)
        val position = GLES20.glGetAttribLocation(avatarProgram, "aPosition")
        val normal = GLES20.glGetAttribLocation(avatarProgram, "aNormal")
        val uv = GLES20.glGetAttribLocation(avatarProgram, "aUv")
        val colorLocation = GLES20.glGetUniformLocation(avatarProgram, "uColor")
        val texturedLocation = GLES20.glGetUniformLocation(avatarProgram, "uTextured")
        rig.model.primitives.forEachIndexed { i, primitive ->
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            val texture = textures.getOrElse(i) { 0 }
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
            GLES20.glUniform1f(texturedLocation, if (texture != 0) 1f else 0f)
            GLES20.glUniform4fv(colorLocation, 1, primitive.color, 0)
            GLES20.glVertexAttribPointer(position, 3, GLES20.GL_FLOAT, false, 0, positionBuffers[i].position(0))
            GLES20.glEnableVertexAttribArray(position)
            GLES20.glVertexAttribPointer(normal, 3, GLES20.GL_FLOAT, false, 0, normalBuffers[i].position(0))
            GLES20.glEnableVertexAttribArray(normal)
            GLES20.glVertexAttribPointer(uv, 2, GLES20.GL_FLOAT, false, 0, uvBuffers[i].position(0))
            GLES20.glEnableVertexAttribArray(uv)
            val indices = indexBuffers[i]
            GLES20.glDrawElements(GLES20.GL_TRIANGLES, indices.capacity(), GLES20.GL_UNSIGNED_SHORT, indices.position(0))
        }
        GLES20.glDisableVertexAttribArray(normal)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
    }

    /** The camera in the top corner with the tracked skeleton over it: what the tracker sees. */
    private fun drawPicture(aspect: Float) {
        val w = if (aspect > 1f) .5f else .7f
        val h = w * aspect / cameraAspect
        // Below the title bar, on the right.
        val x1 = .96f; val x0 = x1 - w
        val y1 = if (aspect > 1f) .66f else .78f; val y0 = y1 - h
        flat(cameraTexture, x0, y0, x1, y1, 0f, 0f, 1f, 1f)
        val body = pose ?: return
        val lines = ArrayList<Float>()
        for (k in BONES.indices step 2) {
            val a = BONES[k]; val b = BONES[k + 1]
            if (body.visibility(a) < .35f || body.visibility(b) < .35f) continue
            lines += listOf(x0 + body.x(a) * w, y1 - body.y(a) * h, x0 + body.x(b) * w, y1 - body.y(b) * h)
        }
        if (lines.isEmpty()) return
        GLES20.glUseProgram(lineProgram)
        GLES20.glUniform4f(GLES20.glGetUniformLocation(lineProgram, "uColor"), 1f, .48f, .1f, 1f)
        GLES20.glLineWidth(5f)
        val buffer = floats(lines.toFloatArray())
        val position = GLES20.glGetAttribLocation(lineProgram, "aPosition")
        GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 8, buffer)
        GLES20.glEnableVertexAttribArray(position)
        GLES20.glDrawArrays(GLES20.GL_LINES, 0, lines.size / 2)
    }

    private fun upload(rig: AvatarRig) {
        if (textures.isNotEmpty()) GLES20.glDeleteTextures(textures.size, textures, 0)
        val byImage = HashMap<Int, Int>()
        textures = IntArray(rig.model.primitives.size) { i ->
            val image = rig.model.primitives[i].image
            val bitmap = rig.model.images.getOrNull(image) ?: return@IntArray 0
            byImage.getOrPut(image) {
                newTexture(repeat = true).also {
                    GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
                    GLES20.glGenerateMipmap(GLES20.GL_TEXTURE_2D)
                    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR_MIPMAP_LINEAR)
                }
            }
        }
        uvBuffers = rig.model.primitives.map { floats(it.uvs) }
        indexBuffers = rig.model.primitives.map { p ->
            ByteBuffer.allocateDirect(p.indices.size * 2).order(ByteOrder.nativeOrder()).asShortBuffer()
                .also { b -> p.indices.forEach { b.put(it.toShort()) }; b.position(0) }
        }
        rig.skin()
        positionBuffers = rig.positions.map { floats(it) }
        normalBuffers = rig.normals.map { floats(it) }
        uploaded = rig
    }

    private fun newTexture(repeat: Boolean = false): Int {
        val id = IntArray(1).also { GLES20.glGenTextures(1, it, 0) }[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, id)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        val wrap = if (repeat) GLES20.GL_REPEAT else GLES20.GL_CLAMP_TO_EDGE
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, wrap)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, wrap)
        return id
    }

    private fun floats(data: FloatArray): FloatBuffer =
        ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(data).also { it.position(0) }

    companion object {
        /** MediaPipe Pose connections drawn as the skeleton. */
        private val BONES = intArrayOf(
            11, 12, 11, 13, 13, 15, 12, 14, 14, 16, 11, 23, 12, 24, 23, 24,
            23, 25, 25, 27, 27, 31, 24, 26, 26, 28, 28, 32, 15, 19, 16, 20, 0, 7, 0, 8,
        )

        /** A GL surface the video encoder can take frames from (EGL_RECORDABLE_ANDROID), with depth. */
        fun configure(view: GLSurfaceView) {
            view.setEGLContextClientVersion(2)
            view.setEGLConfigChooser { egl, display ->
                fun choose(recordable: Boolean): javax.microedition.khronos.egl.EGLConfig? {
                    val attributes = mutableListOf(
                        EGL10.EGL_RED_SIZE, 8, EGL10.EGL_GREEN_SIZE, 8, EGL10.EGL_BLUE_SIZE, 8, EGL10.EGL_ALPHA_SIZE, 8,
                        EGL10.EGL_DEPTH_SIZE, 16, EGL10.EGL_RENDERABLE_TYPE, 4,
                    )
                    if (recordable) attributes += listOf(0x3142, 1)
                    attributes += EGL10.EGL_NONE
                    val configs = arrayOfNulls<javax.microedition.khronos.egl.EGLConfig>(1)
                    val count = IntArray(1)
                    egl.eglChooseConfig(display, attributes.toIntArray(), configs, 1, count)
                    return if (count[0] > 0) configs[0] else null
                }
                choose(true) ?: choose(false) ?: error("Нет подходящей конфигурации OpenGL")
            }
        }

        private const val AVATAR_VERTEX = """
            uniform mat4 uMvp;
            attribute vec3 aPosition;
            attribute vec3 aNormal;
            attribute vec2 aUv;
            varying vec3 vNormal;
            varying vec2 vUv;
            void main() {
                vNormal = aNormal;
                vUv = aUv;
                gl_Position = uMvp * vec4(aPosition, 1.0);
            }
        """
        private const val AVATAR_FRAGMENT = """
            precision mediump float;
            uniform sampler2D uTexture;
            uniform vec4 uColor;
            uniform float uTextured;
            varying vec3 vNormal;
            varying vec2 vUv;
            void main() {
                vec4 base = uColor;
                if (uTextured > 0.5) base *= texture2D(uTexture, vUv);
                vec3 n = normalize(vNormal);
                float key = abs(dot(n, normalize(vec3(0.35, 0.6, 0.75))));
                float fill = abs(dot(n, normalize(vec3(-0.6, 0.2, 0.4))));
                float light = 0.42 + 0.55 * key + 0.18 * fill;
                gl_FragColor = vec4(base.rgb * light, 1.0);
            }
        """
        private const val FLAT_VERTEX = """
            attribute vec2 aPosition;
            attribute vec2 aUv;
            varying vec2 vUv;
            void main() { vUv = aUv; gl_Position = vec4(aPosition, 0.0, 1.0); }
        """
        private const val FLAT_FRAGMENT = """
            precision mediump float;
            uniform sampler2D uTexture;
            varying vec2 vUv;
            void main() { gl_FragColor = texture2D(uTexture, vUv); }
        """
        private const val LINE_VERTEX = """
            attribute vec2 aPosition;
            void main() { gl_Position = vec4(aPosition, 0.0, 1.0); }
        """
        private const val LINE_FRAGMENT = """
            precision mediump float;
            uniform vec4 uColor;
            void main() { gl_FragColor = uColor; }
        """
    }
}
