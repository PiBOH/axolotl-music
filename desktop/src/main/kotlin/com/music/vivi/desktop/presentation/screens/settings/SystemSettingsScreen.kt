package com.music.vivi.desktop.presentation.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.music.vivi.desktop.generated.resources.Res
import com.music.vivi.desktop.generated.resources.ic_automirrored_rounded_arrow_back
import com.music.vivi.desktop.generated.resources.ic_rounded_chevron_right
import com.music.vivi.desktop.generated.resources.ic_rounded_code
import com.music.vivi.desktop.generated.resources.ic_rounded_system_update
import com.music.vivi.desktop.presentation.components.subcomps.AppTooltip
import com.music.vivi.desktop.presentation.viewmodel.PlayerViewModel
import org.jetbrains.compose.resources.painterResource

/**
 * The root System page. Updates used to sit under Backup, and Developer
 * Options was reached from the old 1.55 System page. Both now live here, and
 * Developer Options only exists after the About logo has been tapped seven
 * times.
 */
@Composable
fun SystemSettingsScreen(
    viewModel: PlayerViewModel,
    onBack: () -> Unit,
    onNavigate: (SettingsSection) -> Unit,
    modifier: Modifier = Modifier,
) {
    val developerUnlocked by viewModel.developerOptionsUnlocked.collectAsState()
    val colors = MaterialTheme.colorScheme
    val rows = if (developerUnlocked) 2 else 1

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 10.dp, top = 10.dp, end = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppTooltip("Back") {
                IconButton(onClick = onBack) {
                    Icon(
                        painter = painterResource(Res.drawable.ic_automirrored_rounded_arrow_back),
                        contentDescription = "Back",
                        tint = colors.onSurface,
                    )
                }
            }
            SettingsHeader(
                title = "System",
                subtitle = "Updates, developer tools and diagnostics",
                horizontalPadding = 0.dp,
                modifier = Modifier.weight(1f),
            )
        }

        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(horizontal = 28.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                SettingsSubsection(title = "SYSTEM") {
                    ExpressiveSettingsRow(
                        title = "Updates",
                        subtitle = "Check versions, read the changelog and download the installer",
                        leadingIcon = {
                            Icon(
                                painter = painterResource(Res.drawable.ic_rounded_system_update),
                                contentDescription = null,
                                tint = colors.secondary,
                                modifier = Modifier.size(22.dp),
                            )
                        },
                        trailingIcon = {
                            Icon(
                                painter = painterResource(Res.drawable.ic_rounded_chevron_right),
                                contentDescription = null,
                                tint = colors.onSurfaceVariant.copy(alpha = 0.6f),
                                modifier = Modifier.size(22.dp),
                            )
                        },
                        shape = getGroupShape(0, rows),
                        onClick = { onNavigate(SettingsSection.V155_UPDATES) },
                    )

                    if (developerUnlocked) {
                        ExpressiveSettingsRow(
                            title = "Developer options",
                            subtitle = "Heap, overlays, profiles and logs",
                            leadingIcon = {
                                Icon(
                                    painter = painterResource(Res.drawable.ic_rounded_code),
                                    contentDescription = null,
                                    tint = colors.secondary,
                                    modifier = Modifier.size(22.dp),
                                )
                            },
                            trailingIcon = {
                                Icon(
                                    painter = painterResource(Res.drawable.ic_rounded_chevron_right),
                                    contentDescription = null,
                                    tint = colors.onSurfaceVariant.copy(alpha = 0.6f),
                                    modifier = Modifier.size(22.dp),
                                )
                            },
                            shape = getGroupShape(1, rows),
                            onClick = { onNavigate(SettingsSection.V155_DEVELOPER) },
                        )
                    }
                }
            }
            item { Spacer(Modifier.size(16.dp)) }
        }
    }
}
