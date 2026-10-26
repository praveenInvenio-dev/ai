import React, { useMemo } from 'react';
import * as THREE from 'three';
import { ThreeCanvas } from '@remotion/three';
import { useTheme } from '../themes.jsx';
import { useCue, numAt, valueAt, clamp01 } from '../hooks/cue.js';
import { rotate, project, CAM_Z, FOV } from './three3d.js';

const EL = { H: ['#f2f2f2', 0.55], C: ['#5b6270', 0.85], N: ['#3d6cff', 0.8], O: ['#ff3b3b', 0.8], Cl: ['#2fd07a', 0.95], Na: ['#a45cff', 1.05], S: ['#ffd02e', 0.95], P: ['#ff962e', 0.95], K: ['#c77dff', 1.1], F: ['#9ae66e', 0.7], Mg: ['#7be0b0', 1.0], Fe: ['#d9733f', 1.0], Ca: ['#9aa7ff', 1.05] };
const UP = new THREE.Vector3(0, 1, 0);

const Bond = ({ a, b, prog, offset, color }) => {
  const A = new THREE.Vector3(...a).add(offset), B = new THREE.Vector3(...b).add(offset);
  const dir = B.clone().sub(A);
  const len = dir.length() * prog;
  if (len < 0.01) return null;
  const mid = A.clone().add(dir.clone().normalize().multiplyScalar(len / 2));
  const q = new THREE.Quaternion().setFromUnitVectors(UP, dir.clone().normalize());
  return (
    <mesh position={mid.toArray()} quaternion={q.toArray()}>
      <cylinderGeometry args={[0.075, 0.075, len, 20]} />
      <meshStandardMaterial color={color} roughness={0.45} metalness={0.1} />
    </mesh>
  );
};

/**
 * Real 3D ball-and-stick molecule (three.js through @remotion/three). It turns slowly, bonds grow one by one,
 * and the highlighted atom glows. Labels are HTML on top, positioned with the same projection as the camera.
 */
export const Molecule3D = ({ w, h, props, events }) => {
  const t = useTheme();
  const cue = useCue(events);
  const { atoms, bonds } = props;
  const formed = numAt(cue, 'bonds', bonds.length, { damping: 24, stiffness: 50 });
  const hl = String(valueAt(cue, 'highlight', ''));
  const spin = Number(valueAt(cue, 'spin', 1)) > 0;
  const appear = clamp01(cue.ageMs / 600);

  // 0..100 layout coordinates → centred 3D space that always fits the frame.
  const P = useMemo(() => {
    const raw = atoms.map((a) => [(a.x - 50) / 50, (50 - a.y) / 50, ((a.z ?? 50) - 50) / 50]);
    const c = raw.reduce((s, p) => s.map((v, i) => v + p[i] / raw.length), [0, 0, 0]);
    const ext = Math.max(0.35, ...raw.map((p) => Math.hypot(p[0] - c[0], p[1] - c[1], p[2] - c[2])));
    return Object.fromEntries(atoms.map((a, i) => [a.id, raw[i].map((v, k) => ((v - c[k]) / ext) * 1.75)]));
  }, [atoms]);

  const ry = (spin ? cue.ageMs / 1000 * 0.45 : 0) - 0.35;
  const rx = 0.28 + 0.06 * Math.sin(cue.ageMs / 1700);
  const minDim = Math.min(w, h);
  const fs = Math.max(12, minDim * 0.045);

  return (
    <div style={{ width: w, height: h, position: 'relative' }}>
      <ThreeCanvas width={Math.max(2, Math.round(w))} height={Math.max(2, Math.round(h))} camera={{ fov: FOV, position: [0, 0, CAM_Z], near: 0.1, far: 50 }} gl={{ antialias: true, alpha: true, preserveDrawingBuffer: true }}>
        <ambientLight intensity={0.55} />
        <directionalLight position={[4, 6, 6]} intensity={1.4} />
        <pointLight position={[-5, -3, 4]} intensity={0.6} color={t.accent} />
        <group rotation={[rx, ry, 0]} scale={0.6 + 0.4 * appear}>
          {bonds.map(([a, b, order], i) => {
            const prog = clamp01(formed - i);
            if (prog <= 0 || !P[a] || !P[b]) return null;
            const dir = new THREE.Vector3(...P[b]).sub(new THREE.Vector3(...P[a]));
            const perp = new THREE.Vector3().crossVectors(dir, new THREE.Vector3(0, 0, 1)).normalize().multiplyScalar(0.13);
            if (perp.lengthSq() === 0) perp.set(0.13, 0, 0);
            const offs = order === 1 ? [0] : order === 2 ? [-1, 1] : [-1.6, 0, 1.6];
            return offs.map((o, k) => <Bond key={`${i}-${k}`} a={P[a]} b={P[b]} prog={prog} offset={perp.clone().multiplyScalar(o)} color={t.dark ? '#c9cbd6' : '#555a66'} />);
          })}
          {atoms.map((a) => {
            const [c, rs] = EL[a.el] || ['#cccccc', 0.8];
            const lit = hl === a.id;
            return (
              <mesh key={a.id} position={P[a.id]}>
                <sphereGeometry args={[0.36 * rs, 48, 32]} />
                <meshStandardMaterial color={c} roughness={0.28} metalness={0.08} emissive={lit ? t.accent2 : '#000000'} emissiveIntensity={lit ? 0.55 + 0.25 * Math.sin(cue.frame * 0.3) : 0} />
              </mesh>
            );
          })}
        </group>
      </ThreeCanvas>
      <svg width={w} height={h} style={{ position: 'absolute', inset: 0, fontFamily: t.font, overflow: 'visible' }}>
        {atoms.map((a) => {
          const r3 = rotate(P[a.id].map((v) => v * (0.6 + 0.4 * appear)), rx, ry);
          const s = project(r3, w, h);
          const lit = hl === a.id;
          return (
            <text key={a.id} x={s.x} y={s.y + fs * 0.35} textAnchor="middle" fontSize={fs * (s.scale / (h / 2 / Math.tan((FOV / 2) * Math.PI / 180) / CAM_Z))} fontWeight={900}
              fill={a.el === 'H' || a.el === 'S' ? '#111' : '#fff'} opacity={appear * (r3[2] < -0.35 ? 0.3 : 1)} stroke={lit ? t.accent2 : 'none'} strokeWidth={lit ? 1 : 0}>{a.el}</text>
          );
        })}
      </svg>
    </div>
  );
};
