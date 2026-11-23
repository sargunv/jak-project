package dev.sargunv.opengoal.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.sargunv.opengoal.Game
import dev.sargunv.opengoal.GameFiles
import dev.sargunv.opengoal.InstallError
import dev.sargunv.opengoal.InstallProgress
import dev.sargunv.opengoal.InstallState
import dev.sargunv.opengoal.Installer
import dev.sargunv.opengoal.Installs
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

  data class Installed(val bytes: Long) : GameStatus

  data class Installing(val progress: InstallProgress) : GameStatus

  data class Failed(val error: InstallError) : GameStatus
}

data class GameItem(val game: Game, val status: GameStatus)

class LibraryViewModel(application: Application) : AndroidViewModel(application) {
  private val diskChanges = MutableStateFlow(0)

  /** Each installed game's size on disk, read again when an install starts or ends. */
  private val installedBytes =
    combine(
        diskChanges,
        Installs.state
          .map { installs -> installs.filterValues { it is InstallState.Running }.keys }
          .distinctUntilChanged(),
      ) { _, _ ->
        Game.entries.associateWith { game ->
          if (GameFiles.isInstalled(application, game)) {
            GameFiles.gameDir(application, game).walk().filter { it.isFile }.sumOf { it.length() }
          } else {
            null
          }
        }
      }
      .distinctUntilChanged()
      .flowOn(Dispatchers.IO)

  val games: StateFlow<List<GameItem>> =
    combine(Installs.state, installedBytes) { installs, bytes ->
        Game.entries.map { game ->
          val status =
            when (val install = installs[game]) {
              is InstallState.Running -> GameStatus.Installing(install.progress)
              is InstallState.Failed -> GameStatus.Failed(install.error)
              null -> bytes[game]?.let { GameStatus.Installed(it) } ?: GameStatus.NotInstalled
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
