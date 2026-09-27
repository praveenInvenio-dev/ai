export interface VoiceProfile {
  id: string;
  name: string;
  language?: string;
  provider: string;
  referenceAudioKey?: string;
  voiceName: string;
  personality?: string;
  durationSeconds?: number;
  sampleRate?: number;
  createdAt: string;
  updatedAt: string;
}

export interface VoiceValidationResult {
  ok: boolean;
  error?: string;
  warnings: string[];
  durationSeconds: number;
  sampleRate: number;
}

export interface Project {
  id: string;
  name: string;
  description?: string;
  createdAt: string;
  updatedAt: string;
}

export interface Universe {
  id: string;
  projectId: string;
  name: string;
  description?: string;
  visualStyleJson?: string;
  worldRulesJson?: string;
  colorPaletteJson?: string;
}

export interface Character {
  id: string;
  universeId?: string;
  name: string;
  species?: string;
  age?: string;
  personality?: string;
  canonicalDescription: string;
  negativeConstraints?: string;
  locked: boolean;
  version: number;
  voiceProfileId?: string;
}

export type EpisodeStatus =
  | 'DRAFTING' | 'DRAFT_READY' | 'APPROVED' | 'IN_PRODUCTION'
  | 'PRODUCTION_COMPLETE' | 'FAILED';

export interface ResourceStatus {
  ramUsedMb: number;
  ramTotalMb: number;
  cpuCores: number;
  gpuAvailable: boolean;
  gpuVramUsedMb?: number;
  gpuVramTotalMb?: number;
  gpuUnavailableReason?: string;
}

export interface Episode {
  id: string;
  projectId: string;
  universeId?: string;
  title?: string;
  userPrompt: string;
  status: EpisodeStatus;
  durationTargetSec?: number;
  targetAge?: string;
  genre?: string;
  tone?: string;
  visualStyle?: string;
  language?: string;
  qualityScore?: number;
  ollamaModel?: string;
  musicPreset?: string;
  musicLocked?: boolean;
}

export interface SceneDto {
  id: string;
  sceneNumber: number;
  purpose?: string;
  narration?: string;
  location?: string;
  action?: string;
  emotion?: string;
  camera?: string;
  lighting?: string;
  imagePrompt?: string;
  negativePrompt?: string;
  motionPrompt?: string;
  motionNegativePrompt?: string;
  visualSpecJson?: string;
  narrationSeconds?: number;
  imageDurationSeconds?: number;
  cameraMovement?: string;
  importance?: string;
  locked?: boolean;
  narrationLocked?: boolean;
  animationMode?: string;
  voiceSegments?: VoiceSegment[];
  characterNames?: string[];
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
  emotionIntensity?: number;
  delivery?: string;
  emphasis?: string[];
  breath?: boolean;
  paralinguisticEvent?: string;
  actingDirection?: string;
}

export interface StoryDraftResponse {
  episodeId: string;
  title: string;
  logline: string;
  fullNarration: string;
  qualityScore: number;
  qualityFeedback: string[];
  scenes: SceneDto[];
  estimatedDurationSeconds: number;
}

export interface CreateStoryRequest {
  projectId: string;
  universeId?: string;
  prompt: string;
  durationSeconds?: number;
  targetAge?: string;
  genre?: string;
  tone?: string;
  visualStyle?: string;
  language?: string;
  characterIds?: string[];
  ollamaModel?: string;
  qualityProfile?: string;
}

export interface CharacterReference {
  id: string;
  characterId: string;
  imagePath: string;
  imageHash?: string;
  source: string;
  primary: boolean;
  createdAt: string;
}

export interface OllamaModelsResponse {
  healthy: boolean;
  defaultModel: string;
  models: string[];
}

export type JobStatus =
  | 'QUEUED' | 'ANALYZING' | 'DRAFT_READY' | 'WAITING_FOR_APPROVAL' | 'APPROVED'
  | 'GENERATING_SCENES' | 'GENERATING_IMAGES' | 'VALIDATING_IMAGES' | 'GENERATING_AUDIO'
  | 'ASSEMBLING_VIDEO' | 'GENERATING_THUMBNAIL' | 'GENERATING_SHORTS' | 'QUALITY_CHECK'
  | 'COMPLETED' | 'FAILED' | 'CANCELLED';

export interface JobStep {
  stepName: string;
  status: string;
  retryCount: number;
  errorMessage?: string;
  warningMessage?: string;
  durationMs?: number;
}

export interface JobStatusResponse {
  jobId: string;
  episodeId: string;
  status: JobStatus;
  progressPercent: number;
  errorMessage?: string;
  steps: JobStep[];
}
