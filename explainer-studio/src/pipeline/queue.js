/**
 * Bounded in-memory FIFO with a concurrency cap and cancellation.
 * Rendering is CPU/RAM heavy (Chromium + x264) and Ollama needs the GPU/CPU too, so the default is 1 job at a time:
 * a burst of requests queues instead of OOM-killing the container.
 */
export class JobQueue {
  constructor({ concurrency = 1, maxQueued = 20, runner, onPositions }) {
    Object.assign(this, { concurrency, maxQueued, runner, onPositions });
    this.waiting = [];
    this.running = new Map(); // id -> AbortController
  }

  enqueue(id) {
    if (this.waiting.length >= this.maxQueued) throw Object.assign(new Error('Queue is full, try again later.'), { status: 429 });
    this.waiting.push(id);
    this._pump();           // start immediately if a slot is free, so "queued" only counts jobs that truly wait
    this._positions();
    return this.waiting.length + this.running.size;
  }

  cancel(id) {
    const i = this.waiting.indexOf(id);
    if (i >= 0) { this.waiting.splice(i, 1); this._positions(); return 'queued'; }
    const ac = this.running.get(id);
    if (ac) { ac.abort(); return 'running'; }
    return null;
  }

  abortAll() { for (const ac of this.running.values()) ac.abort(); this.waiting = []; }

  _positions() { this.waiting.forEach((id, i) => this.onPositions?.(id, i + 1)); }

  _pump() {
    while (this.running.size < this.concurrency && this.waiting.length) {
      const id = this.waiting.shift();
      const ac = new AbortController();
      this.running.set(id, ac);
      this._positions();
      Promise.resolve()
        .then(() => this.runner(id, ac.signal))
        .catch(() => {}) // runner handles its own errors; never let one job break the pump
        .finally(() => { this.running.delete(id); this._pump(); });
    }
  }

  stats() { return { running: this.running.size, queued: this.waiting.length, concurrency: this.concurrency }; }
}
