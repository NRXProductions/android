# Home Assistant Companion for Android, multi-server mTLS fork

This fork of [home-assistant/android](https://github.com/home-assistant/android) exists for a single
change: each Home Assistant server gets its own TLS client certificate (mTLS). The official app
keeps one certificate for the whole app, so only the first server protected by a client certificate
can be added.

The goal is to get the change merged upstream. Once it is released there, this fork is not needed
anymore.

## What is different from upstream

| Change | Where |
| --- | --- |
| Client certificate selected per server endpoint instead of once for the app | `common/.../data/keychain/`, `common/.../data/TLSHelper.kt`, `app/.../util/TLSWebViewClient.kt` |
| "TLS client certificate" setting on each server (select, change, remove) | `app/.../settings/server/` |
| Separate application ID and name, so it installs next to the official app | `gradle.properties`, `build-logic/.../AndroidApplicationConventionPlugin.kt` |
| Release pipeline of the fork | `.github/workflows/fork-release.yml`, `.github/scripts/fork-release.sh` |

The first two rows are the fix proposed upstream (branch `fix/multi-server-mtls`), the last two
only exist in this fork (branch `mtls`).

## How the certificate selection works

- The KeyChain alias the user picks is stored per HTTPS endpoint (`host:port`). The certificates and
  private keys never leave the Android KeyChain.
- The WebView, the REST API, the WebSocket and the background work all read the same selection, and
  a certificate is only ever presented to the endpoint it was selected for.
- Adding a server whose endpoint has no selection yet opens the system certificate chooser, whatever
  was selected for the other servers.
- The settings of each server have a **TLS client certificate** entry showing its selection. It
  allows to select another certificate, for example to replace an expired one, or to remove it.
- An installation updated from a version with a single certificate keeps using it for the servers
  it already has.

## Application ID

The fork is built with the application ID `io.homeassistant.companion.android.mtls` (`.minimal` is
appended for the minimal flavor) and the name "Home Assistant mTLS". Both are set in
`gradle.properties` (`homeAssistantAndroidApplicationId`, `homeAssistantAndroidAppName`). It can be
installed next to the official app and shares nothing with it.

## Limitations of the fork builds

- **Push notifications.** The fork has no Firebase project of its own, so it is built with
  placeholder Firebase values: notifications delivered through Firebase Cloud Messaging do not work.
  Enable **Persistent connection** in the settings of the server to receive notifications through
  the WebSocket instead. To use your own Firebase project, store its `google-services.json` in the
  `GOOGLE_SERVICES` secret (the push relay of the server must then be yours too).
- **Language.** The translations of the official app are not stored in the repository, they are
  downloaded from Lokalise when the official release is built. The native screens of the fork
  (settings, onboarding) are therefore in English only. The Home Assistant frontend is not affected.
- **Wear OS and Android Automotive** builds are not published.

## Releases

The `Fork Release` workflow runs every night and on demand:

1. It looks up the latest stable release of home-assistant/android. When the `mtls` branch already
   contains a newer upstream version (it was started on 2026.11.2, a pre-release at that time),
   that version is used instead: a release never goes back to an older upstream version, because
   installed over a newer one it would have to downgrade the app data.
2. If no release of the fork exists yet for that upstream release and the current revision of the
   `mtls` branch, it merges the upstream tag into `mtls` **inside the runner** (nothing is pushed).
3. It runs the unit tests of `:common` and `:app`.
4. In a second job, on a fresh runner, it does the same merge again and builds the `full` and
   `minimal` release APKs, signed with the key of the fork.

Both jobs compile with `--max-workers=1`. When the KSP tasks of two modules run at the same time,
one of them can silently generate nothing and the build fails later with
`Cannot load class ...Hilt_...`. Use the same flag (and `--no-build-cache` once, to get rid of the
bad cached output) when a local build fails that way.
5. It publishes them as a GitHub release named after the upstream release, for example
   `2026.11.2-mtls.1a2b3c4`.

Nothing is published when the merge conflicts, a test fails or the build fails. The run fails and
the reason is written to the run summary and to an issue titled "Fork release failed" (enable issues
on the fork to get it). The issue is closed by the next successful release.

GitHub disables scheduled workflows of a repository without any activity for 60 days. Re-enable the
workflow in the Actions tab when that happens, or run it by hand.

### Resolving a merge conflict

```bash
git fetch https://github.com/home-assistant/android.git tag <upstream tag>
git checkout mtls
git merge <upstream tag>
# resolve the conflicts, then
./gradlew :common:testDebugUnitTest :app:testFullDebugUnitTest
git push
```

The next run of the workflow publishes the release.

### Signing and updates

Every release is signed with the same key, so a new APK installs as an update over the previous
one and keeps the app data. The version code is the number of minutes since the Unix epoch at build
time, so it always grows.

The key lives in these repository secrets:

| Secret | Content |
| --- | --- |
| `KEYSTORE_BASE64` | The keystore file, base64 encoded |
| `KEYSTORE_PASSWORD` | Password of the keystore |
| `KEYSTORE_ALIAS` | Alias of the key in the keystore |
| `KEYSTORE_ALIAS_PASSWORD` | Password of the key |

Keep a backup of the keystore and its password. If the key is lost, Android refuses the next update
and the app has to be uninstalled, which deletes its data.

To build a signed APK locally, set `KEYSTORE_PATH`, `KEYSTORE_PASSWORD`, `KEYSTORE_ALIAS`,
`KEYSTORE_ALIAS_PASSWORD`, `VERSION_CODE` and `VERSION_NAME` in the environment, copy
`.github/mock-google-services.json` to `app/google-services.json` with the application ID replaced,
and run `./gradlew :app:assembleFullRelease`. The version code must be higher than the one of the
installed release.
