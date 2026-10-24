package com.haseeb.recorder.ui.activity

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.text.format.DateFormat
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.signature.ObjectKey
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.haseeb.recorder.R
import com.haseeb.recorder.databinding.ActivityRawVideosBinding
import com.haseeb.recorder.databinding.DialogEditTextBinding
import com.haseeb.recorder.databinding.ItemRawVideoBinding
import com.haseeb.recorder.recording.RawRecordings
import com.haseeb.recorder.remux.RemuxService
import com.haseeb.recorder.util.AppLog
import com.haseeb.recorder.util.LocaleHelper
import com.haseeb.recorder.util.applyBottomInsets
import com.haseeb.recorder.util.applySystemBarInsets
import com.haseeb.recorder.util.applyTopInsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Date

/*
 * Lists recordings made in "staged" mode (safe MKV files that are not MP4 yet).
 * From here the user can watch them, rename, delete, or convert them to MP4 in the background.
 */
class RawVideosActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRawVideosBinding
    private val adapter = RawVideoAdapter()

    private var convertingFile: File? = null
    private var progressDialog: AlertDialog? = null
    private var progressText: TextView? = null
    private var progressBar: LinearProgressIndicator? = null

    private data class RawVideo(val file: File, val durationMs: Long)

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrapContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRawVideosBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.root.applyTopInsets()
        binding.appBarLayout.applySystemBarInsets()
        binding.recyclerView.applyBottomInsets()
        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter
    }

    override fun onResume() {
        super.onResume()
        AppLog.d("RawVideos", "Screen opened")
        RemuxService.listener = { percent, eta, finished, success -> onRemuxUpdate(percent, eta, finished, success) }
        loadVideos()
        if (RemuxService.isRunning && progressDialog == null) {
            val name = RemuxService.currentName ?: ""
            if (convertingFile == null && name.isNotEmpty()) {
                convertingFile = File(RawRecordings.dir(this), "$name.${RawRecordings.EXTENSION}")
            }
            showProgressDialog(name)
        }
    }

    override fun onPause() {
        super.onPause()
        RemuxService.listener = null
        dismissProgressDialog()
    }

    // ---------------------------------------------------------------- list

    private fun loadVideos() {
        lifecycleScope.launch(Dispatchers.IO) {
            val items = RawRecordings.list(this@RawVideosActivity).map { RawVideo(it, readDuration(it)) }
            withContext(Dispatchers.Main) {
                adapter.submit(items)
                binding.emptyText.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    private fun readDuration(file: File): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (_: Exception) {
            0L
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
    }

    // ---------------------------------------------------------------- actions

    private fun play(file: File) {
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.logs", file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "video/x-matroska")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, getString(R.string.raw_toast_no_player), Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            AppLog.w("RawVideos", "Could not open the video", e)
            Toast.makeText(this, getString(R.string.raw_toast_no_player), Toast.LENGTH_LONG).show()
        }
    }

    private fun showRenameDialog(video: RawVideo) {
        val dialogBinding = DialogEditTextBinding.inflate(LayoutInflater.from(this))
        dialogBinding.editText.setText(video.file.nameWithoutExtension)
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.VideoAdapter_dialog_rename_title))
            .setView(dialogBinding.root)
            .setPositiveButton(getString(R.string.VideoAdapter_btn_rename)) { _, _ ->
                val newName = dialogBinding.editText.text.toString().trim()
                val valid = newName.isNotEmpty() && !newName.startsWith(".") && !newName.contains('/') && !newName.contains('\\')
                val target = File(video.file.parentFile, "$newName.${RawRecordings.EXTENSION}")
                if (!valid || (target.exists() && target != video.file) || !video.file.renameTo(target)) {
                    Toast.makeText(this, getString(R.string.VideoAdapter_toast_rename_failed), Toast.LENGTH_SHORT).show()
                } else {
                    RawRecordings.renameConverted(this, video.file.name, target.name)
                    loadVideos()
                }
            }
            .setNegativeButton(getString(R.string.VideoAdapter_btn_cancel), null)
            .show()
    }

    private fun showDeleteDialog(video: RawVideo) {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.raw_dialog_delete_title))
            .setMessage(
                getString(
                    if (RawRecordings.isConverted(this, video.file.name)) R.string.raw_dialog_delete_msg_converted
                    else R.string.raw_dialog_delete_msg
                )
            )
            .setPositiveButton(getString(R.string.VideoAdapter_btn_delete)) { _, _ ->
                deleteOriginal(video.file)
            }
            .setNegativeButton(getString(R.string.VideoAdapter_btn_cancel), null)
            .show()
    }

    /*
     * Warns about long videos first, then starts the background conversion.
     */
    private fun confirmConvert(video: RawVideo) {
        if (RemuxService.isRunning) {
            Toast.makeText(this, getString(R.string.raw_toast_busy), Toast.LENGTH_LONG).show()
            return
        }
        val isLong = video.durationMs >= 30 * 60 * 1000L || video.file.length() >= 800L * 1024 * 1024
        if (!isLong) {
            startConversion(video)
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.raw_dialog_long_title))
            .setMessage(getString(R.string.raw_dialog_long_msg))
            .setPositiveButton(getString(R.string.raw_btn_convert)) { _, _ -> startConversion(video) }
            .setNegativeButton(getString(R.string.VideoAdapter_btn_cancel), null)
            .show()
    }

    private fun startConversion(video: RawVideo) {
        try {
            convertingFile = video.file
            RemuxService.start(this, video.file)
            showProgressDialog(video.file.nameWithoutExtension)
        } catch (e: Exception) {
            AppLog.e("RawVideos", "Could not start the conversion", e)
            Toast.makeText(this, getString(R.string.raw_toast_convert_failed), Toast.LENGTH_LONG).show()
        }
    }

    private fun deleteOriginal(file: File) {
        file.delete()
        RawRecordings.forgetConverted(this, file.name)
        loadVideos()
    }

    /*
     * After a successful conversion, offers to free the space used by the original MKV.
     */
    private fun askDeleteOriginal(file: File) {
        if (isFinishing || isDestroyed) return
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.raw_dialog_converted_title))
            .setMessage(getString(R.string.raw_dialog_converted_msg))
            .setPositiveButton(getString(R.string.raw_btn_delete_original)) { _, _ -> deleteOriginal(file) }
            .setNegativeButton(getString(R.string.raw_btn_keep), null)
            .show()
    }

    // ---------------------------------------------------------------- progress dialog

    private fun showProgressDialog(name: String) {
        if (progressDialog != null) return
        val pad = (24 * resources.displayMetrics.density).toInt()
        val text = TextView(this).apply { this.text = getString(R.string.RemuxService_working) }
        val bar = LinearProgressIndicator(this).apply { isIndeterminate = true }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(text)
            addView(
                bar,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { topMargin = pad / 2 }
            )
        }
        progressText = text
        progressBar = bar
        progressDialog = MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.raw_progress_title, name))
            .setView(content)
            .setPositiveButton(getString(R.string.raw_btn_background), null)
            .setNegativeButton(getString(R.string.raw_btn_cancel_conversion)) { _, _ -> RemuxService.cancel(this) }
            .create()
            .also {
                it.show()
                it.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
    }

    private fun dismissProgressDialog() {
        try { progressDialog?.dismiss() } catch (_: Exception) {}
        progressDialog = null
        progressText = null
        progressBar = null
    }

    private fun onRemuxUpdate(percent: Int, etaSeconds: Long, finished: Boolean, success: Boolean) {
        if (finished) {
            dismissProgressDialog()
            loadVideos()
            val converted = convertingFile
            convertingFile = null
            if (success && converted != null) {
                askDeleteOriginal(converted)
            } else {
                Toast.makeText(this, getString(R.string.raw_toast_convert_stopped), Toast.LENGTH_LONG).show()
            }
            return
        }
        if (percent >= 0) {
            progressBar?.setProgressCompat(percent, true)
            progressText?.text = if (etaSeconds >= 0) {
                getString(R.string.RemuxService_progress_eta, percent, formatEta(etaSeconds))
            } else {
                "$percent%"
            }
        }
    }

    private fun formatEta(seconds: Long): String {
        val minutes = seconds / 60
        return if (minutes >= 1) {
            getString(R.string.RemuxService_eta_minutes, minutes + 1)
        } else {
            getString(R.string.RemuxService_eta_seconds, seconds.coerceAtLeast(1))
        }
    }

    // ---------------------------------------------------------------- adapter

    private inner class RawVideoAdapter : RecyclerView.Adapter<RawVideoAdapter.Holder>() {

        private var items: List<RawVideo> = emptyList()

        inner class Holder(val b: ItemRawVideoBinding) : RecyclerView.ViewHolder(b.root)

        fun submit(newItems: List<RawVideo>) {
            items = newItems
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(ItemRawVideoBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val video = items[position]
            val b = holder.b
            val isActive = video.file.absolutePath == RawRecordings.activePath

            b.titleText.text = video.file.nameWithoutExtension
            val size = Formatter.formatShortFileSize(this@RawVideosActivity, video.file.length())
            val date = DateFormat.getMediumDateFormat(this@RawVideosActivity).format(Date(video.file.lastModified())) +
                " " + DateFormat.getTimeFormat(this@RawVideosActivity).format(Date(video.file.lastModified()))
            val baseSubtitle = if (isActive) {
                getString(R.string.raw_item_recording_now)
            } else {
                getString(R.string.raw_item_subtitle, size, date)
            }
            b.subtitleText.text = if (!isActive && RawRecordings.isConverted(this@RawVideosActivity, video.file.name)) {
                baseSubtitle + " • " + getString(R.string.raw_item_converted)
            } else {
                baseSubtitle
            }

            if (video.durationMs > 0 && !isActive) {
                b.durationCard.visibility = View.VISIBLE
                b.durationText.text = formatDuration(video.durationMs)
            } else {
                b.durationCard.visibility = View.GONE
            }
            if (isActive) {
                b.thumbnail.setImageResource(R.drawable.ic_screen_record)
            } else {
                Glide.with(b.thumbnail)
                    .load(Uri.fromFile(video.file))
                    .signature(ObjectKey("${video.file.lastModified()}-${video.file.length()}"))
                    .centerCrop()
                    .error(R.drawable.ic_screen_record)
                    .into(b.thumbnail)
            }

            b.btnMenu.visibility = if (isActive) View.GONE else View.VISIBLE
            b.cardRoot.isEnabled = !isActive
            b.cardRoot.setOnClickListener { if (!isActive) play(video.file) }
            b.btnMenu.setOnClickListener { anchor ->
                PopupMenu(anchor.context, anchor).apply {
                    menuInflater.inflate(R.menu.raw_video_options_menu, menu)
                    setOnMenuItemClickListener { item ->
                        when (item.itemId) {
                            R.id.action_convert -> { confirmConvert(video); true }
                            R.id.action_rename -> { showRenameDialog(video); true }
                            R.id.action_delete -> { showDeleteDialog(video); true }
                            else -> false
                        }
                    }
                    show()
                }
            }
        }

        private fun formatDuration(ms: Long): String {
            val totalSeconds = ms / 1000
            val h = totalSeconds / 3600
            val m = (totalSeconds % 3600) / 60
            val s = totalSeconds % 60
            return if (h > 0) String.format("%d:%02d:%02d", h, m, s) else String.format("%d:%02d", m, s)
        }
    }
}
