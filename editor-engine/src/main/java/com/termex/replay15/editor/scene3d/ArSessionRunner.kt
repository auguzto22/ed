package com.termex.replay15.editor.scene3d

import android.app.Activity
import android.content.Context
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Session
import com.google.ar.core.TrackingFailureReason
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.UnavailableApkTooOldException
import com.google.ar.core.exceptions.UnavailableArcoreNotInstalledException
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException
import com.google.ar.core.exceptions.UnavailableSdkTooOldException
import com.google.ar.core.exceptions.UnavailableUserDeclinedInstallationException

/**
 * Runs an ARCore session on its own thread (a 1x1 pbuffer EGL context and an external camera
 * texture, since ARCore needs a GL context but nothing is drawn here) and reports the phone's
 * 6DoF pose. The camera image is not shown: the point is the motion of the phone.
 *
 * Callbacks arrive on the AR thread; copy what you need and hop to the UI thread.
 */
class ArSessionRunner(
    private val context: Context,
    private val displayRotation: Int,
    private val viewportWidth: Int,
    private val viewportHeight: Int,
    private val listener: Listener,
) {
    interface Listener {
        /** A tracked pose, column-major 4x4 (OpenGL camera convention). [matrix] is a fresh copy. */
        fun onPose(matrix: FloatArray)
        /** Tracking is not available right now; [message] says why, in Portuguese. */
        fun onTrackingLost(message: String)
        /** The session cannot run (any more); the runner has stopped. */
        fun onFailure(message: String)
    }

    enum class Readiness { READY, INSTALL_REQUESTED, UNAVAILABLE }
    class Preparation(val readiness: Readiness, val message: String = "")

    @Volatile private var running = false
    private var thread: Thread? = null

    val isRunning: Boolean get() = running

    fun start() {
        if (running) return
        running = true
        thread = Thread({ run() }, "Recly-AR").also { it.isDaemon = true; it.start() }
    }

    /** Stops the session and waits for the camera to be released. */
    fun stop() {
        running = false
        val t = thread ?: return
        thread = null
        runCatching { t.join(3000) }
    }

    private fun run() {
        var display: EGLDisplay? = null
        var eglContext: EGLContext? = null
        var surface: EGLSurface? = null
        val texture = IntArray(1)
        var session: Session? = null
        try {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            val version = IntArray(2)
            check(EGL14.eglInitialize(display, version, 0, version, 1)) { "EGL indisponível" }
            val attributes = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_NONE,
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val count = IntArray(1)
            check(EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0) && count[0] > 0) { "EGL sem configuração" }
            eglContext = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
            surface = EGL14.eglCreatePbufferSurface(display, configs[0], intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
            check(EGL14.eglMakeCurrent(display, surface, surface, eglContext)) { "EGL não ativou o contexto" }
            GLES20.glGenTextures(1, texture, 0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture[0])
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)

            val created = Session(context)
            session = created
            created.setCameraTextureNames(intArrayOf(texture[0]))
            created.setDisplayGeometry(displayRotation, viewportWidth.coerceAtLeast(1), viewportHeight.coerceAtLeast(1))
            created.resume()

            val matrix = FloatArray(16)
            while (running) {
                val frame = created.update() // blocks until the next camera frame
                val camera = frame.getCamera()
                if (!running) break
                if (camera.getTrackingState() == TrackingState.TRACKING) {
                    camera.getDisplayOrientedPose().toMatrix(matrix, 0)
                    listener.onPose(matrix.copyOf())
                } else {
                    listener.onTrackingLost(trackingMessage(camera.getTrackingState(), camera.getTrackingFailureReason()))
                }
            }
        } catch (failure: CameraNotAvailableException) {
            if (running) listener.onFailure("A câmera está em uso por outro app. Feche-o e tente de novo.")
        } catch (failure: Throwable) {
            if (running) listener.onFailure(failure.message?.takeIf { it.isNotBlank() } ?: "Não foi possível iniciar a Real Camera")
        } finally {
            running = false
            runCatching { session?.pause() }
            runCatching { session?.close() }
            if (display != null) {
                runCatching { if (texture[0] != 0) GLES20.glDeleteTextures(1, texture, 0) }
                runCatching { EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT) }
                runCatching { surface?.let { EGL14.eglDestroySurface(display, it) } }
                runCatching { eglContext?.let { EGL14.eglDestroyContext(display, it) } }
                runCatching { EGL14.eglTerminate(display) }
            }
        }
    }

    companion object {
        private var installRequested = false

        /**
         * Checks that ARCore can run here, offering to install or update it from Google Play
         * when needed. READY means a session can be started now; INSTALL_REQUESTED means the
         * install screen was opened and the user should try again afterwards.
         */
        fun prepare(activity: Activity): Preparation {
            return try {
            val availability = ArCoreApk.getInstance().checkAvailability(activity)
            if (availability != ArCoreApk.Availability.SUPPORTED_INSTALLED) {
                when (ArCoreApk.getInstance().requestInstall(activity, !installRequested)) {
                    ArCoreApk.InstallStatus.INSTALL_REQUESTED -> { installRequested = true; return Preparation(Readiness.INSTALL_REQUESTED, "Instalando o ARCore. Quando terminar, abra a Real Camera de novo.") }
                    ArCoreApk.InstallStatus.INSTALLED -> installRequested = false
                }
            }
            Preparation(Readiness.READY)
        } catch (e: UnavailableArcoreNotInstalledException) {
            Preparation(Readiness.UNAVAILABLE, "Instale o Google Play Services para RA (ARCore) para usar a Real Camera.")
        } catch (e: UnavailableUserDeclinedInstallationException) {
            Preparation(Readiness.UNAVAILABLE, "A Real Camera precisa do Google Play Services para RA (ARCore).")
        } catch (e: UnavailableApkTooOldException) {
            Preparation(Readiness.UNAVAILABLE, "Atualize o Google Play Services para RA (ARCore).")
        } catch (e: UnavailableSdkTooOldException) {
            Preparation(Readiness.UNAVAILABLE, "Atualize este app para usar a Real Camera.")
        } catch (e: UnavailableDeviceNotCompatibleException) {
            Preparation(Readiness.UNAVAILABLE, "Este celular não é compatível com ARCore, então a Real Camera não funciona nele.")
        } catch (e: Exception) {
            Preparation(Readiness.UNAVAILABLE, "Não foi possível verificar o ARCore.")
            }
        }

        fun trackingMessage(state: TrackingState, reason: TrackingFailureReason): String = when {
            state == TrackingState.STOPPED -> "Rastreamento parado."
            reason == TrackingFailureReason.NONE -> "Procurando onde está… mova o celular devagar."
            reason == TrackingFailureReason.BAD_STATE -> "Erro interno de rastreamento. Reinicie a Real Camera."
            reason == TrackingFailureReason.INSUFFICIENT_LIGHT -> "Pouca luz. Vá para um lugar mais iluminado."
            reason == TrackingFailureReason.EXCESSIVE_MOTION -> "Movimento rápido demais. Mova o celular mais devagar."
            reason == TrackingFailureReason.INSUFFICIENT_FEATURES -> "Poucos detalhes visíveis. Aponte para uma superfície com textura."
            reason == TrackingFailureReason.CAMERA_UNAVAILABLE -> "Câmera indisponível. Feche outros apps que usam a câmera."
            else -> "Rastreamento indisponível."
        }
    }
}
