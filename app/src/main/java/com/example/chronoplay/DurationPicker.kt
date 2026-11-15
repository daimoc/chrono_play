package com.example.chronoplay

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map

private val RowHeight = 48.dp

/** Hours and minutes scroll wheels; [minutes] is the total duration. */
@Composable
fun DurationPicker(minutes: Int, onChange: (Int) -> Unit, modifier: Modifier = Modifier) {
    val h = minutes / 60
    val m = minutes % 60
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Wheel(0..23, h, onValueChange = { onChange(it * 60 + m) })
        Text(stringResource(R.string.hours_unit), style = MaterialTheme.typography.titleMedium)
        Wheel(0..59, m, onValueChange = { onChange(h * 60 + it) })
        Text(stringResource(R.string.minutes_unit), style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun Wheel(range: IntRange, value: Int, onValueChange: (Int) -> Unit) {
    val state = rememberLazyListState(initialFirstVisibleItemIndex = value - range.first)
    val rowHeightPx = with(LocalDensity.current) { RowHeight.toPx() }
    val currentOnValueChange by rememberUpdatedState(onValueChange)

    // A blank row sits above the values, so the first visible item is the one above the center.
    val selected by remember(range) {
        derivedStateOf {
            val index = state.firstVisibleItemIndex +
                if (state.firstVisibleItemScrollOffset > rowHeightPx / 2) 1 else 0
            index.coerceIn(0, range.last - range.first)
        }
    }

    LaunchedEffect(state, range) {
        snapshotFlow { state.isScrollInProgress to selected }
            .filter { (scrolling, _) -> !scrolling }
            .map { (_, index) -> range.first + index }
            .distinctUntilChanged()
            .collect { currentOnValueChange(it) }
    }

    // Follow changes made elsewhere, e.g. replaying a Recent entry.
    LaunchedEffect(value) {
        if (!state.isScrollInProgress && range.first + selected != value) {
            state.scrollToItem(value - range.first)
        }
    }

    Box(Modifier.width(72.dp).height(RowHeight * 3), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(RowHeight)
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
        )
        LazyColumn(
            state = state,
            flingBehavior = rememberSnapFlingBehavior(state),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item { Box(Modifier.height(RowHeight)) }
            items(range.last - range.first + 1) { index ->
                val isSelected = index == selected
                Box(Modifier.height(RowHeight), contentAlignment = Alignment.Center) {
                    Text(
                        text = "%02d".format(range.first + index),
                        style = if (isSelected) MaterialTheme.typography.headlineMedium
                        else MaterialTheme.typography.titleMedium,
                        color = if (isSelected) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    )
                }
            }
            item { Box(Modifier.height(RowHeight)) }
        }
    }
}
