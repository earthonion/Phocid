package org.sunsetware.phocid.ui.views.player

import androidx.compose.runtime.Immutable
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import org.sunsetware.phocid.ui.components.BinaryDragState
import org.sunsetware.phocid.utils.roundToIntOrZero

@Immutable
object PlayerScreenLayoutDefault : PlayerScreenLayout() {
    override fun Placeable.PlacementScope.place(
        topBarStandalone: Measurable,
        topBarOverlay: Measurable,
        artwork: Measurable,
        lyricsView: Measurable,
        lyricsOverlay: Measurable,
        controls: Measurable,
        queue: Measurable,
        scrimQueue: Measurable,
        scrimLyrics: Measurable,
        width: Int,
        height: Int,
        density: Density,
        queueDragState: BinaryDragState,
        lyricsViewVisibility: Float,
    ) {
        with(
            when (aspectRatio(width, height, 1.5f)) {
                AspectRatio.LANDSCAPE -> PlayerScreenLayoutDefaultLandscape
                AspectRatio.SQUARE -> PlayerScreenLayoutDefaultSquare
                AspectRatio.PORTRAIT -> PlayerScreenLayoutDefaultPortrait
            }
        ) {
            place(
                topBarStandalone,
                topBarOverlay,
                artwork,
                lyricsView,
                lyricsOverlay,
                controls,
                queue,
                scrimQueue,
                scrimLyrics,
                width,
                height,
                density,
                queueDragState,
                lyricsViewVisibility,
            )
        }
    }
}

@Immutable
object PlayerScreenLayoutDefaultPortrait : PlayerScreenLayout() {
    override fun Placeable.PlacementScope.place(
        topBarStandalone: Measurable,
        topBarOverlay: Measurable,
        artwork: Measurable,
        lyricsView: Measurable,
        lyricsOverlay: Measurable,
        controls: Measurable,
        queue: Measurable,
        scrimQueue: Measurable,
        scrimLyrics: Measurable,
        width: Int,
        height: Int,
        density: Density,
        queueDragState: BinaryDragState,
        lyricsViewVisibility: Float,
    ) {
        val offset = (queueDragState.length * queueDragState.position).roundToIntOrZero()

        val artworkHeight = width
        artwork
            .measure(Constraints(maxWidth = width, maxHeight = artworkHeight))
            .placeRelative(0, 0)

        lyricsOverlay
            .measure(Constraints(maxWidth = width, maxHeight = artworkHeight))
            .placeRelative(0, 0)
        val topBarOverlayPlaceable =
            topBarOverlay.measure(Constraints(maxWidth = width, maxHeight = artworkHeight))
        if (lyricsViewVisibility <= 0) {
            topBarOverlayPlaceable.placeRelative(0, 0)
        }
        scrimQueue
            .measure(Constraints(maxWidth = width, maxHeight = artworkHeight))
            .placeRelativeWithLayer(0, 0) { alpha = queueDragState.position }

        if (lyricsViewVisibility < 0.5) {
            val controlsPlaceable =
                controls.measure(
                    Constraints(maxWidth = width, maxHeight = height - artworkHeight + offset)
                )
            controlsPlaceable.placeRelative(0, artworkHeight - offset)
            queue
                .measure(
                    Constraints(
                        maxWidth = width,
                        maxHeight =
                            (height - artworkHeight + offset - controlsPlaceable.height)
                                .coerceAtLeast(0),
                    )
                )
                .placeRelative(0, artworkHeight - offset + controlsPlaceable.height)
        }

        if (lyricsViewVisibility > 0) {
            scrimLyrics
                .measure(Constraints(maxWidth = width, maxHeight = height))
                .placeRelativeWithLayer(0, 0) {
                    alpha = (lyricsViewVisibility * 2).coerceIn(0f, 1f)
                }
            topBarOverlayPlaceable.placeRelative(0, 0)
        }
        if (lyricsViewVisibility >= 0.5) {
            val controlsPlaceable =
                controls.measure(
                    Constraints(
                        maxWidth = width,
                        maxHeight = (height - topBarOverlayPlaceable.height).coerceAtLeast(0),
                    )
                )
            lyricsView
                .measure(
                    Constraints(
                        maxWidth = width,
                        maxHeight =
                            (height - topBarOverlayPlaceable.height * 2 - controlsPlaceable.height)
                                .coerceAtLeast(0),
                    )
                )
                .placeRelativeWithLayer(0, topBarOverlayPlaceable.height) {
                    alpha = (lyricsViewVisibility * 2 - 1).coerceIn(0f, 1f)
                }
            controlsPlaceable.placeRelativeWithLayer(0, height - controlsPlaceable.height) {
                alpha = (lyricsViewVisibility * 2 - 1).coerceIn(0f, 1f)
            }
        }

        queueDragState.length = artworkHeight.toFloat()
    }
}

@Immutable
object PlayerScreenLayoutDefaultLandscape : PlayerScreenLayout() {
    override fun Placeable.PlacementScope.place(
        topBarStandalone: Measurable,
        topBarOverlay: Measurable,
        artwork: Measurable,
        lyricsView: Measurable,
        lyricsOverlay: Measurable,
        controls: Measurable,
        queue: Measurable,
        scrimQueue: Measurable,
        scrimLyrics: Measurable,
        width: Int,
        height: Int,
        density: Density,
        queueDragState: BinaryDragState,
        lyricsViewVisibility: Float,
    ) {
        val offset = (queueDragState.length * queueDragState.position).roundToIntOrZero()

        val artworkWidth = height
        artwork
            .measure(Constraints(maxWidth = artworkWidth, maxHeight = height))
            .placeRelative(0, 0)

        lyricsOverlay
            .measure(Constraints(maxWidth = artworkWidth, maxHeight = height))
            .placeRelativeWithLayer(0, 0) {
                alpha = (1 - lyricsViewVisibility * 2).coerceIn(0f, 1f)
            }
        topBarOverlay
            .measure(Constraints(maxWidth = artworkWidth, maxHeight = height))
            .placeRelative(0, 0)

        val controlsPlaceable =
            controls.measure(Constraints(maxWidth = width - artworkWidth, maxHeight = height))
        controlsPlaceable.placeRelative(artworkWidth, 0)
        scrimQueue
            .measure(Constraints(maxWidth = width - artworkWidth, maxHeight = height))
            .placeRelativeWithLayer(artworkWidth, 0) { alpha = queueDragState.position }

        queue
            .measure(
                Constraints(
                    maxWidth = width - artworkWidth,
                    maxHeight = (height - controlsPlaceable.height + offset).coerceAtLeast(0),
                )
            )
            .placeRelative(artworkWidth, controlsPlaceable.height - offset)

        if (lyricsViewVisibility > 0) {
            scrimLyrics
                .measure(Constraints(maxWidth = width - artworkWidth, maxHeight = height))
                .placeRelativeWithLayer(artworkWidth, 0) {
                    alpha = (lyricsViewVisibility * 2).coerceIn(0f, 1f)
                }
        }
        if (lyricsViewVisibility >= 0.5) {
            lyricsView
                .measure(Constraints(maxWidth = width - artworkWidth, maxHeight = height))
                .placeRelativeWithLayer(artworkWidth, 0) {
                    alpha = (lyricsViewVisibility * 2 - 1).coerceIn(0f, 1f)
                }
        }

        queueDragState.length = controlsPlaceable.height.toFloat()
    }
}

@Immutable
object PlayerScreenLayoutDefaultSquare : PlayerScreenLayout() {
    override fun Placeable.PlacementScope.place(
        topBarStandalone: Measurable,
        topBarOverlay: Measurable,
        artwork: Measurable,
        lyricsView: Measurable,
        lyricsOverlay: Measurable,
        controls: Measurable,
        queue: Measurable,
        scrimQueue: Measurable,
        scrimLyrics: Measurable,
        width: Int,
        height: Int,
        density: Density,
        queueDragState: BinaryDragState,
        lyricsViewVisibility: Float,
    ) {
        val offset = (queueDragState.length * queueDragState.position).roundToIntOrZero()

        val topBarPlaceable =
            topBarStandalone.measure(Constraints(maxWidth = width, maxHeight = height))
        if (lyricsViewVisibility <= 0) {
            topBarPlaceable.placeRelative(0, 0)
        }

        val controlsPlaceable =
            controls.measure(
                Constraints(
                    maxWidth = width,
                    maxHeight = (height - topBarPlaceable.height).coerceAtLeast(0),
                )
            )
        controlsPlaceable.placeRelative(0, topBarPlaceable.height)

        scrimQueue
            .measure(Constraints(maxWidth = width, maxHeight = height))
            .placeRelativeWithLayer(0, 0) { alpha = queueDragState.position }

        queue
            .measure(
                Constraints(
                    maxWidth = width,
                    maxHeight =
                        (height - topBarPlaceable.height - controlsPlaceable.height + offset)
                            .coerceAtLeast(0),
                )
            )
            .placeRelative(0, topBarPlaceable.height - offset + controlsPlaceable.height)

        if (lyricsViewVisibility > 0) {
            scrimLyrics
                .measure(Constraints(maxWidth = width, maxHeight = height))
                .placeRelativeWithLayer(0, 0) {
                    alpha = (lyricsViewVisibility * 2).coerceIn(0f, 1f)
                }
            topBarPlaceable.placeRelative(0, 0)
        }
        if (lyricsViewVisibility >= 0.5) {
            lyricsView
                .measure(
                    Constraints(
                        maxWidth = width,
                        maxHeight = (height - topBarPlaceable.height).coerceAtLeast(0),
                    )
                )
                .placeRelativeWithLayer(0, topBarPlaceable.height) {
                    alpha = (lyricsViewVisibility * 2 - 1).coerceIn(0f, 1f)
                }
        }

        queueDragState.length = (topBarPlaceable.height + controlsPlaceable.height).toFloat()
    }
}
