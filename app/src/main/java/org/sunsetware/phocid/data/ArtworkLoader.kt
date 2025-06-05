package org.sunsetware.phocid.data

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.provider.MediaStore
import android.util.Size
import android.view.WindowManager
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlin.math.min
import org.apache.commons.io.FilenameUtils
import org.jaudiotagger.audio.AudioFileIO
import org.sunsetware.omio.VORBIS_COMMENT_METADATA_BLOCK_PICTURE
import org.sunsetware.omio.decodeMetadataBlockPicture
import org.sunsetware.omio.readOpusMetadata
import org.sunsetware.phocid.utils.roundToIntOrZero
import org.sunsetware.phocid.utils.trimAndNormalize

/** https://developer.android.com/media/platform/supported-formats#image-formats */
private val imageFileExtensionScores =
    listOf("png", "bmp", "jpg", "jpeg", "webp", "heif", "heic", "gif")
        .reversed()
        .mapIndexed { index, extension -> extension to index }
        .toMap()

/** "Folder" seems to be off-limits and can't be accessed, listed for completeness */
private val imageFileNameScores =
    listOf("cover", "folder", "artwork", "front", "album")
        .sortedDescending()
        .mapIndexed { index, name -> name to index }
        .toMap()

private val imageMimeTypes =
    setOf(
        "image/bmp",
        "image/gif",
        "image/jpeg",
        "image/png",
        "image/webp",
        "image/heic",
        "image/heif",
    )

private val cachedScreenSize = AtomicInteger(0)

fun loadArtwork(
    context: Context,
    uri: Uri,
    path: String?,
    highRes: Boolean = false,
    sizeLimit: Int? = null,
): Bitmap? {
    val forcedSizeLimit =
        sizeLimit
            ?: cachedScreenSize.get().takeIf { it > 0 }
            ?: run {
                val screenSize =
                    (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager)
                        .maximumWindowMetrics
                        .bounds
                val limit = min(screenSize.width(), screenSize.height()).coerceAtLeast(256)
                cachedScreenSize.set(limit)
                limit
            }

    return if (highRes) {
        loadWithLibrary(path, forcedSizeLimit)
            ?: loadExternal(context, path, forcedSizeLimit)
            ?: loadWithContentResolver(context, uri, forcedSizeLimit)
    } else {
        loadWithContentResolver(context, uri, forcedSizeLimit)
            ?: loadExternal(context, path, forcedSizeLimit)
    }
}

fun loadArtwork(
    context: Context,
    id: Long,
    path: String?,
    highRes: Boolean = false,
    sizeLimit: Int? = null,
): Bitmap? {
    return loadArtwork(
        context,
        ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id),
        path,
        highRes,
        sizeLimit,
    )
}

private fun loadWithLibrary(path: String?, sizeLimit: Int?): Bitmap? {
    return try {
        requireNotNull(path)
        val extension = FilenameUtils.getExtension(path).lowercase()
        val data =
            if (extension == "opus" || extension == "ogg") {
                try {
                    val metadata =
                        FileInputStream(File(path)).buffered().use { stream ->
                            readOpusMetadata(stream, false)
                        }
                    // TODO: find the "front cover" instead of using the first artwork
                    // currently not doing that to avoid OOM
                    requireNotNull(metadata.userComments[VORBIS_COMMENT_METADATA_BLOCK_PICTURE])
                        .firstNotNullOf { block ->
                            decodeMetadataBlockPicture(block)?.takeIf {
                                imageMimeTypes.contains(it.mimeType.trimAndNormalize())
                            }
                        }
                        .data
                } catch (_: Exception) {
                    AudioFileIO.read(File(path)).tag.firstArtwork.binaryData
                }
            } else {
                AudioFileIO.read(File(path)).tag.firstArtwork.binaryData
            }
        ImageDecoder.decodeBitmap(data.let(ByteBuffer::wrap).let(ImageDecoder::createSource)) {
            decoder,
            info,
            source ->
            val resizeFactor = sizeLimit?.toFloat()?.div(max(info.size.width, info.size.height))
            if (
                resizeFactor != null &&
                    resizeFactor.isFinite() &&
                    resizeFactor > 0 &&
                    resizeFactor < 1
            ) {
                decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE)
                decoder.setTargetSize(
                    (resizeFactor * info.size.width)
                        .roundToIntOrZero()
                        .coerceIn(1, info.size.width),
                    (resizeFactor * info.size.height)
                        .roundToIntOrZero()
                        .coerceIn(1, info.size.height),
                )
            }
        }
    } catch (_: Exception) {
        null
    }
}

private fun loadWithContentResolver(context: Context, uri: Uri, sizeLimit: Int): Bitmap? {
    return try {
        context.contentResolver.loadThumbnail(uri, Size(sizeLimit, sizeLimit), null)
    } catch (_: Exception) {
        null
    }
}

private fun loadExternal(context: Context, path: String?, sizeLimit: Int?): Bitmap? {
    if (path == null) return null

    val trackName = FilenameUtils.getBaseName(path)
    val directoryName = FilenameUtils.getName(FilenameUtils.getPathNoEndSeparator(path))
    val files =
        try {
            File(FilenameUtils.getPath(path)).listFiles() ?: emptyArray()
        } catch (_: Exception) {
            emptyArray()
        }
    return files
        .mapNotNull {
            val name = it.nameWithoutExtension
            val extension = it.extension
            // higher score is better
            val extensionScore =
                imageFileExtensionScores[extension.lowercase()] ?: return@mapNotNull null
            val nameScore =
                when {
                    name.equals(trackName, true) -> 999
                    name.equals(directoryName, true) -> 998
                    else -> imageFileNameScores[name.lowercase()] ?: return@mapNotNull null
                }

            it to (nameScore * 1000 + extensionScore)
        }
        .sortedByDescending { it.second }
        .firstNotNullOfOrNull { (file, _) ->
            try {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, source
                    ->
                    val resizeFactor =
                        sizeLimit?.toFloat()?.div(max(info.size.width, info.size.height))
                    if (
                        resizeFactor != null &&
                            resizeFactor.isFinite() &&
                            resizeFactor > 0 &&
                            resizeFactor < 1
                    ) {
                        decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE)
                        decoder.setTargetSize(
                            (resizeFactor * info.size.width)
                                .roundToIntOrZero()
                                .coerceIn(1, info.size.width),
                            (resizeFactor * info.size.height)
                                .roundToIntOrZero()
                                .coerceIn(1, info.size.height),
                        )
                    }
                }
            } catch (_: Exception) {
                null
            }
        }
}
