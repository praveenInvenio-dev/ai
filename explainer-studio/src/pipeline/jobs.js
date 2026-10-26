import fs from 'node:fs/promises';
import fsSync from 'node:fs';
import path from 'node:path';
import { EventEmitter } from 'node:events';
import { config, jobsDir } from '../config.js';

const MAX_LOG = 3000;

/** In-memory job registry with JSON persistence + replayable event streams for SSE. */
export class JobStore {
  constructor() {
    this.jobs = new Map();
    this.bus = new EventEmitter();
    this.bus.setMaxListeners(200);
  }

  dir(id) { return path.join(jobsDir(), id); }

  async init() {
    await fs.mkdir(jobsDir(), { recursive: true });
    const ids = await fs.readdir(jobsDir());
    const cutoff = Date.now() - config.retentionDays * 86_400_000;
    for (const id of ids) {
      const f = path.join(this.dir(id), 'job.json');
      try {
        const job = JSON.parse(await fs.readFile(f, 'utf8'));
        if (job.createdAt < cutoff) { await fs.rm(this.dir(id), { recursive: true, force: true }); continue; }
        if (['queued', 'running'].includes(job.status)) {
          job.status = 'failed';
          job.error = 'Server restarted while this job was running.';
          job.logs.push({ t: Date.now(), level: 'error', msg: job.error });
        }
        this.jobs.set(id, job);
      } catch { /* ignore folders without a valid job.json */ }
    }
  }

  create(id, request) {
    const job = {
      id, request, status: 'queued', stage: 'queued', progress: 0, createdAt: Date.now(),
      logs: [], result: null, error: null, queuePosition: null,
    };
    this.jobs.set(id, job);
    fsSync.mkdirSync(this.dir(id), { recursive: true });
    this.save(job);
    return job;
  }

  get(id) { return this.jobs.get(id); }
  list() { return [...this.jobs.values()].sort((a, b) => b.createdAt - a.createdAt).map(this.summary); }
  summary = (j) => ({ id: j.id, topic: j.request.topic, status: j.status, stage: j.stage, progress: j.progress, createdAt: j.createdAt, request: j.request, result: j.result && { title: j.result.title, durationSeconds: j.result.durationSeconds } });

  async save(job) {
    const f = path.join(this.dir(job.id), 'job.json');
    const tmp = `${f}.tmp`;
    await fs.writeFile(tmp, JSON.stringify(job));
    await fs.rename(tmp, f); // atomic: never leave a half-written job.json
  }

  _emit(job, event) { this.bus.emit(job.id, event); }

  log(job, msg, level = 'info') {
    const entry = { t: Date.now(), level, msg };
    job.logs.push(entry);
    if (job.logs.length > MAX_LOG) job.logs.splice(0, job.logs.length - MAX_LOG);
    this._emit(job, { type: 'log', ...entry });
  }

  update(job, patch) {
    Object.assign(job, patch);
    this._emit(job, { type: 'state', status: job.status, stage: job.stage, progress: job.progress, queuePosition: job.queuePosition, result: job.result, error: job.error });
    if (patch.status || patch.result || patch.error) this.save(job).catch(() => {});
  }

  async remove(id) {
    this.jobs.delete(id);
    await fs.rm(this.dir(id), { recursive: true, force: true });
  }

  subscribe(id, fn) { this.bus.on(id, fn); return () => this.bus.off(id, fn); }
}
