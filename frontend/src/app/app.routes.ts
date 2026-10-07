import { Routes } from '@angular/router';

export const routes: Routes = [
  { path: '', redirectTo: 'dashboard', pathMatch: 'full' },
  { path: 'dashboard', loadComponent: () => import('./pages/dashboard/dashboard.component').then(m => m.DashboardComponent) },
  { path: 'create', loadComponent: () => import('./pages/create-story/create-story.component').then(m => m.CreateStoryComponent) },
  { path: 'episodes/:id/approve', loadComponent: () => import('./pages/story-approval/story-approval.component').then(m => m.StoryApprovalComponent) },
  { path: 'episodes/:id/production', loadComponent: () => import('./pages/production-dashboard/production-dashboard.component').then(m => m.ProductionDashboardComponent) },
  { path: 'episodes/:id/scenes', loadComponent: () => import('./pages/scene-editor/scene-editor.component').then(m => m.SceneEditorComponent) },
  // Two nav entries, one component: everything after the opening panel is
  // identical, so `data.mode` picks the entry point rather than duplicating
  // the scene list, upload grid and assemble flow twice.
  { path: 'build-story', data: { mode: 'CUSTOM' },
    loadComponent: () => import('./pages/storyboard/storyboard.component').then(m => m.StoryboardComponent) },
  { path: 'story-images', data: { mode: 'EXISTING' },
    loadComponent: () => import('./pages/storyboard/storyboard.component').then(m => m.StoryboardComponent) },
  // Kept so existing links and the ?episodeId= deep link do not 404.
  { path: 'storyboard', redirectTo: 'build-story', pathMatch: 'full' },
  { path: 'video-editor', loadComponent: () => import('./pages/video-editor/video-editor.component').then(m => m.VideoEditorComponent) },
  { path: 'funny-skits', loadComponent: () => import('./pages/funny-skit/funny-skit.component').then(m => m.FunnySkitComponent) },
  { path: 'video-generation', loadComponent: () => import('./pages/video-generation/video-generation.component').then(m => m.VideoGenerationComponent) },
  { path: 'video-sequence', loadComponent: () => import('./pages/video-sequence/video-sequence.component').then(m => m.VideoSequenceComponent) },
  { path: 'wan-workflow', data: { workflow: 'WAN_2_2' }, loadComponent: () => import('./pages/video-workflow/video-workflow.component').then(m => m.VideoWorkflowComponent) },
  { path: 'minimax-h3-workflow', data: { workflow: 'MINIMAX_H3' }, loadComponent: () => import('./pages/video-workflow/video-workflow.component').then(m => m.VideoWorkflowComponent) },
  { path: 'voice-lab', loadComponent: () => import('./pages/voice-lab/voice-lab.component').then(m => m.VoiceLabComponent) },
  { path: 'characters', loadComponent: () => import('./pages/character-studio/character-studio.component').then(m => m.CharacterStudioComponent) },
  { path: '**', redirectTo: 'dashboard' }
];
