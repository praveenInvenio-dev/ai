import { chatJson } from './ollama.js';
import { outlineSchema, sceneSchema } from './schemas.js';
import { outlineSystem, outlineUser, sceneSystem, sceneUser, rewriteUser } from './prompts.js';
import { lintScene } from './lint.js';
import { sanitizeScene, fallbackScene, cleanText } from './validate.js';
import { budgetPlan } from '../presets.js';

/**
 * Two-stage generation, deliberately chunked so even a 7-8B model stays reliable:
 *   1. outline  — one small JSON (title, analogy, per-scene goal)
 *   2. per scene — narration + visuals in one small JSON each (so narration/visual cues share one index space)
 * Each stage: JSON-schema constrained → validated → self-repaired → linted → one rewrite pass → fallback.
 */
export async function generateScript({ topic, domain, platform, log, progress, signal, model, lang = 'en' }) {
  const plan = budgetPlan(platform);

  log(`Planning "${topic}" as ${platform.label} (${plan.length} scenes, ≈${Math.round(platform.targetSec)}s target)`, 'llm');
  const outline = await chatJson({
    system: outlineSystem(lang),
    user: outlineUser({ topic, domain, platform, plan }),
    schema: outlineSchema,
    label: 'outline',
    log, signal, model,
    semantic: (o) => (o.scenes.length === plan.length ? { ok: true } : { ok: false, error: `"scenes" must contain exactly ${plan.length} items, you returned ${o.scenes.length}` }),
  });
  outline.title = cleanText(outline.title);
  outline.hashtags = outline.hashtags.map((h) => String(h).replace(/[^\p{L}\p{M}\p{N}_]/gu, '')).filter(Boolean).slice(0, 8);
  log(`Title: ${outline.title}`, 'llm');
  log(`Analogy: ${outline.analogy}`, 'llm');

  const scenes = [];
  for (let i = 0; i < plan.length; i++) {
    signal?.throwIfAborted();
    progress(0.04 + 0.2 * (i / plan.length));
    const heading = cleanText(outline.scenes[i].heading).slice(0, 48);
    const prev = scenes.length ? scenes[scenes.length - 1].sentences.slice(-1)[0].text : undefined;
    const next = outline.scenes[i + 1]?.heading;
    log(`Writing scene ${i + 1}/${plan.length}: ${heading} [${plan[i].phases.join('→')}]`, 'llm');

    const system = sceneSystem({ domain, lang });
    // Visual variety: tell the writer which primitives the video has already used, and lint repeats.
    const usedBefore = scenes.map((sc) => (sc.elements || []).map((e) => e.type));
    const lastTypes = usedBefore.length ? usedBefore[usedBefore.length - 1] : [];
    const user = sceneUser({ topic, domain, platform, outline, index: i, plan, prev, next, usedBefore });
    let scene;
    try {
      let raw = await chatJson({ system, user, schema: sceneSchema, label: `scene ${i + 1}`, log, signal, model });
      let { scene: clean, warnings } = sanitizeScene(raw, { plan: plan[i], heading });
      let issues = [...lintScene(clean.sentences, { sentenceMaxWords: platform.sentenceMaxWords, role: plan[i].role, lang }), ...varietyIssues(clean, lastTypes, plan[i].role)];

      if (issues.length) {
        log(`Narration lint (${issues.length}): ${issues.slice(0, 3).join(' | ')} — asking for a rewrite`, 'warn');
        try {
          raw = await chatJson({
            system, user: `${user}\n\nPREVIOUS DRAFT:\n${JSON.stringify(raw)}\n\n${rewriteUser(issues)}`,
            schema: sceneSchema, label: `scene ${i + 1} rewrite`, log, signal, model, maxAttempts: 2, temperature: 0.6,
          });
          const second = sanitizeScene(raw, { plan: plan[i], heading });
          const issues2 = [...lintScene(second.scene.sentences, { sentenceMaxWords: platform.sentenceMaxWords, role: plan[i].role, lang }), ...varietyIssues(second.scene, lastTypes, plan[i].role)];
          if (issues2.length <= issues.length) { clean = second.scene; warnings = second.warnings; issues = issues2; }
        } catch (e) {
          if (e.name === 'AbortError') throw e;
          log(`Rewrite failed (${e.message}); keeping first draft`, 'warn');
        }
      }
      warnings.forEach((w) => log(`scene ${i + 1}: ${w}`, 'warn'));
      if (issues.length) log(`scene ${i + 1}: ${issues.length} minor style issue(s) remain; continuing`, 'warn');
      scene = clean;
    } catch (e) {
      if (e.name === 'AbortError' || signal?.aborted) throw e;
      log(`Scene ${i + 1} failed (${e.message}). Using safe fallback scene so the job can finish.`, 'error');
      scene = fallbackScene({ heading, plan: plan[i] });
    }
    scenes.push({ role: plan[i].role, heading, goal: outline.scenes[i].goal, phases: plan[i].phases, ...scene });
  }
  return { outline, scenes };
}

/** A scene that reuses exactly the previous scene's visuals looks like a still frame to the viewer: ask for a change. */
function varietyIssues(scene, lastTypes, role) {
  if (role === 'recap' || !lastTypes.length) return [];
  const types = (scene.elements || []).map((e) => e.type);
  const main = types[0];
  if (main && main === lastTypes[0] && types.length === lastTypes.length && types.every((t) => lastTypes.includes(t))) {
    return [`Visuals repeat the previous scene (${types.join(', ')}). Show this beat with a DIFFERENT primitive as the main element (e.g. race, stat, kinetic, icons, flow, code) — keep the same narration.`];
  }
  return [];
}
