# Fixes from reviewing video (44) - real-world breakdown

- Stat cards ("1 million users hitting 1 row", "504 Gateway Timeout"): big numbers collided with the words.
  Now every line shares one baseline, line height follows the biggest token, card height is measured the same way.
  "x" alone no longer counts as a number.
- Photos (opening crowd, "best part" city) were a band in the middle: photos are now always full-screen
  (style "framed" opts out), the series header is drawn on top of them.
- Step tracker floated mid-screen and moved with the camera: it is now pinned to the top in screen space.
- Empty scenes (0:54-1:09 had only the tracker): unknown element types are kept as labels instead of dropped;
  a scene with nothing but the tracker/backdrop gets its key line as a card; prompt forbids empty scenes.
- Blank white phones: skeleton list rows when the model gives none; prompt requires the real list.
- Bloom switched to screen-blend so bright UI (phone screens) keeps its detail instead of washing to white.
- Generated photos: stronger no-text rules (the "Position: 4502" text was painted by the image model).
