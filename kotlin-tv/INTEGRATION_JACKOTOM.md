# Jackotom Android TV integration (draft)

This document describes the Android-native integration in the stacked branch
feature/jackotom-android-tv-integration. It is not a production release record.

## Search and provider identity

For short dramas, the TV search flow merges:
1. The existing signed Hongguo App search.
2. The public Hongguo website search.
3. The official openhongguo Android SDK search.

Results are de-duplicated by provider ID and ranked with exact/prefix title matches first.
Separate seasons remain separate entries. Comic search keeps its existing App-only tab and
cursor contract; it is not mixed with short-drama web results.

Each result carries an explicit Series.source identity. An openhongguo result uses
openhongguo's own episode directory and playback API. Its IDs are never silently sent to
Hongguo App or website playback endpoints. This prevents an unrelated title from being
played after a cross-provider ID collision.

## Playback source order

The Android TV settings store an ordered and enabled list:

| ID | Current Android status | Notes |
| --- | --- | --- |
| openhongguo | Implemented | Apache-2.0 Android AAR 0.4.1; own no-backup anonymous profile; search, paginated episode list and progressive playback resolver. |
| sdk | Not available on Android | Jackotom's current SDK helper binaries target macOS/Windows; there is no Android TV executable. The UI marks this source unavailable and playback safely skips it. |
| app | Existing native path | Uses the target app's current signed App playback/model/compatibility code. Jackotom's remotely signed source-policy pack update mechanism has not yet been ported; this is not represented as a verified remote dynamic policy updater. |
| web | Guarded fallback | Uses the official public full-MP4 endpoint only when it returns an allowed HTTPS CDN URL, not an advertised preview or an inferred media URL. |

For Hongguo-native result IDs, the configured enabled order is applied. For an
openhongguo result, only openhongguo is attempted until a real, validated ID crosswalk
exists. The app must not send an arbitrary provider's episode ID to a different provider.
HLS/DASH streams are currently skipped by this progressive RemoteVideo adapter.

## Native downloads

The Android TV downloader is a Kotlin implementation, not the Electron downloader UI.
It provides a serialized queue, persisted task state, single-episode and whole-series enqueue,
pause/resume/retry/cancel/remove, progress, and local playback. Files are stored in the
application-private filesDir/downloads directory.

Downloads use the same verified OpenedStream / RemoteVideo path as playback, so
the saved bytes follow the app's decrypted media reader rather than saving encrypted raw
segments. Resume checks total length, the stored provider, the remote prefix fingerprint,
and the local partial-file prefix before appending. Incompatible or corrupted partial files
are restarted from byte zero. The current per-file limit is 2 GiB.

A source may refuse download even when it permits online playback. The UI must show that
failure and must not claim that a stream-only URL is a downloadable file.

## Android-native UI

The source settings and download manager are native TV views/dialogs, with focusable
controls for a remote. The source page exposes order, enable/disable and availability.
The download page exposes task state/progress and pause/resume/cancel/retry/delete/local play.
The existing search/catalog/detail/player UI remains the host application.

## Build and test

From the repository root:

    ./kotlin-tv/gradlew -p kotlin-tv :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug

The GitHub Actions Debug artifact is a test build only. Production signing requires the
original distribution keystore and a verified certificate fingerprint. No replacement key
is allowed to masquerade as the existing app's signing identity.

## Known limitations before declaring this integration complete

- The Android SDK helper source is unavailable as an Android binary and remains visibly disabled.
- The signed remote strategy-pack update system is not yet ported; the existing App playback
  path is used instead.
- The openhongguo adapter currently accepts progressive HTTPS streams; HLS/DASH and
  devices/codecs not accepted by the current RemoteVideo path are not yet adapted.
- The public MP4 fallback works only when the official endpoint itself returns a complete,
  valid media URL. It does not unlock protected or preview-only content.
- Real Android TV remote navigation, playback, downloads, pause/resume, storage exhaustion,
  process death/restart, and device-specific codec tests remain pending. CI success is not
  a substitute for physical-device validation.
- The original release keystore has not been found. Debug builds can be tested in parallel;
  production update APKs cannot replace the existing install until the original certificate
  is restored or a separately planned data-migration/re-signing path is accepted.

## Upstream and license notes

- jackotom/hongguo-drama is GPL-3.0. Its Android TV and source/download architecture
  informed the port; any copied or adapted GPL-covered code must retain the original
  copyright/license notices and corresponding source obligations.
- beilunyang/openhongguo Android AAR 0.4.1 is Apache-2.0 and is consumed as a separate
  dependency. Its license and notice must accompany distributed APKs.
- The target native application is GPL-3.0-only; see LICENSE and NOTICE.md.
