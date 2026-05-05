# Piano di Produzione iOS — HubAgency Spatial Scan Plugin
**Data:** 2026-05-04  
**Versione Android stabile:** v1.26  
**Obiettivo:** port dell'algoritmo GPC in Swift, produzione plugin Capacitor iOS, senza toccare Android.

---

## Regole operative

1. **Non modificare Android.** Nessuna modifica a `android/`, alle classi Kotlin, o alle risorse Android esistenti.
2. **Non modificare l'API TypeScript pubblica.** Le firme di `startScan()`, `stopScan()`, `exportPdf()`, `isSupported()`, `requestPermissions()` rimangono identiche.
3. **Modifiche a package/config solo se necessarie per registrare iOS**, in commit separato con review esplicita.
4. **Port 1:1 della logica matematica e dello stato.** Adattamento nativo per lifecycle, UI, storage e rendering:
   - `Activity` → `UIViewController`
   - `GLSurfaceView` → `GLKView`
   - `Handler/Looper` → `DispatchQueue`
   - `SharedPreferences` → `UserDefaults`
   - `Canvas/Paint` → `CoreGraphics`
   - `ARCore Session/Frame` → `ARKit ARSessionDelegate/ARFrame`
5. **Test golden Android → Swift** per ogni classe core: raccogliere 3–5 casi reali di input/output da Android e verificare che Swift produca output numericamente identico.
6. **Test su device fisico** (iPhone, iOS 14+) dopo ogni milestone prima di procedere.
7. **Un recap per milestone** salvato in `recap/` al completamento.

---

## M1 — Scheletro Capacitor iOS + Permissions (2–3h)

**Obiettivo:** plugin compilabile, registrato in Capacitor, `isSupported()` e `requestPermissions()` funzionanti su iPhone.

### Deliverable
- `capacitor-plugin/ios/Plugin/SpatialScanPlugin.swift` — bridge Capacitor (`@objc` methods)
- `capacitor-plugin/ios/Plugin/SpatialScanPlugin.m` — bridge Objective-C (obbligatorio per Capacitor)
- `capacitor-plugin/ios/Plugin/SpatialScanManager.swift` — `ARSession`, `isSupported()`, `requestCameraPermission()`
- `capacitor-plugin/capacitor-hubagency-spatial-scan.podspec` — dipendenze: ARKit, GLKit
- `capacitor-test-app/ios/App/App/Info.plist` — aggiungere `NSCameraUsageDescription`

### Verifica
- `pod lib lint` passa senza errori
- App compila su Xcode su iPhone fisico
- `isSupported()` ritorna `true`
- `requestPermissions()` mostra il dialog camera di sistema
- Camera permission flow completo (granted / denied)

---

## M2 — Logic core in Swift (4–6h)

**Obiettivo:** tutto il codice platform-agnostic portato in Swift, verificato con golden test numerici da Android.

### Deliverable

**Models (port 1:1 da Kotlin):**
- `Models/OpeningModel.swift`
- `Models/RoomModel.swift`
- `Models/RoomExportData.swift`
- `Models/RoomRecord.swift`
- `Models/LinkedOpeningSpec.swift`
- `Models/OpeningMetadata.swift`

**Logic (invarianti assolute — logica identica al Kotlin):**
- `FloorPlaneAnchor.swift` — floor lock: 30 campioni, varianza < 0.5cm², EMA α=0.002, fallback camera.Y − 1.2m
- `PerimeterCapture.swift` — state machine IDLE/CAPTURING/CLOSED, phase logic (AWAIT_FIRST_FLOOR → AWAIT_HEIGHT → AWAIT_SECOND_FLOOR → FLOOR_ONLY), `undo()` phase-aware, `snapFull()`, `axisDirections()`

**Post-processing (verificare valori dal codice Android corrente prima di implementare):**
- `RoomRectifier.swift` — snap SNAP_HARD_DEG=5°, MIN_EDGE_M=0.20m, 8 candidati ogni 45°, asse dominante = muro più lungo, distribuzione errore chiusura lineare

### Golden test obbligatori
Prima di implementare `RoomRectifier.swift`: estrarre dal codice Android 3–5 poligoni reali (coordinate XZ pre/post rectify) e usarli come test case Swift. Output deve essere identico entro 0.5mm.

### Verifica
- Unit test su `PerimeterCapture`: aggiungi punti → chiudi → undo → riapri
- Unit test su `RoomRectifier`: input/output golden da Android
- Nessuna dipendenza ARKit in questo milestone (pura Swift logic)

---

## M3 — ScanningViewController + Rendering (8–12h) ⚠️ critical path

**Obiettivo:** camera AR aperta, reticle tracking, perimetro disegnato in tempo reale, tap per aggiungere punti, auto-switch FLOOR/TOP.

### Deliverable
- `ScanningViewController.swift` — `ARSCNView` (camera) + `GLKView` overlay trasparente (rendering), gesture tap, `ARSessionDelegate`
- `PerimeterRenderer.swift` — GL_LINE_LOOP ciano, live preview punto corrente, goniometro ±65° con isteresi 0.20/0.10 **[invariante assoluta]**
- `OpeningRenderer.swift` — highlight muro hover ciano, fill fucsia semitrasparente, GL_LINE_STRIP (no bottom edge) **[invariante assoluta]**

### Traduzione camera math (invarianti assolute — solo tipi cambiano)
```swift
// screenToWorldFloorPlane
let camPos = frame.camera.transform.columns.3          // = camera.pose.translation
let camFwd = frame.camera.transform * simd_float4(0,0,-1,0)  // = camera.pose.rotateVector([0,0,-1])
let dy = camFwd.y
guard dy < -0.01 else { return nil }
let t = (lastFloorY - camPos.y) / dy
guard t >= 0.05 && t <= 15 else { return nil }
return simd_float3(camPos.x + t * camFwd.x, lastFloorY, camPos.z + t * camFwd.z)

// screenToWorldTopPlane (camera.y positivo = punta verso l'alto)
guard camFwd.y > 0.01 else { return nil }
```

### Rischi tecnici da gestire
1. **GLKView sopra ARSCNView** — verificare: trasparenza (`isOpaque = false`, `backgroundColor = .clear`), depth buffer, orientamento viewport, refresh rate (usa `preferredFramesPerSecond = 60`), sincronizzazione con ARFrame (aggiorna renderer in `session(_:didUpdate:)`)
2. **Coordinate system ARKit** — stare attenti a portrait vs landscape, usare `viewportSize` corretto per `projectionMatrix(for:viewportSize:zNear:zFar:)`, evitare rotazioni spurie
3. **UI programmatica** — tutta la UI di `ScanningActivity` va riportata in `UIKit` programmatico (no storyboard), testare manualmente ogni controllo

### Auto-switch FLOOR/TOP
```swift
// identico ad Android: usa Y della forward vector
let mode: CaptureMode = camFwd.y > 0.12 ? .top : .floor
```

### Verifica
- Scansione completa di una stanza semplice (4 angoli) su iPhone fisico
- Poligono visibile e metricamente coerente rispetto ad Android (stessa stanza)
- Goniometro appare e funziona correttamente

---

## M4 — Export PDF + GLB (4–6h)

**Obiettivo:** `stopScan()` ritorna `floorPlanPath` e `glbPath` su iOS come su Android.

### Deliverable
- `FloorPlanExporter.swift` — CoreGraphics, PNG 1200×1200 + PDF A3 1748×2480 @ 150dpi, linee muri, etichette quote, legenda scala
- `GlbExporter.swift` — GLTF 2.0 binario (.glb), 2 materiali (Wall grigio 0.88, Floor grigio 0.60), normali perpendicolari, ear-clipping poligoni non-convessi, buffer non-interleaved: [positions][normals][indices]
- `RoomDataLoader.swift` — `JSONDecoder` per caricare `RoomRecord`
- `RoomHistoryManager.swift` — `FileManager` per gestione file stanze

### Verifica
- File GLB apribile in viewer 3D (es. model-viewer o Reality Composer)
- PDF A3 con quote leggibili
- Path ritornato da `stopScan()` accessibile dall'app host

---

## M5 — Multi-room Composer (3–4h)

**Obiettivo:** workflow multi-stanza funzionante su iOS come su Android.

### Deliverable
- `CompositionGraph.swift` — persistenza `hub_graph.json` via `FileManager`, BFS bidirezionale, struct `RoomWorldTransform` (roomId, parentId, worldOffsetX/Z, worldRotRad, confirmedAt) — port 1:1 da Kotlin
- `UnlinkedOpeningStore.swift` — port 1:1
- `RoomComposerViewController.swift` — carica componente connessa parent, trasforma in world space, allinea new room su apertura parent, salva world transform nel grafo

### Verifica
- Scan A → Scan B collegata ad A → viewer mostra entrambe allineate
- `hub_graph.json` scritto e riletto correttamente

---

## M6 — Parità, configurazione e produzione (variabile)

**Obiettivo:** parità funzionale confermata, plugin pubblicabile.

### Attività
- Smoke test completo iOS vs Android: stessa stanza fisica, confronto coordinate poligono, area calcolata, export PDF/GLB
- Verifica API TypeScript identica su iOS e Android (stesse chiavi JSON, stessi tipi)
- Aggiornamento `package.json` `capacitor.ios` (commit separato, review)
- Aggiornamento `README.md` con sezione iOS setup
- `pod lib lint` finale pulito

---

## Sequenza

```
M1 ──► M2 ──► M3 ──────────────────────► M4 ──► M5 ──► M6
              ↑
         critical path
         (8–12h, test device fisico obbligatorio prima di M4)
```

**M1 e M2 eseguibili su Mac senza Xcode** (creazione file Swift e logica pura).  
**Da M3 in poi richiede Xcode 15+ e iPhone fisico.**

---

## Prerequisiti (da verificare prima di M1)

- [ ] Xcode 15+ installato sul Mac di sviluppo iOS
- [ ] iPhone fisico iOS 14+ disponibile
- [ ] Apple Developer account attivo
- [ ] Provisioning profile configurato per bundle ID app host
- [ ] CocoaPods installato (`gem install cocoapods`)

---

## Note architetturali

- **GLKit/GLKView sono deprecati** (da iOS 12) ma funzionali. Migration a Metal è post-v2.0.
- **BackgroundRenderer Android eliminato su iOS** — `ARSCNView` gestisce il feed camera automaticamente.
- **Nessuna dipendenza esterna** — solo framework Apple built-in: ARKit, GLKit, OpenGL ES, CoreGraphics, simd.
