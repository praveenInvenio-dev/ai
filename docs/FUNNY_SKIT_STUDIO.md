# Funny Skit Studio

Standalone 15-second short-form comedy generator. Upload one main focus character, lock the reference, select any supported Indian language, and enter a skit idea. Ollama writes a compact script, Rumik generates the selected-language voice, and MiniMax H3 Reference-to-Video generates three continuous clips (5s + 5s + 5s) using the same character image and Rumik audio as reference. Each next part uses the previous part's final frame as its starting image, while the original locked character image remains the identity reference. FFmpeg concatenates the three complete clips.

The three H3 visual/audio part calls are deliberately sequential to stay within the 16 GB GPU profile. Each chunk also uses the same locked character reference.


## Storyboard-first workflow

Funny Skit Studio now creates the three 5-second storyboard images before any H3 video generation. The user can review each frame and regenerate an individual scene. Regenerating scene N invalidates scenes after N because their visual continuity depends on the previous frame; those downstream frames must be regenerated before rendering. Only after all three images are approved does the app run Rumik speech + H3 video/soundscape sequentially and merge the three 5-second clips into the final 15-second video.
