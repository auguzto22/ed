package com.termex.replay15.editor.media

import android.Manifest
import android.app.Activity
import android.app.LoaderManager
import android.content.ContentUris
import android.content.CursorLoader
import android.content.Intent
import android.content.Loader
import android.content.pm.PackageManager
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.exifinterface.media.ExifInterface
import androidx.recyclerview.widget.RecyclerView
import com.recly.editor.R
import com.termex.replay15.editor.design.DesignTokens
import com.termex.replay15.editor.design.SurfaceTokens
import java.io.File
import java.io.InputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Recly's own media picker — a premium, cinematic experience for selecting
 * photos and videos from the device gallery.
 *
 * Features:
 * - Multi-selection with persistent state across navigation
 * - Album organization with thumbnails
 * - Efficient thumbnail loading with LRU cache
 * - Duration display for videos
 * - Loading / empty / permission states
 * - Search across titles
 * - Continues to editor with selected media URIs
 */
class ReclyMediaPicker : AppCompatActivity() {

    // ── Intent Contract ───────────────────────────────────────────────────────

    companion object {
        const val EXTRA_MODE = "mode"
        const val EXTRA_MAX = "max"
        const val RESULT_URIS = "uris"
        const val RESULT_TYPES = "types"  // "video" or "image"

        const val MODE_ANY = 0
        const val MODE_VIDEOS_ONLY = 1
        const val MODE_IMAGES_ONLY = 2

        private const val REQ_PERMISSION = 1001
        private const val LOADER_MEDIA = 2001

        const val ALBUM_ALL = "__all__"

        private val PROJECTION = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE,
            "duration",
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.BUCKET_DISPLAY_NAME,
            MediaStore.MediaColumns.DATE_ADDED,
        )
    }

    private val mode: Int by lazy { intent.getIntExtra(EXTRA_MODE, MODE_ANY) }
    private val maxSelection: Int by lazy { intent.getIntExtra(EXTRA_MAX, 50) }

    // ── State ─────────────────────────────────────────────────────────────────

    private val selectedUris = LinkedHashMap<Long, Uri>()
    private val selectedTypes = LinkedHashMap<Long, String>()

    private var currentAlbum: String = ALBUM_ALL
    private var searchQuery: String = ""
    private var mediaItems: MutableList<MediaEntry> = mutableListOf()
    private var albums: List<AlbumEntry> = emptyList()

    // ── UI ────────────────────────────────────────────────────────────────────

    private lateinit var headerBar: LinearLayout
    private lateinit var albumChip: TextView
    private lateinit var searchInput: android.widget.EditText
    private lateinit var gridView: GridView
    private lateinit var bottomBar: LinearLayout
    private lateinit var counterLabel: TextView
    private lateinit var continueBtn: TextView
    private lateinit var loadingIndicator: ProgressBar
    private lateinit var emptyView: LinearLayout
    private lateinit var permissionView: LinearLayout

    private lateinit var mediaAdapter: MediaGridAdapter

    // ── Loader ────────────────────────────────────────────────────────────────

    private val loaderCallbacks = object : LoaderManager.LoaderCallbacks<Cursor> {
        override fun onCreateLoader(id: Int, args: Bundle?): Loader<Cursor> {
            return CursorLoader(
                this@ReclyMediaPicker,
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                PROJECTION,
                buildSelection(),
                buildSelectionArgs(),
                "$sortOrder DESC"
            )
        }

        override fun onLoadFinished(loader: Loader<Cursor>, cursor: Cursor?) {
            loadingIndicator.visibility = View.GONE
            cursor ?: return
            mediaItems.clear()

            if (currentAlbum == ALBUM_ALL && searchQuery.isBlank()) {
                // Build album list from first pass
                val albumSet = mutableMapOf<String, Long>()
                while (cursor.moveToNext()) {
                    val album = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)) ?: "..."
                    val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID))
                    if (!albumSet.containsKey(album)) albumSet[album] = id
                }
                albums = albumSet.map { (name, _) -> AlbumEntry(name, name) }.sortedBy { it.display }
                cursor.moveToPosition(-1)
            }

            while (cursor.moveToNext()) {
                val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID))
                val name = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)) ?: "Media"
                val mime = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)) ?: ""
                val duration = cursor.getLong(cursor.getColumnIndexOrThrow("duration"))
                val size = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE))
                val album = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)) ?: "..."
                val date = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED))

                val contentUri = ContentUris.withAppendedId(
                    if (mime.startsWith("video/")) MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                    else MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    id
                )

                if (!shouldInclude(mime)) continue

                mediaItems.add(MediaEntry(
                    id = id,
                    uri = contentUri,
                    name = name,
                    mime = mime,
                    duration = duration,
                    size = size,
                    album = album,
                    dateAdded = date,
                ))
            }
            cursor.close()

            if (mediaItems.isEmpty()) {
                emptyView.visibility = View.VISIBLE
                gridView.visibility = View.GONE
            } else {
                emptyView.visibility = View.GONE
                gridView.visibility = View.VISIBLE
                mediaAdapter.notifyDataSetChanged()
            }
        }

        override fun onLoaderReset(loader: Loader<Cursor>) {
            mediaItems.clear()
            mediaAdapter.notifyDataSetChanged()
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Full dark immersive
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = DesignTokens.BG
        window.navigationBarColor = DesignTokens.BG

        setContentView(R.layout.recly_media_picker)
        setupImmersiveMode()

        buildUI()
        applyDesignSystem()
        restoreInstanceState(savedInstanceState)

        if (hasStoragePermission()) {
            showMediaView()
        } else {
            showPermissionView()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("currentAlbum", albums.indexOfFirst { it.name == currentAlbum }.takeIf { it >= 0 } ?: -1)
        outState.putString("searchQuery", searchQuery)
        outState.putString("selectedUris", serializeUris(selectedUris.values))
        outState.putString("selectedTypes", selectedTypes.values.joinToString(","))
    }

    private fun restoreInstanceState(saved: Bundle?) {
        saved ?: return
        val idx = saved.getInt("currentAlbum", -1)
        if (idx >= 0 && albums.isNotEmpty()) currentAlbum = albums[idx].name
        searchQuery = saved.getString("searchQuery", "")
        searchInput.setText(searchQuery)
        deserializeUris(saved.getString("selectedUris", "")).forEachIndexed { i, uri ->
            val type = saved.getString("selectedTypes", "")?.split(",")?.getOrNull(i) ?: ""
            val id = android.content.ContentUris.parseId(uri)
            selectedUris[id] = uri
            selectedTypes[id] = type
        }
        updateCounter()
    }

    // ── Permission ─────────────────────────────────────────────────────────────

    private fun hasStoragePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val granted = grants.values.any { it }
        if (granted) showMediaView() else {
            Toast.makeText(this, "É preciso permitir acesso à galeria para importar mídia.", Toast.LENGTH_LONG).show()
            showPermissionView()
        }
    }

    // ── UI Building ────────────────────────────────────────────────────────────

    private fun buildUI() {
        headerBar = findViewById(R.id.pickerHeader)
        albumChip = findViewById(R.id.albumChip)
        searchInput = findViewById(R.id.searchInput)
        gridView = findViewById(R.id.mediaGrid)
        bottomBar = findViewById(R.id.bottomBar)
        counterLabel = findViewById(R.id.counterLabel)
        continueBtn = findViewById(R.id.continueBtn)
        loadingIndicator = findViewById(R.id.loadingIndicator)
        emptyView = findViewById(R.id.emptyView)
        permissionView = findViewById(R.id.permissionView)

        // Album chip tap → show album picker
        albumChip.setOnClickListener { showAlbumPicker() }

        // Search
        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                searchQuery = s?.toString() ?: ""
                reloadMedia()
            }
        })

        // Bottom bar
        continueBtn.setOnClickListener {
            if (selectedUris.isEmpty()) {
                Toast.makeText(this, "Selecione pelo menos um item", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            returnResults()
        }

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }

        // Grid
        val screenWidth = resources.displayMetrics.widthPixels
        val columns = DesignTokens.GRID_COLUMNS_MEDIA
        val spacing = DesignTokens.SPACE_2
        val itemSize = (screenWidth - (columns + 1) * spacing) / columns

        mediaAdapter = MediaGridAdapter(itemSize)
        gridView.numColumns = columns
        gridView.columnWidth = itemSize
        gridView.horizontalSpacing = spacing
        gridView.verticalSpacing = spacing
        gridView.setPadding(spacing, spacing, spacing, spacing)
        gridView.adapter = mediaAdapter

        gridView.setOnItemClickListener { _, view, position, _ ->
            if (position >= mediaItems.size) return@setOnItemClickListener
            val item = mediaItems[position]
            toggleSelection(item, view)
        }

        gridView.setOnItemLongClickListener { _, view, position, _ ->
            if (position >= mediaItems.size) return@setOnItemLongClickListener false
            val item = mediaItems[position]
            toggleSelection(item, view)
            true
        }
    }

    private fun applyDesignSystem() {
        window.navigationBarColor = DesignTokens.BG
        window.statusBarColor = DesignTokens.BG

        headerBar.setBackgroundColor(DesignTokens.BG)
        bottomBar.setBackgroundColor(DesignTokens.SURFACE_ELEVATED)
    }

    private fun setupImmersiveMode() {
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.pickerRoot)) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            headerBar.setPadding(bars.left, bars.top + DesignTokens.SPACE_2, bars.right, 0)
            bottomBar.setPadding(bars.left, DesignTokens.SPACE_3, bars.right, bars.bottom + DesignTokens.SPACE_3)
            insets
        }
    }

    private fun showMediaView() {
        permissionView.visibility = View.GONE
        loadingIndicator.visibility = View.VISIBLE
        gridView.visibility = View.VISIBLE
        bottomBar.visibility = View.VISIBLE
        albumChip.visibility = View.VISIBLE

        loaderManager.initLoader(LOADER_MEDIA, null, loaderCallbacks)
        updateCounter()
    }

    private fun showPermissionView() {
        permissionView.visibility = View.VISIBLE
        loadingIndicator.visibility = View.GONE
        gridView.visibility = View.GONE
        bottomBar.visibility = View.GONE
        albumChip.visibility = View.GONE

        findViewById<View>(R.id.btnGrantPermission).setOnClickListener {
            requestStoragePermission()
        }
    }

    private fun showEmptyView() {
        emptyView.visibility = View.VISIBLE
        gridView.visibility = View.GONE
    }

    // ── Media Loading ─────────────────────────────────────────────────────────

    private fun reloadMedia() {
        loaderManager.restartLoader(LOADER_MEDIA, null, loaderCallbacks)
    }

    private fun buildSelection(): String {
        val conditions = mutableListOf<String>()

        // Filter by type
        when (mode) {
            MODE_VIDEOS_ONLY -> conditions.add("${MediaStore.MediaColumns.MIME_TYPE} LIKE 'video/%'")
            MODE_IMAGES_ONLY -> conditions.add("${MediaStore.MediaColumns.MIME_TYPE} LIKE 'image/%'")
            else -> conditions.add("(${MediaStore.MediaColumns.MIME_TYPE} LIKE 'video/%' OR ${MediaStore.MediaColumns.MIME_TYPE} LIKE 'image/%')")
        }

        // Album filter
        if (currentAlbum != ALBUM_ALL) {
            conditions.add("${MediaStore.MediaColumns.BUCKET_DISPLAY_NAME} = ?")
        }

        // Search filter
        if (searchQuery.isNotBlank()) {
            conditions.add("${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?")
        }

        conditions.add("${MediaStore.MediaColumns.SIZE} > 0")

        return conditions.joinToString(" AND ")
    }

    private fun buildSelectionArgs(): Array<String> {
        val args = mutableListOf<String>()
        if (currentAlbum != ALBUM_ALL) args += currentAlbum
        if (searchQuery.isNotBlank()) args += "%$searchQuery%"
        return args.toTypedArray()
    }

    private fun shouldInclude(mime: String): Boolean {
        return when (mode) {
            MODE_VIDEOS_ONLY -> mime.startsWith("video/")
            MODE_IMAGES_ONLY -> mime.startsWith("image/")
            else -> mime.startsWith("video/") || mime.startsWith("image/")
        }
    }

    private val sortOrder: String
        get() = when (mode) {
            MODE_VIDEOS_ONLY -> "${MediaStore.Video.Media.DATE_ADDED}"
            MODE_IMAGES_ONLY -> "${MediaStore.Images.Media.DATE_ADDED}"
            else -> "${MediaStore.MediaColumns.DATE_ADDED}"
        }

    // ── Selection ─────────────────────────────────────────────────────────────

    private fun toggleSelection(item: MediaEntry, view: View) {
        val wasSelected = selectedUris.containsKey(item.id)

        if (!wasSelected && selectedUris.size >= maxSelection) {
            Toast.makeText(this, "Máximo de $maxSelection itens selecionados", Toast.LENGTH_SHORT).show()
            return
        }

        if (wasSelected) {
            selectedUris.remove(item.id)
            selectedTypes.remove(item.id)
        } else {
            selectedUris[item.id] = item.uri
            selectedTypes[item.id] = if (item.mime.startsWith("video/")) "video" else "image"
        }

        // Animate selection
        animateSelection(view, !wasSelected)
        updateCounter()
        mediaAdapter.notifyDataSetChanged()
    }

    private fun animateSelection(view: View, selected: Boolean) {
        val scale = if (selected) DesignTokens.SCALE_IN else DesignTokens.SCALE_OUT
        view.animate()
            .scaleX(scale)
            .scaleY(scale)
            .setDuration(DesignTokens.MOTION_SELECT)
            .withEndAction {
                view.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(DesignTokens.MOTION_PRESS)
                    .start()
            }
            .start()
    }

    private fun updateCounter() {
        val count = selectedUris.size
        if (count > 0) {
            counterLabel.text = "$count selecionado${if (count > 1) "s" else ""}"
            counterLabel.visibility = View.VISIBLE
            continueBtn.alpha = 1f
            continueBtn.isEnabled = true
        } else {
            counterLabel.text = "Nenhum item selecionado"
            continueBtn.alpha = 0.5f
            continueBtn.isEnabled = false
        }
    }

    // ── Album Picker ─────────────────────────────────────────────────────────

    private fun showAlbumPicker() {
        val albumList = listOf(
            AlbumEntry(ALBUM_ALL, "Todos")
        ) + albums

        val options = albumList.map { it.display }
        var selectedIdx = albumList.indexOfFirst { it.name == currentAlbum }.coerceAtLeast(0)

        val body = androidx.appcompat.app.AlertDialog.Builder(this, com.termex.replay15.editor.design.SurfaceTokens.dialogStyle(this))
            .setTitle("Álbum")
            .setSingleChoiceItems(
                options.map { it }.toTypedArray(),
                selectedIdx
            ) { dialog, which ->
                selectedIdx = which
            }
            .setPositiveButton("Selecionar") { _, _ ->
                currentAlbum = albumList[selectedIdx].name
                albumChip.text = albumList[selectedIdx].display
                reloadMedia()
            }
            .setNegativeButton("Cancelar", null)
            .create()

        body.window?.setGravity(Gravity.BOTTOM)
        body.show()
    }

    // ── Results ───────────────────────────────────────────────────────────────

    private fun returnResults() {
        val uris = selectedUris.values.toList()
        val types = selectedTypes.values.toList()

        val result = Intent().apply {
            putExtra(RESULT_URIS, uris.map { it.toString() }.toTypedArray())
            putExtra(RESULT_TYPES, types.toTypedArray())
        }
        setResult(Activity.RESULT_OK, result)
        finish()
    }

    // ── Serialization ─────────────────────────────────────────────────────────

    private fun serializeUris(uris: Collection<Uri>): String =
        uris.joinToString("|") { it.toString() }

    private fun deserializeUris(data: String): List<Uri> =
        if (data.isBlank()) emptyList()
        else data.split("|").mapNotNull { runCatching { Uri.parse(it) }.getOrNull() }

    // ── Permission Request ───────────────────────────────────────────────────

    private fun requestStoragePermission() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_IMAGES,
            )
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        permissionLauncher.launch(permissions)
    }

    // ── Data Models ───────────────────────────────────────────────────────────

    data class MediaEntry(
        val id: Long,
        val uri: Uri,
        val name: String,
        val mime: String,
        val duration: Long,
        val size: Long,
        val album: String,
        val dateAdded: Long,
    ) {
        val isVideo: Boolean get() = mime.startsWith("video/")
    }

    data class AlbumEntry(val name: String, val display: String)
}

// ── Media Grid Adapter ────────────────────────────────────────────────────────

class MediaGridAdapter(private val itemSize: Int) : BaseAdapter() {

    private val executor = Executors.newFixedThreadPool(4)
    private val cache = object : LinkedHashMap<Long, Bitmap>(50, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Bitmap>): Boolean {
            if (size > 200) {
                eldest.value.recycle()
                return true
            }
            return false
        }
    }
    private val loadingPlaceholders = mutableSetOf<Long>()

    override fun getCount(): Int = mediaItems.size
    override fun getItem(position: Int): ReclyMediaPicker.MediaEntry? = mediaItems.getOrNull(position)
    override fun getItemId(position: Int): Long = mediaItems.getOrNull(position)?.id ?: 0

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val item = mediaItems.getOrNull(position) ?: return convertView ?: createCell(parent)
        val cell = convertView ?: createCell(parent)
        cell.tag = item.id

        val thumb = cell.findViewById<ImageView>(R.id.thumbImage)
        val overlay = cell.findViewById<View>(R.id.selectionOverlay)
        val checkmark = cell.findViewById<ImageView>(R.id.checkmark)
        val duration = cell.findViewById<TextView>(R.id.durationLabel)
        val videoIcon = cell.findViewById<ImageView>(R.id.videoIcon)

        // Selection state
        val isSelected = selectedUris.containsKey(item.id)
        overlay.visibility = if (isSelected) View.VISIBLE else View.GONE
        checkmark.visibility = if (isSelected) View.VISIBLE else View.GONE

        // Video indicators
        if (item.isVideo) {
            duration.visibility = View.VISIBLE
            duration.text = formatDuration(item.duration)
            videoIcon.visibility = View.VISIBLE
        } else {
            duration.visibility = View.GONE
            videoIcon.visibility = View.GONE
        }

        // Load thumbnail
        thumb.setImageDrawable(null)
        loadThumbnail(item.id, item.uri, thumb)

        return cell
    }

    private fun createCell(parent: ViewGroup): View {
        return android.view.LayoutInflater.from(parent.context)
            .inflate(R.layout.recly_media_picker_item, parent, false).apply {
                layoutParams = android.widget.AbsListView.LayoutParams(itemSize, itemSize)
            }
    }

    private fun loadThumbnail(id: Long, uri: Uri, target: ImageView) {
        cache[id]?.let {
            target.setImageBitmap(it)
            return
        }

        if (id in loadingPlaceholders) return
        loadingPlaceholders.add(id)

        executor.execute {
            try {
                val bitmap = target.context.contentResolver.loadThumbnail(
                    uri,
                    android.util.Size(itemSize * 2, itemSize * 2),
                    null
                )
                synchronized(cache) { cache[id] = bitmap }
                target.context.mainExecutor.execute {
                    loadingPlaceholders.remove(id)
                    if (target.tag == id) {
                        target.setImageBitmap(bitmap)
                    }
                }
            } catch (e: Exception) {
                loadingPlaceholders.remove(id)
            }
        }
    }

    private fun formatDuration(durationMs: Long): String {
        val totalSec = (durationMs / 1000).coerceAtLeast(0)
        val min = totalSec / 60
        val sec = totalSec % 60
        return String.format(java.util.Locale.ROOT, "%d:%02d", min, sec)
    }

    private var selectedUris: LinkedHashMap<Long, Uri> = LinkedHashMap()

    fun updateSelection(uris: LinkedHashMap<Long, Uri>) {
        selectedUris = uris
        notifyDataSetChanged()
    }

    private var mediaItems: MutableList<ReclyMediaPicker.MediaEntry> = mutableListOf()

    fun updateItems(items: MutableList<ReclyMediaPicker.MediaEntry>) {
        mediaItems = items
        notifyDataSetChanged()
    }
}
