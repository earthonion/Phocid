package org.sunsetware.phocid

import android.app.Application
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Environment
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import org.sunsetware.phocid.data.EmptyTrackIndex
import org.sunsetware.phocid.data.LibraryIndex
import org.sunsetware.phocid.data.Lyrics
import org.sunsetware.phocid.data.PlayerManager
import org.sunsetware.phocid.data.PlaylistManager
import org.sunsetware.phocid.data.Preferences
import org.sunsetware.phocid.data.SaveManager
import org.sunsetware.phocid.data.UnfilteredTrackIndex
import org.sunsetware.phocid.data.loadCbor
import org.sunsetware.phocid.data.saveCbor
import org.sunsetware.phocid.data.scanTracks
import org.sunsetware.phocid.ui.components.ArtworkCache
import org.sunsetware.phocid.ui.views.library.LibraryScreenTabInfo
import org.sunsetware.phocid.utils.combine
import org.sunsetware.phocid.utils.map

class MainViewModel(private val application: Application) : AndroidViewModel(application) {
    private val initializationMutex = Mutex()
    private val scanMutex = Mutex()

    val playerManager: PlayerManager = PlayerManager()

    val lyricsCache = AtomicReference(null as Pair<Long, Lyrics>?)
    val carouselArtworkCache = ArtworkCache(viewModelScope, 4)

    private val _initialized = MutableStateFlow(false)
    val initialized = _initialized.asStateFlow()

    private val _unfilteredTrackIndex = MutableStateFlow(EmptyTrackIndex)
    val unfilteredTrackIndex = _unfilteredTrackIndex.asStateFlow()

    private val _isScanningLibrary = MutableStateFlow(null as Boolean?)
    /**
     * - null: not scanning
     * - true: forced (manual)
     * - false: not forced (auto)
     */
    val isScanningLibrary = _isScanningLibrary.asStateFlow()

    private val _libraryScanProgress = MutableStateFlow(null as Pair<Int, Int>?)
    val libraryScanProgress = _libraryScanProgress.asStateFlow()

    private val _preferences = MutableStateFlow(Preferences())
    val preferences = _preferences.asStateFlow()
    private lateinit var preferencesSaveManager: SaveManager<Preferences>

    // These synchronizations are necessary to prevent data popping on launch due to blacklists
    private val libraryIndexInputReady = MutableStateFlow(false)
    private val libraryIndexOutputReady = AtomicBoolean(false)
    val libraryIndex =
        _unfilteredTrackIndex
            .combine(
                viewModelScope,
                _preferences.map(viewModelScope) {
                    object {
                        val collator = it.sortCollator
                        val blacklist = it.blacklistRegexes
                        val whitelist = it.whitelistRegexes
                    }
                },
            ) { trackIndex, tuple ->
                LibraryIndex(trackIndex, tuple.collator, tuple.blacklist, tuple.whitelist)
            }
            .combine(viewModelScope, libraryIndexInputReady, true) { libraryIndex, ready ->
                libraryIndexOutputReady.set(ready)
                libraryIndex
            }

    val playlistManager =
        PlaylistManager(application.applicationContext, viewModelScope, _preferences, libraryIndex)

    val uiManager =
        UiManager(
            application.applicationContext,
            viewModelScope,
            _preferences,
            libraryIndex,
            playlistManager,
        )

    val playlistIoDirectory = MutableStateFlow(null as Uri?)

    override fun onCleared() {
        playerManager.close()
        uiManager.close()
        preferencesSaveManager.close()
        playlistManager.close()
        super.onCleared()
    }

    @OptIn(ExperimentalSerializationApi::class)
    suspend fun initialize(onDismissSplashScreen: () -> Unit) {
        viewModelScope
            .launch {
                withContext(Dispatchers.IO) {
                    if (initializationMutex.tryLock()) {
                        try {
                            if (!_initialized.value) {
                                loadCbor<Preferences>(
                                        application.applicationContext,
                                        PREFERENCES_FILE_NAME,
                                        false,
                                    )
                                    ?.upgrade()
                                    ?.let { preferences -> _preferences.update { preferences } }
                                preferencesSaveManager =
                                    SaveManager(
                                        application.applicationContext,
                                        viewModelScope,
                                        _preferences,
                                        PREFERENCES_FILE_NAME,
                                        false,
                                    )

                                val unfilteredTrackIndex =
                                    loadCbor<UnfilteredTrackIndex>(
                                        application.applicationContext,
                                        TRACK_INDEX_FILE_NAME,
                                        false,
                                    ) ?: EmptyTrackIndex
                                _unfilteredTrackIndex.update { unfilteredTrackIndex }

                                playlistManager.initialize()

                                playerManager.initialize(
                                    application.applicationContext,
                                    viewModelScope,
                                    _preferences,
                                )

                                libraryIndexInputReady.update { true }
                                while (!libraryIndexOutputReady.get()) {
                                    delay(1.milliseconds)
                                }
                                _initialized.update { true }
                            }
                        } finally {
                            onDismissSplashScreen()
                            initializationMutex.unlock()
                        }
                    }
                }
            }
            .join()
    }

    @OptIn(ExperimentalSerializationApi::class)
    fun scanLibrary(force: Boolean) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                if (scanMutex.tryLock()) {
                    Log.d("Phocid", "Library scan started")
                    while (!initialized.value) {
                        delay(1)
                    }
                    try {
                        _libraryScanProgress.update { null }
                        _isScanningLibrary.update { force }

                        if (force || _preferences.value.alwaysRescanMediaStore) {
                            val mediaScannerSignal = AtomicBoolean(false)
                            // Try to obtain all external storage paths through hack.
                            // Result from getExternalStorageDirectory() is still kept in case the
                            // hack no longer works.
                            val storages =
                                application.applicationContext
                                    .getExternalFilesDirs(null)
                                    .mapNotNull {
                                        it?.parentFile?.parentFile?.parentFile?.parentFile?.path
                                    }
                                    .plus(Environment.getExternalStorageDirectory().path)
                                    .distinct()
                                    .toTypedArray()
                            MediaScannerConnection.scanFile(
                                application.applicationContext,
                                storages,
                                arrayOf("audio/*"),
                            ) { _, _ ->
                                mediaScannerSignal.set(true)
                            }
                            while (!mediaScannerSignal.get()) {
                                delay(1)
                            }
                        }

                        val newTrackIndex =
                            scanTracks(
                                application.applicationContext,
                                _preferences.value.advancedMetadataExtraction,
                                _preferences.value.disableArtworkColorExtraction,
                                if (force) null else _unfilteredTrackIndex.value,
                                _preferences.value.artistMetadataSeparators,
                                _preferences.value.artistMetadataSeparatorExceptions,
                                _preferences.value.genreMetadataSeparators,
                                _preferences.value.genreMetadataSeparatorExceptions,
                            ) { current, total ->
                                _libraryScanProgress.update { current to total }
                            }
                        if (newTrackIndex != null) {
                            _unfilteredTrackIndex.update { newTrackIndex }
                            saveCbor(
                                application.applicationContext,
                                TRACK_INDEX_FILE_NAME,
                                false,
                                newTrackIndex,
                            )
                            Log.d("Phocid", "Library scan completed")
                        } else {
                            Log.d("Phocid", "Library scan aborted: permission denied")
                        }
                        playlistManager.syncPlaylists()
                    } finally {
                        scanMutex.unlock()
                        _isScanningLibrary.update { null }
                    }
                }
            }
        }
    }

    fun updatePreferences(transform: (Preferences) -> Preferences) {
        _preferences.update(transform)
    }

    fun updateTabInfo(index: Int, transform: (LibraryScreenTabInfo) -> LibraryScreenTabInfo) {
        _preferences.update { preferences ->
            val type = preferences.tabs[index].type
            preferences.copy(
                tabSettings =
                    preferences.tabSettings.mapValues {
                        if (it.key == type) transform(it.value) else it.value
                    }
            )
        }
    }
}
