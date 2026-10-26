import { Callout } from './Callout.jsx';
import { Code } from './Code.jsx';
import { Threads } from './Threads.jsx';
import { Variable } from './Variable.jsx';
import { Lock } from './Lock.jsx';
import { Memory } from './Memory.jsx';
import { Flow } from './Flow.jsx';
import { Plot } from './Plot.jsx';
import { Projectile } from './Projectile.jsx';
import { Molecule } from './Molecule.jsx';
import { Bars } from './Bars.jsx';
import { Equation } from './Equation.jsx';
import { Icons } from './Icons.jsx';
import { Race } from './Race.jsx';
import { Stat } from './Stat.jsx';
import { Kinetic } from './Kinetic.jsx';
import React from 'react';
import { Molecule3D as Molecule3DRaw } from './Molecule3D.jsx';
import { Solid3D as Solid3DRaw } from './Solid3D.jsx';
import { Safe3D } from './Safe3D.jsx';

// 3D primitives degrade to a 2D equivalent instead of failing the render.
const Molecule3D = (p) => <Safe3D fallback={<Molecule {...p} />}><Molecule3DRaw {...p} /></Safe3D>;
const Solid3D = (p) => <Safe3D fallback={<Callout {...p} props={{ text: p.props.title || p.props.shape, tone: 'info' }} />}><Solid3DRaw {...p} /></Safe3D>;

/** type (from shared/catalog.js) → React component. Keep in sync with the catalog. */
export const PRIMITIVES = { callout: Callout, code: Code, threads: Threads, variable: Variable, lock: Lock, memory: Memory, flow: Flow, plot: Plot, projectile: Projectile, molecule: Molecule, bars: Bars, equation: Equation,
  icons: Icons, molecule3d: Molecule3D, solid3d: Solid3D,
  race: Race, stat: Stat, kinetic: Kinetic };
