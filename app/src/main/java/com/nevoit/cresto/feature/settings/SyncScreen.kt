package com.nevoit.cresto.feature.settings

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import org.koin.compose.viewmodel.koinViewModel
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.nevoit.cresto.R
import com.nevoit.cresto.data.sync.SyncStatus
import com.nevoit.cresto.feature.settings.util.SettingsManager
import com.nevoit.cresto.theme.AppButtonColors
import com.nevoit.cresto.theme.AppColors
import com.nevoit.cresto.theme.harmonize
import com.nevoit.cresto.ui.components.glasense.GlasenseButton
import com.nevoit.cresto.ui.components.glasense.GlasenseDynamicSmallTitle
import com.nevoit.cresto.ui.components.glasense.GlasenseSwitch
import com.nevoit.cresto.ui.components.glasense.extend.overscrollSpacer
import com.nevoit.cresto.ui.components.glasense.isScrolledPast
import com.nevoit.cresto.ui.components.packed.ConfigInfoHeader
import com.nevoit.cresto.ui.components.packed.ConfigItem
import com.nevoit.cresto.ui.components.packed.ConfigItemContainer
import com.nevoit.cresto.ui.components.packed.ConfigTextField
import com.nevoit.cresto.ui.components.packed.PageContent
import com.nevoit.glasense.core.component.Icon
import com.nevoit.glasense.core.component.Text
import com.nevoit.glasense.core.component.VGap
import com.nevoit.glasense.theme.GlasenseTheme
import com.nevoit.glasense.theme.tokens.Blue500
import com.nevoit.glasense.theme.tokens.Green500
import com.nevoit.glasense.theme.tokens.Red500

@Composable
fun SyncScreen(viewModel: SyncSettingsViewModel = koinViewModel()) {
    val activity = LocalActivity.current
    val statusBarHeight = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val backgroundColor = AppColors.pageBackground
    val surfaceColor = AppColors.cardBackground
    val lazyListState = rememberLazyListState()
    val isSmallTitleVisible by lazyListState.isScrolledPast(statusBarHeight + 24.dp)

    val configState by viewModel.configState.collectAsState()
    val syncStatus by viewModel.syncStatus.collectAsState()
    val conflicts by viewModel.conflicts.collectAsState()

    val backdrop = rememberLayerBackdrop {
        drawRect(
            color = backgroundColor,
            size = Size(this.size.width * 3, this.size.height * 3),
            topLeft = Offset(-this.size.width, -this.size.height)
        )
        drawContent()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundColor)
    ) {
        PageContent(
            state = lazyListState,
            modifier = Modifier.layerBackdrop(backdrop),
            tabPadding = false
        ) {
            item {
                Box(modifier = Modifier.padding(top = 48.dp + statusBarHeight + 12.dp))
            }

            // Header
            item {
                ConfigInfoHeader(
                    color = harmonize(Blue500),
                    backgroundColor = surfaceColor,
                    icon = painterResource(R.drawable.ic_cloud_sync),
                    title = stringResource(R.string.webdav_sync),
                    info = stringResource(R.string.webdav_sync_summary)
                )
                VGap()
            }

            // Enable/Disable toggle
            val isSyncEnabled by SettingsManager.isWebDavSyncEnabledState
            item {
                ConfigItemContainer(backgroundColor = surfaceColor) {
                    ConfigItem(
                        title = stringResource(R.string.webdav_auto_sync),
                        clickable = true,
                        indication = true,
                        onClick = { SettingsManager.isWebDavSyncEnabled = !isSyncEnabled }
                    ) {
                        GlasenseSwitch(
                            checked = isSyncEnabled,
                            onCheckedChange = { SettingsManager.isWebDavSyncEnabled = it },
                            backgroundColor = surfaceColor
                        )
                    }
                }
                VGap()
            }

            // Server URL
            item {
                ConfigTextField(
                    title = stringResource(R.string.webdav_server_url),
                    value = configState.serverUrl,
                    onValueChange = { viewModel.updateServerUrl(it) },
                    backgroundColor = surfaceColor,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Done
                    )
                )
                VGap()
            }

            // Username
            item {
                ConfigTextField(
                    title = stringResource(R.string.webdav_username),
                    value = configState.username,
                    onValueChange = { viewModel.updateUsername(it) },
                    backgroundColor = surfaceColor,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        imeAction = ImeAction.Done
                    )
                )
                VGap()
            }

            // Password
            item {
                ConfigTextField(
                    title = stringResource(R.string.webdav_password),
                    value = configState.password,
                    onValueChange = { viewModel.updatePassword(it) },
                    backgroundColor = surfaceColor,
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done
                    )
                )
                VGap()
            }

            // Test Connection
            item {
                ConfigItemContainer(backgroundColor = surfaceColor) {
                    Column {
                        ConfigItem(
                            title = stringResource(R.string.webdav_test_connection),
                            clickable = true,
                            indication = true,
                            onClick = {
                                if (!configState.isTesting && configState.serverUrl.isNotBlank()) {
                                    viewModel.testConnection()
                                }
                            }
                        ) {
                            if (configState.isTesting) {
                                Text(
                                    text = stringResource(R.string.testing),
                                    color = AppColors.contentVariant
                                )
                            } else {
                                Text(
                                    text = stringResource(R.string.test),
                                    color = harmonize(Blue500)
                                )
                            }
                        }
                        configState.testResult?.let { result ->
                            Text(
                                text = when (result) {
                                    is TestConnectionResult.Success ->
                                        stringResource(R.string.webdav_connection_success)
                                    is TestConnectionResult.Failed ->
                                        "${stringResource(R.string.webdav_connection_failed)}: ${result.message}"
                                },
                                style = GlasenseTheme.type.callout,
                                color = when (result) {
                                    is TestConnectionResult.Success -> harmonize(Green500)
                                    is TestConnectionResult.Failed -> harmonize(Red500)
                                },
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                            )
                        }
                    }
                }
                VGap()
            }

            // Sync Status
            item {
                ConfigItemContainer(
                    title = stringResource(R.string.sync),
                    backgroundColor = surfaceColor
                ) {
                    Column {
                        ConfigItem(
                            title = stringResource(R.string.webdav_last_sync)
                        ) {
                            Text(
                                text = when (val status = syncStatus) {
                                    is SyncStatus.Syncing -> stringResource(R.string.webdav_syncing)
                                    is SyncStatus.Success ->
                                        stringResource(R.string.webdav_items_synced, status.itemsSynced)
                                    is SyncStatus.Error -> status.message
                                    is SyncStatus.NotConfigured -> stringResource(R.string.webdav_never)
                                    is SyncStatus.WaitingForNetwork -> stringResource(R.string.waiting_for_network)
                                    else -> viewModel.formatLastSyncTime() ?: stringResource(R.string.webdav_never)
                                },
                                color = when (syncStatus) {
                                    is SyncStatus.Success -> harmonize(Green500)
                                    is SyncStatus.Error -> harmonize(Red500)
                                    is SyncStatus.Syncing -> harmonize(Blue500)
                                    else -> AppColors.contentVariant
                                }
                            )
                        }
                    }
                }
                VGap()
            }

            // Sync Now button
            item {
                ConfigItemContainer(backgroundColor = surfaceColor) {
                    ConfigItem(
                        title = stringResource(R.string.webdav_sync_now),
                        clickable = true,
                        indication = true,
                        onClick = { viewModel.syncNow() }
                    ) {
                        Text(
                            text = stringResource(R.string.webdav_sync_now),
                            color = if (syncStatus !is SyncStatus.Syncing) harmonize(Blue500)
                                    else AppColors.contentVariant
                        )
                    }
                }
                VGap()
            }

            // Conflicts
            if (conflicts.isNotEmpty()) {
                item {
                    ConfigItemContainer(
                        title = stringResource(R.string.webdav_conflicts_title),
                        backgroundColor = surfaceColor
                    ) {
                        conflicts.forEach { conflict ->
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 8.dp)
                            ) {
                                Text(
                                    text = conflict.title,
                                    style = GlasenseTheme.type.body,
                                    color = AppColors.content
                                )
                                Text(
                                    text = stringResource(R.string.webdav_conflicts_found, conflict.conflictFields.size),
                                    style = GlasenseTheme.type.callout,
                                    color = AppColors.contentVariant
                                )
                                Box(modifier = Modifier.padding(top = 8.dp)) {
                                    GlasenseButton(
                                        onClick = { viewModel.acceptLocal(conflict.syncId) },
                                        modifier = Modifier.padding(end = 8.dp),
                                        colors = AppButtonColors.action()
                                    ) {
                                        Text(stringResource(R.string.webdav_accept_local))
                                    }
                                    GlasenseButton(
                                        onClick = { viewModel.acceptRemote(conflict.syncId) },
                                        colors = AppButtonColors.action()
                                    ) {
                                        Text(stringResource(R.string.webdav_accept_remote))
                                    }
                                }
                            }
                        }
                    }
                    VGap()
                }
            }

            // Clear remote data
            item {
                ConfigItemContainer(backgroundColor = surfaceColor) {
                    ConfigItem(
                        title = stringResource(R.string.webdav_clear_remote_data),
                        color = AppColors.error,
                        clickable = true,
                        indication = true,
                        onClick = { viewModel.clearRemoteData() }
                    ) {}
                }
            }

            item { VGap() }
            overscrollSpacer(lazyListState)
        }

        // Small title
        GlasenseDynamicSmallTitle(
            modifier = Modifier.align(Alignment.TopCenter),
            title = stringResource(R.string.webdav_sync),
            statusBarHeight = statusBarHeight,
            isVisible = isSmallTitleVisible,
            backdrop = backdrop,
            surfaceColor = backgroundColor
        ) {}

        // Back button
        GlasenseButton(
            enabled = true,
            shape = CircleShape,
            onClick = { activity?.finish() },
            modifier = Modifier
                .padding(top = statusBarHeight, start = 12.dp)
                .size(48.dp)
                .align(Alignment.TopStart),
            colors = AppButtonColors.action()
        ) {
            Icon(
                painter = painterResource(id = R.drawable.ic_forward_nav),
                contentDescription = stringResource(R.string.back),
                modifier = Modifier.width(32.dp)
            )
        }
    }
}
