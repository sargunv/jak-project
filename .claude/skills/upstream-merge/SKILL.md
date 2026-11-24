---
name: upstream-merge
description: Merge upstream OpenGOAL's master into the android branch, resolve conflicts, verify, and push. Use when asked to merge, sync or update from upstream (open-goal/jak-project).
---

# Merge upstream

`android` is this fork's default branch: upstream's `master` merged in, with the fork's commits.
Merging keeps published history, release tags and links intact, so never rebase or force-push
`android`. Never open anything on open-goal/jak-project.

1. On a clean `android`, fetch upstream with its tags:
   `git fetch https://github.com/open-goal/jak-project.git master 'refs/tags/v*:refs/tags/v*'`.
   Stop if `git log --oneline android..FETCH_HEAD` is empty.
2. `git merge --no-ff FETCH_HEAD -m "chore: merge upstream <newest upstream tag>-<count>-g<sha>"`.
3. Resolve conflicts by keeping upstream's change and reapplying the fork's intent on top. Fork
   changes to upstream files are small and mostly behind `__ANDROID__`, `ANDROID` or `OG_GLES`; keep
   them that way. Where upstream now does what a fork change did (such as an upstream PR the fork
   carried), take upstream's and drop the fork's.
4. Verify before pushing, and fix breakage in follow-up commits:
   - `mise run desktop:build`, then `goalc-test` from `build/Release/bin`
   - `mise run android:build` and `mise run android:test`
   - `mise run shaders:check` if upstream touched shaders
   - `mise run check`
5. `git push origin android 'refs/tags/v*'`. The upstream tags let CI name the upstream version in
   the app's version. CI lints, builds both, and replaces the nightly.
6. Report the upstream range, each conflict and how it was resolved, and anything dropped.
