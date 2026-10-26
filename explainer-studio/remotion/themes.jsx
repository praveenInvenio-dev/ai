import React, { createContext, useContext } from 'react';

// Indian scripts: Noto (fonts-noto-core in the image) after Inter, so Latin keeps the Inter look.
const font = '"Inter","Helvetica Neue",Arial,"DejaVu Sans","Noto Sans Devanagari","Noto Sans Bengali","Noto Sans Tamil","Noto Sans Telugu","Noto Sans Kannada","Noto Sans Malayalam","Noto Sans Gujarati","Noto Sans Gurmukhi","Noto Sans Oriya",sans-serif';
const mono = '"Fira Code","JetBrains Mono",Consolas,"DejaVu Sans Mono","Noto Sans Devanagari","Noto Sans Bengali","Noto Sans Tamil","Noto Sans Telugu","Noto Sans Kannada","Noto Sans Malayalam","Noto Sans Gujarati","Noto Sans Gurmukhi","Noto Sans Oriya",monospace';

export const themes = {
  dracula: {
    id: 'dracula', label: 'Dracula Dark', font, mono, dark: true,
    bg: ['#282a36', '#1b1c26'], grid: 'rgba(189,147,249,0.07)', text: '#f8f8f2', muted: '#6272a4',
    panel: 'rgba(68,71,90,0.55)', panelBorder: 'rgba(98,114,164,0.7)',
    accent: '#bd93f9', accent2: '#ff79c6', good: '#50fa7b', bad: '#ff5555', warn: '#ffb86c', info: '#8be9fd',
    lanes: ['#8be9fd', '#ff79c6', '#50fa7b', '#ffb86c'], glow: 0.25, stroke: 3,
    code: { bg: 'rgba(30,31,41,0.92)', fg: '#f8f8f2', kw: '#ff79c6', num: '#bd93f9', str: '#f1fa8c', com: '#6272a4' },
    captionBg: 'rgba(20,21,30,0.72)', captionFg: '#f8f8f2', captionActive: '#f1fa8c', captionStroke: '#14151e',
  },
  cyberpunk: {
    id: 'cyberpunk', label: 'Cyberpunk', font, mono, dark: true,
    bg: ['#0d0221', '#2b0a4a'], grid: 'rgba(5,217,232,0.10)', text: '#d1f7ff', muted: '#7b6fa3',
    panel: 'rgba(20,6,45,0.62)', panelBorder: 'rgba(255,42,109,0.75)',
    accent: '#05d9e8', accent2: '#ff2a6d', good: '#00ff9f', bad: '#ff2a6d', warn: '#f9f002', info: '#05d9e8',
    lanes: ['#05d9e8', '#ff2a6d', '#f9f002', '#00ff9f'], glow: 0.9, stroke: 3,
    code: { bg: 'rgba(8,2,22,0.92)', fg: '#d1f7ff', kw: '#ff2a6d', num: '#f9f002', str: '#00ff9f', com: '#7b6fa3' },
    captionBg: 'rgba(8,2,22,0.75)', captionFg: '#ffffff', captionActive: '#f9f002', captionStroke: '#0d0221',
  },
  academic: {
    id: 'academic', label: 'High-Contrast Academic', font, mono, dark: false,
    bg: ['#ffffff', '#f2f4f8'], grid: 'rgba(11,61,145,0.08)', text: '#0a0a0a', muted: '#4a5568',
    panel: 'rgba(255,255,255,0.92)', panelBorder: '#0a0a0a',
    accent: '#0b3d91', accent2: '#b00020', good: '#006b3c', bad: '#c00000', warn: '#b35900', info: '#0b3d91',
    lanes: ['#0b3d91', '#b00020', '#006b3c', '#b35900'], glow: 0, stroke: 4,
    code: { bg: 'rgba(245,247,250,0.98)', fg: '#0a0a0a', kw: '#b00020', num: '#0b3d91', str: '#006b3c', com: '#4a5568' },
    captionBg: 'rgba(255,255,255,0.92)', captionFg: '#0a0a0a', captionActive: '#b00020', captionStroke: '#ffffff',
  },
};

export const ThemeCtx = createContext(themes.dracula);
export const useTheme = () => useContext(ThemeCtx);
