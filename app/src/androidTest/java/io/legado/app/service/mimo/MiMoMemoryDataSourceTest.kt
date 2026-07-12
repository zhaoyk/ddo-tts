package io.legado.app.service.mimo

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

@UnstableApi
@RunWith(AndroidJUnit4::class)
class MiMoMemoryDataSourceTest {

    @Test
    fun readsRequestedRangeAndSignalsEnd() {
        val source = MiMoMemoryDataSource(mapOf("segment" to byteArrayOf(1, 2, 3, 4)))
        val spec = DataSpec.Builder()
            .setUri(Uri.parse("memory://mimo/segment"))
            .setPosition(1)
            .setLength(2)
            .build()

        assertEquals(2L, source.open(spec))
        assertEquals(spec.uri, source.uri)
        val buffer = ByteArray(4)
        assertEquals(2, source.read(buffer, 0, buffer.size))
        assertEquals(2, buffer[0].toInt())
        assertEquals(3, buffer[1].toInt())
        assertEquals(C.RESULT_END_OF_INPUT, source.read(buffer, 0, buffer.size))
        source.close()
        assertNull(source.uri)
    }

    @Test
    fun missingMediaIdThrowsIOException() {
        val source = MiMoMemoryDataSource(emptyMap())

        assertThrows(IOException::class.java) {
            source.open(DataSpec(Uri.parse("memory://mimo/missing")))
        }
        assertThrows(IOException::class.java) {
            source.open(DataSpec(Uri.parse("memory://mimo")))
        }
    }

    @Test
    fun rejectsUrisOutsideMiMoMemoryNamespace() {
        val source = MiMoMemoryDataSource(mapOf("segment" to byteArrayOf(1)))

        assertThrows(IOException::class.java) {
            source.open(DataSpec(Uri.parse("file://mimo/segment")))
        }
        assertThrows(IOException::class.java) {
            source.open(DataSpec(Uri.parse("memory://other/segment")))
        }
    }

    @Test
    fun rejectsRangesOutsideMediaBytes() {
        val source = MiMoMemoryDataSource(mapOf("segment" to byteArrayOf(1, 2, 3)))

        assertThrows(IOException::class.java) {
            source.open(
                DataSpec.Builder()
                    .setUri(Uri.parse("memory://mimo/segment"))
                    .setPosition(4)
                    .build()
            )
        }
        assertThrows(IOException::class.java) {
            source.open(
                DataSpec.Builder()
                    .setUri(Uri.parse("memory://mimo/segment"))
                    .setPosition(2)
                    .setLength(2)
                    .build()
            )
        }
    }

    @Test
    fun closeAllowsOpeningAnotherMediaItem() {
        val source = MiMoMemoryDataSource(
            mapOf(
                "first" to byteArrayOf(1, 2),
                "second" to byteArrayOf(8, 9),
            )
        )
        val buffer = ByteArray(2)

        assertEquals(2L, source.open(DataSpec(Uri.parse("memory://mimo/first"))))
        assertEquals(1, source.read(buffer, 0, 1))
        source.close()

        assertEquals(2L, source.open(DataSpec(Uri.parse("memory://mimo/second"))))
        assertEquals(2, source.read(buffer, 0, buffer.size))
        assertEquals(8, buffer[0].toInt())
        assertEquals(9, buffer[1].toInt())
        source.close()
    }

    @Test
    fun reportsNonNetworkTransferLifecycle() {
        val source = MiMoMemoryDataSource(mapOf("segment" to byteArrayOf(1, 2, 3)))
        val events = mutableListOf<String>()
        val networkValues = mutableListOf<Boolean>()
        source.addTransferListener(object : TransferListener {
            override fun onTransferInitializing(
                source: DataSource,
                dataSpec: DataSpec,
                isNetwork: Boolean,
            ) {
                events += "initializing"
                networkValues += isNetwork
            }

            override fun onTransferStart(
                source: DataSource,
                dataSpec: DataSpec,
                isNetwork: Boolean,
            ) {
                events += "start"
                networkValues += isNetwork
            }

            override fun onBytesTransferred(
                source: DataSource,
                dataSpec: DataSpec,
                isNetwork: Boolean,
                bytesTransferred: Int,
            ) {
                events += "bytes:$bytesTransferred"
                networkValues += isNetwork
            }

            override fun onTransferEnd(
                source: DataSource,
                dataSpec: DataSpec,
                isNetwork: Boolean,
            ) {
                events += "end"
                networkValues += isNetwork
            }
        })

        source.open(DataSpec(Uri.parse("memory://mimo/segment")))
        assertEquals(0, source.read(ByteArray(0), 0, 0))
        assertEquals(2, source.read(ByteArray(2), 0, 2))
        source.close()

        assertEquals(listOf("initializing", "start", "bytes:2", "end"), events)
        assertTrue(networkValues.isNotEmpty())
        assertFalse(networkValues.any { it })
    }

    @Test
    fun factoryCreatesIndependentSourcesOverSharedMemory() {
        val factory = MiMoMemoryDataSourceFactory(mapOf("segment" to byteArrayOf(5)))
        val first = factory.createDataSource()
        val second = factory.createDataSource()

        assertTrue(first !== second)
        assertEquals(1L, first.open(DataSpec(Uri.parse("memory://mimo/segment"))))
        assertEquals(1L, second.open(DataSpec(Uri.parse("memory://mimo/segment"))))
        first.close()
        second.close()
    }
}
