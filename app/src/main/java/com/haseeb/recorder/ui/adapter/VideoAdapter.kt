package com.haseeb.recorder.ui.adapter

import android.app.RecoverableSecurityException
import android.content.ContentValues
import android.content.Intent
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import com.bumptech.glide.Glide
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.listitem.ListItemViewHolder
import com.haseeb.recorder.compress.CompressionService
import com.haseeb.recorder.util.VideoCompressor
import com.haseeb.recorder.R
import com.haseeb.recorder.data.VideoFile
import com.haseeb.recorder.databinding.DialogEditTextBinding
import com.haseeb.recorder.databinding.ItemVideoBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/*
 * Adapter for managing and displaying video files in the recordings list.
 */
class VideoAdapter : ListAdapter<VideoFile, ListItemViewHolder>(VideoDiffCallback()) {
    companion object {
        const val REQUEST_DELETE_VIDEO = 1001
        const val REQUEST_RENAME_VIDEO = 1002
    }

    /*
     * Inflates the item layout and creates the ListItemViewHolder.
     */
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ListItemViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_video, parent, false)
        return ListItemViewHolder(view)
    }

    /*
     * Binds video data and sets up click handlers on list item views.
     */
    override fun onBindViewHolder(holder: ListItemViewHolder, position: Int) {
        val video = getItem(position)
        holder.bind(position, itemCount)

        val binding = ItemVideoBinding.bind(holder.itemView)
        bindMetadata(binding, video)
        
        binding.root.setOnClickListener { openVideo(it.context, video.uri) }
        binding.btnMenu.setOnClickListener { showPopupMenu(it, video) }
    }

    /*
     * Refreshes item shapes when list items are modified or removed.
     */
    override fun onCurrentListChanged(previousList: MutableList<VideoFile>, currentList: MutableList<VideoFile>) {
        super.onCurrentListChanged(previousList, currentList)
        notifyItemRangeChanged(0, itemCount, "shape_refresh")
    }

    /*
     * Updates title, duration, date, size, quality chip, and thumbnail for the video item.
     */
    private fun bindMetadata(binding: ItemVideoBinding, video: VideoFile) {
        binding.titleText.text = video.name.removeSuffix(".mp4")

        val sizeMb = video.size / (1024f * 1024f)
        val duration = String.format(Locale.US, "%02d:%02d", (video.duration / 1000) / 60, (video.duration / 1000) % 60)
        val date = SimpleDateFormat("MMM d, yyyy", Locale.US).format(Date(video.dateAdded * 1000L))

        binding.durationText.text = duration
        binding.tvDate.text = date
        binding.tvSize.text = String.format(Locale.US, "%.1f MB", sizeMb)

        if (!video.qualityFps.isNullOrEmpty()) {
            binding.cardQualityFps.visibility = android.view.View.VISIBLE
            binding.tvQualityFps.text = video.qualityFps
        } else {
            binding.cardQualityFps.visibility = android.view.View.GONE
        }

        Glide.with(binding.root)
            .load(video.uri)
            .centerCrop()
            .error(R.drawable.ic_screen_record)
            .into(binding.thumbnail)
    }

    /*
     * Opens the video file in an external media player application.
     */
    private fun openVideo(context: android.content.Context, uri: Uri) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "video/mp4")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(context, context.getString(R.string.VideoAdapter_toast_no_player), Toast.LENGTH_SHORT).show()
        }
    }

    /*
     * Displays options popup menu for rename, share, or delete actions.
     */
    private fun showPopupMenu(view: android.view.View, video: VideoFile) {
        PopupMenu(view.context, view).apply {
            menuInflater.inflate(R.menu.video_options_menu, menu)
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    R.id.action_rename -> { showRenameDialog(video, view.context); true }
                    R.id.action_share -> { shareVideo(video, view.context); true }
                    R.id.action_compress -> { showCompressDialog(video, view.context); true }
                    R.id.action_delete -> { showDeleteDialog(video, view.context); true }
                    else -> false
                }
            }
            show()
        }
    }

    /*
     * Displays a dialog to rename the selected recording file.
     */
    /* Invoked after a change that MediaStore may not announce (e.g. a renamed file in a custom folder). */
    var onListChanged: (() -> Unit)? = null

    private var pendingRename: Pair<VideoFile, String>? = null

    /*
     * Renames any video, including ones owned by a previous install (asks the user for access when needed).
     */
    private fun renameVideo(video: VideoFile, newBase: String, context: android.content.Context) {
        val fileName = "$newBase.mp4"
        try {
            if (video.uri.authority == MediaStore.AUTHORITY) {
                val values = ContentValues().apply { put(MediaStore.Video.Media.DISPLAY_NAME, fileName) }
                val rows = context.contentResolver.update(video.uri, values, null, null)
                if (rows <= 0) throw SecurityException("Not allowed to update this video")
            } else {
                android.provider.DocumentsContract.renameDocument(context.contentResolver, video.uri, fileName)
                    ?: throw IllegalStateException("Rename failed")
                com.haseeb.recorder.data.ConfigManager(context).addKnownVideoName(fileName)
                onListChanged?.invoke()
            }
        } catch (e: SecurityException) {
            if (!requestRenameAccess(video, newBase, e, context)) showRenameFailedToast(context)
        } catch (e: Exception) {
            showRenameFailedToast(context)
        }
    }

    private fun requestRenameAccess(
        video: VideoFile,
        newBase: String,
        error: SecurityException,
        context: android.content.Context
    ): Boolean {
        val activity = context as? AppCompatActivity ?: return false
        return try {
            val sender: IntentSender = when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ->
                    MediaStore.createWriteRequest(context.contentResolver, listOf(video.uri)).intentSender
                Build.VERSION.SDK_INT == Build.VERSION_CODES.Q && error is android.app.RecoverableSecurityException ->
                    error.userAction.actionIntent.intentSender
                else -> return false
            }
            pendingRename = Pair(video, newBase)
            activity.startIntentSenderForResult(sender, REQUEST_RENAME_VIDEO, null, 0, 0, 0)
            true
        } catch (e: Exception) {
            false
        }
    }

    /*
     * Finishes a rename after the user allowed the app to modify the video.
     */
    fun completePendingRename(context: android.content.Context) {
        val pending = pendingRename ?: return
        pendingRename = null
        renameVideo(pending.first, pending.second, context)
    }

    private fun showRenameFailedToast(context: android.content.Context) {
        Toast.makeText(context, context.getString(R.string.VideoAdapter_toast_rename_failed), Toast.LENGTH_SHORT).show()
    }

    private fun showRenameDialog(video: VideoFile, context: android.content.Context) {
        val binding = DialogEditTextBinding.inflate(LayoutInflater.from(context))
        binding.editText.setText(video.name.removeSuffix(".mp4"))

        MaterialAlertDialogBuilder(context)
            .setTitle(context.getString(R.string.VideoAdapter_dialog_rename_title))
            .setView(binding.root)
            .setPositiveButton(context.getString(R.string.VideoAdapter_btn_rename)) { _, _ ->
                val newName = binding.editText.text.toString().trim()
                if (newName.isNotEmpty()) renameVideo(video, newName, context)
            }
            .setNegativeButton(context.getString(R.string.VideoAdapter_btn_cancel), null)
            .show()
    }

    /**
     * Starts the system sharing chooser for the video.
     * Includes grant flags for the content URI.
     */
    private fun shareVideo(video: VideoFile, context: android.content.Context) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "video/mp4"
            putExtra(Intent.EXTRA_STREAM, video.uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, context.getString(R.string.VideoAdapter_chooser_share)))
    }

    /**
     * Displays a confirmation dialog before deleting the video.
     * Handles MediaStore deletion for various Android versions.
     */
    private fun showDeleteDialog(video: VideoFile, context: android.content.Context) {
        MaterialAlertDialogBuilder(context)
            .setTitle(context.getString(R.string.VideoAdapter_dialog_delete_title))
            .setMessage(context.getString(R.string.VideoAdapter_dialog_delete_msg))
            .setPositiveButton(context.getString(R.string.VideoAdapter_btn_delete)) { _, _ ->
                deleteVideo(video, context)
            }
            .setNegativeButton(context.getString(R.string.VideoAdapter_btn_cancel), null)
            .show()
    }

    private fun deleteVideo(video: VideoFile, context: android.content.Context) {
        try {
            val rowsDeleted = context.contentResolver.delete(video.uri, null, null)
            if (rowsDeleted <= 0) requestSystemDelete(video, context)
        } catch (securityException: SecurityException) {
            val requestStarted = requestSystemDeleteAfterSecurityException(securityException, video, context)
            if (!requestStarted) showDeleteFailedToast(context)
        } catch (e: Exception) {
            showDeleteFailedToast(context)
        }
    }

    private fun requestSystemDeleteAfterSecurityException(
        securityException: SecurityException,
        video: VideoFile,
        context: android.content.Context
    ): Boolean {
        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> requestSystemDelete(video, context)
            Build.VERSION.SDK_INT == Build.VERSION_CODES.Q && securityException is RecoverableSecurityException -> {
                launchIntentSender(context, securityException.userAction.actionIntent.intentSender)
            }
            else -> false
        }
    }

    private fun requestSystemDelete(video: VideoFile, context: android.content.Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false

        return try {
            val pendingIntent = MediaStore.createDeleteRequest(context.contentResolver, listOf(video.uri))
            launchIntentSender(context, pendingIntent.intentSender)
        } catch (e: Exception) {
            false
        }
    }

    private fun launchIntentSender(context: android.content.Context, intentSender: IntentSender): Boolean {
        val activity = context as? AppCompatActivity ?: return false
        return try {
            activity.startIntentSenderForResult(intentSender, REQUEST_DELETE_VIDEO, null, 0, 0, 0)
            true
        } catch (e: IntentSender.SendIntentException) {
            false
        }
    }

    private fun showDeleteFailedToast(context: android.content.Context) {
        Toast.makeText(context, context.getString(R.string.VideoAdapter_toast_delete_failed), Toast.LENGTH_SHORT).show()
    }

    /*
     * Lets the user pick a compression strength for the selected recording.
     */
    private fun showCompressDialog(video: VideoFile, context: android.content.Context) {
        val labels = arrayOf(
            context.getString(R.string.VideoAdapter_compress_preset_light),
            context.getString(R.string.VideoAdapter_compress_preset_balanced),
            context.getString(R.string.VideoAdapter_compress_preset_maximum)
        )
        var selected = 1
        MaterialAlertDialogBuilder(context)
            .setTitle(context.getString(R.string.VideoAdapter_compress_title))
            .setSingleChoiceItems(labels, selected) { _, which -> selected = which }
            .setPositiveButton(context.getString(R.string.VideoAdapter_compress_btn_start)) { _, _ ->
                startCompression(video, VideoCompressor.Preset.values()[selected], context)
            }
            .setNegativeButton(context.getString(R.string.VideoAdapter_btn_cancel), null)
            .show()
    }

    /*
     * Starts the background compression service and tells the user where to follow the progress.
     */
    private fun startCompression(video: VideoFile, preset: VideoCompressor.Preset, context: android.content.Context) {
        if (CompressionService.isRunning) {
            Toast.makeText(context, context.getString(R.string.VideoAdapter_compress_toast_busy), Toast.LENGTH_LONG).show()
            return
        }
        try {
            CompressionService.start(context, video.uri, video.name, video.size, video.duration, preset)
            Toast.makeText(context, context.getString(R.string.VideoAdapter_compress_toast_started), Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(context, context.getString(R.string.VideoAdapter_compress_toast_failed), Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Callback for calculating list differences.
     * Allows ListAdapter to update items efficiently with animations.
     */
    private class VideoDiffCallback : DiffUtil.ItemCallback<VideoFile>() {
        override fun areItemsTheSame(oldItem: VideoFile, newItem: VideoFile) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: VideoFile, newItem: VideoFile) = oldItem == newItem
    }
}
