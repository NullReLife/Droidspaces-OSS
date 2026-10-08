package com.droidspaces.app.ui.util

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Standardized loading indicator sizes.
 */
enum class LoadingSize(val size: Dp) {
    Small(16.dp),
    Medium(24.dp),
    Large(48.dp),
    /** Full-screen setup-flow hero (backend installer). The empty-state icon stays 64. */
    Hero(96.dp)
}

/**
 * The M3 Expressive loading indicator at one of the app's standard sizes. [contained]
 * puts it in its primaryContainer bubble, the same pebble the pull-to-refresh shows,
 * for a hero that a result container will take over from.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun LoadingIndicator(
    size: LoadingSize,
    modifier: Modifier = Modifier,
    color: Color? = null,
    contained: Boolean = false
) {
    if (contained) {
        androidx.compose.material3.ContainedLoadingIndicator(modifier = modifier.size(size.size))
    } else {
        androidx.compose.material3.LoadingIndicator(
            modifier = modifier.size(size.size),
            color = color ?: MaterialTheme.colorScheme.primary
        )
    }
}

/**
 * Full-screen loading indicator with optional message.
 */
@Composable
fun FullScreenLoading(
    message: String? = null,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            LoadingIndicator(size = LoadingSize.Large)
            message?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    // Fills the column so the text's centre line is the spinner's
                    // centre line; without it the column shrinks to the text and a
                    // wrapped message drifts out of line with the spinner above it.
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}
