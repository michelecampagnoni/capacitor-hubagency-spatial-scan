# Piano di Sviluppo — iOS Port
## capacitor-hubagency-spatial-scan v2.0 (Android + iOS)

**Data stesura:** 2026-04-29
**Versione Android di riferimento:** v1.26
**Obiettivo:** implementare il layer iOS del plugin Capacitor usando ARKit al posto di ARCore, mantenendo UX/UI e API TypeScript identiche, senza alcuna regressione Android.

---

## Risposta: come si testa su iOS?

### Durante lo sviluppo
```
Mac (Xcode) → USB → iPhone fisico → Run (⌘R)
```
- Xcode compila il Capacitor plugin + la test app e la installa direttamente sul device
- **ARKit non funziona sul Simulator** (nessuna fotocamera reale) — serve sempre un iPhone fisico
- Requisito: Apple Developer account (anche free tier per device personali, ma serve il paid per TestFlight)

### Per test su device del cliente / QA
```
Xcode → Product → Archive → Distribute App → TestFlight (Internal/External)
```
- TestFlight è gratuito incluso nell'Apple Developer Program ($99/anno)
- Il cliente installa l'app TestFlight dall'App Store e riceve l'APK-equivalente via invito email
- Non serve un Mac al cliente — solo il link di invito
- External Testing richiede review Apple (~24h); Internal Testing (max 100 persone team) è immediato

### Confronto con flusso Android attuale
| | Android | iOS |
|--|---------|-----|
| Dev test | `adb install -r app-debug.apk` | Xcode ⌘R su device collegato |
| Client test | `.zip` con APK + INSTALL.txt | TestFlight link via email |
| Requisiti | ADB + Android Studio | Xcode (solo Mac) + Apple Dev account |

---

## Principio guida: minime modifiche, massima parità

**Regola d'oro:** qualsiasi file che non contiene import AR platform-specific viene portato in Swift con modifica minima alla logica. Solo la "superficie di contatto" con il framework AR e il layer UI viene riscritto.

**L'algoritmo GPC (Guided Perimeter Capture) NON cambia.** La fisica del pavimento, lo snap goniometrico, i buffer di smoothing, la state machine — tutto identico. Solo i tipi cambiano (`FloatArray` → `simd_float3`, `camera.pose.translation` → `frame.camera.transform.columns.3`).

---

## Architettura target

```
TypeScript (definitions.ts) ← invariato
       │
       ▼
Capacitor Bridge
   ├── Android: SpatialScanPlugin.kt (invariato)
   └── iOS:     SpatialScanPlugin.swift + SpatialScanPlugin.m (NUOVO)
                    │
                    ▼
             ScanningViewController.swift  ←  UIViewController + ARSCNView
                    │
             ┌──────┴─────────────────────────────────────┐
             │ ARKit layer          │ Logic layer (shared) │
             │ ARSession            │ PerimeterCapture     │
             │ ARFrame              │ FloorPlaneAnchor     │
             │ ARPlaneAnchor        │ RoomRectifier        │
             │ ARCamera             │ GlbExporter          │
             │ camera.transform     │ FloorPlanExporter    │
             └──────────────────────┴──────────────────────┘
                    │
             Rendering layer
             ARSCNView (camera bg) + GLKView overlay (GLES 2.0)
             PerimeterRenderer (port GLES) + OpeningRenderer (port GLES)
```

### Scelta stack rendering: GLKView + OpenGL ES 2.0

**Motivazione:** i shader di `PerimeterRenderer` e `OpeningRenderer` sono GLES 2.0 standard, senza estensioni Android-specifiche (solo `GL_TEXTURE_EXTERNAL_OES` è in `BackgroundRenderer`, che viene eliminato). Su iOS si usa `GLKView` (deprecato ma funzionale su tutti i device target iOS 14+). Zero riscrittura shader, zero regressioni visive.

**Stack:**
```
ARSCNView (fullscreen, gestisce camera feed ARKit automaticamente)
    └── GLKView overlay trasparente (stesso GLSurface Android)
            └── PerimeterRenderer + OpeningRenderer (GLES 2.0 portati)
    └── UIView overlay (pulsanti, guidance, reticle — UIKit)
```

Migration Metal è un milestone separato post-v2.0 se necessario.

---

## Struttura file iOS

```
capacitor-plugin/
├── ios/
│   ├── Plugin/
│   │   ├── SpatialScanPlugin.swift          # M1: Capacitor bridge
│   │   ├── SpatialScanPlugin.m              # M1: @objc bridge (obbligatorio Capacitor)
│   │   ├── SpatialScanManager.swift         # M1: ARKit session + isSupported
│   │   ├── FrameUpdateData.swift            # M1: struct dati (≈ Kotlin data class)
│   │   ├── FloorPlaneAnchor.swift           # M2: ARKit floor detection
│   │   ├── PerimeterCapture.swift           # M2: port 1:1 da Kotlin
│   │   ├── ScanningViewController.swift     # M3: UIViewController principale
│   │   ├── PerimeterRenderer.swift          # M3: GLES 2.0 port
│   │   ├── OpeningRenderer.swift            # M3: GLES 2.0 port
│   │   ├── RoomRectifier.swift              # M4: port 1:1
│   │   ├── FloorPlanExporter.swift          # M4: CoreGraphics
│   │   ├── GlbExporter.swift               # M4: Data + UInt8
│   │   ├── RoomDataLoader.swift             # M4: JSONDecoder
│   │   ├── RoomHistoryManager.swift         # M4: FileManager
│   │   ├── RoomComposerViewController.swift # M5: UIViewController
│   │   ├── CompositionGraph.swift           # M5: port 1:1
│   │   ├── UnlinkedOpeningStore.swift       # M5: port 1:1
│   │   └── Models/
│   │       ├── OpeningModel.swift           # M2: struct
│   │       ├── RoomModel.swift              # M2: struct
│   │       ├── RoomExportData.swift         # M2: struct
│   │       ├── RoomRecord.swift             # M4: struct
│   │       ├── LinkedOpeningSpec.swift      # M5: struct
│   │       └── OpeningMetadata.swift        # M5: struct
│   └── capacitor-hubagency-spatial-scan.podspec
├── android/ (invariato)
└── src/ (invariato — TypeScript)
```

---

## ARCore → ARKit: tabella di traduzione completa

### Session e configurazione

| ARCore (Kotlin) | ARKit (Swift) |
|----------------|---------------|
| `Session(context)` | `ARSession()` |
| `Config(session)` | `ARWorldTrackingConfiguration()` |
| `config.planeFindingMode = HORIZONTAL_AND_VERTICAL` | `config.planeDetection = [.horizontal, .vertical]` |
| `config.depthMode = AUTOMATIC` | `config.frameSemantics = .sceneDepth` |
| `config.focusMode = AUTO` | n/a (ARKit gestisce AF automaticamente) |
| `session.configure(config)` | `session.run(config)` |
| `session.resume()` | `session.run(config)` (stesso) |
| `session.pause()` | `session.pause()` |
| `session.close()` | `session.pause()` + deinit |
| `session.update() → Frame` | `ARSessionDelegate.session(_:didUpdate frame:)` |
| `ArCoreApk.checkAvailability()` | `ARWorldTrackingConfiguration.isSupported` (Bool) |

### Camera pose (cuore del motore GPC)

| ARCore (Kotlin) | ARKit (Swift) |
|----------------|---------------|
| `camera.pose.translation` → `FloatArray(3)` | `frame.camera.transform.columns.3` → `simd_float4` (usare `.x .y .z`) |
| `camera.pose.ty()` | `frame.camera.transform.columns.3.y` |
| `camera.pose.rotateVector(floatArrayOf(0f,0f,-1f))` | `frame.camera.transform * simd_float4(0,0,-1,0)` → `.xyz` |
| `camera.pose.transformPoint(floatArrayOf(0f,0f,-2f))` | `(frame.camera.transform * simd_float4(0,0,-2,1)).xyz` |
| `camera.trackingState == TrackingState.TRACKING` | `frame.camera.trackingState == .normal` |
| `camera.trackingState == TrackingState.PAUSED` | `frame.camera.trackingState == .limited(...)` |
| `camera.trackingFailureReason.name` | `frame.camera.trackingState` case `.limited(let reason)` → `reason` |

### Proiezione e hit test

| ARCore (Kotlin) | ARKit (Swift) |
|----------------|---------------|
| `frame.hitTest(px, py)` | `sceneView.raycastQuery(from:allowing:alignment:)` → `session.raycast(query)` |
| hit su `Plane` | `ARRaycastResult.type == .existingPlaneGeometry` |
| hit su `Point` | `ARRaycastResult.type == .estimatedPlane` |
| `hit.hitPose.tx/ty/tz()` | `hit.worldTransform.columns.3.x/y/z` |
| `camera.getProjectionMatrix(near,far,w,h)` | `frame.camera.projectionMatrix(for:viewportSize:zNear:zFar:)` |
| `camera.getViewMatrix(displayRotation)` | `frame.camera.viewMatrix(for:)` con `UIInterfaceOrientation` |

### Planes (floor detection)

| ARCore (Kotlin) | ARKit (Swift) |
|----------------|---------------|
| `session.getAllTrackables(Plane::class.java)` | `frame.anchors.compactMap { $0 as? ARPlaneAnchor }` |
| `plane.type == HORIZONTAL_UPWARD_FACING` | `anchor.alignment == .horizontal` |
| `plane.trackingState == TrackingState.TRACKING` | (gli anchor ARKit sono automaticamente tracked se presenti nel frame) |
| `plane.subsumedBy == null` | `anchor.geometry.vertices.count > 0` (non c'è concetto di subsume diretto) |
| `plane.centerPose.ty()` | `anchor.transform.columns.3.y` |
| `plane.extentX * plane.extentZ` | `anchor.planeExtent.width * anchor.planeExtent.height` |

### Depth (opzionale, LiDAR)

| ARCore (Kotlin) | ARKit (Swift) |
|----------------|---------------|
| `session.isDepthModeSupported(AUTOMATIC)` | `ARWorldTrackingConfiguration.supportsFrameSemantics(.sceneDepth)` |
| `config.depthMode = AUTOMATIC` | `config.frameSemantics = .sceneDepth` |
| `frame.acquireDepthImage()` | `frame.sceneDepth?.depthMap` → `CVPixelBuffer` |

### Rendering camera background

| ARCore (Kotlin) | ARKit (Swift) |
|----------------|---------------|
| `BackgroundRenderer` (GL_TEXTURE_EXTERNAL_OES) | **Eliminato** — `ARSCNView` disegna il feed automaticamente |
| shader OES custom | n/a |

### Dati / Storage

| Android (Kotlin) | iOS (Swift) |
|-----------------|-------------|
| `ByteBuffer.allocateDirect(n).order(ByteOrder.nativeOrder())` | `Data(count: n)` + `withUnsafeMutableBytes { ... }` |
| `context.cacheDir` | `FileManager.default.temporaryDirectory` |
| `context.filesDir` | `FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]` |
| `SharedPreferences("hub_session")` | `UserDefaults.standard` |
| `prefs.edit().putString(k,v).apply()` | `UserDefaults.standard.set(v, forKey: k)` |
| `context.startActivity(intent)` | `present(viewController, animated: true)` |
| `Activity.finish()` | `dismiss(animated: true)` |
| `Handler(Looper.getMainLooper()).post { }` | `DispatchQueue.main.async { }` |
| `AlertDialog.Builder` | `UIAlertController(style: .alert)` |
| `Canvas` + `Paint` | `UIGraphicsImageRenderer` + `CGContext` |

### UI

| Android | iOS |
|---------|-----|
| `Activity` | `UIViewController` |
| `FrameLayout` | `UIView` con `frame` o Auto Layout |
| `LinearLayout(VERTICAL)` | `UIStackView(axis: .vertical)` |
| `LinearLayout(HORIZONTAL)` | `UIStackView(axis: .horizontal)` |
| `Button` | `UIButton` |
| `TextView` | `UILabel` |
| `GradientDrawable` | `CALayer` + `layer.cornerRadius` + `layer.borderColor` |
| `GLSurfaceView` | `GLKView` (overlay su `ARSCNView`) |
| `ViewGroup.LayoutParams.MATCH_PARENT` | `translatesAutoresizingMaskIntoConstraints = false` + `NSLayoutConstraint` o frame = superview.bounds |
| `dp(n)` helper | `n * UIScreen.main.scale` oppure `n` direttamente in pt (iOS usa pt) |
| `setTextColor(Color.argb(a,r,g,b))` | `UIColor(red:green:blue:alpha:)` |
| `setShadowLayer(...)` | `label.layer.shadowRadius`, `shadowColor`, `shadowOffset`, `shadowOpacity` |
| `TypedValue.COMPLEX_UNIT_SP` | già pt su iOS — `UIFont.systemFont(ofSize: n)` |

---

## Milestone di sviluppo

### M1 — Scheletro Capacitor iOS + isSupported()
**Obiettivo:** plugin compilabile, installabile in un'app Capacitor iOS, `isSupported()` funzionante.
**File da creare:** `SpatialScanPlugin.swift`, `SpatialScanPlugin.m`, `SpatialScanManager.swift` (solo `checkSupport()`), `.podspec`.
**Test:** `SpatialScan.isSupported()` da JS ritorna `{ supported: true }` su iPhone ARKit-compatible.
**Android:** zero modifiche.

#### File: `SpatialScanPlugin.m` (bridge obbligatorio Capacitor)
```objc
#import <Foundation/Foundation.h>
#import <Capacitor/Capacitor.h>
CAP_PLUGIN(SpatialScanPlugin, "SpatialScan",
    CAP_PLUGIN_METHOD(isSupported, CAPPluginReturnPromise);
    CAP_PLUGIN_METHOD(requestPermissions, CAPPluginReturnPromise);
    CAP_PLUGIN_METHOD(startScan, CAPPluginReturnPromise);
    CAP_PLUGIN_METHOD(stopScan, CAPPluginReturnPromise);
    CAP_PLUGIN_METHOD(cancelScan, CAPPluginReturnPromise);
    CAP_PLUGIN_METHOD(getScanStatus, CAPPluginReturnPromise);
    CAP_PLUGIN_METHOD(exportPdf, CAPPluginReturnPromise);
)
```

#### podspec additions
```ruby
s.ios.deployment_target = '14.0'
s.ios.frameworks = ['ARKit', 'GLKit', 'OpenGLES']
s.ios.source_files = 'ios/Plugin/**/*.{swift,h,m}'
```

---

### M2 — Logic core portato in Swift
**Obiettivo:** tutti i file platform-agnostic compilano su iOS.
**File da creare:** `PerimeterCapture.swift`, `FloorPlaneAnchor.swift`, tutti i Models.
**Cambiamenti rispetto al Kotlin:**
- `FloatArray(3)` → `simd_float3`
- `kotlin.math.sqrt/atan2/cos/sin` → `Foundation.sqrt` ecc. (o `simd`)
- `mutableListOf<FloatArray>()` → `[simd_float3]`
- `data class` con `copy()` → `struct` con proprietà mutabili dove serve
- `@Volatile` → non necessario (Swift usa DispatchQueue o actor)
- `companion object { const val }` → `static let` / `enum`

**`FloorPlaneAnchor.swift` — diff chiave:**
```swift
// Invece di: fun update(planes: Collection<Plane>, ...)
func update(planeAnchors: [ARPlaneAnchor], cameraY: Float, isTracking: Bool) -> Float {
    // La logica mediana/varianza è identica bit-per-bit
    let floorAnchors = planeAnchors.filter { $0.alignment == .horizontal }
    let largest = floorAnchors.max { a, b in
        a.planeExtent.width * a.planeExtent.height < b.planeExtent.width * b.planeExtent.height
    }
    let raw = largest.map { Float($0.transform.columns.3.y) } ?? (cameraY - 1.2)
    // ... resto identico
}
```

**Android:** zero modifiche.

---

### M3 — ScanningViewController + rendering
**Obiettivo:** la UI AR si apre, mostra feed camera, reticolo, e il flusso GPC è operativo end-to-end.
**File da creare:** `ScanningViewController.swift`, `PerimeterRenderer.swift`, `OpeningRenderer.swift`.

#### Struttura `ScanningViewController`

```swift
class ScanningViewController: UIViewController, ARSessionDelegate {

    // AR
    var arView: ARSCNView!
    var session: ARSession { arView.session }

    // GL overlay (stesso ruolo di GLSurfaceView)
    var glView: GLKView!
    var glContext: EAGLContext!

    // Logic (identici al Kotlin)
    let perimeterCapture = PerimeterCapture()
    let floorAnchor = FloorPlaneAnchor()
    let perimeterRenderer = PerimeterRenderer()
    let openingRenderer = OpeningRenderer()

    // State (identico)
    var lastFloorY: Float = 0
    var planeFindingDisabled = false
    var reticleTopMode = false
    var wallHeightPreview: Float = 2.50
    // ... tutti gli altri @Volatile → var normali (accesso su main thread tramite DispatchQueue)

    // Camera update (sostituisce onDrawFrame)
    func session(_ session: ARSession, didUpdate frame: ARFrame) {
        // identico a onDrawFrame, tipi diversi
        let allAnchors = frame.anchors.compactMap { $0 as? ARPlaneAnchor }
        lastFloorY = floorAnchor.update(
            planeAnchors: allAnchors,
            cameraY: frame.camera.transform.columns.3.y,
            isTracking: frame.camera.trackingState == .normal
        )
        // screenToWorldFloorPlane, goniometro, smoothing buffer — identici
        DispatchQueue.main.async { self.glView.display() }
    }
}
```

#### `GLKView` setup (sostituisce GLSurfaceView)
```swift
glContext = EAGLContext(api: .openGLES2)!
glView = GLKView(frame: view.bounds, context: glContext)
glView.backgroundColor = .clear
glView.isOpaque = false
glView.delegate = self
view.insertSubview(glView, aboveSubview: arView)
```

#### `screenToWorldFloorPlane` — diff minima
```swift
// Android
private fun screenToWorldFloorPlane(camera: Camera): FloatArray? {
    val camPos = camera.pose.translation
    val camFwd = camera.pose.rotateVector(floatArrayOf(0f, 0f, -1f))

// iOS
private func screenToWorldFloorPlane(frame: ARFrame) -> simd_float3? {
    let t = frame.camera.transform
    let camPos = simd_float3(t.columns.3.x, t.columns.3.y, t.columns.3.z)
    let fwdH   = t * simd_float4(0, 0, -1, 0)
    let camFwd = simd_float3(fwdH.x, fwdH.y, fwdH.z)
    // da qui: identico
```

#### Lifecycle iOS vs Android
| Android | iOS |
|---------|-----|
| `onCreate` | `viewDidLoad` |
| `onResume` → `session.resume()` | `viewWillAppear` → `session.run(config)` |
| `onPause` → `glView.onPause()` + `session.pause()` | `viewWillDisappear` → `session.pause()` |
| `onDestroy` → `session.close()` | `deinit` |
| `onBackPressed` | `navigationController?.popViewController` o gesture custom |

**Android:** zero modifiche.

---

### M4 — Export pipeline (PDF + GLB)
**Obiettivo:** `exportPdf()` e `glbPath` funzionanti su iOS.

#### `GlbExporter.swift` — diff principale
```swift
// Android: ByteBuffer.allocateDirect(n).order(ByteOrder.nativeOrder())
// iOS:     Data(count: n) con accesso via withUnsafeMutableBytes

static func floatsToData(_ floats: [Float]) -> Data {
    var data = Data(count: floats.count * 4)
    data.withUnsafeMutableBytes { ptr in
        let buf = ptr.bindMemory(to: Float.self)
        for (i, f) in floats.enumerated() { buf[i] = f }
    }
    return data
}
// FileOutputStream → FileManager.default.createFile(atPath:contents:)
```

#### `FloorPlanExporter.swift` — diff principale
```swift
// Android: Canvas + Paint → drawLine, drawText, drawPath
// iOS: UIGraphicsImageRenderer + CGContext

func exportPng(data: RoomExportData, cacheDir: URL) -> String? {
    let renderer = UIGraphicsImageRenderer(size: CGSize(width: 1200, height: 1200))
    let img = renderer.image { ctx in
        let cgCtx = ctx.cgContext
        // drawLine: cgCtx.move(to:) + cgCtx.addLine(to:) + cgCtx.strokePath()
        // drawText: NSAttributedString + NSString.draw(at:withAttributes:)
    }
    // PDF A3: UIGraphicsPDFRenderer(bounds:) — simile
}
```

**Android:** zero modifiche.

---

### M5 — Multi-room Composer
**Obiettivo:** `RoomComposerViewController` funzionante su iOS.
**File da creare:** `RoomComposerViewController.swift`, `CompositionGraph.swift`, `UnlinkedOpeningStore.swift`.
**Note:** `RoomComposerView` usa Android `Canvas` draw. iOS: `UIView` con `draw(_ rect:)` override + CoreGraphics — API molto simile.

**Android:** zero modifiche.

---

### M6 — Test e parità con Android

#### Checklist funzionale
- [ ] `isSupported()` → true su iPhone ARKit-compatible
- [ ] `requestPermissions()` → richiesta camera iOS (`NSCameraUsageDescription`)
- [ ] `startScan()` → ARSCNView fullscreen, tracking inizia
- [ ] Floor lock → plane detection disabilitata, GPC attivo
- [ ] AWAIT_FIRST_FLOOR → tap P0 riconosciuto
- [ ] AWAIT_HEIGHT → stepper +/- altezza funzionante
- [ ] FLOOR_ONLY → punti multipli, reticolo con smoothing
- [ ] TOP mode → auto-attivazione a >12° tilt, goniometro su piano superiore
- [ ] Undo → state machine identica ad Android
- [ ] Close polygon → `canClose` con ≥ 3 punti
- [ ] Opening mode → highlight hover (ciano) + selezione (fucsia)
- [ ] Stepper aperture → posizione/larghezza/altezza/battuta
- [ ] Export PNG → file leggibile
- [ ] Export PDF A3 → file leggibile
- [ ] Export GLB → model-viewer carica correttamente
- [ ] Multi-room composer → allineamento planimetrie
- [ ] `onScanComplete` listener → dati ricevuti in JS
- [ ] `onFrameUpdate` listener → dati aggiornati ogni frame

#### Device minimo consigliato per test
- **Minimo ARKit:** iPhone 6s (iOS 11) — tracking base
- **Consigliato sviluppo:** iPhone 12+ (VIO migliorato, performance)
- **LiDAR (depth):** iPhone 12 Pro / 13 Pro / 14 Pro / 15 Pro

---

## definitions.ts — unica modifica TypeScript necessaria

```typescript
export interface ScanMetadata {
  scanDurationSeconds: number;
  planesDetected: number;
  wallsInResult: number;
  depthApiUsed: boolean;
  arcoreVersion: string;   // mantenuto per backward compat Android
  arkitVersion?: string;   // aggiunto per iOS (opzionale)
}

// Aggiungere all'UnsupportedReason
export type UnsupportedReason =
  | 'ARCORE_NOT_AVAILABLE'
  | 'SDK_TOO_OLD'
  | 'DEVICE_NOT_CERTIFIED'
  | 'ARKIT_NOT_SUPPORTED';  // nuovo
```

---

## Invarianti assolute (mai toccare)

Questi file/funzioni devono rimanere bit-per-bit identici tra Android e iOS nella logica:

1. **`PerimeterCapture` — state machine** (IDLE→CAPTURING→CLOSED, addPoint, undo, close)
2. **`screenToWorldFloorPlane` / `screenToWorldTopPlane`** — solo tipi diversi, mai la formula
3. **`FloorPlaneAnchor` — mediana mobile e lock** (30 campioni, varianza < 0.5cm²)
4. **Goniometro** — settore ±65°, isteresi 0.20/0.10, snap 1°
5. **`RoomRectifier`** — algoritmo dominantAxis + 8 candidati 45°
6. **Buffer smoothing** — 8 campioni floor, 4 campioni height, reset al cambio modalità

---

## Dipendenze iOS (nessuna esterna)

| Framework | Uso | Built-in? |
|-----------|-----|-----------|
| `ARKit` | sessione AR, camera pose, plane detection | ✅ iOS 11+ |
| `GLKit` | `GLKView`, `GLKMatrix4` | ✅ deprecated ma funzionale |
| `OpenGLES` | shader GLES 2.0 | ✅ deprecated ma funzionale iOS 14+ |
| `CoreGraphics` | export PNG/PDF | ✅ |
| `UIKit` | tutta la UI | ✅ |
| `Foundation` | file, JSON, UserDefaults | ✅ |
| `simd` | operazioni vettori/matrici | ✅ |

**Nessun CocoaPod di terze parti.** Zero dipendenze esterne aggiuntive.

---

## Ordine di esecuzione consigliato

```
M1 (2-3h)  →  M2 (4-6h)  →  M3 (8-12h)  →  M4 (4-6h)  →  M5 (3-4h)  →  M6 (test)
scheletro      logica core   UI + rendering   export         composer       parità
```

M3 è il milestone più critico e lungo. M1+M2 possono essere sviluppati senza device fisico (logica pura). Da M3 in poi serve iPhone fisico collegato a Xcode.

---

## Prerequisiti prima di iniziare

1. **Apple Developer account** attivo (per deploy su device e TestFlight)
2. **Xcode 15+** installato sul Mac
3. **iPhone fisico** con iOS 14+ per test (il Simulator non supporta ARKit)
4. **Provisioning profile** configurato per il bundle ID dell'app host
5. Aggiungere `NSCameraUsageDescription` nell'`Info.plist` dell'app host Capacitor
6. Aggiungere la cartella `ios/` al `.gitignore` pattern di node_modules, non al sorgente

---

*Documento di riferimento per lo sviluppo iOS. Aggiornare ad ogni milestone completato.*
