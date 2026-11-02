# Bilibili Live Photo fix and 0.0.57 release

Reported input: https://b23.tv/vnCvmGF (opus 1132756461357629440).
The opus page contains five picture objects with paired `url` and `live_url` fields. Previously only the still URL was read. The parser now returns one LIVE_PHOTO per pair and preserves the still as its optional cover. Static pictures retain existing behavior; duplicate covers can be enriched with later live metadata without changing order.

| Verification | Result | Evidence |
| --- | --- | --- |
| Real source fixture, mixed/repeated pictures, absent/null/invalid live URL | 3 tests passed | unit-results/TEST-cn.weibo.filedownloader.BilibiliOpusMediaParserTest.xml |
| Bilibili link and playback regression | 12 tests passed | Other Bilibili XML files in unit-results/ |
| Fixed 0.0.56 release, original link and all media | Five live clips and five stills downloaded through actual app UI/WorkManager/MediaStore; videos decoded at 1080x1440; only test files removed | release-device-download.log |
| 0.0.57 / 10047 release build | BUILD SUCCESSFUL; five ABI/universal APKs; versions and APK signatures verified | Local release-0.0.57-build.log and dist/SHA256SUMS-0.0.57.txt |
| Installed 0.0.57 release, original short link | Exactly five ordered LIVE_PHOTO items | installed-0.0.57.txt, release-0.0.57-parse.log |

The version bump is the only product change after the successful download/decoder tests. Unit and download tests above were performed on the preceding fixed 0.0.56 build; 0.0.57 was rebuilt, installed, and parsed again. Physical-device and native gallery Live Photo packaging are not claimed. APKs remain local under dist; this commit does not publish a GitHub Release.
