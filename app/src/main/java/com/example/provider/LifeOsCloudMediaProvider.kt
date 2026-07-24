package com.example.provider

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Point
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.CloudMediaProvider
import android.provider.CloudMediaProviderContract
import android.util.Log
import androidx.annotation.RequiresApi
import java.io.File
import java.io.FileOutputStream

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
class LifeOsCloudMediaProvider : CloudMediaProvider() {

    companion object {
        private const val TAG = "LifeOsCloudMedia"
        private const val MEDIA_COLLECTION_ID = "lifeos_cloud_collection_v1"
        private const val CURRENT_SYNC_GENERATION = 100L

        // Sample Cloud Items
        data class CloudMediaItem(
            val id: String,
            val mimeType: String,
            val dateTakenMillis: Long,
            val sizeBytes: Long,
            val width: Int,
            val height: Int,
            val durationMillis: Long = 0L,
            val isFavorite: Boolean = false,
            val albumId: String? = null,
            val isDeleted: Boolean = false
        )

        data class CloudAlbum(
            val id: String,
            val displayName: String,
            val dateTakenMillis: Long,
            val mediaCount: Int,
            val coverId: String
        )
    }

    private val cloudMediaItems = mutableListOf<CloudMediaItem>()
    private val cloudAlbums = mutableListOf<CloudAlbum>()

    override fun onCreate(): Boolean {
        Log.d(TAG, "Initializing LifeOsCloudMediaProvider")
        initSampleData()
        return true
    }

    private fun initSampleData() {
        val now = System.currentTimeMillis()

        cloudAlbums.add(
            CloudAlbum(
                id = "album_vacation",
                displayName = "Vacation 2026",
                dateTakenMillis = now - 86400000L * 5,
                mediaCount = 3,
                coverId = "cloud_img_1"
            )
        )
        cloudAlbums.add(
            CloudAlbum(
                id = "album_favorites",
                displayName = "Starred Memories",
                dateTakenMillis = now - 86400000L * 2,
                mediaCount = 2,
                coverId = "cloud_img_2"
            )
        )

        cloudMediaItems.add(
            CloudMediaItem(
                id = "cloud_img_1",
                mimeType = "image/jpeg",
                dateTakenMillis = now - 86400000L * 5,
                sizeBytes = 2048000L,
                width = 1920,
                height = 1080,
                albumId = "album_vacation"
            )
        )
        cloudMediaItems.add(
            CloudMediaItem(
                id = "cloud_img_2",
                mimeType = "image/jpeg",
                dateTakenMillis = now - 86400000L * 2,
                sizeBytes = 1536000L,
                width = 2048,
                height = 1536,
                isFavorite = true,
                albumId = "album_favorites"
            )
        )
        cloudMediaItems.add(
            CloudMediaItem(
                id = "cloud_vid_1",
                mimeType = "video/mp4",
                dateTakenMillis = now - 86400000L * 1,
                sizeBytes = 15728640L,
                width = 1920,
                height = 1080,
                durationMillis = 15000L,
                albumId = "album_vacation"
            )
        )
    }

    override fun onGetMediaCollectionInfo(extras: Bundle): Bundle {
        Log.d(TAG, "onGetMediaCollectionInfo called with extras: $extras")
        return Bundle().apply {
            putString(CloudMediaProviderContract.MediaCollectionInfo.MEDIA_COLLECTION_ID, MEDIA_COLLECTION_ID)
            putLong(CloudMediaProviderContract.MediaCollectionInfo.LAST_MEDIA_SYNC_GENERATION, CURRENT_SYNC_GENERATION)
            putString(CloudMediaProviderContract.MediaCollectionInfo.ACCOUNT_NAME, "user@lifeos.app")
        }
    }

    override fun onQueryMedia(extras: Bundle): Cursor {
        Log.d(TAG, "onQueryMedia called with extras: $extras")
        val albumId = extras.getString(CloudMediaProviderContract.EXTRA_ALBUM_ID)
        val pageSize = extras.getInt(CloudMediaProviderContract.EXTRA_PAGE_SIZE, 50)

        val columns = arrayOf(
            CloudMediaProviderContract.MediaColumns.ID,
            CloudMediaProviderContract.MediaColumns.MIME_TYPE,
            CloudMediaProviderContract.MediaColumns.DATE_TAKEN_MILLIS,
            CloudMediaProviderContract.MediaColumns.SIZE_BYTES,
            CloudMediaProviderContract.MediaColumns.WIDTH,
            CloudMediaProviderContract.MediaColumns.HEIGHT,
            CloudMediaProviderContract.MediaColumns.DURATION_MILLIS,
            CloudMediaProviderContract.MediaColumns.IS_FAVORITE,
            CloudMediaProviderContract.MediaColumns.SYNC_GENERATION
        )

        val cursor = MatrixCursor(columns)

        var items = cloudMediaItems.filter { !it.isDeleted }
        if (!albumId.isNullOrEmpty()) {
            items = items.filter { it.albumId == albumId }
        }

        // Sort reverse chronological
        items.sortedByDescending { it.dateTakenMillis }.take(pageSize).forEach { item ->
            cursor.addRow(
                arrayOf(
                    item.id,
                    item.mimeType,
                    item.dateTakenMillis,
                    item.sizeBytes,
                    item.width,
                    item.height,
                    item.durationMillis,
                    if (item.isFavorite) 1 else 0,
                    CURRENT_SYNC_GENERATION
                )
            )
        }

        val cursorExtras = Bundle().apply {
            putString(CloudMediaProviderContract.EXTRA_MEDIA_COLLECTION_ID, MEDIA_COLLECTION_ID)
            putStringArray(ContentResolver.EXTRA_HONORED_ARGS, arrayOf(CloudMediaProviderContract.EXTRA_ALBUM_ID, CloudMediaProviderContract.EXTRA_PAGE_SIZE))
        }
        cursor.extras = cursorExtras

        return cursor
    }

    override fun onQueryDeletedMedia(extras: Bundle): Cursor {
        Log.d(TAG, "onQueryDeletedMedia called")
        val columns = arrayOf(CloudMediaProviderContract.MediaColumns.ID)
        val cursor = MatrixCursor(columns)

        cloudMediaItems.filter { it.isDeleted }.forEach { item ->
            cursor.addRow(arrayOf(item.id))
        }

        val cursorExtras = Bundle().apply {
            putString(CloudMediaProviderContract.EXTRA_MEDIA_COLLECTION_ID, MEDIA_COLLECTION_ID)
        }
        cursor.extras = cursorExtras

        return cursor
    }

    override fun onQueryAlbums(extras: Bundle): Cursor {
        Log.d(TAG, "onQueryAlbums called")
        val columns = arrayOf(
            CloudMediaProviderContract.AlbumColumns.ID,
            CloudMediaProviderContract.AlbumColumns.DISPLAY_NAME,
            CloudMediaProviderContract.AlbumColumns.DATE_TAKEN_MILLIS,
            CloudMediaProviderContract.AlbumColumns.MEDIA_COUNT,
            CloudMediaProviderContract.AlbumColumns.MEDIA_COVER_ID
        )

        val cursor = MatrixCursor(columns)

        cloudAlbums.sortedByDescending { it.dateTakenMillis }.forEach { album ->
            cursor.addRow(
                arrayOf(
                    album.id,
                    album.displayName,
                    album.dateTakenMillis,
                    album.mediaCount,
                    album.coverId
                )
            )
        }

        val cursorExtras = Bundle().apply {
            putString(CloudMediaProviderContract.EXTRA_MEDIA_COLLECTION_ID, MEDIA_COLLECTION_ID)
        }
        cursor.extras = cursorExtras

        return cursor
    }

    override fun onOpenMedia(
        mediaId: String,
        extras: Bundle?,
        cancellationSignal: CancellationSignal?
    ): ParcelFileDescriptor {
        Log.d(TAG, "onOpenMedia for mediaId: $mediaId")

        val ctx = context ?: throw IllegalStateException("Context is null")
        val file = File(ctx.cacheDir, "cloud_media_$mediaId.jpg")

        if (!file.exists()) {
            generatePlaceholderImage(file, mediaId, 1920, 1080)
        }

        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun onOpenPreview(
        mediaId: String,
        size: Point,
        extras: Bundle?,
        cancellationSignal: CancellationSignal?
    ): android.content.res.AssetFileDescriptor {
        Log.d(TAG, "onOpenPreview for mediaId: $mediaId with size: ${size.x}x${size.y}")

        val ctx = context ?: throw IllegalStateException("Context is null")
        val previewFile = File(ctx.cacheDir, "preview_${mediaId}_${size.x}x${size.y}.jpg")

        if (!previewFile.exists()) {
            val width = if (size.x > 0) size.x else 512
            val height = if (size.y > 0) size.y else 512
            generatePlaceholderImage(previewFile, mediaId, width, height)
        }

        val pfd = ParcelFileDescriptor.open(previewFile, ParcelFileDescriptor.MODE_READ_ONLY)
        return android.content.res.AssetFileDescriptor(pfd, 0, previewFile.length())
    }

    private fun generatePlaceholderImage(file: File, label: String, width: Int, height: Int) {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.DKGRAY)

        val paint = Paint().apply {
            color = Color.WHITE
            textSize = (height / 10).coerceAtLeast(24).toFloat()
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
        }

        canvas.drawText("Life OS Cloud: $label", width / 2f, height / 2f, paint)

        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
        }
    }
}
