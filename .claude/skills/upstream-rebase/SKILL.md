---
name: upstream-rebase
description: Rebase the android branch onto upstream OpenGOAL's master, resolve conflicts, verify, and push. Use when asked to rebase on upstream, sync with upstream, or update from open-goal/jak-project.
---

# Rebase on upstream

`android` is this fork's default branch: upstream's `master` plus the fork's commits on top. Upstream
doesn't take Android changes, so the fork rebases instead of merging, and never opens anything on
open-goal/jak-project.

1. `git fetch https://github.com/open-goal/jak-project.git master` and note how many commits
   `FETCH_HEAD` adds (`git log --oneline android..FETCH_HEAD`). Stop if there are none.
2. `git rebase FETCH_HEAD` on a clean `android`.
3. Resolve each conflict by keeping upstream's change and reapplying the fork's intent on top. Fork
   changes to upstream files are small and mostly behind `__ANDROID__`, `ANDROID` or `OG_GLES`; keep
   them that way. When a fork commit duplicates something upstream now does (such as a merged
   upstream PR the fork carried), drop the fork's version. Reword a fork commit only if its message
   becomes wrong.
4. Verify, and fix what breaks in the commit that caused it (`git rebase -i` isn't available; use
   `git commit --fixup` and `git rebase --autosquash` with `GIT_SEQUENCE_EDITOR=:`):
   - `mise run desktop:build`, then `goalc-test` from `build/Release/bin` (all tests pass)
   - `mise run android:build` and `mise run android:test`
   - `mise run shaders:check` if upstream touched shaders
   - `mise run check`
5. `git push --force-with-lease origin android`. CI (`.github/workflows/android.yaml`) runs lint and
   both builds, and replaces the nightly release.
6. Report the upstream range, the conflicts and how each was resolved, and anything dropped.
