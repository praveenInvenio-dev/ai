import React, { useMemo } from 'react';
import * as THREE from 'three';
import { ThreeCanvas } from '@remotion/three';
import { useTheme } from '../themes.jsx';
import { useCue, numAt, valueAt, sinceMs, popSpring, clamp01 } from '../hooks/cue.js';
import { CAM_Z, FOV } from './three3d.js';
import { hexA } from './common.jsx';

export const SHAPES = ['cube', 'cuboid', 'sphere', 'cylinder', 'cone', 'pyramid', 'prism', 'torus', 'hemisphere'];

function makeGeometry(shape) {
  switch (shape) {
    case 'cuboid': return new THREE.BoxGeometry(2.6, 1.5, 1.6);
    case 'sphere': return new THREE.SphereGeometry(1.45, 64, 40);
    case 'hemisphere': return new THREE.SphereGeometry(1.5, 64, 32, 0, Math.PI * 2, 0, Math.PI / 2);
    case 'cylinder': return new THREE.CylinderGeometry(1.1, 1.1, 2.5, 64);
    case 'cone': return new THREE.ConeGeometry(1.35, 2.5, 64);
    case 'pyramid': return new THREE.ConeGeometry(1.6, 2.3, 4);
    case 'prism': return new THREE.CylinderGeometry(1.3, 1.3, 2.3, 3);
    case 'torus': return new THREE.TorusGeometry(1.15, 0.45, 48, 128);
    default: return new THREE.BoxGeometry(2, 2, 2);
  }
}

/**
 * A lit, slowly turning 3D solid (three.js via @remotion/three) for geometry, mensuration and physics:
 * translucent faces + crisp edges, optional wireframe/x-ray mode, a pop when the scale changes, and up to three
 * measurement labels ("r = 3 cm", "h = 7 cm", "V = πr²h") that slide in beside it.
 */
export const Solid3D = ({ w, h, props, events }) => {
  const t = useTheme();
  const cue = useCue(events);
  const geo = useMemo(() => makeGeometry(props.shape), [props.shape]);
  const edges = useMemo(() => new THREE.EdgesGeometry(geo, 25), [geo]);
  const wire = Number(valueAt(cue, 'wire', 0)) > 0;
  const scale = numAt(cue, 'scale', 1, { damping: 14, stiffness: 120 });
  const label = String(valueAt(cue, 'label', props.title || ''));
  const reveal = numAt(cue, 'reveal', (props.labels || []).length, { damping: 24, stiffness: 70 });
  const pop = popSpring(cue, sinceMs(cue, 'scale'));
  const appear = clamp01(cue.ageMs / 600);
  const ry = cue.ageMs / 1000 * 0.5 + 0.6;
  const rx = 0.42 + 0.05 * Math.sin(cue.ageMs / 1500);
  const side = (props.labels || []).length > 0 && w > h * 1.15;
  const cw = side ? w * 0.62 : w, ch = side ? h : h * ((props.labels || []).length ? 0.74 : 1);
  const fs = Math.max(12, Math.min(w, h) * 0.055);
  return (
    <div style={{ width: w, height: h, position: 'relative', display: 'flex', flexDirection: side ? 'row' : 'column', alignItems: 'center', fontFamily: t.font }}>
      <div style={{ width: cw, height: ch, position: 'relative' }}>
        <ThreeCanvas width={Math.max(2, Math.round(cw))} height={Math.max(2, Math.round(ch))} camera={{ fov: FOV, position: [0, 0, CAM_Z], near: 0.1, far: 50 }} gl={{ antialias: true, alpha: true, preserveDrawingBuffer: true }}>
          <ambientLight intensity={0.5} />
          <directionalLight position={[5, 7, 6]} intensity={1.5} />
          <pointLight position={[-6, -2, 5]} intensity={0.8} color={t.accent2} />
          <group rotation={[rx, ry, 0]} scale={(0.55 + 0.45 * appear) * scale * (0.97 + 0.03 * pop)}>
            <mesh geometry={geo}>
              <meshStandardMaterial color={t.accent} roughness={0.35} metalness={0.15} transparent opacity={wire ? 0.12 : 0.82} side={THREE.DoubleSide} depthWrite={!wire} />
            </mesh>
            <lineSegments geometry={edges}>
              <lineBasicMaterial color={wire ? t.accent2 : (t.dark ? '#ffffff' : '#0a0a0a')} transparent opacity={wire ? 1 : 0.55} />
            </lineSegments>
          </group>
        </ThreeCanvas>
        {label && <div style={{ position: 'absolute', left: 0, right: 0, bottom: ch * 0.02, textAlign: 'center', fontWeight: 800, fontSize: fs, color: t.text, opacity: appear }}>{label}</div>}
      </div>
      {(props.labels || []).length > 0 && (
        <div style={{ flex: 1, display: 'flex', flexDirection: side ? 'column' : 'row', justifyContent: 'center', gap: fs * 0.55, flexWrap: 'wrap' }}>
          {props.labels.map((l, i) => {
            const a = clamp01(reveal - i);
            return (
              <div key={i} style={{ opacity: a, transform: `translateX(${(1 - a) * fs * 1.5}px)`, fontFamily: t.mono, fontWeight: 700, fontSize: fs * 0.95, color: t.text, padding: `${fs * 0.25}px ${fs * 0.6}px`, borderRadius: fs * 0.4, border: `2px solid ${i === 0 ? t.accent2 : t.panelBorder}`, background: hexA(i === 0 ? t.accent2 : t.accent, 0.12), whiteSpace: 'nowrap' }}>{l}</div>
            );
          })}
        </div>
      )}
    </div>
  );
};
