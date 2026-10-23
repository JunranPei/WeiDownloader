# Douyin download edge-test matrix (2026-10-23)

## Scope and baseline

- Repository: `D:\workplace\WeiDownloader`, branch `codex/xiaohongshu-reddit`.
- Product version under test: `0.0.52` (`versionCode 10042`).
- Scope: Douyin shared-link parsing, post resolution, slide/video extraction, and one-item download/commit path. Baseline was `0.0.52`; final re-checks used `0.0.53`. This is not a full multi-platform audit.
- Keep all test output in a run-specific `AllDownloader/EdgeTest-*` location and remove only files created by this run.
- Baseline status: app `0.0.52` (`versionCode 10042`) on API 36 emulator `emulator-5554`; WebView `148.0.7778.226`.

## Traceable matrix

| ID | Preconditions and operation/fault | Expected result / state invariant | Layer | Status | Evidence |
| --- | --- | --- | --- | --- | --- |
| D01 | Parse the supplied copied share text with Chinese caption, emoji/punctuation, and trailing Douyin share text | Extract exactly `https://v.douyin.com/npve5sp-teE/`; no caption text included | Unit / parser | 通过 | `DouyinContentPolicyTest.extractsTheProvidedSlidesShareLinkAndPostId` |
| D02 | Try unsupported scheme, URL user-info, hostname suffix lookalikes, malformed URL, and unrelated host | Reject before network access; only supported Douyin hosts are accepted | Unit / parser | 通过 | `DouyinContentPolicyTest.rejectsUnsafeSchemeCredentialsCustomPortsAndHostnameLookalikes`; unsupported host test |
| D03 | Follow redirects that stay on allowed HTTPS Douyin hosts; inject off-domain, downgrade, loop, excessive-hop redirects, and unsafe initial targets | Reject an unsafe hop before making a request to it; bound redirect count; never send origin credentials to another origin | Unit + API 36 controlled HTTP | 通过 | `SafeHttpRedirectsTest` (6 cases); instrumentation `redirect-cookie-probe`: before repair Cookie forwarded, after repair `forwarded=false` |
| D04 | Normalize slide, video, and live-photo detail fixtures; include wrong post ID, missing ID, empty media and unrelated promotional resources | Keep only media bound to the requested post; reject empty/wrong-ID details and unrelated assets | Unit / model | 通过 | `DouyinContentPolicyTest` fixture and ID-scope cases (17 tests) |
| D05 | Parse React Flight chunks, response-captured detail, malformed chunks, duplicate chunks, and a different post's ID | Return matching media only; malformed or unrelated data cannot satisfy the request | Node / extractor | 通过 | `node tools/verify-media-extractors.cjs`: 15 checks passed |
| D06 | Resolve the user's supplied link through the installed app UI on the emulator | Show the expected post ID and all 19 media entries; no stale result from a previous request | Real app UI / external network | 通过 | Instrumentation output: post `7699493946036397211`, media `19` |
| D07 | Force the WebView fallback for a public post and let the page hydrate after initial load | Extract the target post despite initial shell/late hydration; fail cleanly on renderer exit or timeout | Instrumentation / real WebView | 通过 | Activity-hosted WebView output: post `7699493946036397211`, media `19` |
| D08 | Start, cancel, replace resolution requests, and deliver late completion from an obsolete request | Callback is delivered at most once; canceled or stale request cannot overwrite the current post | Unit / lifecycle | 通过 | `ResolutionRequestTest` (3 cases); concurrent live UI overlap not separately injected |
| D09 | Rotate delivery URLs while preserving attachment identity; reorder, remove, duplicate, or replace media | Renew only the same attachment and selected quality; fail closed on missing/ambiguous identity | Unit / durable locator | 通过 | `DownloadLocatorTest` and `DownloadRenewalTest`; full local unit suite |
| D10 | Download one selected slide image from the supplied share link into a unique test folder using the real WorkManager worker | Published bytes are non-empty and match image type; no other album/download entry is changed | API 36 emulator / real app UI / WorkManager / MediaStore | 通过 | Final 0.0.53 run `EdgeTest-7384fd3d`: 309,429 bytes, `image/jpeg`, JPEG signature valid, `IS_PENDING=0`, test row removed |
| D11 | Inject HTTP 200 with `application/octet-stream` and a readable plain-text body through the real Worker | Worker fails; no partial or published/pending MediaStore item remains | API 36 emulator / real WorkManager / controlled HTTP | 通过（修复后） | Before repair, the text body was published as an image; after repair, the `octet-text` D14 case returns `FAILED`, `rows=0`, 4 controlled requests |
| D12 | Inject destination create/open/write/finalize failures (MediaStore and SAF where available) | Work fails without a published partial item; test-created temporary output is removed | Controlled provider / worker | 未测 | No isolated faulting MediaStore/SAF provider was available in this run |
| D13 | Verify exact source and app versions on the test emulator before interpreting results | Installed package version matches the APK built from this checkout; SDK/WebView version recorded | Device / build | 通过 | Baseline `0.0.52` / `10042`; final candidate `0.0.53` / `10043` installed on API 36; WebView `148.0.7778.226` |
| D14 | Inject Worker responses with HTTP 403/404, HTML/JSON, wrong media MIME, zero bytes, and truncated bodies | Each response fails without publishing partial media | API 36 emulator / real WorkManager / controlled HTTP | 通过 | `download-invalid-response-suite`: all 7 cases `FAILED`, `rows:0`, `requests:4` (the four requests are the WorkManager retry attempts) |

## Evidence and sources

- Android `HttpURLConnection` documents automatic HTTP redirect following as enabled by default: <https://developer.android.com/reference/java/net/HttpURLConnection#setFollowRedirects(boolean)>.
- Android documents that `HttpURLConnection` can follow up to five redirects across different origins, but does not change between HTTP and HTTPS: <https://developer.android.com/reference/java/net/HttpURLConnection>.
- RFC 9110 identifies `Cookie`, `Authorization`, and `Referer` as origin/resource-specific headers when following redirects: <https://www.rfc-editor.org/rfc/rfc9110.html>.
- RFC 9205 notes that origin changes can affect `Authorization` and `Cookie` headers during redirects: <https://www.rfc-editor.org/rfc/rfc9205.html>.
- Android `WebViewClient.onRenderProcessGone` requires removing the affected WebView and cleaning references: <https://developer.android.com/reference/android/webkit/WebViewClient#onRenderProcessGone(android.webkit.WebView,android.webkit.RenderProcessGoneDetail)>.
- AndroidX `WebViewCompat.addDocumentStartJavaScript` is gated by the `DOCUMENT_START_SCRIPT` feature: <https://developer.android.com/reference/androidx/webkit/WebViewCompat#addDocumentStartJavaScript(android.webkit.WebView,java.lang.String,java.util.Set)>.
- Android `MediaStore.MediaColumns.IS_PENDING` keeps a file unpublished while its owner is inserting it: <https://developer.android.com/reference/android/provider/MediaStore.MediaColumns#IS_PENDING>.

## Results

Update the matrix after each run. Record actual assertion output and artifact paths; do not infer external-service success from a local parser or successful build.

### Confirmed defects

1. The download worker accepted a readable text error payload served as `application/octet-stream`, published it with an image filename, and reported WorkManager success. Reproduced against a loopback HTTP server before repair.
2. Android `HttpURLConnection` followed a controlled cross-host redirect and forwarded a manually supplied `Cookie` header. Douyin's authenticated detail/SEO requests used automatic redirects with Cookie headers, so the same runtime behavior could expose those credentials if a request endpoint redirected off-origin. The public Douyin endpoint was not observed redirecting off-origin during this run.
3. The shared safe-redirect helper did not validate the initial URL as strictly as subsequent redirect targets. It now rejects user-info, unsupported schemes, invalid ports, and caller-disallowed initial hosts before opening a connection.

### Repairs and re-tests

- Added readable-text rejection to the initial media response sniff and read enough prefix bytes before opening the output. Unit test plus real Worker/MediaStore fault injection now end in `FAILED` with zero output rows; the supplied Douyin share link still resolves and downloads a valid image.
- Added `SafeHttpRedirects`: maximum five hops, loop/downgrade/user-info checks, caller-supplied host allowlist, and removal of non-safe headers on cross-origin hops. Douyin share/detail/SEO and download requests now use it. Unit tests and a device loopback probe confirm the test Cookie is not forwarded; the supplied short link and forced WebView fallback still resolve.
- Validated the initial URL before opening any connection; unsafe user-info, scheme, port, and caller host-policy inputs are rejected before the connection factory runs.
- Tightened share URL validation for schemes, user-info, custom ports, and hostname lookalikes.
- Corrected the WebView instrumentation to host the fallback in a real `MainActivity`; its previous Application-context setup could not attach a rendering WebView and was not valid product evidence.
- Expanded the isolated Worker probe to include D14; all seven response cases failed and left zero published or pending MediaStore rows after four WorkManager attempts each.

### Limitations

- SAF provider create/open/write/finalize failures and a real OEM device were not tested. The redirect probe used loopback endpoints; no off-origin redirect was seen from Douyin's production service. The instrument run briefly lost its emulator process; after restarting the same API 36 AVD, the required UI and download checks passed. Final 0.0.53 debug and release builds completed successfully; D14 and the redirect-cookie probe were repeated against installed 0.0.53.
