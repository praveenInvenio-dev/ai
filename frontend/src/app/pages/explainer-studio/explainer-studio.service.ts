import { Injectable, NgZone, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';

/** Explainer Studio runs as its own container; nginx proxies /explainer-api → explainer-studio:4000/api. */
const BASE = '/explainer-api';

export interface ExMeta {
  platforms: { id: string; label: string; group: 'short' | 'long'; targetSec: number; defaultAspect: string; scenes: number }[];
  aspects: string[]; fps: number[]; themes: string[];
  domains: { id: string; label: string }[];
  voices: { id: string; label: string; engine: string; available: boolean }[];
  defaultVoice: string; primitives: string[]; model: string;
}
export interface ExHealth {
  ok: boolean;
  ollama: { reachable: boolean; modelPresent?: boolean; model?: string; models?: string[]; error?: string };
  remotionBundle: boolean;
  queue: { running: number; queued: number; concurrency: number };
  renderer?: { browser: string; gl: string };
  tts?: { kokoroService: string | null };
}
export interface ExRequest { topic: string; domain: string; platform: string; aspect: string; fps: number; theme: string; voice: string; model?: string }
export interface ExLog { t: number; level: string; msg: string }
export interface ExResult {
  title: string; durationSeconds: number; sizeMB: number; video: string; thumbnail: string; srt: string; vtt: string; elapsedSeconds: number;
  metadata: { title: string; titleOptions: string[]; hashtags: string[]; description: string; chapters: string; platform: string; aspect: string; fps: number };
}
export interface ExState { status: string; stage: string; progress: number; queuePosition: number | null; result: ExResult | null; error: string | null }
export interface ExJob { id: string; topic: string; status: string; stage: string; progress: number; createdAt: number; request: ExRequest; result: { title: string; durationSeconds: number } | null }
export type ExEvent = ({ type: 'log' } & ExLog) | ({ type: 'state' } & ExState);

@Injectable({ providedIn: 'root' })
export class ExplainerStudioService {
  private http = inject(HttpClient);
  private zone = inject(NgZone);

  meta() { return this.http.get<ExMeta>(`${BASE}/meta`); }
  health() { return this.http.get<ExHealth>(`${BASE}/health`); }
  list() { return this.http.get<ExJob[]>(`${BASE}/jobs`); }
  create(req: ExRequest) { return this.http.post<{ id: string; position: number }>(`${BASE}/jobs`, req); }
  rerender(id: string, patch: Partial<ExRequest>) { return this.http.post<{ id: string }>(`${BASE}/jobs/${id}/rerender`, patch); }
  remove(id: string) { return this.http.delete(`${BASE}/jobs/${id}`); }

  /** SSE stream; the server replays the whole log on connect, so a refresh keeps history. */
  events(id: string): Observable<ExEvent> {
    return new Observable((sub) => {
      const es = new EventSource(`${BASE}/jobs/${id}/events`);
      es.onmessage = (m) => this.zone.run(() => {
        const e = JSON.parse(m.data) as ExEvent;
        sub.next(e);
        if (e.type === 'state' && ['done', 'failed', 'cancelled'].includes(e.status)) { es.close(); sub.complete(); }
      });
      return () => es.close();
    });
  }
}
