# Changelog — capacitor-hubagency-spatial-scan

## [1.27.0] — 2026-05-08

### Fixed
- **Multi-room PNG regression (Android):** `RoomComposerActivity.confirmSave()` non aggiornava
  `ScanningActivity.pendingResult` con il path del PNG/GLB combinato. Il plugin leggeva ancora
  il path della singola stanza. Fix: `pendingResult` aggiornato prima di invocare `onScanComplete`.
- **Multi-room event fired too early (Android):** `ScanningActivity.doStopScan()` chiamava
  `onScanComplete` in cima, prima di decidere se aprire il Composer. Il listener JS si
  autodistruggeva sul primo evento e non riceveva quello combined. Fix: `onScanComplete` spostato
  nei soli branch "no-Composer".
- **GLB floor invisible (Android + iOS):** `GlbExporter.earWindingCorrect()` produceva triangoli
  con normale -Y invece di +Y. La condizione `cross >= 0` era invertita: cross >= 0 implica
  componente Y del prodotto vettoriale <= 0 (back-face). Fix: condizione corretta a `cross < 0`.
  Floor material impostato `doubleSided:true` come salvaguardia.

---

## [1.26.0] — 2026-04-29

### Added
- GLB 3D export con ear-clipping triangulation per il pavimento
- PDF A3 export in background
- iOS port completo: M1–M6 (ARKit scanning, Composer multi-room, export PNG/GLB)
- Opening UI: porte, finestre, portefinestre con dimensioni configurabili

---

## [1.25.0] — 2026-04-28

### Added
- `CompositionGraph`: grafo persistente delle connessioni tra stanze
- Trasformazioni world-space per multi-room composition
- Anti-overlap automatico nel Composer

---

## [1.24.0] — 2026-04-28

### Added
- Multi-room workflow completo: `RoomComposerActivity`, allineamento aperture, drag&drop
- `RoomHistoryManager`: persistenza locale stanze
- `LinkedOpeningSpec`: collegamento bidirezionale aperture

---

## [1.10.0] — 2026-04-20 (baseline stabile pre-iOS)

### Added
- ARCore scanning fullscreen con `ScanningActivity`
- Floor plan PNG export con `FloorPlanExporter`
- Room history con `RoomHistoryManager`
- Goniometro top-view, reticolo, wall merger
- Tutti i milestones Android 0.4 → 1.10
