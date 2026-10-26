/** Cue helpers shared by the server (timeline builder) and tests. */

/** Flatten nested `set` objects: {T1:{progress:1}} -> {"T1.progress":1}. Mirrors remotion/hooks. */
export function flatten(obj, prefix = '', out = {}) {
  for (const [k, v] of Object.entries(obj || {})) {
    const key = prefix ? `${prefix}.${k}` : k;
    if (v && typeof v === 'object' && !Array.isArray(v)) flatten(v, key, out);
    else out[key] = v;
  }
  return out;
}

export const clamp = (n, lo, hi) => Math.min(hi, Math.max(lo, n));
