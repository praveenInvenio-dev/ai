# v18.1 FFmpeg build fix

Fixed `backend/src/main/java/com/aistorystudio/provider/FFmpegProcessor.java`.

The v18 transition implementation referenced `args` and `concatList` outside the scope where they were created. The assembly method now creates both locally before branching and keeps the real xfade/acrossfade path plus the legacy concat fallback.

Also keeps optional background music ducking and avoids referencing `[0:a]` when the fallback clips do not all contain audio.

Validation performed in this environment:
- Java source brace/scope sanity check passed for FFmpegProcessor.
- The reported undefined `args`/`concatList` references in `assembleVideo` are now declared before all branches.
- Full Maven build could not be executed in this environment because Maven and Docker are unavailable and external package download is unavailable.

The ZIP contains the complete v18 project with this targeted fix only.
