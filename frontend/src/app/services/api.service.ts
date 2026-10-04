import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { map } from 'rxjs/operators';
import { environment } from '../../environments/environment';
import {
  Project, Universe, Character, Episode, SceneDto,
  StoryDraftResponse, CreateStoryRequest, JobStatusResponse, OllamaModelsResponse, CharacterReference,
  ResourceStatus, VoiceProfile, VoiceValidationResult
} from '../models/models';

@Injectable({ providedIn: 'root' })
export class ApiService {
  private base = environment.apiBaseUrl;

  constructor(private http: HttpClient) {}

  // Projects
  listProjects(): Observable<Project[]> {
    return this.http.get<Project[]>(`${this.base}/projects`);
  }
  createProject(name: string, description?: string): Observable<Project> {
    return this.http.post<Project>(`${this.base}/projects`, { name, description });
  }
  getProject(id: string): Observable<Project> {
    return this.http.get<Project>(`${this.base}/projects/${id}`);
  }

  // Universes
  listUniverses(projectId: string): Observable<Universe[]> {
    return this.http.get<Universe[]>(`${this.base}/universes`, { params: { projectId } });
  }
  createUniverse(payload: Partial<Universe> & { projectId: string; name: string }): Observable<Universe> {
    return this.http.post<Universe>(`${this.base}/universes`, payload);
  }

  // Characters
  listCharacters(universeId: string): Observable<Character[]> {
    return this.http.get<Character[]>(`${this.base}/characters`, { params: { universeId } });
  }
  createCharacter(payload: Partial<Character> & { name: string; canonicalDescription: string }): Observable<Character> {
    return this.http.post<Character>(`${this.base}/characters`, payload);
  }
  lockCharacter(id: string, locked: boolean): Observable<Character> {
    return this.http.post<Character>(`${this.base}/characters/${id}/lock`, {}, { params: { locked } });
  }

  // Episodes / stories
  setNarratorVoiceProfile(episodeId: string, voiceProfileId: string | null): Observable<Episode> {
    return this.http.post<Episode>(`${this.base}/episodes/${episodeId}/narrator-voice`, { voiceProfileId: voiceProfileId || '' });
  }

  listEpisodes(projectId: string): Observable<Episode[]> {
    return this.http.get<Episode[]>(`${this.base}/episodes`, { params: { projectId } });
  }
  getEpisode(id: string): Observable<Episode> {
    return this.http.get<Episode>(`${this.base}/episodes/${id}`);
  }
  getScenes(episodeId: string): Observable<SceneDto[]> {
    return this.http.get<SceneDto[]>(`${this.base}/episodes/${episodeId}/scenes`);
  }
  getEpisodeCharacters(episodeId: string): Observable<Character[]> {
    return this.http.get<Character[]>(`${this.base}/episodes/${episodeId}/characters`);
  }
  generateCharacterMasterPrompt(characterId: string): Observable<{ prompt: string }> {
    return this.http.post<{ prompt: string }>(`${this.base}/characters/${characterId}/reference-prompt`, {});
  }
  generateCharacterReference(characterId: string, visualStyle?: string, prompt?: string): Observable<CharacterReference> {
    return this.http.post<CharacterReference>(`${this.base}/characters/${characterId}/generate-reference`, { visualStyle, prompt });
  }
  uploadCharacterReference(characterId: string, file: File): Observable<CharacterReference> {
    const form = new FormData();
    form.append('file', file);
    return this.http.post<CharacterReference>(`${this.base}/characters/${characterId}/upload-reference`, form);
  }
  lockCharacterReference(characterId: string, referenceId: string, locked: boolean): Observable<CharacterReference> {
    return this.http.post<CharacterReference>(`${this.base}/characters/${characterId}/references/${referenceId}/lock`, {}, { params: { locked } });
  }
  listCharacterReferences(characterId: string): Observable<CharacterReference[]> {
    return this.http.get<CharacterReference[]>(`${this.base}/characters/${characterId}/references`);
  }

  createDraft(req: CreateStoryRequest): Observable<StoryDraftResponse> {
    return this.http.post<StoryDraftResponse>(`${this.base}/stories/draft`, req);
  }
  regenerateDraft(episodeId: string, characterIds: string[] = []): Observable<StoryDraftResponse> {
    return this.http.post<StoryDraftResponse>(`${this.base}/stories/${episodeId}/regenerate`, characterIds);
  }

  generateStoryImages(episodeId: string): Observable<{ id: string }> {
    return this.http.post<{ id: string }>(`${this.base}/episodes/${episodeId}/generate-images`, {});
  }

  updateSceneVoices(episodeId: string, sceneId: string, segments: VoiceSegment[]): Observable<SceneDto> {
    return this.http.put<SceneDto>(`${this.base}/episodes/${episodeId}/scenes/${sceneId}/voice-segments`, segments);
  }

  setSceneLock(episodeId: string, sceneId: string, locked: boolean): Observable<SceneDto> {
    return this.http.post<SceneDto>(`${this.base}/episodes/${episodeId}/scenes/${sceneId}/lock?locked=${locked}`, {});
  }

  regenerateSceneImage(episodeId: string, sceneId: string): Observable<SceneDto> {
    return this.http.post<SceneDto>(`${this.base}/episodes/${episodeId}/scenes/${sceneId}/regenerate-image`, {});
  }

  setNarrationLock(episodeId: string, sceneId: string, locked: boolean): Observable<SceneDto> {
    return this.http.post<SceneDto>(`${this.base}/episodes/${episodeId}/scenes/${sceneId}/lock-narration?locked=${locked}`, {});
  }

  regenerateSceneNarration(episodeId: string, sceneId: string): Observable<SceneDto> {
    return this.http.post<SceneDto>(`${this.base}/episodes/${episodeId}/scenes/${sceneId}/regenerate-narration`, {});
  }

  setAnimationMode(episodeId: string, sceneId: string, mode: string): Observable<SceneDto> {
    return this.http.post<SceneDto>(`${this.base}/episodes/${episodeId}/scenes/${sceneId}/animation-mode`, {}, { params: { mode } });
  }

  setMusicPreset(episodeId: string, preset: string | null): Observable<Episode> {
    const options = preset ? { params: { preset } } : {};
    return this.http.post<Episode>(`${this.base}/episodes/${episodeId}/music-preset`, {}, options);
  }

  setMusicLock(episodeId: string, locked: boolean): Observable<Episode> {
    return this.http.post<Episode>(`${this.base}/episodes/${episodeId}/music-lock`, {}, { params: { locked } });
  }

  resourceStatus(): Observable<ResourceStatus> {
    return this.http.get<ResourceStatus>(`${this.base}/system/resources`);
  }

  attachMusic(episodeId: string, file: File): Observable<any> {
    const form = new FormData();
    form.append('file', file);
    return this.http.post(`${this.base}/storyboard/episodes/${episodeId}/music`, form);
  }

  approveAndProduce(episodeId: string): Observable<{ id: string }> {
    return this.http.post<{ id: string }>(`${this.base}/episodes/${episodeId}/approve`, {});
  }

  getJobStatus(jobId: string): Observable<JobStatusResponse> {
    return this.http.get<JobStatusResponse>(`${this.base}/jobs/${jobId}`);
  }

  getLatestJobForEpisode(episodeId: string): Observable<JobStatusResponse> {
    return this.http.get<JobStatusResponse>(`${this.base}/episodes/${episodeId}/latest-job`);
  }

  streamJob(jobId: string): EventSource {
    return new EventSource(`${this.base}/jobs/${jobId}/stream`);
  }

  downloadPackageUrl(episodeId: string): string {
    return `${this.base}/episodes/${episodeId}/package`;
  }

  sceneImageUrl(sceneId: string): string {
    return `${this.base}/scenes/${sceneId}/image`;
  }

  sceneAudioUrl(sceneId: string): string {
    return `${this.base}/scenes/${sceneId}/audio`;
  }

  episodeVideoUrl(episodeId: string): string {
    return `${this.base}/episodes/${episodeId}/video`;
  }

  episodeThumbnailUrl(episodeId: string): string {
    return `${this.base}/episodes/${episodeId}/thumbnail`;
  }

  health(): Observable<any> {
    return this.http.get(`${this.base}/health`);
  }

  listOllamaModels(): Observable<OllamaModelsResponse> {
    return this.http.get<OllamaModelsResponse>(`${this.base}/models/ollama`);
  }


  characterReferenceImageUrl(referenceId: string): string {
    return `${this.base}/character-references/${referenceId}/image`;
  }

  // Voice library
  listVoices(): Observable<VoiceListResponse> {
    return this.http.get<VoiceListResponse>(`${this.base}/tts/voices`);
  }

  installVoice(voiceId: string): Observable<any> {
    return this.http.post(`${this.base}/tts/voices/${voiceId}`, {});
  }

  /**
   * Returns the WAV as a Blob rather than a URL string: the preview is a POST
   * (the text can be long and is not URL-safe), so it cannot be handed to an
   * <audio src> directly. The caller wraps it with URL.createObjectURL.
   */
  previewVoice(text: string, voice: string, speed: number, pitch: number): Observable<Blob> {
    return this.http.post(`${this.base}/tts/preview`, { text, voice, speed, pitch }, { responseType: 'blob' });
  }

  // Video editor
  videoEditorCapabilities(): Observable<VideoEditorCapabilities> {
    return this.http.get<VideoEditorCapabilities>(`${this.base}/video-editor/capabilities`);
  }

  createVideoEditorProject(body: Partial<VideoEditorProject>): Observable<VideoEditorProject> {
    return this.http.post<VideoEditorProject>(`${this.base}/video-editor/projects`, body);
  }

  listVideoEditorProjects(): Observable<VideoEditorProject[]> {
    return this.http.get<VideoEditorProject[]>(`${this.base}/video-editor/projects`);
  }

  getVideoEditorProject(id: string): Observable<VideoEditorProjectDetail> {
    return this.http.get<VideoEditorProjectDetail>(`${this.base}/video-editor/projects/${id}`);
  }

  deleteVideoEditorProject(id: string): Observable<void> {
    return this.http.delete<void>(`${this.base}/video-editor/projects/${id}`);
  }

  /** Multipart upload. Angular sets the boundary itself, so no Content-Type
   *  header is passed - setting it manually omits the boundary and the server
   *  rejects the request. */
  uploadVideoClips(projectId: string, files: File[]): Observable<VideoClip[]> {
    const form = new FormData();
    files.forEach(f => form.append('files', f, f.name));
    return this.http.post<VideoClip[]>(`${this.base}/video-editor/projects/${projectId}/upload`, form);
  }

  completeVideoUpload(projectId: string): Observable<VideoEditorProject> {
    return this.http.post<VideoEditorProject>(`${this.base}/video-editor/projects/${projectId}/upload/complete`, {});
  }

  updateVideoEditorProject(projectId: string, body: Partial<VideoEditorProject>): Observable<VideoEditorProject> {
    return this.http.patch<VideoEditorProject>(`${this.base}/video-editor/projects/${projectId}`, body);
  }

  removeVideoClip(projectId: string, clipId: string): Observable<void> {
    return this.http.delete<void>(`${this.base}/video-editor/projects/${projectId}/clips/${clipId}`);
  }

  reorderVideoClips(projectId: string, clipIds: string[]): Observable<VideoClip[]> {
    return this.http.post<VideoClip[]>(`${this.base}/video-editor/projects/${projectId}/clips/reorder`, { clipIds });
  }

  renameVideoClip(projectId: string, clipId: string, name: string): Observable<VideoClip> {
    return this.http.patch<VideoClip>(`${this.base}/video-editor/projects/${projectId}/clips/${clipId}`, { name });
  }

  analyzeVideoProject(projectId: string): Observable<VideoJobStart> {
    return this.http.post<VideoJobStart>(`${this.base}/video-editor/projects/${projectId}/analyze`, {});
  }

  aiEditVideoProject(projectId: string, useAiDirector = true): Observable<VideoJobStart> {
    return this.http.post<VideoJobStart>(
      `${this.base}/video-editor/projects/${projectId}/ai-edit`, { useAiDirector });
  }

  previewVideoProject(projectId: string): Observable<VideoJobStart> {
    return this.http.post<VideoJobStart>(`${this.base}/video-editor/projects/${projectId}/preview`, {});
  }

  renderVideoProject(projectId: string): Observable<VideoJobStart> {
    return this.http.post<VideoJobStart>(`${this.base}/video-editor/projects/${projectId}/render`, {});
  }

  videoTimeline(projectId: string): Observable<VideoTimelineClip[]> {
    return this.http.get<VideoTimelineClip[]>(`${this.base}/video-editor/projects/${projectId}/timeline`);
  }

  replaceVideoTimeline(projectId: string, timeline: Array<{ clipId: string; sourceStartSec: number; sourceEndSec: number; techniqueIn?: string | null; techniqueOut?: string | null; transitionSec?: number | null; speed?: number; volume?: number; muted?: boolean; locked?: boolean }>): Observable<VideoTimelineClip[]> {
    return this.http.put<VideoTimelineClip[]>(`${this.base}/video-editor/projects/${projectId}/timeline`, { timeline });
  }

  videoEditorVersions(projectId: string): Observable<VideoEditorVersion[]> {
    return this.http.get<VideoEditorVersion[]>(`${this.base}/video-editor/projects/${projectId}/versions`);
  }

  restoreVideoEditorVersion(projectId: string, planId: string): Observable<VideoTimelineClip[]> {
    return this.http.post<VideoTimelineClip[]>(`${this.base}/video-editor/projects/${projectId}/versions/${planId}/restore`, {});
  }

  sourceVideoUrl(projectId: string, clipId: string): string {
    return `${this.base}/video-editor/projects/${projectId}/clips/${clipId}/file`;
  }

  videoClipThumbnailUrl(projectId: string, clipId: string): string {
    return `${this.base}/video-editor/projects/${projectId}/clips/${clipId}/thumbnail`;
  }

  videoJob(jobId: string): Observable<VideoRenderJob> {
    return this.http.get<VideoRenderJob>(`${this.base}/video-editor/jobs/${jobId}`);
  }

  uploadVideoMusic(projectId: string, file: File): Observable<any> {
    const form = new FormData();
    form.append('file', file, file.name);
    return this.http.post(`${this.base}/video-editor/projects/${projectId}/music`, form);
  }

  /** Direct URL for a <video src>. The endpoint supports byte ranges so the
   *  browser can scrub without downloading the whole file first. */
  renderedVideoUrl(projectId: string, jobId: string): string {
    return `${this.base}/video-editor/projects/${projectId}/renders/${jobId}/file`;
  }

  renderedVideoDownloadUrl(projectId: string, jobId: string): string {
    return `${this.base}/video-editor/projects/${projectId}/renders/${jobId}/download`;
  }

  /** SSE progress. EventSource is used directly rather than HttpClient because
   *  HttpClient buffers the whole response, which never completes on a stream. */
  videoProgressStream(jobId: string): EventSource {
    return new EventSource(`${this.base}/video-editor/jobs/${jobId}/progress`);
  }

  // Storyboard
  createStoryboard(body: { projectId?: string; title: string; visualStyle?: string; language?: string }):
      Observable<StoryboardEpisode> {
    return this.http.post<StoryboardEpisode>(`${this.base}/storyboard/episodes`, body);
  }

  storyboardScenes(episodeId: string): Observable<StoryboardScene[]> {
    return this.http.get<StoryboardScene[]>(`${this.base}/storyboard/episodes/${episodeId}/scenes`);
  }

  addStoryboardScene(episodeId: string, body: Partial<StoryboardScene>): Observable<StoryboardScene> {
    return this.http.post<StoryboardScene>(`${this.base}/storyboard/episodes/${episodeId}/scenes`, body);
  }

  updateStoryboardScene(episodeId: string, sceneId: string, body: Partial<StoryboardScene>):
      Observable<StoryboardScene> {
    return this.http.put<StoryboardScene>(
      `${this.base}/storyboard/episodes/${episodeId}/scenes/${sceneId}`, body);
  }

  deleteStoryboardScene(episodeId: string, sceneId: string): Observable<void> {
    return this.http.delete<void>(`${this.base}/storyboard/episodes/${episodeId}/scenes/${sceneId}`);
  }

  reorderStoryboardScenes(episodeId: string, sceneIds: string[]): Observable<StoryboardScene[]> {
    return this.http.post<StoryboardScene[]>(
      `${this.base}/storyboard/episodes/${episodeId}/scenes/reorder`, { sceneIds });
  }

  uploadSceneImage(episodeId: string, sceneId: string, file: File): Observable<any> {
    const form = new FormData();
    form.append('file', file, file.name);
    return this.http.post(`${this.base}/storyboard/episodes/${episodeId}/scenes/${sceneId}/image`, form);
  }

  /** Bulk upload. Order is the contract: files are matched to scenes in the
   *  order appended here, not by filename. */
  uploadSceneImagesInOrder(episodeId: string, files: File[]): Observable<any[]> {
    const form = new FormData();
    files.forEach(f => form.append('files', f, f.name));
    return this.http.post<any[]>(`${this.base}/storyboard/episodes/${episodeId}/images`, form);
  }

  assembleStoryboard(episodeId: string, voice?: string): Observable<{ status: string; fileName: string }> {
    return this.http.post<{ status: string; fileName: string }>(
      `${this.base}/storyboard/episodes/${episodeId}/assemble`, { voice: voice ?? null });
  }

  // --- Video generation (standalone page) -----------------------------

  videoGenerationStatus(): Observable<VideoGenerationStatus> {
    return this.http.get<VideoGenerationStatus>(`${this.base}/video-generation/status`);
  }

  createVideoGenerationJob(
    image: File | null, prompt: string, negativePrompt: string, durationSeconds: number,
    narrationText?: string, voiceProfileId?: string, workflow?: string
  ): Observable<{ jobId: string }> {
    const form = new FormData();
    if (image) { form.append('image', image, image.name); }
    form.append('prompt', prompt);
    if (negativePrompt) { form.append('negativePrompt', negativePrompt); }
    form.append('durationSeconds', String(durationSeconds));
    if (narrationText) { form.append('narrationText', narrationText); }
    if (voiceProfileId) { form.append('voiceProfileId', voiceProfileId); }
    if (workflow) { form.append('workflow', workflow); }
    return this.http.post<{ jobId: string }>(`${this.base}/video-generation/jobs`, form);
  }

  getVideoGenerationJob(jobId: string): Observable<VideoGenerationJob> {
    return this.http.get<VideoGenerationJob>(`${this.base}/video-generation/jobs/${jobId}`);
  }

  // --- Scene sequence ------------------------------------------------

  sequenceStatus(): Observable<SequenceStatusInfo> {
    return this.http.get<SequenceStatusInfo>(`${this.base}/video-sequences/status`);
  }
  listSequences(): Observable<SequenceView[]> {
    return this.http.get<SequenceView[]>(`${this.base}/video-sequences`);
  }
  createSequence(req: CreateSequenceRequest): Observable<SequenceView> {
    return this.http.post<SequenceView>(`${this.base}/video-sequences`, req);
  }
  getSequence(id: string): Observable<SequenceView> {
    return this.http.get<SequenceView>(`${this.base}/video-sequences/${id}`);
  }
  deleteSequence(id: string): Observable<void> {
    return this.http.delete<void>(`${this.base}/video-sequences/${id}`);
  }
  startSequenceVideos(id: string): Observable<unknown> {
    return this.http.post(`${this.base}/video-sequences/${id}/start-videos`, {});
  }
  mergeSequence(id: string): Observable<unknown> {
    return this.http.post(`${this.base}/video-sequences/${id}/merge`, {});
  }
  cancelSequence(id: string): Observable<unknown> {
    return this.http.post(`${this.base}/video-sequences/${id}/cancel`, {});
  }
  updateSequenceScene(id: string, index: number, visual: string, motion: string): Observable<void> {
    return this.http.put<void>(`${this.base}/video-sequences/${id}/scenes/${index}`, { visual, motion });
  }
  regenerateSequenceKeyframe(id: string, index: number): Observable<unknown> {
    return this.http.post(`${this.base}/video-sequences/${id}/scenes/${index}/regenerate-keyframe`, {});
  }
  regenerateSequenceVideo(id: string, index: number): Observable<unknown> {
    return this.http.post(`${this.base}/video-sequences/${id}/scenes/${index}/regenerate-video`, {});
  }
  uploadSequenceKeyframe(id: string, index: number, image: File): Observable<void> {
    const form = new FormData();
    form.append('image', image, image.name);
    return this.http.post<void>(`${this.base}/video-sequences/${id}/scenes/${index}/keyframe`, form);
  }
  sequenceKeyframeUrl(id: string, index: number, stamp: number): string {
    return `${this.base}/video-sequences/${id}/scenes/${index}/keyframe?t=${stamp}`;
  }
  sequenceClipUrl(id: string, index: number, stamp: number): string {
    return `${this.base}/video-sequences/${id}/scenes/${index}/video?t=${stamp}`;
  }
  sequenceMergedUrl(id: string, stamp: number): string {
    return `${this.base}/video-sequences/${id}/video?t=${stamp}`;
  }

  videoGenerationResultUrl(jobId: string): string {
    return `${this.base}/video-generation/jobs/${jobId}/video`;
  }

  /** Fetches a generated scene image as a Blob, so the Video generation page
   *  can hand it to createVideoGenerationJob() as if the user had picked it
   *  from disk - same upload path either way, no separate "use existing
   *  asset" code path on the backend needed. */
  fetchImageBlob(url: string): Observable<Blob> {
    return this.http.get(url, { responseType: 'blob' });
  }

  // --- Voice Library (Phase 1) -----------------------------------------

  listVoiceProfiles(): Observable<VoiceProfile[]> {
    return this.http.get<VoiceProfile[]>(`${this.base}/voice-profiles`);
  }

  validateVoiceAudio(audio: Blob, filename: string): Observable<VoiceValidationResult> {
    const form = new FormData();
    form.append('audio', audio, filename);
    return this.http.post<VoiceValidationResult>(`${this.base}/voice-profiles/validate`, form);
  }

  createClonedVoiceProfile(
    name: string, language: string, provider: string, personality: string, audio: Blob, filename: string, referenceTranscript = ''
  ): Observable<VoiceProfile> {
    const form = new FormData();
    form.append('name', name);
    if (language) { form.append('language', language); }
    form.append('provider', provider);
    if (personality) { form.append('personality', personality); }
    if (referenceTranscript) { form.append('referenceTranscript', referenceTranscript); }
    form.append('audio', audio, filename);
    return this.http.post<VoiceProfile>(`${this.base}/voice-profiles/cloned`, form);
  }

  /** warning is set when the audio is actually MockTTSProvider's silent
   *  placeholder (the assigned voice provider failed and fell back) - see
   *  VoiceProfileController's X-Tts-Warning header. Without this, a failed
   *  ChatterBox/CosyVoice call plays back as genuine silence with zero
   *  indication why. */
  testVoiceProfile(id: string, text: string): Observable<{ blob: Blob; warning: string | null }> {
    return this.http.post(`${this.base}/voice-profiles/${id}/test`, { text },
      { responseType: 'blob', observe: 'response' }
    ).pipe(map(res => ({ blob: res.body as Blob, warning: res.headers.get('X-Tts-Warning') })));
  }

  getVoiceReferenceAudio(id: string): Observable<Blob> {
    return this.http.get(`${this.base}/voice-profiles/${id}/reference-audio`, { responseType: 'blob' });
  }

  renameVoiceProfile(id: string, name: string): Observable<VoiceProfile> {
    return this.http.put<VoiceProfile>(`${this.base}/voice-profiles/${id}/rename`, { name });
  }

  deleteVoiceProfile(id: string): Observable<void> {
    return this.http.delete<void>(`${this.base}/voice-profiles/${id}`);
  }

  assignCharacterVoice(characterId: string, voiceProfileId: string | null): Observable<Character> {
    return this.http.put<Character>(`${this.base}/characters/${characterId}/voice`, { voiceProfileId: voiceProfileId || '' });
  }

  characterReferenceSheetPrompt(universeId: string, storyTitle?: string): Observable<{ prompt: string }> {
    const params: Record<string, string> = { universeId };
    if (storyTitle) { params['storyTitle'] = storyTitle; }
    return this.http.get<{ prompt: string }>(`${this.base}/characters/reference-sheet-prompt`, { params });
  }
}

export interface Voice {
  id: string;
  label: string;
  accent: string;
  gender: string;
  quality: string;
  notes: string;
  installed: boolean;
  /** 'piper' | 'edge' | 'indicf5' - which engine actually speaks this voice. */
  engine?: string;
  isDefault: boolean;
}

// ---- Storyboard (user-supplied images) ------------------------------------

export interface StoryboardEpisode {
  id: string;
  title: string;
  status: string;
  language: string | null;
  visualStyle: string | null;
  durationTargetSec: number | null;
}

export interface StoryboardScene {
  id: string;
  sceneNumber: number;
  narration: string | null;
  action: string | null;
  location: string | null;
  emotion: string | null;
  imageDurationSeconds: number | null;
  narrationSeconds: number | null;
  hasImage: boolean;
  voiceSegments: VoiceSegment[];
  characterNames: string[];
}

export interface VoiceSegment {
  character: string;
  text: string;
  voice: string;
  speed: number;
  pitch: number;
  emotion: string;
  pauseBeforeMs: number;
  pauseAfterMs: number;
}

// ---- AI Video Editor ------------------------------------------------------

export interface VideoEditorCapabilities {
  maxClips: number;
  maxUploadMb: number;
  allowedVideoExtensions: string[];
  allowedAudioExtensions: string[];
  previewResolution: number;
  defaultResolution: number;
  categories: string[];
  styles: string[];
  intensities: string[];
  aspectRatios: { id: string; width: number; height: number }[];
  /**
   * Which backend stages exist in this build. The UI disables controls whose
   * stage is false rather than offering a button that returns 501 - a control
   * that fails when pressed is worse than one that says why it is unavailable.
   */
  implemented: {
    upload: boolean;
    analysis: boolean;
    planning: boolean;
    preview: boolean;
    render: boolean;
    captions: boolean;
    music: boolean;
  };
}

export interface VideoEditorProject {
  id: string;
  name: string;
  category: string;
  editingStyle: string;
  intensity: string;
  aspectRatio: string;
  targetDurationSec: number | null;
  customInstructions: string | null;
  state: string;
  errorMessage: string | null;
  smartCuts: boolean;
  beatSync: boolean;
  smartTransitions: boolean;
  autoCaptions: boolean;
  audioEnhancement: boolean;
  smartReframing: boolean;
}

export interface VideoClip {
  id: string;
  displayName: string;
  sortOrder: number;
  sizeBytes: number | null;
  durationSec: number | null;
  width: number | null;
  height: number | null;
  fps: number | null;
  videoCodec: string | null;
  audioCodec: string | null;
  hasAudio: boolean;
  analyzed: boolean;
  portrait: boolean;
}

export interface VideoTimelineClip {
  id: string;
  clipId: string;
  sortOrder: number;
  sourceStartSec: number;
  sourceEndSec: number;
  techniqueIn: string | null;
  techniqueOut: string | null;
  transitionSec: number | null;
  speed: number;
  volume: number;
  muted: boolean;
  locked: boolean;
  reason: string | null;
  outputDurationSec: number;
}

export interface VideoEditorVersion {
  id: string;
  planner: string;
  rationale: string | null;
  shotCount: number;
  createdAt: string;
}

export interface VideoJobStart {
  jobId: string;
  status?: string;
  kind?: string;
}

export interface VideoRenderJob {
  id: string;
  projectId: string;
  kind: string;
  status: string;
  progressPercent: number;
  stage: string | null;
  errorMessage: string | null;
}

export interface VideoEditorProjectDetail {
  project: VideoEditorProject;
  clips: VideoClip[];
  timeline: VideoTimelineClip[];
  rationale: string | null;
}

export interface VoiceListResponse {
  voices: Voice[];
  defaultVoice: string;
}

// --- Video generation (standalone page) ------------------------------------

export interface VideoGenerationStatus {
  available: boolean;
  reason: string | null;
  defaultWidth: number;
  defaultHeight: number;
  maxDurationSeconds: number;
  /** Per-engine longest clip (seconds). Optional: older backends only send maxDurationSeconds. */
  wanMaxSeconds?: number;
  wan14bMaxSeconds?: number;
  h3MaxSeconds?: number;
}

// --- Scene sequence (multi-scene, consistent characters, merged long video) ----

export type SequenceStatus = 'KEYFRAMES_RUNNING' | 'AWAITING_APPROVAL' | 'VIDEOS_RUNNING' | 'MERGING'
  | 'COMPLETED' | 'PARTIAL' | 'FAILED' | 'CANCELLED';
export type SceneStep = 'PENDING' | 'KEYFRAME_RUNNING' | 'KEYFRAME_READY' | 'VIDEO_RUNNING' | 'DONE' | 'FAILED';

export interface SequenceStatusInfo {
  available: boolean;
  reason: string | null;
  wanMaxSeconds: number;
  wan14bMaxSeconds: number;
  h3MaxSeconds: number;
  maxScenes: number;
}

export interface SequenceSceneView {
  index: number;
  visual: string;
  motion: string;
  step: SceneStep;
  error: string | null;
  hasKeyframe: boolean;
  hasClip: boolean;
  uploadedKeyframe: boolean;
  clipSeconds: number | null;
  requestedSeconds: number | null;
  videoMillis: number | null;
  keyframeStamp: number;
  clipStamp: number;
}

export interface SequenceView {
  id: string;
  title: string;
  style: string;
  characterIds: string[];
  engine: string;
  secondsPerScene: number;
  orientation: string;
  crossfadeSeconds: number;
  continuity: string;
  reviewKeyframes: boolean;
  status: SequenceStatus;
  error: string | null;
  busy: boolean;
  hasMerged: boolean;
  mergedSeconds: number | null;
  mergedStamp: number;
  doneScenes: number;
  totalScenes: number;
  createdAt: string;
  scenes: SequenceSceneView[];
}

export interface CreateSequenceRequest {
  title: string;
  style: string;
  characterIds: string[];
  engine: string;
  secondsPerScene: number;
  orientation: string;
  crossfadeSeconds: number;
  continuity: string;
  reviewKeyframes: boolean;
  scenes: { visual: string; motion: string }[];
}

export type VideoGenJobStatus = 'QUEUED' | 'RUNNING' | 'SUCCEEDED' | 'FAILED';

export interface VideoGenerationJob {
  id: string;
  status: VideoGenJobStatus;
  errorMessage: string | null;
  seedUsed: number | null;
  workflowUsed: string | null;
}
