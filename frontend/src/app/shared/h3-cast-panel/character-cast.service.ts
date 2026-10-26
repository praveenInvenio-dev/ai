import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, map } from 'rxjs';

export type CastStatus = 'NONE' | 'GENERATING' | 'READY' | 'FAILED';
export interface CastCharacter {
  id: string; name: string; aliases: string[]; masterPrompt: string;
  uploadPath: string; referencePath?: string; referenceStatus: CastStatus; referenceError?: string;
  referenceIsUpload: boolean; locked: boolean; enabled: boolean;
  libraryCharacterId?: string; librarySyncedReference?: string; createdAt: number; updatedAt: number;
}

/** H3 character cast: named, locked reference images that every H3 clip mentioning the name receives. */
@Injectable({ providedIn: 'root' })
export class CharacterCastService {
  private http = inject(HttpClient);
  private base = '/api/character-cast';

  list() { return this.http.get<CastCharacter[]>(this.base); }
  create(name: string, masterPrompt: string, aliases: string, file: File, generate: boolean) {
    const f = new FormData();
    f.append('name', name); f.append('masterPrompt', masterPrompt); f.append('aliases', aliases);
    f.append('generate', String(generate)); f.append('file', file, file.name);
    return this.http.post<CastCharacter>(this.base, f);
  }
  update(id: string, patch: { name?: string; masterPrompt?: string; aliases?: string; enabled?: boolean }) {
    return this.http.put<CastCharacter>(`${this.base}/${id}`, patch);
  }
  replaceImage(id: string, file: File) {
    const f = new FormData(); f.append('file', file, file.name);
    return this.http.post<CastCharacter>(`${this.base}/${id}/image`, f);
  }
  generate(id: string) { return this.http.post<CastCharacter>(`${this.base}/${id}/reference`, {}); }
  useUpload(id: string) { return this.http.post<CastCharacter>(`${this.base}/${id}/use-upload`, {}); }
  lock(id: string, locked: boolean) { return this.http.post<CastCharacter>(`${this.base}/${id}/lock`, {}, { params: { locked: String(locked) } }); }
  linkLibrary(id: string, characterId: string) { return this.http.post<CastCharacter>(`${this.base}/${id}/library-link`, {}, { params: { characterId } }); }
  remove(id: string) { return this.http.delete(`${this.base}/${id}`); }

  /** Cache-busted so a regenerated reference shows immediately. */
  imageUrl(c: CastCharacter, kind: 'upload' | 'reference') { return `${this.base}/${c.id}/image/${kind}?v=${c.updatedAt}`; }

  /** The locked reference as a File (for the character library used by Story-to-H3 keyframes). */
  referenceFile(c: CastCharacter): Observable<File> {
    return this.http.get(`${this.base}/${c.id}/image/reference`, { responseType: 'blob' }).pipe(
      map((b) => new File([b], `${c.name.replace(/[^\w-]+/g, '_')}-reference.${b.type.includes('jpeg') ? 'jpg' : b.type.includes('webp') ? 'webp' : 'png'}`, { type: b.type || 'image/png' })),
    );
  }
}
