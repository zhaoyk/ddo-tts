package io.legado.app.service.mimo

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import java.io.IOException

@UnstableApi
class MiMoMemoryDataSource(
    private val media: Map<String, ByteArray>,
) : BaseDataSource(false) {

    private var openedUri: Uri? = null
    private var bytes = EMPTY_BYTES
    private var position = 0
    private var remaining = 0
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        if (opened) throw IOException("MiMo memory source is already open")
        transferInitializing(dataSpec)

        val mediaId = parseMediaId(dataSpec.uri)
        val selectedBytes = media[mediaId]
            ?: throw IOException("Missing MiMo audio for media id")
        val start = dataSpec.position
        val available = selectedBytes.size.toLong()
        if (start < 0 || start > available) {
            throw IOException("Invalid MiMo audio range")
        }
        val requested = if (dataSpec.length == C.LENGTH_UNSET.toLong()) {
            available - start
        } else {
            dataSpec.length
        }
        if (requested < 0 || requested > available - start) {
            throw IOException("Invalid MiMo audio range")
        }

        openedUri = dataSpec.uri
        bytes = selectedBytes
        position = start.toInt()
        remaining = requested.toInt()
        opened = true
        transferStarted(dataSpec)
        return requested
    }

    override fun read(buffer: ByteArray, offset: Int, readLength: Int): Int {
        if (!opened) throw IOException("MiMo memory source is not open")
        if (readLength == 0) return 0
        if (remaining == 0) return C.RESULT_END_OF_INPUT

        val count = minOf(readLength, remaining)
        bytes.copyInto(buffer, offset, position, position + count)
        position += count
        remaining -= count
        bytesTransferred(count)
        return count
    }

    override fun getUri(): Uri? = openedUri

    override fun close() {
        openedUri = null
        bytes = EMPTY_BYTES
        position = 0
        remaining = 0
        if (opened) {
            opened = false
            transferEnded()
        }
    }

    private fun parseMediaId(uri: Uri): String {
        if (uri.scheme != MEMORY_SCHEME || uri.authority != MIMO_AUTHORITY) {
            throw IOException("Invalid MiMo memory URI")
        }
        val pathSegments = uri.pathSegments
        if (pathSegments.size != 1 || pathSegments.single().isBlank()) {
            throw IOException("Missing MiMo media id")
        }
        return pathSegments.single()
    }

    private companion object {
        const val MEMORY_SCHEME = "memory"
        const val MIMO_AUTHORITY = "mimo"
        val EMPTY_BYTES = ByteArray(0)
    }
}

@UnstableApi
class MiMoMemoryDataSourceFactory(
    private val media: Map<String, ByteArray>,
) : DataSource.Factory {
    override fun createDataSource(): DataSource = MiMoMemoryDataSource(media)
}
