# Recap 2026-04-29 — iOS Port Plan + APK client package
**Data:** 2026-04-29
**Branch:** main
**Versione Android corrente:** v1.26

---

## Sessione

### 1. Pacchetto APK per il cliente
- APK `v1.26-glb-pdf-export-debug.apk` impacchettato in `/Users/michelecampagnoni/Desktop/HubAgency-debug-2026-04-29.zip` (8.7MB)
- Contiene: `HubAgency-debug.apk` + `INSTALL.txt` con istruzioni installazione Android

---

### 2. Audit iOS — analisi preliminare

**Risultato chiave:** ARCore è usato molto meno di quanto atteso. Dopo il floor lock l'intero motore GPC funziona con geometria pura (`screenToWorldFloorPlane/TopPlane`). ARCore serve solo per `camera.pose` (posizione + orientamento) e il floor plane detection iniziale.

**Usi ARCore effettivi:**
- `camera.pose.translation` + `camera.pose.rotateVector()` → usato sempre (cuore GPC)
- `frame.hitTest(px,py)` → solo pre-lock
- `sess.getAllTrackables(Plane)` → solo per floor lock iniziale
- `BackgroundRenderer` (GL_TEXTURE_EXTERNAL_OES) → eliminato su iOS, ARSCNView lo gestisce gratis
- `ArCoreApk.checkAvailability()` → `isSupported()` solo

**File platform-agnostic (port 1:1 in Swift):**
`PerimeterCapture`, `RoomRectifier`, `GlbExporter`, `RoomDataLoader`, `RoomHistoryManager`, `CompositionGraph`, `UnlinkedOpeningStore`, tutti i modelli data.

**File da riscrivere:**
- `BackgroundRenderer` → eliminato (ARSCNView)
- `ScanningActivity` → `ScanningViewController` (UIViewController + ARSCNView + GLKView)
- `SpatialScanPlugin.kt` → `SpatialScanPlugin.swift` + `.m` bridge
- `SpatialScanManager.kt` → `SpatialScanManager.swift` (ARKit session)
- `FloorPlaneAnchor.kt` → `FloorPlaneAnchor.swift` (stessa logica, tipi ARKit)
- `FloorPlanExporter.kt` → `FloorPlanExporter.swift` (Canvas → CoreGraphics)
- `RoomComposerActivity` → `RoomComposerViewController`

---

### 3. Piano di lavoro definitivo

Documento completo: `/Users/michelecampagnoni/Desktop/HubAgencyNative/docs/ios-port-plan.md`

**6 Milestone:**
| # | Contenuto | Complessità |
|---|-----------|-------------|
| M1 | Scheletro Capacitor iOS + `isSupported()` | Bassa |
| M2 | `PerimeterCapture`, `FloorPlaneAnchor`, modelli Swift | Bassa |
| M3 | `ScanningViewController` + GLKView + rendering | **Alta** |
| M4 | Export PDF (CoreGraphics) + GLB (Data) | Media |
| M5 | Multi-room Composer | Media |
| M6 | Test parità device fisico | — |

**Stack rendering scelto:** ARSCNView (camera bg) + GLKView overlay trasparente (GLES 2.0 — stessi shader Android, zero riscrittura). Metal migration = milestone separato post-v2.0.

**Dipendenze iOS:** zero esterne. ARKit, GLKit, OpenGLES, CoreGraphics, UIKit — tutti built-in.

---

### 4. Testing iOS

- **Sviluppo:** Xcode + iPhone fisico via USB → `⌘R` (ARKit non funziona su Simulator)
- **Client:** Xcode → Archive → TestFlight (Apple Developer Program $99/anno)
- **Prerequisiti prima di iniziare M1:** Apple Developer account, Xcode 15+, iPhone fisico iOS 14+, `NSCameraUsageDescription` in Info.plist app host

---

### 5. Invarianti assolute (mai toccare in entrambe le piattaforme)

1. `PerimeterCapture` state machine
2. `screenToWorldFloorPlane` / `screenToWorldTopPlane` — solo tipi diversi, mai la formula
3. `FloorPlaneAnchor` — mediana 30 campioni, varianza < 0.5cm²
4. Goniometro — settore ±65°, isteresi 0.20/0.10
5. `RoomRectifier` — dominantAxis + 8 candidati 45°
6. Buffer smoothing — 8 campioni floor, 4 height

---

## Prossimo step

Iniziare M1: creare `ios/Plugin/` con `SpatialScanPlugin.swift`, `SpatialScanPlugin.m`, podspec.
