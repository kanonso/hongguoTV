# Jackotom Android TV integration (draft)

This is an Android-native integration branch for the formal target repository
kanonso/hongguoTV. It is a stacked Draft PR and is not a production release record.
The default main branch is unchanged.

## Search and provider identity

For short dramas, TV search independently queries and merges:
1. The existing signed Hongguo App search.
2. The official public website search.
3. The official openhongguo Android SDK search.

Results are de-duplicated by provider ID, ranked with exact/prefix title matches first,
and retain separate seasons. If title text does not prove a match, server-returned aliases
and pinyin matches are kept rather than silently discarded. Motion-comic search keeps its
existing App-only tab and pagination contract.

Every result has an explicit Series.source identity. An openhongguo result uses that SDK's
own paginated episode directory and playback IDs. A Hongguo-native result can try an
openhongguo crosswalk only after a case/Unicode-normalized exact full-title match and an
unambiguous 1-based episode-number match. If the series or episode mapping is missing or
ambiguous, openhongguo is skipped and the configured remaining sources are tried. An
arbitrary provider's episode ID is never sent to a different provider.

## Four playback sources

The source settings persist order and enablement. For a Hongguo-native result the outer
resolver tries enabled sources in the user-selected order:

| ID | Android status | Integration |
| --- | --- | --- |
| openhongguo | Implemented | Apache-2.0 Android AAR 0.4.1; independent no-backup anonymous profile; search, paginated episodes, progressive HTTPS playback, and guarded exact-title/episode crosswalk. |
| sdk | Not available on Android | Current jackotom helper binaries target macOS/Windows; no Android TV executable is present. The UI marks it unavailable and playback safely skips it. |
| app | Implemented | Existing signed App path plus the project-signed dynamic strategy pack. Its ordered routes cover preload_current, video_tab, player_b, player_a, model_0, model_1 and legacy. |
| web | Guarded fallback | Official public full-MP4 endpoint only when the endpoint itself returns a complete, unexpired HTTPS CDN URL from the allowlist. It does not use previews or infer a media URL. |

For an openhongguo-origin series, playback and downloads remain on openhongguo unless a
validated crosswalk is explicitly used for a Hongguo-native result. HLS/DASH streams are
currently skipped by the progressive RemoteVideo adapter. The SDK helper is not presented
as working on Android.

## Signed App strategy pack

The current upstream signed JSON pack is bundled as the initial policy. The Android
PlaybackStrategyPackManager:
- verifies Ed25519 signatures against the two pinned project public keys;
- checks the canonical JSON bytes, key ID, format, project ID, revision and validity dates;
- accepts only the seven known route IDs, HTTPS hosts under fqnovel.com/snssdk.com,
  the v5/player/none signing families, a short header allowlist and bounded JSON fields;
- rejects identity/time-field overrides and unapproved headers or URLs;
- applies route order, version/query overrides, nested JSON body overrides and field21
  to the existing native request/signing path (JSON request bodies are gzip-compressed
  before signing);
- checks the configured mirrors at startup and every six hours while the TV app process is
  running; only a verified higher revision is activated;
- persists the active and previous envelope, exposes version/status/last-check state,
  supports automatic-check enable/disable, immediate refresh, rollback and restore-embedded.

The downloaded pack is data-only. No remote JavaScript, shell, executable or arbitrary
code is evaluated. A missing/disabled route in an active verified policy is not silently
replaced with an unreviewed fallback URL. The App source retains its existing bounded
failure and compatibility behavior.

## Native downloader

The Kotlin TV downloader provides a serialized persistent queue, single-episode and
whole-series enqueue, progress, pause/resume/retry/cancel/remove, provider identity,
and local playback. Files are stored in the application-private filesDir/downloads directory.

It uses the same verified OpenedStream / RemoteVideo byte reader as playback, then saves
the processed media rather than encrypted raw bytes. Resume checks total length, provider,
remote prefix fingerprint and the local partial-file prefix before appending. Corrupt or
mismatched partial files restart at byte zero. The current per-file limit is 2 GiB.

The downloader runs in the app process; keep the app open while a task is downloading.
On Activity/process shutdown, active work is closed and persisted tasks return as paused
when the app is reopened. There is no separate always-on foreground download service in
this draft. A provider may allow online playback but refuse a download; the task reports
that failure rather than claiming success.

## Android TV UI

The source settings and download manager are native focusable TV views/dialogs. The source
page exposes priority (direct up/down controls), enablement, availability, strategy-pack
revision/status, refresh and rollback. The download page exposes task state/progress and
pause/resume/cancel/retry/delete/local play. The existing search/catalog/detail/player UI
remains the host app.

The About / Open Source Licenses page loads the Apache-2.0, CC0 and GPL-3.0 texts bundled
inside APK assets/licenses.

## Build and validation

From the repository root:

    ./kotlin-tv/gradlew -p kotlin-tv :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug

GitHub Actions Debug artifacts are test builds only. Node/React Native checks have passed
on several recent commits; the most recent Kotlin, lint and Debug candidate results must
be checked against the exact branch SHA before declaring a build successful. Physical TV
testing remains a separate gate.

Production signing requires the original distribution keystore and a verified certificate
fingerprint. No replacement key is allowed to masquerade as the existing app's signing
identity. The original release keystore has not yet been recovered, so no production
update APK or formal Release is produced by this integration branch.

## Known limitations before declaring this integration complete

- The SDK helper source is unavailable as an Android binary and remains visibly disabled.
- openhongguo playback currently accepts progressive HTTPS streams; HLS/DASH and devices
  or codecs not accepted by RemoteVideo are not adapted yet.
- Exact-title cross-provider mapping intentionally skips ambiguous or differently titled
  seasons; a provider-native ID crosswalk is still preferable where one is published.
- The public MP4 fallback only works when the official endpoint returns a complete valid
  media URL. It does not unlock protected, locked or preview-only content.
- Physical Android TV remote focus, device-specific codec negotiation, playback failover,
  queue pause/resume, process death/restart, low-storage, and local playback tests remain
  pending. CI success is not a substitute for physical-device validation.
- The downloader currently runs only while the app process is active; background/foreground
  service support remains a later hardening task.

## Upstream and license notes

- jackotom/hongguo-drama is GPL-3.0. Its Android TV/source/download architecture informed
  the port. GPL-covered adapted material retains the relevant license and notices.
- beilunyang/openhongguo Android AAR 0.4.1 is Apache-2.0.
- bloxbean/ed25519-java 0.3.1 is CC0 1.0 Universal and is used for Android-compatible
  Ed25519 verification.
- The target native app is GPL-3.0-only. Relevant license texts are in LICENSE,
  NOTICE.md and app/src/main/assets/licenses/.
