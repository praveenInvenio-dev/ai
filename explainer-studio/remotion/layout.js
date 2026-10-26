/**
 * Safe-zone-aware layout. One script, two aspect ratios.
 * Portrait keeps the bottom ~22% free of key content (Instagram/Shorts UI covers it) and the
 * top ~12% clear for the header; captions sit in the "thumb-safe" middle band where eyes already are.
 */
export function getLayout(width, height) {
  const portrait = height > width;
  if (portrait) {
    return {
      portrait, width, height,
      header: { x: width * 0.06, y: height * 0.045, w: width * 0.88, h: height * 0.085 },
      content: { x: width * 0.06, y: height * 0.145, w: width * 0.88, h: height * 0.50 },
      captions: { x: width * 0.04, y: height * 0.665, w: width * 0.92, h: height * 0.12 },
      tech: { x: width * 0.06, y: height * 0.795, w: width * 0.88, h: height * 0.06 },
      gap: width * 0.03,
    };
  }
  return {
    portrait, width, height,
    header: { x: width * 0.04, y: height * 0.035, w: width * 0.92, h: height * 0.085 },
    content: { x: width * 0.05, y: height * 0.15, w: width * 0.9, h: height * 0.55 },
    captions: { x: width * 0.08, y: height * 0.8, w: width * 0.84, h: height * 0.13 },
    tech: { x: width * 0.05, y: height * 0.725, w: width * 0.9, h: height * 0.06 },
    gap: width * 0.02,
  };
}

/** Distribute the content rect over the slots a scene actually uses. */
export function slotRects(L, used) {
  const { content: c, gap, portrait } = L;
  const has = (s) => used.has(s);
  let top = c.y, bottom = c.y + c.h;
  const rects = {};
  if (has('banner')) { const h = c.h * 0.2; rects.banner = { x: c.x, y: top, w: c.w, h }; top += h + gap; }
  if (has('c')) { const h = c.h * (portrait ? 0.24 : 0.28); rects.c = { x: c.x, y: bottom - h, w: c.w, h }; bottom -= h + gap; }
  const mid = { x: c.x, y: top, w: c.w, h: Math.max(10, bottom - top) };
  rects.main = mid;
  const onlyA = has('a') && !has('b'), onlyB = has('b') && !has('a');
  if (onlyA) rects.a = mid;
  else if (onlyB) rects.b = mid;
  else if (portrait) {
    const h = (mid.h - gap) / 2;
    rects.a = { x: mid.x, y: mid.y, w: mid.w, h };
    rects.b = { x: mid.x, y: mid.y + h + gap, w: mid.w, h };
  } else {
    const w = (mid.w - gap) / 2;
    rects.a = { x: mid.x, y: mid.y, w, h: mid.h };
    rects.b = { x: mid.x + w + gap, y: mid.y, w, h: mid.h };
  }
  return rects;
}
