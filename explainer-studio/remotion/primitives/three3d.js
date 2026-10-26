/** Small shared helpers for the three.js primitives (rotation + projection kept in plain JS so HTML labels
 *  can be placed exactly over the WebGL objects without reading anything back from the GPU). */
export const CAM_Z = 8;
export const FOV = 35;

/** Same order three.js applies a group's Euler 'XYZ' rotation to a vector: Rz, then Ry, then Rx. */
export function rotate([x, y, z], rx, ry) {
  const cy = Math.cos(ry), sy = Math.sin(ry);
  const x1 = x * cy + z * sy, z1 = -x * sy + z * cy;
  const cx = Math.cos(rx), sx = Math.sin(rx);
  return [x1, y * cx - z1 * sx, y * sx + z1 * cx];
}

export function project([x, y, z], w, h) {
  const f = (h / 2) / Math.tan(((FOV / 2) * Math.PI) / 180);
  const d = Math.max(0.5, CAM_Z - z);
  return { x: w / 2 + (f * x) / d, y: h / 2 - (f * y) / d, depth: d, scale: f / d };
}
