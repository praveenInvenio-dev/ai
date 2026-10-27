# Increment 69 — Direct Text-to-Video Full Story Mode

- Removed the separate **Story to video · H3** mode tab.
- Added **Generate a complete story automatically** inside **Text to video** when MiniMax H3 is selected.
- Reuses the existing H3 Character references panel and its selected characters instead of presenting a separate story-specific character picker.
- Story mode accepts a premise, duration, genre, language, visual style, narration style, and orientation; the AI plans the complete story in fixed five-second scenes and starts sequential H3 rendering automatically.
- Keeps the user on Video generation; no Storyboard navigation or manual per-scene prompt entry is required.
- Keeps selected character references attached to every H3 scene through the existing sequence reference scope and chains each scene from the previous scene's last frame for continuity.
- Enables short stories from 10 seconds (two five-second scenes) through 360 seconds, in five-second increments.
- Keeps the single-clip Text-to-video and Image-to-video workflows unchanged.
