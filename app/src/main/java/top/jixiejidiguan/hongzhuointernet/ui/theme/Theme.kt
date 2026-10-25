package top.jixiejidiguan.hongzhuointernet.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val LightColorScheme = lightColorScheme(
    primary = PurplePrimary,
    onPrimary = PurpleOnPrimary,
    primaryContainer = PurplePrimaryContainer,
    onPrimaryContainer = PurpleOnPrimaryContainer,
    secondary = PurpleSecondary,
    secondaryContainer = PurpleSecondaryContainer,
    onSecondaryContainer = PurpleOnSecondaryContainer,
    tertiaryContainer = PurpleTertiaryContainer,
    onTertiaryContainer = PurpleOnTertiaryContainer,
    surface = PurpleSurface,
    surfaceContainerLow = PurpleSurfaceContainerLow,
    surfaceContainer = PurpleSurfaceContainer,
    surfaceContainerHigh = PurpleSurfaceContainerHigh,
    surfaceContainerHighest = PurpleSurfaceContainerHighest,
    onSurface = PurpleOnSurface,
    onSurfaceVariant = PurpleOnSurfaceVariant,
    outline = PurpleOutline,
    outlineVariant = PurpleOutlineVariant,
    inverseSurface = PurpleInverseSurface,
    inverseOnSurface = PurpleInverseOnSurface,
    inversePrimary = PurpleInversePrimary,
    error = PurpleError,
    onError = PurpleOnError,
    errorContainer = PurpleErrorContainer,
    onErrorContainer = PurpleOnErrorContainer
)

@Composable
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
fun HongzhuointernetTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LightColorScheme,
        typography = Typography,
        motionScheme = MotionScheme.standard(),
        content = content
    )
}