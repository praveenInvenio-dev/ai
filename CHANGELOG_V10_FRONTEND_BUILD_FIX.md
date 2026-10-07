# v10 Frontend Build Fix

Fixed two Angular production-build errors found during Docker build:

1. Funny Skit Studio now passes `null` instead of `undefined` when no temporary upload file is selected, satisfying the `File | null` API contract.
2. Story Video Production now includes the missing `referenceLabel()` helper used by the locked character reference selector.

No backend, H3, TTS, audio mixing, or video-generation behavior was changed.
