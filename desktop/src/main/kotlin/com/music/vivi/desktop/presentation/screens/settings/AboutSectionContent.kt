package com.music.vivi.desktop.presentation.screens.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource

import com.music.vivi.desktop.BuildConfig
import com.music.vivi.desktop.generated.resources.Res
import com.music.vivi.desktop.generated.resources.ic_automirrored_rounded_open_in_new
import com.music.vivi.desktop.generated.resources.ic_github
import com.music.vivi.desktop.generated.resources.ic_rounded_auto_awesome
import com.music.vivi.desktop.generated.resources.ic_rounded_balance
import com.music.vivi.desktop.generated.resources.ic_rounded_chevron_right
import com.music.vivi.desktop.generated.resources.ic_rounded_group
import com.music.vivi.desktop.generated.resources.ic_rounded_palette
import com.music.vivi.desktop.presentation.components.ViviLogo
import com.music.vivi.desktop.generated.resources.ic_rounded_public
import com.music.vivi.desktop.generated.resources.ic_telegram
import com.music.vivi.desktop.presentation.viewmodel.PlayerViewModel
import com.music.vivi.desktop.utils.BrowserUtils
import kotlinx.coroutines.delay

private data class ProjectLink(
    val id: String,
    val title: String,
    val subtitle: String,
    val icon: DrawableResource,
    val url: String
)

private data class Contributor(
    val id: String,
    val displayName: String,
    val role: String,
    val detail: String,
    val avatarUrl: String,
    val githubUrl: String? = null,
    val telegramUrl: String? = null
)

private val MaintainersList = listOf(
    Contributor(
        id = "piboh",
        displayName = "@PiBOH",
        role = "Lead developer",
        detail = "Lead developer and maintainer of Axolotl Music.",
        avatarUrl = "https://github.com/PiBOH.png",
        githubUrl = "https://github.com/PiBOH"
    )
)

private val ProjectLinks = listOf(
    ProjectLink(
        id = "source",
        title = "Source code",
        subtitle = "github.com/PiBOH/axolotl-music",
        icon = Res.drawable.ic_github,
        url = "https://github.com/PiBOH/axolotl-music"
    ),
    ProjectLink(
        id = "licenses",
        title = "Licenses & attribution",
        subtitle = "Open-source libraries, fonts and bundled data",
        icon = Res.drawable.ic_rounded_balance,
        url = ""
    )
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AboutSectionContent(
    viewModel: PlayerViewModel? = null,
    onOpenLicenses: () -> Unit = {},
    onOpenContributors: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    val developerUnlocked = viewModel?.developerOptionsUnlocked?.collectAsState()?.value ?: false

    var logoTaps by remember { mutableStateOf(0) }
    var logoFeedback by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(logoFeedback) {
        if (logoFeedback != null) {
            delay(2_400)
            logoFeedback = null
        }
    }

    val onLogoTap: () -> Unit = {
        if (developerUnlocked) {
            logoFeedback = "Developer options are already unlocked"
        } else {
            logoTaps += 1
            if (logoTaps >= 7) {
                viewModel?.setDeveloperOptionsUnlocked(true)
                logoFeedback = "Developer options unlocked"
                logoTaps = 0
            } else if (logoTaps >= 4) {
                logoFeedback = "${7 - logoTaps} more taps to unlock Developer options"
            }
        }
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = colors.surfaceContainer,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ViviLogo(
                        modifier = Modifier
                            .size(64.dp)
                            .clickable { onLogoTap() }
                            .testTag("about-logo"),
                        contentDescription = BuildConfig.APP_NAME
                    )

                    Spacer(modifier = Modifier.width(16.dp))

                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = BuildConfig.APP_NAME,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = colors.onSurface
                        )
                        Text(
                            text = "Local-first music playback crafted with Material 3 expressive design.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = colors.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                AnimatedVisibility(visible = logoFeedback != null) {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = colors.secondaryContainer,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = logoFeedback.orEmpty(),
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.onSecondaryContainer,
                        )
                    }
                }

                Surface(
                    shape = CircleShape,
                    color = colors.tertiaryContainer
                ) {
                    Text(
                        text = "Version ${BuildConfig.APP_VERSION_NAME}",
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.onTertiaryContainer
                    )
                }

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val signals = listOf(
                        "Open source" to Res.drawable.ic_rounded_public,
                        "Community-first" to Res.drawable.ic_rounded_auto_awesome,
                        "Material 3 expressive" to Res.drawable.ic_rounded_palette
                    )
                    signals.forEach { (label, icon) ->
                        Surface(
                            shape = CircleShape,
                            color = colors.surfaceContainerHigh.copy(alpha = 0.9f)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    painter = painterResource(icon),
                                    contentDescription = null,
                                    tint = colors.onSurfaceVariant,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = colors.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }

        SettingsSubsection(title = "PROJECT") {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                ProjectLinks.forEachIndexed { index, link ->
                    Surface(
                        shape = getGroupShape(index, ProjectLinks.size),
                        color = colors.surfaceContainer,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                if (link.id == "licenses") {
                                    onOpenLicenses()
                                } else {
                                    BrowserUtils.openBrowser(link.url)
                                }
                            }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = colors.primaryContainer
                            ) {
                                Icon(
                                    painter = painterResource(link.icon),
                                    contentDescription = null,
                                    tint = colors.onPrimaryContainer,
                                    modifier = Modifier
                                        .padding(10.dp)
                                        .size(22.dp)
                                )
                            }

                            Spacer(modifier = Modifier.width(16.dp))

                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                Text(
                                    text = link.title,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = colors.onSurface
                                )
                                Text(
                                    text = link.subtitle,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = colors.onSurfaceVariant
                                )
                            }

                            val trailingIcon = if (link.id == "licenses") {
                                Res.drawable.ic_rounded_chevron_right
                            } else {
                                Res.drawable.ic_automirrored_rounded_open_in_new
                            }

                            Icon(
                                painter = painterResource(trailingIcon),
                                contentDescription = null,
                                tint = colors.onSurfaceVariant.copy(alpha = 0.6f),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
        }

        SettingsSubsection(title = "MAINTAINERS & AUTHORS") {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    MaintainersList.forEachIndexed { index, contributor ->
                        ContributorCard(
                            contributor = contributor,
                            shape = getGroupShape(index, MaintainersList.size),
                            onOpenUrl = BrowserUtils::openBrowser
                        )
                    }
                }
                ContributorsCard(onOpen = onOpenContributors)
            }
        }
    }
}

@Composable
private fun ContributorCard(
    contributor: Contributor,
    shape: androidx.compose.ui.graphics.Shape,
    onOpenUrl: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme

    Surface(
        shape = shape,
        color = colors.surfaceContainer,
        modifier = modifier
            .fillMaxWidth()
            .clickable(enabled = contributor.githubUrl != null) {
                contributor.githubUrl?.let(onOpenUrl)
            }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ContributorAvatar(
                avatarUrl = contributor.avatarUrl,
                fallbackName = contributor.displayName,
                modifier = Modifier.size(48.dp)
            )

            Spacer(modifier = Modifier.width(16.dp))

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = contributor.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.onSurface
                )
                Text(
                    text = contributor.role,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = colors.primary
                )
                Text(
                    text = contributor.detail,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                    lineHeight = 20.sp
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (contributor.githubUrl != null) {
                    IconButton(
                        onClick = { onOpenUrl(contributor.githubUrl) },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            painter = painterResource(Res.drawable.ic_github),
                            contentDescription = "GitHub profile",
                            tint = colors.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                if (contributor.telegramUrl != null) {
                    IconButton(
                        onClick = { onOpenUrl(contributor.telegramUrl) },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            painter = painterResource(Res.drawable.ic_telegram),
                            contentDescription = "Telegram profile",
                            tint = colors.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ContributorsCard(
    onOpen: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = colors.surfaceContainer,
        modifier = modifier
            .fillMaxWidth()
            .clickable { onOpen() }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = CircleShape,
                color = colors.primaryContainer
            ) {
                Icon(
                    painter = painterResource(Res.drawable.ic_rounded_group),
                    contentDescription = null,
                    tint = colors.onPrimaryContainer,
                    modifier = Modifier
                        .padding(10.dp)
                        .size(22.dp)
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 12.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = "Contributors",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.onSurface
                )
                Text(
                    text = "The people who build and test ${BuildConfig.APP_NAME}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.width(4.dp))

            Icon(
                painter = painterResource(Res.drawable.ic_rounded_chevron_right),
                contentDescription = null,
                tint = colors.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.size(20.dp)
            )
        }
    }
}
