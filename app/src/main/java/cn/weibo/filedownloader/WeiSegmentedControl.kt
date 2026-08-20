package cn.weibo.filedownloader

import android.content.Context
import android.content.res.Configuration
import android.util.TypedValue
import androidx.annotation.AttrRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color.Companion.Transparent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.google.android.material.R as MaterialR

@Composable
fun WeiSingleChoiceSegmentedControl(
    labels: List<String>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
) {
    MaterialTheme(colorScheme = currentViewColorScheme()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(CircleShape)
                .background(Transparent, CircleShape),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            labels.forEachIndexed { index, label ->
                val selected = selectedIndex == index
                val shape = if (selected) CircleShape else RoundedCornerShape(10.dp)
                val containerColor by animateColorAsState(
                    if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
                    label = "ButtonColor",
                )
                val contentColor by animateColorAsState(
                    if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                    label = "ContentColor",
                )

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .clip(shape)
                        .background(containerColor)
                        .clickable { onSelected(index) },
                    contentAlignment = Alignment.Center,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        AnimatedVisibility(visible = selected) {
                            Icon(
                                painter = painterResource(R.drawable.ic_done),
                                contentDescription = null,
                                modifier = Modifier.padding(end = 8.dp).size(18.dp),
                                tint = contentColor,
                            )
                        }
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelLarge,
                            color = contentColor,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun currentViewColorScheme() = LocalContext.current.let { context ->
    val dark = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
        Configuration.UI_MODE_NIGHT_YES
    val primary = context.themeColor(MaterialR.attr.colorPrimary)
    val onPrimary = context.themeColor(MaterialR.attr.colorOnPrimary)
    val primaryContainer = context.themeColor(MaterialR.attr.colorPrimaryContainer)
    val onPrimaryContainer = context.themeColor(MaterialR.attr.colorOnPrimaryContainer)
    val secondary = context.themeColor(MaterialR.attr.colorSecondary)
    val onSecondary = context.themeColor(MaterialR.attr.colorOnSecondary)
    val secondaryContainer = context.themeColor(MaterialR.attr.colorSecondaryContainer)
    val onSecondaryContainer = context.themeColor(MaterialR.attr.colorOnSecondaryContainer)
    val surface = context.themeColor(MaterialR.attr.colorSurface)
    val onSurface = context.themeColor(MaterialR.attr.colorOnSurface)
    val surfaceVariant = context.themeColor(MaterialR.attr.colorSurfaceVariant)
    val onSurfaceVariant = context.themeColor(MaterialR.attr.colorOnSurfaceVariant)
    val outline = context.themeColor(MaterialR.attr.colorOutline)
    val error = context.themeColor(MaterialR.attr.colorError)
    val onError = context.themeColor(MaterialR.attr.colorOnError)
    val errorContainer = context.themeColor(MaterialR.attr.colorErrorContainer)
    val onErrorContainer = context.themeColor(MaterialR.attr.colorOnErrorContainer)
    if (dark) {
        darkColorScheme(
            primary = primary,
            onPrimary = onPrimary,
            primaryContainer = primaryContainer,
            onPrimaryContainer = onPrimaryContainer,
            secondary = secondary,
            onSecondary = onSecondary,
            secondaryContainer = secondaryContainer,
            onSecondaryContainer = onSecondaryContainer,
            surface = surface,
            onSurface = onSurface,
            surfaceVariant = surfaceVariant,
            onSurfaceVariant = onSurfaceVariant,
            outline = outline,
            error = error,
            onError = onError,
            errorContainer = errorContainer,
            onErrorContainer = onErrorContainer,
        )
    } else {
        lightColorScheme(
            primary = primary,
            onPrimary = onPrimary,
            primaryContainer = primaryContainer,
            onPrimaryContainer = onPrimaryContainer,
            secondary = secondary,
            onSecondary = onSecondary,
            secondaryContainer = secondaryContainer,
            onSecondaryContainer = onSecondaryContainer,
            surface = surface,
            onSurface = onSurface,
            surfaceVariant = surfaceVariant,
            onSurfaceVariant = onSurfaceVariant,
            outline = outline,
            error = error,
            onError = onError,
            errorContainer = errorContainer,
            onErrorContainer = onErrorContainer,
        )
    }
}

private fun Context.themeColor(@AttrRes attribute: Int): Color {
    val value = TypedValue()
    check(theme.resolveAttribute(attribute, value, true)) { "Missing theme color attribute $attribute" }
    val color = if (value.resourceId != 0) ContextCompat.getColor(this, value.resourceId) else value.data
    return Color(color)
}
