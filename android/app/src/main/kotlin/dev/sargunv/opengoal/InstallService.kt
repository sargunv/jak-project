package dev.sargunv.opengoal

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** An install in progress, or one that failed, until the user installs again or dismisses it. */
sealed interface InstallState {
  data class Running(val progress: InstallProgress) : InstallState

  data class Failed(val error: InstallError) : InstallState
}

/** The installs in this process, by game. One game installs at a time. */
object Installs {
  private val mutableState = MutableStateFlow<Map<Game, InstallState>>(emptyMap())
  val state: StateFlow<Map<Game, InstallState>> = mutableState.asStateFlow()

  /** Whether a game is installing, which makes the others wait. */
  val isRunning: Boolean
    get() = state.value.values.any { it is InstallState.Running }

  internal fun set(game: Game, state: InstallState?) = mutableState.update {
    if (state == null) it - game else it + (game to state)
  }

  fun dismiss(game: Game) = mutableState.update {
    if (it[game] is InstallState.Failed) it - game else it
  }
}

/**
 * Installs a game from its ISO in the foreground, with a notification, so the install keeps going
 * when the user leaves the app. It takes a few minutes on a handheld.
 */
class InstallService : Service() {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private var installer: Installer? = null
  private lateinit var notifications: NotificationManager

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onCreate() {
    super.onCreate()
    notifications = getSystemService(NotificationManager::class.java)
    notifications.createNotificationChannel(
      NotificationChannel(
        CHANNEL,
        getString(R.string.install_channel),
        NotificationManager.IMPORTANCE_LOW,
      ),
    )
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    if (intent?.action == ACTION_CANCEL) {
      installer?.cancel()
      return START_NOT_STICKY
    }
    val game = intent?.getStringExtra(EXTRA_GAME)?.let { id -> Game.entries.find { it.id == id } }
    val iso = intent?.data
    if (game == null || iso == null || installer != null) {
      if (installer == null) stopSelf(startId)
      return START_NOT_STICKY
    }

    val first = InstallProgress(InstallStep.Prepare, null)
    startForeground(
      NOTIFICATION_PROGRESS,
      progressNotification(game, first),
      ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
    )
    Installs.set(game, InstallState.Running(first))
    val installer =
      Installer(this, game, iso) { progress ->
        Installs.set(game, InstallState.Running(progress))
        notifications.notify(NOTIFICATION_PROGRESS, progressNotification(game, progress))
      }
    this.installer = installer

    scope.launch {
      val result =
        try {
          installer.run()
        } catch (e: Exception) {
          InstallResult.Failure(InstallError.Failed(-1, e.message ?: e.toString()))
        }
      Installs.set(game, (result as? InstallResult.Failure)?.let { InstallState.Failed(it.error) })
      stopForeground(STOP_FOREGROUND_REMOVE)
      if (result != InstallResult.Cancelled) {
        notifications.notify(NOTIFICATION_RESULT, resultNotification(game, result))
      }
      this@InstallService.installer = null
      stopSelf()
    }
    return START_NOT_STICKY
  }

  /** Android limits how long a data sync service runs each day. Six hours is plenty. */
  override fun onTimeout(startId: Int, fgsType: Int) {
    installer?.cancel()
  }

  override fun onDestroy() {
    installer?.cancel()
    scope.cancel()
    super.onDestroy()
  }

  private fun progressNotification(game: Game, progress: InstallProgress): Notification {
    val cancel =
      PendingIntent.getService(
        this,
        0,
        Intent(this, InstallService::class.java).setAction(ACTION_CANCEL),
        PendingIntent.FLAG_IMMUTABLE,
      )
    val fraction = progress.fraction
    return Notification.Builder(this, CHANNEL)
      .setSmallIcon(R.drawable.ic_notification)
      .setContentTitle(getString(R.string.install_notification_title, getString(game.title)))
      .setContentText(getString(progress.step.label))
      .setProgress(100, ((fraction ?: 0f) * 100).toInt(), fraction == null)
      .setOngoing(true)
      .setOnlyAlertOnce(true)
      .setContentIntent(openApp())
      .addAction(
        Notification.Action.Builder(null, getString(android.R.string.cancel), cancel).build(),
      )
      .build()
  }

  private fun resultNotification(game: Game, result: InstallResult): Notification {
    val title =
      if (result == InstallResult.Success) R.string.install_done_title
      else R.string.install_failed_title
    return Notification.Builder(this, CHANNEL)
      .setSmallIcon(R.drawable.ic_notification)
      .setContentTitle(getString(title, getString(game.title)))
      .setContentIntent(openApp())
      .setAutoCancel(true)
      .build()
  }

  private fun openApp() =
    PendingIntent.getActivity(
      this,
      0,
      Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
      PendingIntent.FLAG_IMMUTABLE,
    )

  companion object {
    private const val CHANNEL = "install"
    private const val NOTIFICATION_PROGRESS = 1
    private const val NOTIFICATION_RESULT = 2
    private const val EXTRA_GAME = "game"
    private const val ACTION_CANCEL = "dev.sargunv.opengoal.action.CANCEL_INSTALL"

    fun start(context: Context, game: Game, iso: Uri) {
      context.startForegroundService(
        Intent(context, InstallService::class.java)
          .putExtra(EXTRA_GAME, game.id)
          .setData(iso)
          .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
      )
    }

    fun cancel(context: Context) {
      context.startService(Intent(context, InstallService::class.java).setAction(ACTION_CANCEL))
    }
  }
}

/** The step's name, for the notification and the game list. */
val InstallStep.label: Int
  get() =
    when (this) {
      InstallStep.Prepare -> R.string.install_step_prepare
      InstallStep.Extract -> R.string.install_step_extract
      InstallStep.Decompile -> R.string.install_step_decompile
      InstallStep.Compile -> R.string.install_step_compile
      InstallStep.Finish -> R.string.install_step_finish
    }
