@file:OptIn(ExperimentalFoundationApi::class)

package org.sunsetware.phocid.ui.views.preferences

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.core.view.HapticFeedbackConstantsCompat
import androidx.core.view.ViewCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.sunsetware.phocid.Dialog
import org.sunsetware.phocid.MainViewModel
import org.sunsetware.phocid.R
import org.sunsetware.phocid.globals.Strings
import org.sunsetware.phocid.ui.components.DialogBase
import org.sunsetware.phocid.ui.components.UtilityCheckBoxListItem
import org.sunsetware.phocid.utils.swap
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

@Stable
class PreferencesTabsDialog() : Dialog() {
    private val lazyListState = LazyListState()

    @Composable
    override fun Compose(viewModel: MainViewModel) {
        val view = LocalView.current

        val preferences by viewModel.preferences.collectAsStateWithLifecycle()

        val reorderableLazyListState =
            rememberReorderableLazyListState(lazyListState) { from, to ->
                ViewCompat.performHapticFeedback(
                    view,
                    HapticFeedbackConstantsCompat.SEGMENT_FREQUENT_TICK,
                )
                viewModel.updatePreferences {
                    it.copy(
                        tabOrderAndVisibility =
                            it.tabOrderAndVisibility.toMutableList().apply {
                                add(to.index, removeAt(from.index))
                            }
                    )
                }
            }

        DialogBase(
            title = Strings[R.string.preferences_tabs],
            onConfirmOrDismiss = { viewModel.uiManager.closeDialog() },
        ) {
            LazyColumn(state = lazyListState) {
                itemsIndexed(preferences.tabOrderAndVisibility, { _, (type, _) -> type }) {
                    index,
                    (type, visibility) ->
                    ReorderableItem(reorderableLazyListState, type) { isDragging ->
                        UtilityCheckBoxListItem(
                            text = Strings[type.stringId],
                            checked = visibility,
                            onCheckedChange = { newVisibility ->
                                viewModel.updatePreferences { preferences ->
                                    preferences.copy(
                                        tabOrderAndVisibility =
                                            preferences.tabOrderAndVisibility.map {
                                                it.first to
                                                    (if (it.first == type) newVisibility
                                                    else it.second)
                                            }
                                    )
                                }
                            },
                            actions = {
                                IconButton(
                                    onClick = {
                                        if (index > 0) {
                                            viewModel.updatePreferences {
                                                it.copy(
                                                    tabOrderAndVisibility =
                                                        it.tabOrderAndVisibility.swap(
                                                            index,
                                                            index - 1,
                                                        )
                                                )
                                            }
                                        }
                                    }
                                ) {
                                    Icon(
                                        Icons.Filled.ArrowUpward,
                                        contentDescription = Strings[R.string.list_move_up],
                                    )
                                }
                                IconButton(
                                    onClick = {
                                        if (index < preferences.tabOrderAndVisibility.size - 1) {
                                            viewModel.updatePreferences {
                                                it.copy(
                                                    tabOrderAndVisibility =
                                                        it.tabOrderAndVisibility.swap(
                                                            index,
                                                            index + 1,
                                                        )
                                                )
                                            }
                                        }
                                    }
                                ) {
                                    Icon(
                                        Icons.Filled.ArrowDownward,
                                        contentDescription = Strings[R.string.list_move_down],
                                    )
                                }
                            },
                            modifier =
                                Modifier.draggableHandle(
                                        onDragStarted = {
                                            ViewCompat.performHapticFeedback(
                                                view,
                                                HapticFeedbackConstantsCompat.DRAG_START,
                                            )
                                        },
                                        onDragStopped = {
                                            ViewCompat.performHapticFeedback(
                                                view,
                                                HapticFeedbackConstantsCompat.GESTURE_END,
                                            )
                                        },
                                    )
                                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                        )
                    }
                }
            }
        }
    }
}
