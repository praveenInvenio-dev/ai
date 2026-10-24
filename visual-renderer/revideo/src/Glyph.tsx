import { Node, Path } from '@revideo/2d';
import { ICON_PATHS } from './icons';

/** A Lucide icon drawn with Path nodes, centred on (0, 0) and `size` pixels wide. layout={false}: inside a layout parent the paths must not be flex-positioned. */
export function Glyph({ name, color, size }: { name: string; color: string; size: number }) {
  const k = size / 24;
  return (
    <Node scale={k}>
      {(ICON_PATHS[name] || []).map(d => (
        <Path layout={false} data={d} x={-12} y={-12} stroke={color} lineWidth={2} lineCap="round" lineJoin="round" />
      ))}
    </Node>
  );
}
