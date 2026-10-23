package com.xera.ptzcam

import android.Manifest
import android.app.RecoverableSecurityException
import android.content.ActivityNotFoundException
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.AttributeSet
import android.util.LruCache
import android.util.Size
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Square cell for the gallery grid. */
class SquareFrameLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, widthMeasureSpec)
    }
}

/** Photos (Pictures/XeraCAM) and recordings (Movies/XeraCAM) taken with the app. */
class GalleryActivity : AppCompatActivity() {

    data class Item(
        val uri: Uri,
        val name: String,
        val isVideo: Boolean,
        val durationMs: Long,
        val dateSec: Long,
    )

    private lateinit var grid: RecyclerView
    private lateinit var empty: TextView
    private lateinit var count: TextView
    private lateinit var tabPhotos: TextView
    private lateinit var tabVideos: TextView

    private var showVideos = false
    private var loadJob: Job? = null
    private val adapter = MediaAdapter()
    private val thumbs = LruCache<Uri, Bitmap>(80)

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { load() }

    private val deleteLauncher =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { load() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_gallery)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        grid = findViewById(R.id.galleryGrid)
        empty = findViewById(R.id.galleryEmpty)
        count = findViewById(R.id.galleryCount)
        tabPhotos = findViewById(R.id.tabPhotos)
        tabVideos = findViewById(R.id.tabVideos)

        findViewById<ImageButton>(R.id.galleryBack).setOnClickListener { finish() }
        tabPhotos.setOnClickListener { selectTab(false) }
        tabVideos.setOnClickListener { selectTab(true) }

        grid.layoutManager = GridLayoutManager(this, 5)
        grid.adapter = adapter

        showVideos = intent.getBooleanExtra(EXTRA_VIDEOS, false)
        selectTab(showVideos)
        requestPermissionsIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    private fun selectTab(videos: Boolean) {
        showVideos = videos
        tabPhotos.isSelected = !videos
        tabVideos.isSelected = videos
        load()
    }

    private fun requestPermissionsIfNeeded() {
        val perms = if (Build.VERSION.SDK_INT >= 33) {
            arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        val missing = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) permLauncher.launch(missing.toTypedArray())
    }

    private fun load() {
        val videos = showVideos
        loadJob?.cancel()                      // a newer request replaces any load still running
        loadJob = lifecycleScope.launch {
            val items = withContext(Dispatchers.IO) { runCatching { query(videos) }.getOrDefault(emptyList()) }
            if (videos != showVideos) return@launch
            adapter.submit(items)
            count.text = "${items.size} ${if (videos) "video" else "photo"}${if (items.size == 1) "" else "s"}"
            empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
            empty.text = if (videos) "No recordings yet\nUse REC on the main screen"
            else "No photos yet\nUse PHOTO on the main screen"
        }
    }

    private fun query(videos: Boolean): List<Item> {
        val collection = if (videos) MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val folder = if (videos) "Movies/XeraCAM/" else "Pictures/XeraCAM/"
        val projection = mutableListOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.DATE_ADDED,
        )
        if (videos) projection.add(MediaStore.Video.Media.DURATION)

        val out = ArrayList<Item>()
        contentResolver.query(
            collection,
            projection.toTypedArray(),
            "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
            arrayOf("$folder%"),
            "${MediaStore.MediaColumns.DATE_ADDED} DESC",
        )?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val dateCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
            val durCol = if (videos) c.getColumnIndex(MediaStore.Video.Media.DURATION) else -1
            while (c.moveToNext()) {
                val id = c.getLong(idCol)
                out.add(
                    Item(
                        uri = ContentUris.withAppendedId(collection, id),
                        name = c.getString(nameCol) ?: "",
                        isVideo = videos,
                        durationMs = if (durCol >= 0) c.getLong(durCol) else 0L,
                        dateSec = c.getLong(dateCol),
                    )
                )
            }
        }
        return out
    }

    private fun open(item: Item) {
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(item.uri, if (item.isVideo) "video/*" else "image/*")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "No app found to open this file", Toast.LENGTH_SHORT).show()
        }
    }

    private fun share(item: Item) {
        val send = Intent(Intent.ACTION_SEND)
            .setType(if (item.isVideo) "video/mp4" else "image/jpeg")
            .putExtra(Intent.EXTRA_STREAM, item.uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(send, "Share ${item.name}"))
    }

    private fun delete(item: Item) {
        try {
            contentResolver.delete(item.uri, null, null)
            thumbs.remove(item.uri)
            load()
        } catch (e: SecurityException) {
            val sender = when {
                Build.VERSION.SDK_INT >= 30 ->
                    MediaStore.createDeleteRequest(contentResolver, listOf(item.uri)).intentSender
                e is RecoverableSecurityException -> e.userAction.actionIntent.intentSender
                else -> null
            }
            if (sender != null) deleteLauncher.launch(IntentSenderRequest.Builder(sender).build())
            else Toast.makeText(this, "Cannot delete this file", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showActions(item: Item) {
        val date = SimpleDateFormat("dd MMM yyyy, HH:mm:ss", Locale.getDefault()).format(Date(item.dateSec * 1000))
        AlertDialog.Builder(this, R.style.Dialog_XeraCAM)
            .setTitle(item.name)
            .setMessage(date)
            .setPositiveButton("Share") { _, _ -> share(item) }
            .setNegativeButton("Delete") { _, _ ->
                AlertDialog.Builder(this, R.style.Dialog_XeraCAM)
                    .setMessage("Delete ${item.name}?")
                    .setPositiveButton("Delete") { _, _ -> delete(item) }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
            .setNeutralButton("Open") { _, _ -> open(item) }
            .show()
    }

    private fun formatDuration(ms: Long): String {
        val s = ms / 1000
        return if (s >= 3600) String.format(Locale.US, "%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60)
        else String.format(Locale.US, "%d:%02d", s / 60, s % 60)
    }

    // ------------------------------------------------------------------ adapter

    private inner class MediaAdapter : RecyclerView.Adapter<MediaHolder>() {
        private var items: List<Item> = emptyList()

        fun submit(list: List<Item>) {
            items = list
            @Suppress("NotifyDataSetChanged")
            notifyDataSetChanged()
        }

        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MediaHolder =
            MediaHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_media, parent, false))

        override fun onBindViewHolder(holder: MediaHolder, position: Int) = holder.bind(items[position])
    }

    private inner class MediaHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val img: ImageView = view.findViewById(R.id.thumb)
        private val play: View = view.findViewById(R.id.playIcon)
        private val dur: TextView = view.findViewById(R.id.duration)
        private var job: Job? = null

        init {
            view.clipToOutline = true
        }

        fun bind(item: Item) {
            play.visibility = if (item.isVideo) View.VISIBLE else View.GONE
            dur.visibility = if (item.isVideo && item.durationMs > 0) View.VISIBLE else View.GONE
            dur.text = formatDuration(item.durationMs)
            itemView.setOnClickListener { open(item) }
            itemView.setOnLongClickListener { showActions(item); true }

            job?.cancel()
            val cached = thumbs.get(item.uri)
            if (cached != null) {
                img.setImageBitmap(cached)
                return
            }
            img.setImageDrawable(null)
            job = lifecycleScope.launch {
                val bmp = withContext(Dispatchers.IO) {
                    runCatching { contentResolver.loadThumbnail(item.uri, Size(360, 360), null) }.getOrNull()
                }
                if (bmp != null) {
                    thumbs.put(item.uri, bmp)
                    img.setImageBitmap(bmp)
                }
            }
        }
    }

    companion object {
        const val EXTRA_VIDEOS = "videos"
    }
}
