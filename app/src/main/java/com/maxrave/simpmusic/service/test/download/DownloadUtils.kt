package com.maxrave.simpmusic.service.test.download

import android.content.Context
import android.util.Log
import androidx.core.net.toUri
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.DatabaseProvider
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadNotificationHelper
import com.maxrave.simpmusic.common.Config
import com.maxrave.simpmusic.data.model.streams.StreamData
import com.maxrave.simpmusic.di.DownloadCache
import com.maxrave.simpmusic.di.PlayerCache
import com.maxrave.simpmusic.service.test.download.MusicDownloadService.Companion.CHANNEL_ID
import dagger.hilt.android.qualifiers.ApplicationContext
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.request.get
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.headers
import io.ktor.utils.io.core.EOFException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import java.util.concurrent.Executor
import javax.inject.Inject
import javax.inject.Singleton

@UnstableApi
@Singleton
class DownloadUtils @Inject constructor(
    @ApplicationContext context: Context,
    @PlayerCache private val playerCache: SimpleCache,
    @DownloadCache private val downloadCache: SimpleCache,
    private val ktorClient: HttpClient,
    private val databaseProvider: DatabaseProvider) {


    private val dataSourceFactory = ResolvingDataSource.Factory(
        CacheDataSource.Factory()
            .setCache(playerCache)
            .setCacheWriteDataSinkFactory(null)
            .setUpstreamDataSourceFactory(
                DefaultHttpDataSource.Factory()
                    .setAllowCrossProtocolRedirects(true)
                    .setUserAgent("Mozilla/5.0 (Windows NT 10.0) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/113.0.0.0 Safari/537.36")
                    .setConnectTimeoutMs(5000)
            )
    ) { dataSpec ->

        val mediaId = dataSpec.key ?: error("No media id")
        val length = if (dataSpec.length >= 0) dataSpec.length else 1

        if (playerCache.isCached(mediaId, dataSpec.position, length)) {
            return@Factory dataSpec
        }
        var extract: DataSpec? = null
        runBlocking(Dispatchers.Main) {
//            mainRepository.getSong(mediaId).collect {values ->
//                values.data?.forEach { song ->
//                    if (song.itag == 251) {
//                        val url = song.url
//                        Log.d("DownloadUtils", "url: $url")
//                        extract = dataSpec.withUri((url).toUri())
//                    }
//                }
//            }
            try {
                val response = ktorClient.get("${Config.BASE_STREAM_URL}${mediaId}") {
                    method = HttpMethod.Get
                    headers {
                        append(HttpHeaders.UserAgent, Config.USER_AGENT)
                        append(HttpHeaders.Accept, "application/json")
                    }
                    contentType(ContentType.Application.Json)
                }
                if (response.status == HttpStatusCode.OK) {
                    val streamData: StreamData = response.body()
                    streamData.audioStreams?.forEach { stream ->
                        if (stream.itag == 251) {
                            val url = stream.url
                            Log.d("DownloadUtils", "url: $url")
                            if (url != null) {
                                extract = dataSpec.withUri((url).toUri())
                            }
                        }
                    }
                }
                else {
                    Log.d("DownloadUtils", "response: ${response.status}")
                }
            }
            catch (e: HttpRequestTimeoutException) {
                Log.d("DownloadUtils", "Exception: ${e.message}")
            }
            catch (e: EOFException){
                Log.d("DownloadUtils", "Exception: ${e.message}")
            }
        }
        Log.d("DownloadUtils", "extract: ${extract.toString()}")
        return@Factory extract!!
    }
    val downloadNotificationHelper = DownloadNotificationHelper(context, CHANNEL_ID)
    val downloadManager: DownloadManager = DownloadManager(context, databaseProvider, downloadCache, dataSourceFactory, Executor(Runnable::run)).apply {
        maxParallelDownloads = 3
        minRetryCount = 5
        addListener(
            MusicDownloadService.TerminalStateNotificationHelper(
                context = context,
                notificationHelper = downloadNotificationHelper,
                nextNotificationId = MusicDownloadService.NOTIFICATION_ID + 1
            )
        )
    }
    val downloads = MutableStateFlow<Map<String, Download>>(emptyMap())

    fun getDownload(songId: String): Flow<Download?> = downloads.map { it[songId] }

    fun getAllDownloads(): Flow<List<Download>> = downloads.map { it.values.toList() }

    init {
        val result = mutableMapOf<String, Download>()
        val cursor = downloadManager.downloadIndex.getDownloads()
        while (cursor.moveToNext()) {
            result[cursor.download.request.id] = cursor.download
        }
        downloads.value = result
        downloadManager.addListener(
            object : DownloadManager.Listener {
                override fun onDownloadChanged(downloadManager: DownloadManager, download: Download, finalException: Exception?) {
                    downloads.update { map ->
                        map.toMutableMap().apply {
                            set(download.request.id, download)
                        }
                    }
                }
            }
        )
    }
}