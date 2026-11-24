package dev.sargunv.pocketgoal

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * The disc image the player picked for each game, which the app keeps permission to read, so
 * updating a game's data doesn't ask for it again.
 */
internal object DiscImages {
  private const val FILE = "disc-images"

  private fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

  /** Keeps [uri], a document the player just picked for [game], and permission to read it. */
  fun remember(context: Context, game: Game, uri: Uri) {
    runCatching {
      context.contentResolver.takePersistableUriPermission(
        uri,
        Intent.FLAG_GRANT_READ_URI_PERMISSION,
      )
      prefs(context).edit().putString(game.id, uri.toString()).apply()
    }
  }

  /** [game]'s disc image, if the app can still read it. */
  fun get(context: Context, game: Game): Uri? {
    val uri = prefs(context).getString(game.id, null)?.let(Uri::parse) ?: return null
    val permitted =
      context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }
    return uri.takeIf { permitted }
  }
}
