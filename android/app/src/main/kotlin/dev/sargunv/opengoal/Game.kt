package dev.sargunv.opengoal

import androidx.annotation.StringRes

/** The games the runtime plays, by the name the runtime and the extractor use for each. */
enum class Game(val id: String, @param:StringRes val title: Int) {
  Jak1("jak1", R.string.game_jak1),
  Jak2("jak2", R.string.game_jak2),
  Jak3("jak3", R.string.game_jak3),
}
