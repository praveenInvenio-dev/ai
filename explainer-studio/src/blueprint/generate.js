import { chatJson } from '../llm/ollama.js';
import { languageBlock, LANGUAGES, latinShare, devanagariShare, scriptShare } from '../shared/languages.js';
import { BEAT_TYPES, beatsPrompt, sanitizeBeat } from './beats.js';

/**
 * Blueprint explainer: the "narrated diagram" format of channels like Few Shot Studio.
 *   00 / THE QUESTION  -> a question card + the stakes (one big number)
 *   01..n / chapters   -> the story of the real-world scenario, step by step, a NEW visual for every sentence
 *   last / THE ANSWER  -> the question card again + the answer as a ticked checklist + a question for the comments
 * A journey bar (3-4 milestones of the scenario: "TOSS -> 1ST BALL -> AD BREAK") shows where we are.
 */

const outlineSchema = {
  type: 'object',
  required: ['title', 'series', 'question', 'stakes', 'journey', 'chapters', 'answer', 'endQuestion', 'analogy', 'hashtags'],
  properties: {
    title: { type: 'string' },
    series: { type: 'string' },
    question: { type: 'object', required: ['asker', 'text'], properties: { asker: { type: 'string' }, text: { type: 'string' }, highlight: { type: 'string' } } },
    stakes: { type: 'object', required: ['value', 'label'], properties: { value: { type: 'string' }, unit: { type: 'string' }, label: { type: 'string' } } },
    journey: { type: 'array', items: { type: 'string' }, minItems: 2, maxItems: 4 },
    chapters: { type: 'array', minItems: 3, maxItems: 7, items: { type: 'object', required: ['title', 'goal'], properties: { title: { type: 'string' }, goal: { type: 'string' }, milestone: { type: 'integer' } } } },
    answer: { type: 'object', required: ['headline', 'items'], properties: { headline: { type: 'string' }, items: { type: 'array', items: { type: 'string' } } } },
    endQuestion: { type: 'string' },
    analogy: { type: 'string' },
    hashtags: { type: 'array', items: { type: 'string' } },
  },
};

const chapterSchema = {
  type: 'object',
  required: ['sentences'],
  properties: {
    sentences: {
      type: 'array', minItems: 2, maxItems: 9,
      items: { type: 'object', required: ['text', 'beat'], properties: { text: { type: 'string' }, speech: { type: 'string' }, beat: { type: 'object' } } },
    },
  },
};

const STYLE = `STYLE (copy this exactly - it is what makes these videos work):
- Anchor the WHOLE video in ONE real, concrete situation the viewer can picture, told in order:
  tech: "an IPL final on JioHotstar"; physics: "a 400-tonne A380 taking off", "a cricket ball hit for six", "you braking in an auto";
  chemistry: "a matchstick catching fire", "rust on your bicycle"; biology: "the sip of chai you just drank", "a mosquito bite";
  maths: "splitting a pizza bill", "a phone battery draining". Real numbers, real names, real moments.
- Open with the question card and the stakes (one big surprising number). Each chapter = the next moment of that situation and
  the ONE idea of the science/maths/engineering it forces. By the end the viewer can explain the WHY, not just the what.
- Explain the actual mechanism the way a great teacher draws it on the board: forces and their directions, what changes and why,
  the formula with every symbol explained, a quick calculation with real numbers. No hand-waving, no "magic".
- Teach like a friend at a chai stall: short spoken sentences (6-14 words), "socho", "ab dekho", one everyday analogy when an idea
  is abstract, THEN the real term.
- Name every technical term only AFTER showing it, with a term beat.
- End by answering the opening question in 3 points (checklist), then ask the viewer one question for the comments.
- No hype words without facts. No "in this video". No greeting.`;

/** Which board diagrams fit each subject (the writer still picks per sentence). */
const SUBJECT = {
  cs: { asker: 'INTERVIEWER', series: 'SYSTEM DESIGN', beats: 'devices, pipeline, fanout, bars, chart, compare, stat, term', extra: '' },
  physics_jee: { asker: 'YOUR FRIEND / JEE', series: 'PHYSICS', beats: 'forces (free-body diagrams!), motion, graph, equation, flow, wave, stat, compare, term',
    extra: 'Show forces as arrows with direction and size, motion as it happens, the formula with symbols explained, ONE quick numeric example (equation + stat), and the classic mistake students make (compare).' },
  math_jee: { asker: 'JEE / YOUR FRIEND', series: 'MATHS', beats: 'graph, equation, compare, stat, pipeline (steps of a method), term',
    extra: 'Build the idea visually first (graph / picture), then the formula, then one worked example with real numbers, then the common trap.' },
  chemistry_neet: { asker: 'NEET / YOUR FRIEND', series: 'CHEMISTRY', beats: 'reaction, parts (atom), equation, graph, compare, cycle, stat, term',
    extra: 'Show what the particles/electrons actually do, the balanced reaction with its condition, one number, and the common mistake.' },
  biology_neet: { asker: 'NEET / YOUR FRIEND', series: 'BIOLOGY', beats: 'parts (cell, heart, leaf, eye, neuron), cycle, pipeline, compare, stat, term',
    extra: 'Show the structure with labelled parts, then the process step by step (pipeline/cycle), one fact NEET loves to ask, and the common confusion.' },
  general: { asker: 'YOUR FRIEND', series: 'EXPLAINED', beats: 'any - pick what a teacher would draw (forces, motion, graph, equation, flow, parts, cycle, reaction, wave, devices, pipeline, compare...)', extra: '' },
};
const subjectOf = (domain) => SUBJECT[domain?.id] || SUBJECT.general;

function outlineSystem(lang, subj) {
  return `You plan short narrated diagram explainers (Instagram/YouTube), in the style of system-design and science explainer channels.
Output ONLY JSON matching the schema.
${STYLE}
SUBJECT: ${subj.series}. ${subj.extra}${languageBlock(lang)}`;
}

function outlineUser({ topic, targetSec, chapters, subj }) {
  return `TOPIC: ${topic}
Length ≈ ${targetSec}s narration. Plan exactly ${chapters} chapters; the FIRST is the question + stakes ("THE QUESTION"), the LAST is "THE ANSWER".
Fields:
- title: catchy title (max 60 chars)
- series: 1-2 word series label in CAPS for the corner (default "${subj.series}")
- question: {asker: ${subj.asker.split(" / ").map((x) => `"${x}"`).join(" | ")} (or another fitting asker), text: the question in one line (max 60 chars, e.g. "Build JioHotstar. Crores watch the IPL final live."), highlight: the most important words of it}
- stakes: the big number that shows why it is hard/interesting {value:"5.5", unit:"CRORE", label:"people watching at once"}
- journey: 3-4 short milestone labels of the situation in time order (e.g. ["TOSS","1ST BALL","AD BREAK"], ["RUNWAY","TAKE-OFF","CRUISE"], ["REST","PUSH","MOVING"])
- chapters: [{title: 1-3 words CAPS ("BEFORE THE TOSS"), goal: what this chapter teaches, milestone: index into journey}]
- answer: {headline: the core idea in 1-3 words ("Autoscaling"), items: 3 short answer points}
- endQuestion: one question for the comments ("Which moment was the hardest?")
- analogy: one everyday analogy used once where the idea is abstract
- hashtags: 4-8 without '#'`;
}

function chapterSystem(lang, subj) {
  return `You write ONE chapter of a narrated diagram explainer. Output ONLY JSON: {"sentences":[{"text", ${lang === 'hinglish' ? '"speech", ' : ''}"beat"}]}.
${STYLE}

VISUAL BEATS - every sentence gets ONE beat: the diagram that is on screen WHILE that sentence is spoken.
The picture must show exactly what the sentence says, with the topic's real nouns and numbers. Change the beat when the sentence moves on;
use "same": true only to keep building the previous beat (e.g. light up more devices, tick the next item, move "active" to the next step).
Beat types:
${beatsPrompt()}
Every beat also has:
- phrase: the 2-6 word key phrase shown under the diagram (the idea of this sentence, e.g. "shrinks the camera video")
- highlight: the ONE word of the phrase to colour
- kicker: optional 1-2 word CAPS label above the phrase (e.g. "ENCODER")
Vary beat types across the chapter; never 3 sentences in a row of the same type unless "same": true builds it.
SUBJECT ${subj.series}: best beats here are ${subj.beats}. ${subj.extra}
Pick the beat a teacher would DRAW for that exact sentence ("lift pushes the wing up" -> forces with an up arrow "Lift" on a plane, or flow over an airfoil;
"speed keeps increasing" -> motion accelerate or graph linear v-t; "F equals m a" -> equation).${languageBlock(lang)}`;
}

function chapterUser({ topic, outline, index, n, perChapter, prevText }) {
  const ch = outline.chapters[index];
  const first = index === 0, last = index === n - 1;
  return `TOPIC: ${topic}
VIDEO: "${outline.title}" - question: "${outline.question.text}" (${outline.question.asker}); stakes: ${outline.stakes.value} ${outline.stakes.unit || ''} ${outline.stakes.label}
JOURNEY: ${outline.journey.join(' -> ')}   ANALOGY (use once, where it helps): ${outline.analogy}
ALL CHAPTERS: ${outline.chapters.map((c, i) => `${String(i).padStart(2, '0')} ${c.title}`).join(' | ')}
THIS CHAPTER: ${String(index).padStart(2, '0')} / ${ch.title} - ${ch.goal}
${first ? `This is the opening: sentence 1 = the question (beat "question" with asker "${outline.question.asker}"), then the stakes (beat "stat" ${outline.stakes.value} ${outline.stakes.unit || ''}), then why it is hard.` : ''}
${last ? `This is the ending: bring back the question card, then answer it with a "checklist" beat (items: ${JSON.stringify(outline.answer.items)}; tick one per sentence with "same": true), headline "${outline.answer.headline}", then ask: "${outline.endQuestion}" (beat "question" with asker "YOUR TURN").` : ''}
${prevText ? `Previous sentence: "${prevText}" - continue naturally.` : ''}
Write ${perChapter} sentences.`;
}

function lintChapter(sentences, lang) {
  const issues = [];
  sentences.forEach((s, i) => {
    const words = String(s.text || '').trim().split(/\s+/).length;
    if (words > 20) issues.push(`Sentence ${i} has ${words} words - split it (max 16).`);
    if (!s.beat || !BEAT_TYPES.includes(s.beat.type)) issues.push(`Sentence ${i} needs a beat with a valid type (${BEAT_TYPES.join(', ')}).`);
    if (lang === 'hinglish') {
      if (latinShare(s.text) < 0.85) issues.push(`Sentence ${i} "text" must be Hinglish in Roman letters.`);
      if (!s.speech || devanagariShare(s.speech) < 0.6) issues.push(`Sentence ${i} needs "speech" in Devanagari.`);
    } else if (lang !== 'en' && scriptShare(s.text, lang) < 0.85) issues.push(`Sentence ${i} is not written in ${LANGUAGES[lang]?.label} script.`);
  });
  const types = sentences.map((s) => s.beat?.type);
  for (let i = 2; i < types.length; i++) {
    if (types[i] === types[i - 1] && types[i] === types[i - 2] && !sentences[i].beat?.same && !sentences[i - 1].beat?.same) {
      issues.push(`Sentences ${i - 2}-${i} all use "${types[i]}" without building: vary the visuals.`);
      break;
    }
  }
  return issues;
}

export async function generateBlueprint({ topic, platform, domain, log, progress, signal, model, lang = 'hinglish' }) {
  const subj = subjectOf(domain);
  const targetSec = platform.targetSec;
  const chapters = Math.max(4, Math.min(7, Math.round(targetSec / 24)));
  const perChapter = Math.max(3, Math.min(7, Math.round(targetSec / 3.6 / chapters)));
  log(`Planning blueprint explainer "${topic}" (${chapters} chapters, ≈${targetSec}s)`, 'llm');
  const outline = await chatJson({
    system: outlineSystem(lang, subj), user: outlineUser({ topic, targetSec, chapters, subj }), schema: outlineSchema, label: 'outline', log, signal, model,
    semantic: (o) => (o.chapters.length === chapters ? { ok: true } : { ok: false, error: `"chapters" must have exactly ${chapters} items` }),
  });
  outline.journey = outline.journey.map((j) => String(j).toUpperCase().slice(0, 14));
  outline.hashtags = (outline.hashtags || []).map((h) => String(h).replace(/[^\p{L}\p{M}\p{N}_]/gu, '')).filter(Boolean).slice(0, 8);
  outline.onScreenHook = '';
  outline.ctaLine = outline.endQuestion;
  outline.titleOptions = [outline.title];
  outline.logline = outline.answer?.headline || outline.title;
  log(`Title: ${outline.title} · question: ${outline.question.text} · stakes: ${outline.stakes.value} ${outline.stakes.unit || ''}`, 'llm');

  const scenes = [];
  let prevText = '';
  for (let i = 0; i < chapters; i++) {
    signal?.throwIfAborted();
    const user = chapterUser({ topic, outline, index: i, n: chapters, perChapter, prevText });
    let raw = await chatJson({ system: chapterSystem(lang, subj), user, schema: chapterSchema, label: `chapter ${i}`, log, signal, model });
    let issues = lintChapter(raw.sentences, lang);
    if (issues.length) {
      log(`Chapter ${i} lint: ${issues.join(' | ')}`, 'warn');
      try {
        const again = await chatJson({ system: chapterSystem(lang, subj), user: `${user}\n\nYour previous attempt had these problems - fix them:\n- ${issues.join('\n- ')}`,
          schema: chapterSchema, label: `chapter ${i} rewrite`, log, signal, model, maxAttempts: 2 });
        if (lintChapter(again.sentences, lang).length <= issues.length) raw = again;
      } catch (e) { log(`Chapter ${i} rewrite failed (${e.message}) - keeping the first draft`, 'warn'); }
    }
    const ch = outline.chapters[i];
    const sentences = raw.sentences.map((s) => ({
      text: String(s.text || '').trim(),
      speech: typeof s.speech === 'string' && s.speech.trim() ? s.speech.trim() : undefined,
      phase: 'explain', emphasis: false,
      beat: sanitizeBeat(s.beat, String(s.text || '')),
    })).filter((s) => s.text.length > 1);
    if (!sentences.length) throw new Error(`chapter ${i} produced no sentences`);
    scenes.push({ role: i === 0 ? 'question' : i === chapters - 1 ? 'answer' : 'chapter', heading: String(ch.title).toUpperCase().slice(0, 28),
      phases: ['explain'], elements: [], sentences, milestone: Math.max(0, Math.min(outline.journey.length - 1, Number(ch.milestone ?? Math.round((i / Math.max(1, chapters - 1)) * (outline.journey.length - 1))))) });
    prevText = sentences.at(-1).text;
    progress(0.02 + 0.22 * ((i + 1) / chapters));
    log(`Chapter ${String(i).padStart(2, '0')} / ${scenes.at(-1).heading}: ${sentences.length} sentences · beats ${sentences.map((s) => s.beat.type).join(', ')}`, 'llm');
  }
  return { outline, scenes };
}
