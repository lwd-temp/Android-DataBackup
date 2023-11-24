package com.xayah.feature.main.task.medium.local.backup.list

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import com.xayah.core.common.viewmodel.BaseViewModel
import com.xayah.core.common.viewmodel.UiEffect
import com.xayah.core.common.viewmodel.UiIntent
import com.xayah.core.common.viewmodel.UiState
import com.xayah.core.data.repository.MediaBackupRepository
import com.xayah.core.database.model.MediaBackupEntity
import com.xayah.core.database.model.MediaBackupEntityUpsert
import com.xayah.core.datastore.ConstantUtil
import com.xayah.core.rootservice.service.RemoteRootService
import com.xayah.core.ui.model.StringResourceToken
import com.xayah.core.ui.model.TopBarState
import com.xayah.core.ui.util.fromStringId
import com.xayah.core.util.PathUtil
import com.xayah.core.util.localBackupSaveDir
import com.xayah.feature.main.task.medium.local.R
import com.xayah.libpickyou.ui.PickYouLauncher
import com.xayah.libpickyou.ui.activity.PickerType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject

data class IndexUiState(
    val shimmerCount: Int = ConstantUtil.DefaultMediaList.size,
    val emphasizedState: Boolean = false,
    val batchSelection: List<String> = listOf(),
) : UiState

sealed class IndexUiIntent : UiIntent {
    object Update : IndexUiIntent()
    data class UpdateMedia(val entity: MediaBackupEntity) : IndexUiIntent()
    data class RefreshMedia(val entity: MediaBackupEntity) : IndexUiIntent()
    data class FilterByKey(val key: String) : IndexUiIntent()
    object Emphasize : IndexUiIntent()
    object BatchingSelectAll : IndexUiIntent()
    data class BatchingSelect(val path: String) : IndexUiIntent()
    data class BatchSelectOp(val selected: Boolean, val pathList: List<String>) : IndexUiIntent()
    data class Add(val context: Context) : IndexUiIntent()
    data class Delete(val entity: MediaBackupEntity) : IndexUiIntent()
}

sealed class IndexUiEffect : UiEffect {
    data class ShowSnackbar(
        val message: String,
        val actionLabel: String? = null,
        val withDismissAction: Boolean = false,
        val duration: SnackbarDuration = if (actionLabel == null) SnackbarDuration.Short else SnackbarDuration.Indefinite,
    ) : IndexUiEffect()
}

@ExperimentalMaterial3Api
@HiltViewModel
class IndexViewModel @Inject constructor(
    rootService: RemoteRootService,
    private val mediaBackupRepository: MediaBackupRepository,
) :
    BaseViewModel<IndexUiState, IndexUiIntent, IndexUiEffect>(IndexUiState()) {
    init {
        rootService.onFailure = {
            val msg = it.message
            if (msg != null)
                emitEffect(IndexUiEffect.ShowSnackbar(message = msg))
        }
    }

    override suspend fun onEvent(state: IndexUiState, intent: IndexUiIntent) {
        when (intent) {
            is IndexUiIntent.Update -> {
                mediaBackupRepository.updateDefaultMedium()
                _shimmeringState.value = false
            }

            is IndexUiIntent.UpdateMedia -> {
                mediaBackupRepository.upsertBackup(intent.entity)
            }

            is IndexUiIntent.RefreshMedia -> {
                mediaBackupRepository.refreshMedia(intent.entity)
            }

            is IndexUiIntent.FilterByKey -> {
                _keyState.value = intent.key
            }

            is IndexUiIntent.Emphasize -> {
                emitState(state.copy(emphasizedState = state.emphasizedState.not()))
            }

            is IndexUiIntent.BatchingSelectAll -> {
                var batchSelection: List<String> = listOf()
                if (state.batchSelection.isEmpty()) {
                    batchSelection = mediumState.first().map { it.path }
                }
                emitState(state.copy(batchSelection = batchSelection))
            }

            is IndexUiIntent.BatchingSelect -> {
                val batchSelection = state.batchSelection.toMutableList()
                if (intent.path in batchSelection)
                    batchSelection.remove(intent.path)
                else
                    batchSelection.add(intent.path)
                emitState(state.copy(batchSelection = batchSelection))
            }

            is IndexUiIntent.BatchSelectOp -> {
                mediaBackupRepository.batchSelectOp(selected = intent.selected, pathList = intent.pathList)
            }

            is IndexUiIntent.Add -> {
                withMainContext {
                    val context = intent.context as ComponentActivity
                    PickYouLauncher().apply {
                        setTitle(context.getString(R.string.select_target_directory))
                        setType(PickerType.DIRECTORY)
                        setLimitation(0)
                        launch(context) { pathList ->
                            launchOnIO {
                                val customMediaList = mutableListOf<MediaBackupEntityUpsert>()
                                pathList.forEach { pathString ->
                                    if (pathString.isNotEmpty() && ConstantUtil.DefaultMediaList.indexOfFirst { it.second == pathString } == -1) {
                                        if (pathString == context.localBackupSaveDir()) {
                                            emitEffect(IndexUiEffect.ShowSnackbar(message = context.getString(R.string.backup_dir_as_media_error)))
                                            return@forEach
                                        }
                                        var name = PathUtil.getFileName(pathString)
                                        mediaBackupRepository.queryAllBackup().forEach {
                                            if (it.name == name && it.path != pathString) name = mediaBackupRepository.renameDuplicateMedia(name)
                                        }
                                        customMediaList.forEach {
                                            if (it.name == name && it.path != pathString) name = mediaBackupRepository.renameDuplicateMedia(name)
                                        }
                                        customMediaList.add(MediaBackupEntityUpsert(path = pathString, name = name))
                                    }
                                }
                                mediaBackupRepository.upsertBackup(customMediaList)
                                emitEffect(IndexUiEffect.ShowSnackbar(message = "${context.getString(R.string.succeed)}: ${customMediaList.size}"))
                            }
                        }
                    }
                }
            }

            is IndexUiIntent.Delete -> {
                mediaBackupRepository.deleteBackup(item = intent.entity)
            }
        }
    }

    val snackbarHostState: SnackbarHostState = SnackbarHostState()
    override suspend fun onEffect(effect: IndexUiEffect) {
        when (effect) {
            is IndexUiEffect.ShowSnackbar -> {
                snackbarHostState.showSnackbar(effect.message, effect.actionLabel, effect.withDismissAction, effect.duration)
            }
        }
    }

    private var _medium: Flow<List<MediaBackupEntity>> = mediaBackupRepository.medium.flowOnIO()
    private var _keyState: MutableStateFlow<String> = MutableStateFlow("")
    private val _mediumState: Flow<List<MediaBackupEntity>> =
        combine(_medium, _keyState) { medium, key ->
            medium.filter(mediaBackupRepository.getKeyPredicate(key = key))
        }.flowOnIO()
    val mediumState: StateFlow<List<MediaBackupEntity>> = _mediumState.stateInScope(listOf())
    val mediumSelectedState: StateFlow<List<MediaBackupEntity>> = _mediumState.map { medium ->
        medium.filter { it.selected }
    }.flowOnIO().stateInScope(listOf())
    val mediumNotSelectedState: StateFlow<List<MediaBackupEntity>> = _mediumState.map { medium ->
        medium.filter { it.selected.not() }
    }.flowOnIO().stateInScope(listOf())

    private val _topBarState: MutableStateFlow<TopBarState> = MutableStateFlow(TopBarState(title = StringResourceToken.fromStringId(R.string.backup_list)))
    val topBarState: StateFlow<TopBarState> = _topBarState.asStateFlow()
    private val _shimmeringState: MutableStateFlow<Boolean> = MutableStateFlow(true)
    val shimmeringState: StateFlow<Boolean> = _shimmeringState.asStateFlow()
}
