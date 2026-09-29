package com.termex.replay15.editor.export

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.*
import android.provider.MediaStore
import android.util.Log
import androidx.media3.common.MimeTypes
import androidx.media3.transformer.*
import com.recly.editor.engine.R
import com.termex.replay15.editor.domain.Project
import com.termex.replay15.editor.domain.allVideos
import com.termex.replay15.editor.assets.AssetRepository
import com.termex.replay15.editor.media.MediaImport
import com.termex.replay15.editor.project.ProjectStore
import com.termex.replay15.editor.render.ProjectComposition
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

data class ExportState(val active: Boolean = false, val progress: Int = 0, val message: String = "", val uri: String? = null)

@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class EditorExportService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val cancelled = AtomicBoolean(false)
    private var transformer: Transformer? = null
    private var output: File? = null
    private var started = false
    private var jobId: String? = null
    private val poll = object : Runnable {
        override fun run() {
            val t = transformer ?: return
            val holder = ProgressHolder()
            val progress = if (t.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) holder.progress else 0
            publish(ExportState(true, progress, "Exportando MP4: $progress%"))
            main.postDelayed(this, 1000)
        }
    }
    override fun onBind(intent: Intent?) = null
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Exportacao do editor", NotificationManager.IMPORTANCE_LOW))
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == CANCEL) { cancelExport(); return START_NOT_STICKY }
        if (started) return START_NOT_STICKY
        started = true
        jobId = intent?.getStringExtra("project")
        state = ExportState(true, 0, "Preparando exportacao...")
        startForeground(NOTIFICATION, notification(state), if (Build.VERSION.SDK_INT >= 35)
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        if (intent?.action == RETRY_SAVE) {
            val id = jobId
            val saved = id?.let { File(File(filesDir, "editor-export-pending"), "$it.mp4") }
            if (saved?.isFile == true) {
                output = saved
                publish(ExportState(true, 100, "Salvando na galeria..."))
                saveToGallery(saved)
            } else finishWith(ExportState(message = "O MP4 pendente não foi encontrado. Exporte novamente."))
            return START_NOT_STICKY
        }
        io.execute {
            val result = runCatching {
                val id = requireNotNull(jobId)
                val project = activeProjectSnapshot?.takeIf { it.id == id }
                    ?: ProjectStore(File(filesDir, "editor-export")).load(id)
                val missing = MediaImport.missing(this, project)
                require(missing.isEmpty()) {
                    "Uma fonte de mídia ou LUT não está mais acessível. Reabra o editor para substituir o arquivo."
                }
                require(project.durationUs > 0L) { "O projeto não possui duração exportável" }
                val assets = AssetRepository(this)
                project.allVideos.flatMap { it.effects }.filter { it.enabled }.distinctBy { it.assetId to it.version }.forEach {
                    require(runCatching { assets.resolve(it.assetId, it.version) }.isSuccess) { "Um efeito deste projeto precisa ser instalado novamente" }
                }
                require(EncoderSupport.supports(project)) { "Resolucao/FPS nao suportados pelo encoder. Escolha 720p/30 FPS." }
                require(filesDir.usableSpace > project.export.estimatedBytes(project.durationUs) * 2 + 32_000_000) { "Espaco insuficiente para exportar" }
                project
            }
            main.post {
                if (cancelled.get()) return@post
                result.fold(::begin) { finishWith(ExportState(message = "Falha: ${it.message}")) }
            }
        }
        return START_NOT_STICKY
    }
    private fun begin(project: Project) {
        try {
            val folder = File(filesDir, "editor-export-pending").apply { check(exists() || mkdirs()) }
            output = File(folder, "${project.id}.mp4")
            output!!.delete()
            val encoder = DefaultEncoderFactory.Builder(this).setEnableFallback(false)
                .setVideoEncoderSelector { com.google.common.collect.ImmutableList.copyOf(EncoderSupport.encoders(project)) }
                .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(project.export.bitrate).build())
                .setRequestedAudioEncoderSettings(AudioEncoderSettings.Builder().setBitrate(project.export.audioBitrate).build()).build()
            transformer = Transformer.Builder(this).setVideoMimeType(MimeTypes.VIDEO_H264).setAudioMimeType(MimeTypes.AUDIO_AAC)
                .setEncoderFactory(encoder).setPortraitEncodingEnabled(true).setUsePlatformDiagnostics(false)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        main.removeCallbacks(poll); transformer = null
                        publish(ExportState(true, 100, "Salvando na galeria..."))
                        saveToGallery(requireNotNull(output))
                    }
                    override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                        Log.e(TAG, "Transformer failed for ${project.id}: ${exportException.errorCodeName}", exportException)
                        finishWith(ExportState(message = "Falha ao exportar: ${exportException.errorCodeName}. Ajuste resolução/FPS e tente novamente."))
                    }
                }).build()
            transformer!!.start(ProjectComposition.build(this, project), output!!.absolutePath)
            main.post(poll)
        } catch (e: Exception) { finishWith(ExportState(message = "Falha ao exportar: ${e.message}")) }
    }
    private fun saveToGallery(file: File) {
        io.execute {
            var uri: Uri? = null
            val result = runCatching {
                if (cancelled.get()) throw IOException("Cancelado")
                require(file.isFile && file.length() > 0L) {
                    "O exportador não produziu um MP4 válido"
                }
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, "Editado_${System.currentTimeMillis()}.mp4")
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/GravadorDeTela/Editados")
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
                val created = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                    ?: throw IOException("Nao foi possivel criar o arquivo")
                uri = created
                contentResolver.openOutputStream(created, "w")?.use { dest ->
                    file.inputStream().use { source ->
                        val buffer = ByteArray(256 * 1024)
                        var copied = 0L
                        while (true) {
                            if (cancelled.get()) throw IOException("Cancelado")
                            val count = source.read(buffer); if (count < 0) break
                            dest.write(buffer, 0, count)
                            copied += count
                        }
                        require(copied == file.length() && copied > 0L) {
                            "A cópia final do vídeo ficou incompleta"
                        }
                    }
                } ?: throw IOException("Galeria indisponivel")
                if (cancelled.get()) throw IOException("Cancelado")
                val published = contentResolver.update(created,
                    ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
                if (published != 1) throw IOException("A galeria não confirmou a publicação do vídeo")
                val storedSize = contentResolver.openFileDescriptor(created, "r")?.use { it.statSize } ?: -1L
                if (storedSize != file.length()) throw IOException("O arquivo salvo ficou incompleto")
                created.toString()
            }
            if (result.isFailure) uri?.let { runCatching { contentResolver.delete(it, null, null) } }
            if (result.isFailure) Log.e(TAG, "Could not publish exported MP4: ${file.absolutePath}", result.exceptionOrNull())
            main.post {
                if (!cancelled.get()) result.fold(
                    { finishWith(ExportState(progress = 100, message = "Video salvo na galeria", uri = it)) },
                    { finishWith(ExportState(message = "MP4 preservado. Erro ao salvar: ${it.message}"), deleteOutput = false) })
            }
        }
    }
    private fun publish(value: ExportState) {
        state = value
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification(value))
    }
    private fun notification(value: ExportState): Notification {
        val openIntent = (packageManager.getLaunchIntentForPackage(packageName)
            ?: Intent().setComponent(android.content.ComponentName(this, "com.termex.replay15.editor.ui.EditorActivity"))).apply {
            putExtra("project", jobId)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val open = PendingIntent.getActivity(this, 510, openIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_save_15)
            .setContentTitle("Editor Replay15").setContentText(value.message).setContentIntent(open)
            .setOnlyAlertOnce(true).setOngoing(value.active)
        if (value.active) {
            builder.setProgress(100, value.progress, value.progress == 0)
            val cancel = PendingIntent.getService(this, 511, Intent(this, EditorExportService::class.java).setAction(CANCEL),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            builder.addAction(Notification.Action.Builder(null, "Cancelar", cancel).build())
        } else {
            builder.setAutoCancel(true)
            if (output?.isFile == true) {
                val retry = PendingIntent.getForegroundService(this, 512,
                    Intent(this, EditorExportService::class.java).setAction(RETRY_SAVE).putExtra("project", jobId),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                builder.addAction(Notification.Action.Builder(null, "Tentar salvar", retry).build())
            }
        }
        return builder.build()
    }
    private fun finishWith(value: ExportState, deleteOutput: Boolean = true) {
        activeProjectSnapshot = null
        main.removeCallbacks(poll)
        transformer?.cancel(); transformer = null
        if (deleteOutput) output?.delete()
        publish(value)
        stopForeground(STOP_FOREGROUND_DETACH)
        stopSelf()
    }
    private fun cancelExport() {
        cancelled.set(true)
        finishWith(ExportState(message = "Exportacao cancelada"))
    }
    override fun onTimeout(startId: Int, fgsType: Int) { cancelExport() }
    override fun onDestroy() {
        cancelled.set(true); main.removeCallbacksAndMessages(null)
        transformer?.cancel(); transformer = null
        io.shutdown()
        if (state.active) state = ExportState(message = "Exportacao interrompida. O projeto continua salvo.")
        super.onDestroy()
    }
    companion object {
        private const val TAG = "ReclyExport"
        private const val CHANNEL = "editor_export"
        private const val NOTIFICATION = 515
        const val CANCEL = "com.termex.replay15.editor.CANCEL"
        const val RETRY_SAVE = "com.termex.replay15.editor.RETRY_SAVE"
        @Volatile var activeProjectSnapshot: Project? = null
        @Volatile var state = ExportState(); private set
    }
}
