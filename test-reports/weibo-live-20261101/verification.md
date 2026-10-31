# Weibo Live Photo positions, 2026-11-01

Repository: D:\workplace\WeiDownloader, branch codex/xiaohongshu-reddit; baseline version 0.0.55 / 10045; fixed release version 0.0.56 / 10046.

Reported URL: https://weibo.com/5886191812/5360196664164603

Actual Android mobile-status response: `mobile.json`. Seven picture IDs; pics 4 and 6 have explicit livephoto/videoSrc fields. The top-level live_photo array contains only those two clips, without picture indices. Desktop status provider did not return the post on the emulator. Unauthenticated host requests returned a visitor challenge and were not treated as post evidence.

Cause: liveVideoHints treated the compact two-clip array as picture positions 0 and 1. Those wrong hints changed the first two photos into Live Photos; duplicate clip URL suppression then hid their actual attachments, and inventory completeness correctly rejected the mismatched result.

Fix: infer positions only from a full-length positional live_photo list, preserving blank/null slots; retain explicit pic_video index precedence. Compact arrays rely on each picture's own clip fields. The completeness gate remains enabled.

Upstream endpoint/schema reference checked: https://github.com/mikf/gallery-dl/blob/master/gallery_dl/extractor/weibo.py . The exact sparse-array diagnosis comes from the captured response, not the upstream extractor.

| Case | Expected | Layer | Result / evidence |
| --- | --- | --- | --- |
| Original release, reported URL | Reproduce incomplete-media error | Actual Android app UI | PASS, before-ui.log |
| Actual response fixture | 7 items, IMAGE/IMAGE/IMAGE/LIVE_PHOTO/IMAGE/LIVE_PHOTO/IMAGE, original IDs and exact clip URLs | Unit | PASS, 83 tests in 9 classes, zero failures/errors; unit-results/ |
| Compact array without picture positions | Do not invent picture associations | Unit | PASS, 83 tests in 9 classes, zero failures/errors; unit-results/ |
| Full positional array with blanks/null; explicit index conflict | Preserve positions and explicit pic_video precedence | Unit | PASS, 83 tests in 9 classes, zero failures/errors; unit-results/ |
| Existing Weibo parsing/inventory/HTTP/auth regressions | Pass | Unit | PASS, 83 tests in 9 classes, zero failures/errors; unit-results/ |
| Fixed app, same URL | Exact 7 types, original order/identities | Actual Android app UI | PASS, after-download.log and after-ui.png (debug build) |
| Original images and both Live clips | Committed files; decodable images and playable media; remove only test files | Android WorkManager | PASS, all 5 images and both Live clips, media tracks checked, test files removed; after-download.log |
| Installable fixed release | Build and verify APK | Build | PASS, release-build.log; all 5 APK variants have version 0.0.56 / 10046; dist/SHA256SUMS-0.0.56.txt |

Final release acceptance: installed 0.0.56 / 10046, parsed the same 7 ordered source IDs and downloaded all seven again. Five images decoded; both Live clips have AVC video and AAC audio tracks. Only test-created MediaStore rows were removed. Evidence: release-download.log, installed-version.txt, verify-release.py. No whole-app or physical-device acceptance is claimed.
