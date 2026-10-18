# Increment 15 — Angular strict-template build fix

## Why the previous Docker build failed
Angular strict template checking rejected a repeated `status.h3MaxSeconds` access in the Story-to-Video warning condition because the property may be undefined.

## Change
Moved that boolean condition into `VideoGenerationComponent.showStoryProviderWarning`. The getter snapshots `this.status` in a local constant and uses a null check before comparing `h3MaxSeconds`, allowing TypeScript control-flow narrowing to work correctly. The template now uses `*ngIf="showStoryProviderWarning"`.

This preserves the previous behavior: show the warning if the provider is unavailable, or if a defined H3 maximum duration is zero/negative. An undefined maximum alone does not trigger the second clause.

## Validation
The backend Maven build succeeded in the user's supplied log. The Angular production build failed only on this template type error; the `NG8102` messages in Concept Explainer were warnings. This patch was reviewed against that error, but a full Angular/Docker build was not run in this environment because the Docker CLI and frontend `node_modules` are unavailable here.

## Verify on server
```bash
docker compose -f docker-compose.yml -f docker-compose.gpu.yml build frontend
# If successful:
docker compose -f docker-compose.yml -f docker-compose.gpu.yml up -d
```
