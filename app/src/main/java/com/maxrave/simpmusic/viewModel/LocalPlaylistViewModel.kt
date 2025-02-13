package com.maxrave.simpmusic.viewModel

import android.app.Application
import android.graphics.drawable.GradientDrawable
import android.util.Log
import android.widget.Toast
import androidx.core.net.toUri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import com.maxrave.simpmusic.common.DownloadState
import com.maxrave.simpmusic.data.db.entities.LocalPlaylistEntity
import com.maxrave.simpmusic.data.db.entities.SongEntity
import com.maxrave.simpmusic.data.model.browse.album.Track
import com.maxrave.simpmusic.data.repository.MainRepository
import com.maxrave.simpmusic.extension.addThumbnails
import com.maxrave.simpmusic.extension.toSongEntity
import com.maxrave.simpmusic.service.test.download.DownloadUtils
import com.maxrave.simpmusic.service.test.download.MusicDownloadService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class LocalPlaylistViewModel @Inject constructor(private val mainRepository: MainRepository, application: Application) : AndroidViewModel(application) {
    @Inject
    lateinit var downloadUtils: DownloadUtils

    val id: MutableLiveData<Long> = MutableLiveData()

    private var _listLocalPlaylist: MutableLiveData<LocalPlaylistEntity?> = MutableLiveData()
    val localPlaylist: LiveData<LocalPlaylistEntity?> = _listLocalPlaylist

    private var _listTrack: MutableLiveData<List<SongEntity>> = MutableLiveData()
    val listTrack: LiveData<List<SongEntity>> = _listTrack

    var gradientDrawable: MutableLiveData<GradientDrawable> = MutableLiveData()

    fun getLocalPlaylist(id: Long) {
        viewModelScope.launch {
            mainRepository.getLocalPlaylist(id).collect {
                _listLocalPlaylist.postValue(it)
            }
        }
    }

    fun getListTrack(list: List<String>) {
        viewModelScope.launch {
            mainRepository.getSongsByListVideoId(list).collect {
                _listTrack.postValue(it)
                var count = 0
                it.forEach { track ->
                    if (track.downloadState == DownloadState.STATE_DOWNLOADED) {
                        count++
                    }
                }
                if (count == it.size && localPlaylist.value?.downloadState != DownloadState.STATE_DOWNLOADED) {
                    updatePlaylistDownloadState(id.value!!, DownloadState.STATE_DOWNLOADED)
                    getLocalPlaylist(id.value!!)
                }
                else if (count != it.size && localPlaylist.value?.downloadState != DownloadState.STATE_NOT_DOWNLOADED && localPlaylist.value?.downloadState != DownloadState.STATE_DOWNLOADING) {
                    updatePlaylistDownloadState(id.value!!, DownloadState.STATE_NOT_DOWNLOADED)
                    getLocalPlaylist(id.value!!)
                }
            }
        }
    }

    val playlistDownloadState: MutableStateFlow<Int> = MutableStateFlow(DownloadState.STATE_NOT_DOWNLOADED)


    private fun updateSongDownloadState(song: SongEntity, state: Int) {
        viewModelScope.launch {
            mainRepository.insertSong(song)
            mainRepository.getSongById(song.videoId).collect {
                mainRepository.updateDownloadState(song.videoId, state)
            }
        }
    }


    private fun updatePlaylistDownloadState(id: Long, state: Int) {
        viewModelScope.launch {
            mainRepository.getLocalPlaylist(id).collect { playlist ->
                _listLocalPlaylist.value = playlist
                mainRepository.updateLocalPlaylistDownloadState(state, id)
                playlistDownloadState.value = state
            }
        }
    }
    @UnstableApi
    fun getAllDownloadStateFromService(id: Long) {
        var downloadState: StateFlow<List<Download?>>
        viewModelScope.launch {
            downloadState = downloadUtils.getAllDownloads().stateIn(viewModelScope)
            downloadState.collect { down ->
                if (down.isNotEmpty()){
                    var count = 0
                    down.forEach { downloadItem ->
                        if (downloadItem?.state == Download.STATE_COMPLETED) {
                            count++
                        }
                        else if (downloadItem?.state == Download.STATE_FAILED) {
                            updatePlaylistDownloadState(id, DownloadState.STATE_NOT_DOWNLOADED)
                        }
                    }
                    if (count == down.size) {
                        mainRepository.getLocalPlaylist(id).collect{ playlist ->
                            mainRepository.getSongsByListVideoId(playlist.tracks!!).collect{ tracks ->
                                tracks.forEach { track ->
                                    if (track.downloadState != DownloadState.STATE_DOWNLOADED) {
                                        mainRepository.updateDownloadState(track.videoId, DownloadState.STATE_NOT_DOWNLOADED)
                                        Toast.makeText(getApplication(), "Download Failed", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        }
                        Log.d("Check Downloaded", "Downloaded")
                        updatePlaylistDownloadState(id, DownloadState.STATE_DOWNLOADED)
                        Toast.makeText(getApplication(), "Download Completed", Toast.LENGTH_SHORT).show()
                    }
                    else {
                        updatePlaylistDownloadState(id, DownloadState.STATE_DOWNLOADING)
                    }
                }
                else {
                    updatePlaylistDownloadState(id, DownloadState.STATE_NOT_DOWNLOADED)
                }
            }
        }
    }

    @UnstableApi
    fun getDownloadStateFromService(videoId: String) {
        viewModelScope.launch {
            val downloadState = downloadUtils.getDownload(videoId).stateIn(viewModelScope)
            downloadState.collect { down ->
                if (down != null) {
                    when (down.state) {
                        Download.STATE_COMPLETED -> {
                            mainRepository.getSongById(videoId).collect{ song ->
                                if (song?.downloadState != DownloadState.STATE_DOWNLOADED) {
                                    mainRepository.updateDownloadState(videoId, DownloadState.STATE_DOWNLOADED)
                                }
                            }
                            Log.d("Check Downloaded", "Downloaded")
                        }
                        Download.STATE_FAILED -> {
                            mainRepository.getSongById(videoId).collect{ song ->
                                if (song?.downloadState != DownloadState.STATE_NOT_DOWNLOADED) {
                                    mainRepository.updateDownloadState(videoId, DownloadState.STATE_NOT_DOWNLOADED)
                                }
                            }
                            Log.d("Check Downloaded", "Failed")
                        }
                        Download.STATE_DOWNLOADING -> {
                            mainRepository.getSongById(videoId).collect{ song ->
                                if (song?.downloadState != DownloadState.STATE_DOWNLOADING) {
                                    mainRepository.updateDownloadState(videoId, DownloadState.STATE_DOWNLOADING)
                                }
                            }
                            Log.d("Check Downloaded", "Downloading ${down.percentDownloaded}")
                        }
                        Download.STATE_QUEUED -> {
                            mainRepository.getSongById(videoId).collect{ song ->
                                if (song?.downloadState != DownloadState.STATE_PREPARING) {
                                    mainRepository.updateDownloadState(videoId, DownloadState.STATE_PREPARING)
                                }
                            }
                            Log.d("Check Downloaded", "Queued")
                        }
                        else -> {
                            Log.d("Check Downloaded", "Not Downloaded")
                        }
                    }
                }
            }
        }
    }

    @UnstableApi
    fun downloadPlaylist(list: ArrayList<Track>, id: Long) {
        list.forEach { track ->
            Log.d("Check Track Download", track.toString())
            val trackWithThumbnail = track.addThumbnails()
            updateSongDownloadState(trackWithThumbnail.toSongEntity(), DownloadState.STATE_PREPARING)
            updatePlaylistDownloadState(id, DownloadState.STATE_PREPARING)
        }
        list.forEach { track ->
            updatePlaylistDownloadState(id, DownloadState.STATE_DOWNLOADING)
            val downloadRequest = DownloadRequest.Builder(track.videoId, track.videoId.toUri())
                .setData(track.title.toByteArray())
                .setCustomCacheKey(track.videoId)
                .build()
            DownloadService.sendAddDownload(
                getApplication(),
                MusicDownloadService::class.java,
                downloadRequest,
                false
            )
            getDownloadStateFromService(track.videoId)
        }
        getAllDownloadStateFromService(id)
    }

    fun updatePlaylistTitle(title: String, id: Long) {
        viewModelScope.launch {
            mainRepository.updateLocalPlaylistTitle(title, id)
        }
    }

    fun deletePlaylist(id: Long) {
        viewModelScope.launch {
            mainRepository.deleteLocalPlaylist(id)
        }
    }

    fun updatePlaylistThumbnail(uri: String, id: Long) {
        viewModelScope.launch {
            mainRepository.updateLocalPlaylistThumbnail(uri, id)
        }
    }

    fun clearLocalPlaylist() {
        _listLocalPlaylist.value = null
    }

}