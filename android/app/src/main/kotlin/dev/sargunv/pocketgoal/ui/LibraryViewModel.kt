package dev.sargunv.pocketgoal.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.sargunv.pocketgoal.Game
import dev.sargunv.pocketgoal.GameFiles
import dev.sargunv.pocketgoal.InstallError
import dev.sargunv.pocketgoal.InstallProgress
import dev.sargunv.pocketgoal.InstallState
import dev.sargunv.pocketgoal.Installer
import dev.sargunv.pocketgoal.Installs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the game list shows for each game. */
sealed interface GameStatus {
  data object NotInstalled : GameStatus

  /** [needsUpdate]: the data comes from another version of the app (GameFiles.needsUpdate). */
  data class Installed(val bytes: Long, val needsUpdate: Boolean = false) : GameStatus

  data class Installing(val progress: InstallProgress) : GameStatus

  data class Failed(val error: InstallError) : GameStatus
}

data class GameItem(val game: Game, val status: GameStatus)

class LibraryViewModel(application: Application) : AndroidViewModel(application) {
  private val diskChanges = MutableStateFlow(0)

  /**
   * Each installed game's size on disk and whether it needs updating, read again when an install
   * starts or ends.
   */
  private val installed =
    combine(
        diskChanges,
        Installs.state
          .map { installs -> installs.filterValues { it is InstallState.Running }.keys }
          .distinctUntilChanged(),
      ) { _, _ ->
        Game.entries.associateWith { game ->
          if (GameFiles.isInstalled(application, game)) {
            GameStatus.Installed(
              bytes =
                GameFiles.gameDir(application, game)
                  .walk()
                  .filter { it.isFile }
                  .sumOf { it.length() },
              needsUpdate = GameFiles.needsUpdate(application, game),
            )
          } else {
            null
          }
        }
      }
      .distinctUntilChanged()
      .flowOn(Dispatchers.IO)

  val games: StateFlow<List<GameItem>> =
    combine(Installs.state, installed) { installs, installed ->
        Game.entries.map { game ->
          val status =
            when (val install = installs[game]) {
              is InstallState.Running -> GameStatus.Installing(install.progress)
              is InstallState.Failed -> GameStatus.Failed(install.error)
              null -> installed[game] ?: GameStatus.NotInstalled
            }
          GameItem(game, status)
        }
      }
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

  init {
    // An install that the system stopped with the app leaves its work folder behind
    viewModelScope.launch(Dispatchers.IO) {
      if (!Installs.isRunning) {
        Installer.cleanUpInterrupted(application)?.let { Installs.interrupted(it) }
      }
    }
  }

  /** Looks at the installed games again, as when the app comes back to the foreground. */
  fun refresh() = diskChanges.update { it + 1 }

  fun delete(game: Game) {
    viewModelScope.launch(Dispatchers.IO) {
      GameFiles.gameDir(getApplication(), game).deleteRecursively()
      refresh()
    }
  }

  fun dismissError(game: Game) = Installs.dismiss(game)
}
