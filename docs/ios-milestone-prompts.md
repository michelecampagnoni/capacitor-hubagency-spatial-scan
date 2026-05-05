# Prompt operativi per milestone iOS
**Da usare su Mac con Xcode 15+ come istruzioni a Claude Code**  
**Riferimento piano:** `docs/ios-production-plan.md`  
**Codebase:** `/path/to/HubAgencyNative` (adattare al path sul Mac destinazione)

---

## Come usare questo documento

Per ogni milestone, copia il blocco "PROMPT" e incollalo come messaggio a Claude Code sul Mac con Xcode. Claude leggerà il codice Android esistente, capirà il contesto, e implementerà il milestone. Ogni prompt è autosufficiente — include tutto il contesto necessario.

---

## M1 — Scheletro Capacitor iOS + Permissions

```
PROMPT M1:

Leggi prima questi file per capire il contesto:
- docs/ios-production-plan.md
- capacitor-plugin/src/definitions.ts
- capacitor-plugin/src/index.ts
- capacitor-plugin/package.json
- recap/ (tutti i file)

Poi implementa M1 del piano iOS:

1. Crea `capacitor-plugin/ios/Plugin/SpatialScanPlugin.swift`:
   - Bridge Capacitor con @objc per tutti i metodi pubblici
   - Metodi da esporre: startScan, stopScan, exportPdf, isSupported, requestPermissions
   - Ogni metodo chiama SpatialScanManager

2. Crea `capacitor-plugin/ios/Plugin/SpatialScanPlugin.m`:
   - CAP_PLUGIN macro con registrazione di tutti i metodi
   - Obbligatorio per Capacitor su iOS

3. Crea `capacitor-plugin/ios/Plugin/SpatialScanManager.swift`:
   - isSupported(): controlla ARWorldTrackingConfiguration.isSupported
   - requestCameraPermission(): AVCaptureDevice.requestAccess(for: .video)
   - ARSession property (non ancora avviata)
   - startScan() e stopScan() stub (ritornano errore "not implemented" per ora)

4. Crea `capacitor-plugin/capacitor-hubagency-spatial-scan.podspec`:
   - name: capacitor-hubagency-spatial-scan
   - frameworks: ARKit, GLKit, OpenGLES, CoreGraphics
   - dependency 'Capacitor'
   - source_files: ios/Plugin/**/*.{swift,h,m}

5. Aggiungi NSCameraUsageDescription in capacitor-test-app/ios/App/App/Info.plist

Regole:
- Non toccare nulla in android/ o src/ TypeScript
- Nessuna dipendenza esterna oltre ai framework Apple
- Dopo creazione file: esegui `pod lib lint capacitor-hubagency-spatial-scan.podspec` e mostrami output
- Verifica che l'app compili su Xcode e che requestPermissions() mostri il dialog camera

Salva un recap in recap/recap_[data]_m1-ios-skeleton.md al completamento.
```

---

## M2 — Logic core in Swift

```
PROMPT M2:

Leggi prima questi file Android per capire l'implementazione esatta:
- capacitor-plugin/android/src/main/java/it/hubagency/spatialscan/FloorPlaneAnchor.kt
- capacitor-plugin/android/src/main/java/it/hubagency/spatialscan/PerimeterCapture.kt
- capacitor-plugin/android/src/main/java/it/hubagency/spatialscan/RoomRectifier.kt
- capacitor-plugin/android/src/main/java/it/hubagency/spatialscan/models/ (tutti i file)
- docs/ios-production-plan.md (sezione M2)

Poi implementa M2:

1. Crea tutti i file in `capacitor-plugin/ios/Plugin/Models/`:
   OpeningModel.swift, RoomModel.swift, RoomExportData.swift, RoomRecord.swift,
   LinkedOpeningSpec.swift, OpeningMetadata.swift
   Port 1:1 delle data class Kotlin — usa struct Swift con Codable.

2. Crea `capacitor-plugin/ios/Plugin/FloorPlaneAnchor.swift`:
   - Invarianti ASSOLUTE (non modificare): 30 campioni, varianza < 0.0005 (= 0.5cm²), EMA α=0.002
   - Fallback: camera.Y − 1.2m se nessun floor plane
   - Traduci tipi: FloatArray → [Float], ARCore Plane → ARPlaneAnchor

3. Crea `capacitor-plugin/ios/Plugin/PerimeterCapture.swift`:
   - Invarianti ASSOLUTE: state machine IDLE/CAPTURING/CLOSED, phase logic identica,
     undo() phase-aware, snapFull(), axisDirections()
   - SNAP_DEG = 90, SNAP_THRESHOLD_DEG = 0 (snap disabilitato), MIN_SEG_M = 0.15, MIN_HEIGHT_M = 1.50
   - Usa simd_float3 invece di FloatArray

4. Crea `capacitor-plugin/ios/Plugin/RoomRectifier.swift`:
   PRIMA di implementare: leggi il file Kotlin riga per riga e verifica i valori esatti.
   - SNAP_HARD_DEG = 5°, MIN_EDGE_M = 0.20m, 8 candidati ogni 45°
   - Logica dominant axis = muro più lungo
   - Distribuzione errore chiusura lineare

5. Crea test unitari Swift per PerimeterCapture e RoomRectifier:
   - Per RoomRectifier: usa come input 3 poligoni reali estratti dai file di test Android
     (o costruiscili manualmente con coordinate note) e verifica output identico entro 0.5mm

Regole:
- Zero dipendenze ARKit in questo milestone — logica pura Swift
- Non toccare Android
- Mostrami i valori esatti trovati nel Kotlin prima di implementare RoomRectifier

Salva recap in recap/recap_[data]_m2-ios-core-logic.md al completamento.
```

---

## M3 — ScanningViewController + Rendering

```
PROMPT M3:

Leggi prima questi file Android:
- android/src/main/java/it/hubagency/spatialscan/ScanningActivity.kt (tutto — è lungo)
- capacitor-plugin/android/src/main/java/it/hubagency/spatialscan/PerimeterRenderer.kt
- capacitor-plugin/android/src/main/java/it/hubagency/spatialscan/OpeningRenderer.kt
- docs/ios-production-plan.md (sezione M3, inclusi rischi tecnici)

Poi implementa M3 seguendo ESATTAMENTE le formule nel piano.

1. Crea `ScanningViewController.swift`:
   - ARSCNView (camera background automatica — NON serve BackgroundRenderer)
   - GLKView sopra con isOpaque=false, backgroundColor=.clear
   - ARSessionDelegate: session(_:didUpdate:) per aggiornare renderer
   - UITapGestureRecognizer per aggiungere punti
   - Auto-switch FLOOR/TOP da camFwd.y > 0.12
   - screenToWorldFloorPlane() e screenToWorldTopPlane() con formule ESATTE dal piano
   - DispatchQueue.main per UI updates

2. Crea `PerimeterRenderer.swift`:
   - GLES 2.0: GL_LINE_LOOP ciano per poligono confermato
   - Live preview punto corrente (snap-aware)
   - Goniometro ±65° — INVARIANTE ASSOLUTA: settore non full circle, isteresi 0.20/0.10
   - Buffer smoothing: 8 campioni floor, 4 campioni height, reset al cambio mode

3. Crea `OpeningRenderer.swift`:
   - Highlight muro hover (ciano)
   - Fill muro selezionato (fucsia semitrasparente)
   - GL_LINE_STRIP bordo (no bottom edge — come in v1.26 Android)

ATTENZIONE ai rischi tecnici:
- GLKView: impostare preferredFramesPerSecond=60, non usare depth buffer separato
- Camera matrix: usare frame.camera.projectionMatrix(for:viewportSize:zNear:zFar:) con
  viewportSize = bounds.size del GLKView (in punti, non pixel — attenzione al contentScaleFactor)
- Orientamento: bloccare a portrait durante scanning per semplicità
- Sincronizzare rendering con ARFrame tramite ARSessionDelegate, non con CADisplayLink

Verifica:
- Test su iPhone fisico: scansiona stanza 4 angoli
- Poligono visibile e metrico (misura una parete reale e confronta con display)
- Goniometro appare quando ci sono 2+ punti
- Auto-switch funziona (inclina telefono su/giù)

Salva recap in recap/recap_[data]_m3-ios-scanning-view.md al completamento.
```

---

## M4 — Export PDF + GLB

```
PROMPT M4:

Leggi prima:
- capacitor-plugin/android/src/main/java/it/hubagency/spatialscan/FloorPlanExporter.kt
- capacitor-plugin/android/src/main/java/it/hubagency/spatialscan/GlbExporter.kt
- docs/ios-production-plan.md (sezione M4)

Implementa M4:

1. Crea `FloorPlanExporter.swift`:
   - CoreGraphics: PNG 1200×1200 px, PDF A3 (1748×2480 px) @ 150dpi
   - Stesse proporzioni, etichette quote, legenda scala del Kotlin
   - Esecuzione in background: DispatchQueue.global(qos:.userInitiated)
   - Salva in FileManager.default.temporaryDirectory

2. Crea `GlbExporter.swift`:
   - GLTF 2.0 binario (.glb)
   - 2 materiali: Wall (baseColorFactor=[0.88,0.88,0.88,1]), Floor (baseColorFactor=[0.60,0.60,0.60,1])
   - Normali perpendicolari ai muri
   - Ear-clipping per poligoni non convessi (porta la stessa logica Kotlin)
   - Buffer NON-interleaved: [positions block][normals block][indices block]
   - Intagli aperture: segmenti solidi + architrave + davanzale

3. Crea `RoomDataLoader.swift` e `RoomHistoryManager.swift`:
   - JSONDecoder/JSONEncoder per RoomRecord
   - FileManager per lettura/scrittura file stanze in Documents directory

4. Connetti stopScan() in SpatialScanManager a FloorPlanExporter + GlbExporter:
   - Ritorna { floorPlanPath, glbPath } al bridge Capacitor

Verifica:
- GLB apri in Reality Composer o model-viewer.dev
- PDF A3 leggibile con quote
- Path accessibile dall'app host JavaScript

Salva recap in recap/recap_[data]_m4-ios-export.md al completamento.
```

---

## M5 — Multi-room Composer

```
PROMPT M5:

Leggi prima:
- capacitor-plugin/android/src/main/java/it/hubagency/spatialscan/CompositionGraph.kt
- capacitor-plugin/android/src/main/java/it/hubagency/spatialscan/UnlinkedOpeningStore.kt
- android/src/main/java/it/hubagency/spatialscan/RoomComposerActivity.kt
- docs/ios-production-plan.md (sezione M5)

Implementa M5 come port 1:1 della logica Kotlin:

1. Crea `CompositionGraph.swift`:
   - Struct RoomWorldTransform: roomId, parentId, worldOffsetX, worldOffsetZ, worldRotRad, confirmedAt
   - Persistenza in hub_graph.json (Documents directory via FileManager)
   - addTransform(), getTransform(), getRootId(), getComponentRoomIds() (BFS bidirezionale)
   - JSONCodable

2. Crea `UnlinkedOpeningStore.swift`:
   - Port 1:1 da Kotlin
   - Persistenza JSON

3. Crea `RoomComposerViewController.swift`:
   - UIViewController con rendering overlay stanze già composte
   - Carica componente connessa del parent (tutte le stanze via BFS)
   - Trasforma in world space
   - Allinea new room su apertura selezionata del parent
   - Conferma → salva RoomWorldTransform nel grafo

Verifica:
- Scan A → stopScan → startScan con parentId=A → Scan B → stopScan
- RoomComposerViewController mostra A + B allineate correttamente
- hub_graph.json scritto e riletto senza perdita dati

Salva recap in recap/recap_[data]_m5-ios-multiroom.md al completamento.
```

---

## M6 — Parità e produzione

```
PROMPT M6:

Leggi:
- Tutti i recap iOS (recap/recap_*_m1* fino a m5*)
- capacitor-plugin/src/definitions.ts
- docs/ios-production-plan.md (sezione M6)

Esegui verifica finale:

1. Smoke test parità iOS vs Android:
   - Scansiona la stessa stanza fisica con entrambi i device
   - Confronta: numero vertici poligono, area calcolata (tolleranza ±5%), export PDF visivamente identico
   - Documenta risultati nel recap

2. Verifica API TypeScript:
   - Controlla che ogni metodo in definitions.ts sia implementato sia in Android che iOS
   - Stesse chiavi JSON nei risultati (floorPlanPath, glbPath, ecc.)

3. Aggiorna package.json (commit separato):
   - Aggiungi "ios": { "src": "ios" } in "capacitor"
   - Verifica che non rompe nulla Android

4. Esegui `pod lib lint` finale — deve passare senza warning/errori

5. Aggiorna README.md:
   - Aggiungi sezione iOS Requirements
   - Aggiungi sezione iOS Installation (pod setup)

Salva recap finale in recap/recap_[data]_m6-ios-production.md con:
- Risultati smoke test
- Eventuali divergenze vs Android (documentare, non fixare se non critiche)
- Stato di produzione del plugin
```

---

## Note per il Mac destinazione

- **Branch da creare:** `git checkout -b feature/ios-port` prima di iniziare M1
- **CocoaPods:** `sudo gem install cocoapods` se non installato
- **Testare sempre su device fisico** — ARKit non funziona su Simulator
- **Ordine tassativo:** M1 → M2 → M3 → M4 → M5 → M6. Non saltare.
- **Se M3 si blocca** su GLKView + ARSCNView: consultare il risk log in `docs/ios-production-plan.md`
