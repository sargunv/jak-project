# PocketGOAL

An Android build of [OpenGOAL](https://opengoal.dev), the PC port of Jak and
Daxter, Jak II and Jak 3. Bring your own disc images (ISO or CHD); the app
installs each game from its image on the device.

Not affiliated with the OpenGOAL project. Report issues here, not upstream.

## Download

Get the APK from [Releases](https://github.com/sargunv/jak-project/releases).
`nightly` is the latest build of the `android` branch.

To get updates to releases:

- F-Droid client: add the repo `https://code.sargunv.dev/jak-project/repo`.
- Obtainium: [add PocketGOAL](https://apps.obtainium.imranr.dev/redirect?r=obtainium://app/%7B%22id%22%3A%22dev.sargunv.pocketgoal%22%2C%22url%22%3A%22https%3A%2F%2Fgithub.com%2Fsargunv%2Fjak-project%22%2C%22author%22%3A%22sargunv%22%2C%22name%22%3A%22PocketGOAL%22%2C%22preferredApkIndex%22%3A0%2C%22additionalSettings%22%3A%22%7B%5C%22includePrereleases%5C%22%3Afalse%2C%5C%22fallbackToOlderReleases%5C%22%3Atrue%2C%5C%22releaseTitleAsVersion%5C%22%3Atrue%2C%5C%22versionDetection%5C%22%3Atrue%2C%5C%22apkFilterRegEx%5C%22%3A%5C%22%5EPocketGOAL-.%2A%5C%5C%5C%5C.apk%24%5C%22%2C%5C%22autoApkFilterByArch%5C%22%3Atrue%2C%5C%22trackOnly%5C%22%3Afalse%7D%22%2C%22overrideSource%22%3Anull%7D).

After an update, the app may ask to install a game again from its disc image.
Saves and settings are kept.

Needs Android 9 or later, a 64-bit ARM device with OpenGL ES 3.2, and about
6 GB of free memory while a game installs. Tested on the AYN Thor.

## Development

The `android` branch is OpenGOAL's `master` plus the port. See
[docs/android](../docs/android/README.md).
