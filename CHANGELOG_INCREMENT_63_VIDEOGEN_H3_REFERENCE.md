# Increment 63 — Video Generation (text/image → video, MiniMax H3): explicit character reference

Includes Increments 55-62 (62 fixed Story → H3; this fixes the plain Video Generation page).

## Problem
On Video Generation with MiniMax H3 (text-to-video), the uploaded character was only applied if the prompt contained the
character's exact name (or it was pinned) - and the job request itself never said which character to use.
With H3 speech, the clip goes through H3SceneRenderer, which passed only a start image.

## Fix
- The H3 character panel on Video Generation now has **"Use as reference in this video"** checkboxes (max 2).
  Pinned / first characters are ticked automatically.
- The ticked ids are sent with the job (`castIds`). The backend opens an `H3ReferenceScope` for the whole job, so
  **every** H3 clip of that job - plain H3, and H3 speech/multi-shot via H3SceneRenderer - goes through Ref2VA with those
  exact reference images and master prompts, regardless of what the prompt calls the character.
- Explicit references take the slots first; name-matched/pinned panel characters fill what is left.
- The result line shows it: `workflow: … · references: <name>` - if that is missing, no reference was used.
- Unchanged: broken Ref2VA → clear error ("./verify-models.sh --fix"), never a different character.

Verified: Angular production build; VideoGenerationService / VideoGenJob / provider compile except for gaps in the
sandbox's stand-in ProviderGateway (unrelated methods). No real H3 render possible here.
