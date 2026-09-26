# Image Style UI Update

This update improves the Create Story image-style selector without changing the story-generation API contract.

## UI
- Replaces the small style dropdown with selectable visual style cards.
- Defaults to **3D Realistic**.
- Shows an icon, short description, selected-state indicator, and a full style description.
- Responsive layout for desktop/tablet/mobile.
- Keeps the existing `visualStyle` field sent by `createDraft()`.

## Styles
- 3D Realistic
- 3D Animated Feature
- Cinematic Realistic
- Anime
- Cartoon
- Storybook
- Watercolor
- 2D Animation
- Claymation
- Comic Book
- Fantasy Illustration
- Realistic

## Compatibility
No backend API, database schema, Docker service, Ollama configuration, ComfyUI workflow, video worker, or TTS component was changed by this UI update.
