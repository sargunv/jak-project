@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package dev.sargunv.pocketgoal.ui

import android.text.format.Formatter
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.ListItemShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.WavyProgressIndicatorDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.sargunv.pocketgoal.Game
import dev.sargunv.pocketgoal.InstallError
import dev.sargunv.pocketgoal.InstallProgress
import dev.sargunv.pocketgoal.InstallStep
import dev.sargunv.pocketgoal.R
import dev.sargunv.pocketgoal.label
import kotlin.math.roundToInt

/** The games, and for each one: play it, or install it from its ISO. */
@Composable
fun LibraryScreen(
  games: List<GameItem>,
  onInstall: (Game) -> Unit,
  onUpdate: (Game) -> Unit,
  onPlay: (Game) -> Unit,
  onCancelInstall: () -> Unit,
  onDelete: (Game) -> Unit,
  onDismissError: (Game) -> Unit,
) {
  val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
  val installing = games.any { it.status is GameStatus.Installing }
  var deleting by rememberSaveable { mutableStateOf<Game?>(null) }
  var showingError by rememberSaveable { mutableStateOf<Game?>(null) }
  var updating by rememberSaveable { mutableStateOf<Game?>(null) }

  // Segmented list items are the surface color, on a darker container, as in the system's settings.
  // On wide screens, the list keeps to a readable width in the middle.
  val background = MaterialTheme.colorScheme.surfaceContainer
  Box(Modifier.fillMaxSize().background(background), contentAlignment = Alignment.TopCenter) {
    Scaffold(
      modifier = Modifier.widthIn(max = 840.dp).nestedScroll(scrollBehavior.nestedScrollConnection),
      containerColor = background,
      topBar = {
        LargeFlexibleTopAppBar(
          title = { Text(stringResource(R.string.app_name)) },
          colors =
            TopAppBarDefaults.topAppBarColors(
              containerColor = background,
              scrolledContainerColor = background,
            ),
          scrollBehavior = scrollBehavior,
        )
      },
    ) { padding ->
      LazyColumn(
        contentPadding =
          PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = padding.calculateTopPadding(),
            bottom = padding.calculateBottomPadding() + 16.dp,
          ),
        verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
      ) {
        itemsIndexed(games, key = { _, item -> item.game }) { index, item ->
          GameListItem(
            item = item,
            shapes = ListItemDefaults.segmentedShapes(index, games.size),
            canInstall = !installing,
            onInstall = { onInstall(item.game) },
            onPlay = {
              if ((item.status as? GameStatus.Installed)?.needsUpdate == true) {
                updating = item.game
              } else {
                onPlay(item.game)
              }
            },
            onCancelInstall = onCancelInstall,
            onDelete = { deleting = item.game },
            onShowError = { showingError = item.game },
          )
        }
        item {
          Text(
            stringResource(R.string.library_footer),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
    }
  }

  updating?.let { game ->
    UpdateDialog(
      game = game,
      canUpdate = !installing,
      onConfirm = {
        updating = null
        onUpdate(game)
      },
      onDismiss = { updating = null },
    )
  }

  games
    .find { it.game == deleting }
    ?.let { item ->
      DeleteDialog(
        item = item,
        onConfirm = {
          onDelete(item.game)
          deleting = null
        },
        onDismiss = { deleting = null },
      )
    }

  games
    .find { it.game == showingError }
    ?.let { item ->
      val error = (item.status as? GameStatus.Failed)?.error ?: return@let
      ErrorDialog(
        game = item.game,
        error = error,
        onRetry = {
          showingError = null
          onDismissError(item.game)
          onInstall(item.game)
        },
        onDismiss = {
          showingError = null
          onDismissError(item.game)
        },
      )
    }
}

@Composable
private fun GameListItem(
  item: GameItem,
  shapes: ListItemShapes,
  canInstall: Boolean,
  onInstall: () -> Unit,
  onPlay: () -> Unit,
  onCancelInstall: () -> Unit,
  onDelete: () -> Unit,
  onShowError: () -> Unit,
) {
  val title = @Composable { Text(stringResource(item.game.title)) }
  when (val status = item.status) {
    is GameStatus.Installed ->
      SegmentedListItem(
        onClick = onPlay,
        shapes = shapes,
        supportingContent = {
          val size = Formatter.formatShortFileSize(LocalContext.current, status.bytes)
          if (status.needsUpdate) {
            Text(
              stringResource(R.string.game_needs_update, size),
              color = MaterialTheme.colorScheme.primary,
            )
          } else {
            Text(stringResource(R.string.game_installed, size))
          }
        },
        trailingContent = {
          GameMenu(canInstall = canInstall, onReinstall = onInstall, onDelete = onDelete)
        },
        content = title,
      )
    is GameStatus.NotInstalled ->
      SegmentedListItem(
        onClick = onInstall,
        shapes = shapes,
        enabled = canInstall,
        supportingContent = { Text(stringResource(R.string.game_not_installed)) },
        content = title,
      )
    is GameStatus.Installing ->
      SegmentedListItem(
        shapes = shapes,
        supportingContent = { InstallProgressContent(status.progress) },
        trailingContent = {
          TextButton(onClick = onCancelInstall) { Text(stringResource(android.R.string.cancel)) }
        },
        content = title,
      )
    is GameStatus.Failed ->
      SegmentedListItem(
        onClick = onShowError,
        shapes = shapes,
        supportingContent = {
          Text(
            stringResource(R.string.game_install_failed),
            color = MaterialTheme.colorScheme.error,
          )
        },
        content = title,
      )
  }
}

@Composable
private fun InstallProgressContent(progress: InstallProgress) {
  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    val step = stringResource(progress.step.label)
    val fraction = progress.fraction
    Text(
      if (fraction == null) step
      else stringResource(R.string.install_step_percent, step, (fraction * 100).roundToInt()),
    )
    val modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
    if (fraction == null) {
      LinearWavyProgressIndicator(modifier = modifier)
    } else {
      // Each step starts from its own progress, instead of running back from the last step's end
      key(progress.step) {
        val animated by
          animateFloatAsState(fraction, WavyProgressIndicatorDefaults.ProgressAnimationSpec)
        LinearWavyProgressIndicator(progress = { animated }, modifier = modifier)
      }
    }
  }
}

@Composable
private fun GameMenu(canInstall: Boolean, onReinstall: () -> Unit, onDelete: () -> Unit) {
  var expanded by remember { mutableStateOf(false) }
  Box {
    IconButton(onClick = { expanded = true }) {
      Icon(
        painterResource(R.drawable.ic_more_vert),
        contentDescription = stringResource(R.string.game_menu),
      )
    }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
      DropdownMenuItem(
        text = { Text(stringResource(R.string.game_reinstall)) },
        enabled = canInstall,
        onClick = {
          expanded = false
          onReinstall()
        },
      )
      DropdownMenuItem(
        text = { Text(stringResource(R.string.game_delete)) },
        onClick = {
          expanded = false
          onDelete()
        },
      )
    }
  }
}

@Composable
private fun UpdateDialog(
  game: Game,
  canUpdate: Boolean,
  onConfirm: () -> Unit,
  onDismiss: () -> Unit,
) {
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(stringResource(R.string.update_title, stringResource(game.title))) },
    text = { Text(stringResource(R.string.update_text)) },
    confirmButton = {
      TextButton(onClick = onConfirm, enabled = canUpdate) {
        Text(stringResource(R.string.update_confirm))
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
    },
  )
}

@Composable
private fun DeleteDialog(item: GameItem, onConfirm: () -> Unit, onDismiss: () -> Unit) {
  AlertDialog(
    onDismissRequest = onDismiss,
    title = {
      Text(stringResource(R.string.delete_title, stringResource(item.game.title)))
    },
    text = { Text(stringResource(R.string.delete_text)) },
    confirmButton = {
      TextButton(onClick = onConfirm) { Text(stringResource(R.string.game_delete)) }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
    },
  )
}

@Composable
private fun ErrorDialog(
  game: Game,
  error: InstallError,
  onRetry: () -> Unit,
  onDismiss: () -> Unit,
) {
  val context = LocalContext.current
  val gameTitle = stringResource(game.title)
  val message =
    when (error) {
      is InstallError.NotEnoughSpace ->
        stringResource(
          R.string.error_space,
          Formatter.formatShortFileSize(context, error.neededBytes),
        )
      is InstallError.NotAnIso -> stringResource(R.string.error_not_iso)
      is InstallError.UnknownIso -> stringResource(R.string.error_unknown_iso, gameTitle)
      is InstallError.Killed -> stringResource(R.string.error_killed)
      is InstallError.Failed -> stringResource(R.string.error_failed, error.exitStatus)
    }
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(stringResource(R.string.error_title, gameTitle)) },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(message)
        error.detail?.let {
          Text(
            it,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
    },
    confirmButton = {
      TextButton(onClick = onRetry) { Text(stringResource(R.string.error_retry)) }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) { Text(stringResource(R.string.error_close)) }
    },
  )
}

@Preview
@Composable
private fun LibraryScreenPreview() {
  AppTheme {
    LibraryScreen(
      games =
        listOf(
          GameItem(Game.Jak1, GameStatus.Installed(1_650_000_000)),
          GameItem(Game.Jak2, GameStatus.Installing(InstallProgress(InstallStep.Decompile, 0.4f))),
          GameItem(Game.Jak3, GameStatus.NotInstalled),
        ),
      onInstall = {},
      onUpdate = {},
      onPlay = {},
      onCancelInstall = {},
      onDelete = {},
      onDismissError = {},
    )
  }
}
