# Recap M1 + M2 — iOS Skeleton + Core Logic
**Data:** 2026-05-04  
**Milestone:** M1 (Scheletro Capacitor iOS) + M2 (Logic core Swift)  
**Stato:** File creati — in attesa di compilazione su Mac con Xcode 15+

---

## File creati

### M1 — Scheletro Capacitor iOS
| File | Note |
|------|------|
| `capacitor-plugin/ios/Plugin/SpatialScanPlugin.swift` | Bridge Capacitor, tutti i metodi pubblici, `CAPBridgedPlugin` |
| `capacitor-plugin/ios/Plugin/SpatialScanPlugin.m` | Bridge Objective-C obbligatorio, `CAP_PLUGIN` macro |
| `capacitor-plugin/ios/Plugin/SpatialScanManager.swift` | `ARSession`, `checkSupport()`, `requestCameraPermission()`, stub startScan/stopScan |
| `capacitor-plugin/capacitor-hubagency-spatial-scan.podspec` | Frameworks: ARKit, GLKit, OpenGLES, CoreGraphics, AVFoundation. `ios.deployment_target = '14.0'` |

### M2 — Logic core Swift
| File | Note |
|------|------|
| `ios/Plugin/Models/OpeningModel.swift` | `OpeningKind`, `OpeningModel`, `WallModel`, `RoomModel` — port 1:1 da Kotlin con SIMD3<Float> |
| `ios/Plugin/Models/OpeningMetadata.swift` | `ConnectionStatus`, `OpeningMetadata` — Codable |
| `ios/Plugin/Models/LinkedOpeningSpec.swift` | Serializzazione via Dictionary (no Intent Android) |
| `ios/Plugin/Models/RoomRecord.swift` | Codable, identico a Kotlin |
| `ios/Plugin/Models/RoomExportData.swift` | `ExportWall`, `ExportOpening`, `RoomExportData`, shoelace area |
| `ios/Plugin/FloorPlaneAnchor.swift` | **Invariante assoluta** — 30 campioni, varianza < 0.0005, EMA 0.998/0.002 |
| `ios/Plugin/PerimeterCapture.swift` | **Invariante assoluta** — state machine IDLE/CAPTURING/CLOSED, phase logic, undo, snapFull, axisDirections |
| `ios/Plugin/RoomRectifier.swift` | **Invariante assoluta** — SNAP_HARD_DEG=5°, MIN_EDGE_M=0.20m, 8 candidati 45°, distribuzione errore chiusura |

---

## Decisioni prese

- `FloatArray` Kotlin → `SIMD3<Float>` Swift ovunque (allineato con simd usato in ARKit)
- `Intent` extras Android → `Dictionary<String,Any>` per `LinkedOpeningSpec` (no UIKit dependency nei modelli)
- `JSONObject` Kotlin → `Codable` Swift per `RoomRecord` e `OpeningMetadata`
- `SpatialScanPlugin.m` mantiene lo stesso set di metodi del Kotlin per parità API
- `startScan` in `SpatialScanManager` è uno stub: la presentazione di `ScanningViewController` viene implementata in M3

---

## Da fare su Mac con Xcode prima di M3

1. `pod lib lint capacitor-hubagency-spatial-scan.podspec` — deve passare senza errori
2. Aggiungere `NSCameraUsageDescription` in `capacitor-test-app/ios/App/App/Info.plist`
3. Compilare con Xcode 15 su iPhone fisico
4. Verificare che `isSupported()` ritorni `true` e `requestPermissions()` mostri il dialog camera
5. Golden test per `RoomRectifier`: costruire 3 poligoni di test con coordinate note e verificare output identico al Kotlin entro 0.5mm

---

## Note critiche per M3

- `ScanningViewController` deve essere presentato modalmente da `SpatialScanPlugin.startScan()`
- `SpatialScanManager.didFinishScan(_:)` è il punto di rientro quando la scansione termina
- Ricordare: `ARSCNView` gestisce camera automaticamente — non serve `BackgroundRenderer`
- `GLKView` overlay: `isOpaque = false`, `backgroundColor = .clear`, `preferredFramesPerSecond = 60`
- Tutte le formule `screenToWorldFloorPlane` e `screenToWorldTopPlane` sono documentate in `docs/ios-production-plan.md`
