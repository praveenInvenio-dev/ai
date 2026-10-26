import { config } from '../config.js';
import { checkSchema } from './schemas.js';

let schemaFormatSupported = true; // flips to false if this Ollama build rejects JSON-schema `format`

export async function ollamaStatus(wantModel) {
  try {
    const res = await fetch(`${config.ollama.host}/api/tags`, { signal: AbortSignal.timeout(4000) });
    if (!res.ok) return { reachable: false, error: `HTTP ${res.status}` };
    const { models = [] } = await res.json();
    const names = models.map((m) => m.name);
    const want = wantModel || config.ollama.model;
    const modelPresent = names.some((n) => n === want || n.startsWith(want.includes(':') ? want : `${want}:`));
    return { reachable: true, model: want, modelPresent, models: names };
  } catch (e) {
    return { reachable: false, error: e.message };
  }
}

function stripFences(s) {
  return s.replace(/^\s*```(?:json)?\s*/i, '').replace(/\s*```\s*$/i, '').trim();
}

/** One streamed /api/chat call. Returns the full assistant text. */
async function chatOnce({ messages, format, temperature, signal, onProgress, model }) {
  const body = {
    model: model || config.ollama.model,
    messages,
    stream: true,
    format,
    keep_alive: config.ollama.keepAlive,
    options: { temperature, num_ctx: config.ollama.numCtx, top_p: 0.92, repeat_penalty: 1.08 },
  };
  const sig = AbortSignal.any([...(signal ? [signal] : []), AbortSignal.timeout(config.ollama.requestTimeoutMs)]);
  const res = await fetch(`${config.ollama.host}/api/chat`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify(body),
    signal: sig,
  });
  if (!res.ok) {
    const text = await res.text().catch(() => '');
    const err = new Error(`Ollama HTTP ${res.status}: ${text.slice(0, 300)}`);
    err.status = res.status;
    throw err;
  }
  const dec = new TextDecoder();
  let buf = '';
  let out = '';
  let n = 0;
  for await (const chunk of res.body) {
    buf += dec.decode(chunk, { stream: true });
    let i;
    while ((i = buf.indexOf('\n')) >= 0) {
      const line = buf.slice(0, i).trim();
      buf = buf.slice(i + 1);
      if (!line) continue;
      const msg = JSON.parse(line);
      if (msg.error) throw new Error(`Ollama: ${msg.error}`);
      if (msg.message?.content) {
        out += msg.message.content;
        if (++n % 60 === 0) onProgress?.(n);
      }
    }
  }
  return out;
}

/**
 * Ask for JSON, validate (JSON-Schema + optional semantic check), and self-repair by
 * showing the model its own error. This is what makes 7-8B models reliable.
 */
export async function chatJson({ system, user, schema, semantic, label, log, signal, maxAttempts = 3, temperature, model }) {
  const messages = [{ role: 'system', content: system }, { role: 'user', content: user }];
  let lastErr = 'unknown';
  for (let attempt = 1; attempt <= maxAttempts; attempt++) {
    signal?.throwIfAborted();
    // Lower the temperature on retries: we want compliance, not creativity.
    const temp = (temperature ?? config.ollama.temperature) * (attempt === 1 ? 1 : 0.6);
    let raw;
    try {
      raw = await chatOnce({
        messages, signal, temperature: temp, model,
        format: schemaFormatSupported ? schema : 'json',
        onProgress: (n) => log?.(`  ↳ ${label}: ${n} tokens…`, 'llm'),
      });
    } catch (e) {
      if (e.status === 400 && schemaFormatSupported) {
        schemaFormatSupported = false;
        log?.('Ollama rejected schema-format; falling back to plain JSON mode.', 'warn');
        attempt--;
        continue;
      }
      if (e.name === 'AbortError' || e.name === 'TimeoutError') throw e;
      lastErr = e.message;
      log?.(`${label}: request failed (${e.message}); attempt ${attempt}/${maxAttempts}`, 'warn');
      await new Promise((r) => setTimeout(r, 1500 * attempt));
      continue;
    }

    let data;
    try {
      data = JSON.parse(stripFences(raw));
    } catch (e) {
      lastErr = `not valid JSON (${e.message})`;
    }
    if (data !== undefined) {
      const s = checkSchema(schema, data);
      if (!s.ok) lastErr = `schema errors: ${s.errors.join('; ')}`;
      else {
        const sem = semantic ? semantic(data) : { ok: true };
        if (sem.ok) return data;
        lastErr = sem.error;
      }
    }
    log?.(`${label}: attempt ${attempt}/${maxAttempts} rejected — ${lastErr}`, 'warn');
    messages.push({ role: 'assistant', content: raw.slice(0, 6000) });
    messages.push({ role: 'user', content: `That response was rejected: ${lastErr}\nReturn the complete corrected JSON object only. No commentary.` });
  }
  throw new Error(`${label}: model could not produce valid JSON after ${maxAttempts} attempts (${lastErr})`);
}
