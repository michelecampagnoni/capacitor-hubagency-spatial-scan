# Recap M3 — ScanningViewController + Rendering (iOS)
Data: 2026-05-04

## Obiettivo
Port completo di ScanningActivity.kt (Android) → ScanningViewController.swift (iOS, ARKit + GLES 2.0).

## File creati / modificati

### Nuovi file
- `ios/Plugin/PerimeterRenderer.swift` — Port 1:1 di PerimeterRenderer.kt in GLES 2.0 iOS.
  - Palette colori identica a Android (cyan, fucsia, violet).
  - Tutti i layer: wall fills, wall grid, height preview, segmenti confermati, close hint, live segment, vertical edges, ceiling traces (TOP mode), top cursor, goniometro (±65°), ghost corner, vertex dots, wall skeleton.
  - Goniometro: SECTOR_RAD = DEG1_RAD * 65 (invariante). Spoke a 10°, tick a 1°/5°.
  - Helper: `prim(_ mode: GLenum, _ verts: [Float], _ count: GLsizei)` con `withUnsafeBufferPointer`.
  - Helper: `matToArray(_ m: simd_float4x4) -> [Float]` per `glUniformMatrix4fv`.

- `ios/Plugin/OpeningRenderer.swift` — Port 1:1 di OpeningRenderer.kt in GLES 2.0 iOS.
  - Wall highlight hover (cyan) e selected (fucsia/violet).
  - Outline a 3 lati (bordo a terra omesso, come v1.26 Android).
  - Opening box: GL_TRIANGLE_FAN fill + GL_LINE_LOOP border + polygon offset z-fighting.
  - Tick centrale al bordo inferiore.
  - Colori: door (arancione), window (blu), frenchDoor (viola), border (bianco).

- `ios/Plugin/ScanningViewController.swift` (~1340+ righe)
  - ARSCNView (camera background, ARKit) + EAGLContext/GLKView trasparente sopra (GLES 2.0).
  - Integrazione completa macchina a stati GPC (PerimeterCapture, FloorPlaneAnchor).
  - Formula screenToWorldFloorPlane: invariante assoluta (ray-plane intersection con floor Y).
  - Formula screenToWorldTopPlane: invariante assoluta (ray-plane intersection con wallTopY).
  - Goniometro snap: ±65°, passo 1°, isteresi 0.20/0.10.
  - Smoothing: 8 campioni floor XZ, 4 campioni height.
  - Auto-switch TOP/FLOOR: camFwd.y > 0.20 attiva TOP, < 0.10 disattiva.
  - Opening mode: spawnOpening, showOpeningEditPanel, nudge, adjustWidth/Height/Bottom, confirm, delete.
  - doStopScan(): salva hub_room_{id}.json + aggiorna hub_rooms.json (RoomRecord list).
  - Risultato: { success, roomId, area, wallCount, floorPlanPath: "", glbPath: "" }.
  - UI UIKit programmatica completa: guidance labels, height banner, distance label, stats row, action button, height control (↓/↑), opening phase bar, edit panel con stepper, main button row.
  - Orientamento solo portrait.
  - Callback: onScanFinished, onScanCancelled, weak var manager.
  - Metodo pubblico `triggerStop()` per stop esterno da SpatialScanManager.

### File modificati
- `ios/Plugin/SpatialScanManager.swift`
  - Aggiunto `import UIKit`.
  - Aggiunta proprietà `private weak var scanningVC: ScanningViewController?`.
  - `startScan()`: ora presenta ScanningViewController modalmente (fullScreen) via `UIApplication.shared.topViewController()`.
  - Wira `onScanFinished` e `onScanCancelled` callbacks verso il manager.
  - `requestStop()`: chiama `vc.triggerStop()` se VC è presente.
  - `cancelScan()`: fa dismiss del VC se presente.
  - Aggiunta extension privata `UIApplication.topViewController()` che usa `connectedScenes`.

## Invarianti rispettate
- SNAP_HARD_DEG=5°, MIN_EDGE_M=0.20m (RoomRectifier, non modificato).
- Goniometro ±65°, isteresi 0.20/0.10.
- Smoothing: 8 floor, 4 height.
- screenToWorldFloorPlane: guard dy < -0.01, t in [0.05, 15].
- screenToWorldTopPlane: guard dy > 0.01, t in [0.05, 15].
- Auto-switch TOP: soglia 0.20 ON / 0.10 OFF.
- EMA floor α=0.002 (FloorPlaneAnchor, non modificato).
- Varianza floor < 0.0005 per lock (FloorPlaneAnchor, non modificato).

## Stato
M3 completo. Pronto per test su iPhone fisico (ARKit richiede device reale).

## Prossimo step: M4
- FloorPlanExporter.swift (CoreGraphics PNG + PDF A3)
- GlbExporter.swift (GLTF 2.0 binary)
- RoomDataLoader.swift
- RoomHistoryManager.swift
