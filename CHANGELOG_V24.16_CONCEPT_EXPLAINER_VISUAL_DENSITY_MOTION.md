# Increment 8 — Concept Explainer visual density and motion

## Changes
- Reworked shared tutorial visual prompt guidance to target 75–88% intentional visual occupancy with safe margins rather than large unused regions.
- Added a balanced layered composition rule: dominant visual, supporting detail, and compact labels/callouts.
- Added required causal actions and visible state transitions, not static decorative diagrams.
- Tightened layout coordinates and spacing; raised the recommended visible-element target to 4–8 while retaining a hard cap for readability.
- Added specific Java concurrency guidance: show the UI thread blocked/unblocked separately from I/O wait; show race-condition reads/writes and corrected lock-protected outcomes; avoid false CPU utilization gauges.
- Added gentle camera push-in to tutorial scenes (1.2%, scaled by motion intensity) and retained stronger camera movement for real-world breakdowns. STATIC mode still disables camera movement.
- Documented an open-source renderer roadmap: Motion Canvas for voice-synced 2D diagrams, existing Manim for maths/physics, Three.js for intentional 3D, and optional Rive runtime for interactive asset preview.

## Validation limitations
- ZIP integrity and source-level checks should be performed before deployment.
- Full Maven/JUnit and Angular production builds depend on the user's build environment.
- Real rendered-video inspection is still required to confirm that a specific generated lesson has no empty frames, pacing gaps, layout collisions or narration mismatch.
