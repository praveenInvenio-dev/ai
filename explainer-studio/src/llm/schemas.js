import Ajv from 'ajv';
import { TYPES, SLOTS, PHASES } from '../shared/catalog.js';

const cue = {
  type: 'object',
  required: ['sentence'],
  properties: { sentence: { type: 'integer', minimum: 0 }, offsetMs: { type: 'integer', minimum: 0, maximum: 8000 } },
};

export const outlineSchema = {
  type: 'object',
  required: ['title', 'titleOptions', 'onScreenHook', 'ctaLine', 'analogy', 'logline', 'hashtags', 'scenes'],
  properties: {
    title: { type: 'string', minLength: 4, maxLength: 90 },
    titleOptions: { type: 'array', items: { type: 'string' }, minItems: 2, maxItems: 5 },
    onScreenHook: { type: 'string', minLength: 3, maxLength: 60 },
    ctaLine: { type: 'string', minLength: 3, maxLength: 120 },
    analogy: { type: 'string', minLength: 5 },
    logline: { type: 'string', minLength: 5 },
    hashtags: { type: 'array', items: { type: 'string' }, minItems: 2, maxItems: 10 },
    scenes: {
      type: 'array',
      minItems: 1,
      items: {
        type: 'object',
        required: ['heading', 'goal', 'keyVisual'],
        properties: { heading: { type: 'string' }, goal: { type: 'string' }, keyVisual: { type: 'string' } },
      },
    },
  },
};

export const sceneSchema = {
  type: 'object',
  required: ['sentences', 'elements'],
  properties: {
    sentences: {
      type: 'array',
      minItems: 1,
      maxItems: 40,
      items: {
        type: 'object',
        required: ['text', 'phase'],
        properties: {
          text: { type: 'string', minLength: 2 },
          phase: { type: 'string', enum: PHASES },
          techNote: { type: 'string' },
          emphasis: { type: 'boolean' },
        },
      },
    },
    elements: {
      type: 'array',
      maxItems: 6,
      items: {
        type: 'object',
        required: ['type', 'slot', 'props', 'enter'],
        properties: {
          type: { type: 'string', enum: TYPES },
          slot: { type: 'string', enum: SLOTS },
          props: { type: 'object' },
          enter: cue,
          exit: cue,
          events: {
            type: 'array',
            maxItems: 24,
            items: { type: 'object', required: ['at', 'set'], properties: { at: cue, set: { type: 'object' } } },
          },
        },
      },
    },
  },
};

const ajv = new Ajv({ allErrors: true, strict: false });
const compiled = new Map();
export function checkSchema(schema, data) {
  if (!compiled.has(schema)) compiled.set(schema, ajv.compile(schema));
  const fn = compiled.get(schema);
  const ok = fn(data);
  return { ok, errors: ok ? [] : (fn.errors || []).slice(0, 8).map((e) => `${e.instancePath || '/'} ${e.message}`) };
}
