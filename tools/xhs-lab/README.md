# Xiaohongshu local lab

Run `npm install` in this directory, then `npm test` for offline browser regression tests or `npm start` for the local debugging page at http://127.0.0.1:18766.

The lab reads JavaScript directly from the Android source. Pagination observes the website's own requests; it does not reconstruct signatures or resend those requests. The checked-in guest fixture contains synthetic placeholders, and mock results are explicitly labeled.

Browser login state, captured pages, response evidence and screenshots stay in `.local/`, which is ignored by Git. Complete interactive sign-in on the website before live checks. Local browser results do not replace Android emulator verification.
