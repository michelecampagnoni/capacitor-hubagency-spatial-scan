# v0.4-ui-round1 — Release Notes
**Date:** 2026-03-31
**Branch:** capture-stable
**Smoke test:** PASSED

## Cosa c'è in questa build
Revert completo dell'architettura anchor/local-space introdotta durante UI round 2.
Ritorno alla baseline world-space (v0.3-pre-ui-baseline) con tutti i fix di stabilità mantenuti.

## Motore GPC
- handlePerimeterTap: lastReticleWorld (floor) / lastReticleWorldFree (height) — baseline originale
- livePreview: diretto da perimeterCapture, nessuna trasformazione
- Renderer: perimeterCapture.getPolygon() world-space

## Fix mantenuti (non revertiti)
- onPause order (GL prima di session.pause)
- cancelScanAndFinish via queueEvent (SIGSEGV fix)
- isTouchOnVisibleButton guard
- screenToWorld forceFloor=false 2m fallback
- Floor grid disabilitata

## Files
- `HubAgency-v0.4-ui-round1-debug.apk` — installabile via adb
- `source-v0.4-ui-round1.zip` — sorgente capacitor-plugin
- `release-notes.md` — questo file
