import React from 'react';

/**
 * If WebGL is unavailable in the render browser (no SwiftShader / ANGLE), a 3D primitive must not take the whole
 * video down: render its 2D counterpart instead. Remotion renders each frame in React, so a boundary is enough.
 */
export class Safe3D extends React.Component {
  constructor(p) { super(p); this.state = { failed: false }; }
  static getDerivedStateFromError() { return { failed: true }; }
  componentDidCatch(e) { console.warn(`3D primitive fell back to 2D: ${e?.message}`); }
  render() { return this.state.failed ? this.props.fallback : this.props.children; }
}
