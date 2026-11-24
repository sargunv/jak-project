package dev.sargunv.pocketgoal

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.sargunv.pocketgoal.ui.AppTheme
import dev.sargunv.pocketgoal.ui.LibraryScreen
import dev.sargunv.pocketgoal.ui.LibraryViewModel
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The app's launcher: the games, installing them from their ISOs, and playing them.
 *
 * Other apps, such as frontends, can also open a game's disc image with it (ACTION_VIEW), to play
 * the game on the disc. It installs the game from that disc first, if it isn't installed or needs
 * updating, and starts it when the install finishes.
 */
class MainActivity : ComponentActivity() {
  /** The game to start when its install finishes, for a disc another app opened. */
  private var playWhenInstalled: Game? = null

  override fun onCreate(savedInstanceState: Bundle?) {
    enableEdgeToEdge()
    super.onCreate(savedInstanceState)
    playWhenInstalled =
      savedInstanceState?.getString(STATE_PLAY_WHEN_INSTALLED)?.let { id ->
        Game.entries.find { it.id == id }
      }
    setContent { AppTheme { Library() } }
    if (savedInstanceState == null) handleIntent()
    playWhenInstalls()
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    handleIntent()
  }

  override fun onSaveInstanceState(outState: Bundle) {
    super.onSaveInstanceState(outState)
    playWhenInstalled?.let { outState.putString(STATE_PLAY_WHEN_INSTALLED, it.id) }
  }

  private fun handleIntent() {
    if (intent.action == Intent.ACTION_VIEW) openDisc() else installFromDevIntent()
  }

  /** Plays the game on the disc image that another app opened with this one. */
  private fun openDisc() {
    val disc = intent.data ?: return
    lifecycleScope.launch {
      val game = withContext(Dispatchers.IO) { DiscIdentifier.identify(this@MainActivity, disc) }
      when {
        game == null ->
          Toast.makeText(this@MainActivity, R.string.open_unknown_disc, Toast.LENGTH_LONG).show()
        GameFiles.isInstalled(this@MainActivity, game) &&
          !GameFiles.needsUpdate(this@MainActivity, game) -> {
          play(this@MainActivity, game)
          // Leaving the game goes back to the app that opened the disc
          finish()
        }
        Installs.isRunning ->
          // One game installs at a time, and the library shows the one that's running
          Unit
        else -> {
          playWhenInstalled = game
          InstallService.start(this@MainActivity, game, disc)
        }
      }
    }
  }

  /**
   * Starts [playWhenInstalled] when its install succeeds, and forgets it if the install fails. A
   * game being updated is installed before its install starts, so it waits to see the install run.
   */
  private fun playWhenInstalls() {
    lifecycleScope.launch {
      repeatOnLifecycle(Lifecycle.State.STARTED) {
        var running = false
        Installs.state.collect { installs ->
          val game = playWhenInstalled ?: return@collect
          when (installs[game]) {
            is InstallState.Running -> running = true
            is InstallState.Failed -> playWhenInstalled = null
            null ->
              if (running && GameFiles.isInstalled(this@MainActivity, game)) {
                playWhenInstalled = null
                play(this@MainActivity, game)
                finish()
              }
          }
        }
      }
    }
  }

  /**
   * Debug builds install a game from an ISO path that the app can read, without the file picker,
   * for scripts and for Innkeeper, whose picker is the desktop's (android/scripts/install-iso.sh):
   * ```
   * adb shell am start -n dev.sargunv.pocketgoal/.MainActivity --es install_game jak1 \
   *   --es install_iso /sdcard/Android/data/dev.sargunv.pocketgoal/files/jak1.iso
   * ```
   */
  private fun installFromDevIntent() {
    if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return
    val id = intent.getStringExtra("install_game") ?: return
    val path = intent.getStringExtra("install_iso") ?: return
    val game = Game.entries.find { it.id == id } ?: return
    intent.removeExtra("install_game")
    InstallService.start(this, game, Uri.fromFile(File(path)))
  }
}

@Composable
private fun Library(viewModel: LibraryViewModel = viewModel()) {
  val context = LocalContext.current
  val games by viewModel.games.collectAsStateWithLifecycle()
  var installing by rememberSaveable { mutableStateOf<Game?>(null) }

  val pickIso =
    rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
      val game = installing
      installing = null
      if (game != null && uri != null) {
        DiscImages.remember(context, game, uri)
        InstallService.start(context, game, uri)
      }
    }
  // The install shows its progress in a notification, but goes ahead without one
  val askForNotifications =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
      pickIso.launch(ISO_TYPES)
    }

  // Game data can change while the app is away, as from push-data.sh
  LifecycleResumeEffect(Unit) {
    viewModel.refresh()
    onPauseOrDispose {}
  }

  val install: (Game) -> Unit = { game ->
    installing = game
    if (
      Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
          PackageManager.PERMISSION_GRANTED
    ) {
      askForNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    } else {
      pickIso.launch(ISO_TYPES)
    }
  }

  LibraryScreen(
    games = games,
    onInstall = install,
    // From the disc image the player picked before, or a new one if the app can't read it now
    onUpdate = { game ->
      DiscImages.get(context, game)?.let { InstallService.start(context, game, it) }
        ?: install(game)
    },
    onPlay = { game -> play(context, game) },
    onCancelInstall = { InstallService.cancel(context) },
    onDelete = viewModel::delete,
    onDismissError = viewModel::dismissError,
  )
}

private fun play(context: Context, game: Game) =
  context.startActivity(Intent(context, GameActivity::class.java).putExtra("game", game.id))

private const val STATE_PLAY_WHEN_INSTALLED = "play_when_installed"

/** Any file: providers label ISOs inconsistently, and the extractor checks what it gets. */
private val ISO_TYPES = arrayOf("*/*")
