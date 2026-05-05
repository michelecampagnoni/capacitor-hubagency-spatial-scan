# Recap M5 — Multi-room native flow (iOS)
Data: 2026-05-04

## Obiettivo
Port 1:1 del workflow multi-stanza Android v1.24/v1.25 su iOS.
Nessun coinvolgimento JS nel linking delle stanze. Flow completamente nativo.

## File creati

### `ios/Plugin/UnlinkedOpeningStore.swift`
Registro persistente delle aperture interne PENDING non ancora collegate bilateralmente.
- Struct `UnlinkedOpening` con tutti i campi (id, sourceRoomId, sourceRoomName, openingId, kind, width, height, bottom, wallIndex, customLabel)
- `withSourceRoomId(_:)` — crea copia con sourceRoomId popolato dopo il save
- `UnlinkedOpeningStore.shared` — singleton con `add`, `remove`, `loadAll`, `clear`
- Persistenza: `Documents/hub_unlinked_openings.json`

### `ios/Plugin/CompositionGraph.swift`
Grafo di composizione planimetrica multi-stanza.
- Struct `RoomWorldTransform` (roomId, parentId, worldOffsetX, worldOffsetZ, worldRotRad, confirmedAt)
- `CompositionGraph.shared` — `addTransform`, `getTransform`, `getRootId`, `getComponentRoomIds` (BFS bidirezionale), `removeTransform`, `getChildIds`, `clear`
- Persistenza: `Documents/hub_graph.json` con `{ "nodes": [...] }`

### `ios/Plugin/RoomHistoryManager.swift`
Persistenza stanze con formato JSON compatibile con il Composer.
- `save(roomData: [String:Any], name: String) -> RoomRecord?` — salva `hub_room_{id}.json` + aggiorna `hub_rooms.json`
- `loadAll() -> [RoomRecord]` — legge hub_rooms.json (Codable)
- `loadRoomData(id:) -> [String:Any]?` — legge hub_room_{id}.json grezzo
- `updateOpeningMetadata(roomId:openingId:linkedRoomId:linkedRoomName:)` — aggiornamento bilaterale LINKED in entrambi i file
- `delete(id:)` — rimuove dal registro e cancella il file geometrico
- `clearAll()` — cancella hub_rooms.json, hub_graph.json, hub_unlinked_openings.json, tutti hub_room_*.json

**Formato hub_room_{id}.json** (compatibile Composer):
```json
{
  "walls": [{ "id": "w0", "startPoint": {"x":…,"z":…}, "endPoint": {"x":…,"z":…},
              "height": 2.5, "openings": [{ "id":"op_…", "kind":"DOOR",
              "offsetAlongWall":0.5, "width":0.8, "height":2.1, "bottom":0.0,
              "isInternal":false, "connectionStatus":"EXTERNAL",
              "linkedRoomId":"", "connectionLabel":"" }] }],
  "floor": { "vertices": [{"x":…,"z":…}], "area": 12.5 },
  "roomDimensions": { "width":4.0, "length":3.5, "height":2.5, "area":12.5, "perimeter":14.0 }
}
```

### `ios/Plugin/RoomComposerViewController.swift`
Port 1:1 di RoomComposerActivity.kt + RoomComposerView.kt.

**`RoomComposerView: UIView`** (CoreGraphics):
- Disegna ambienti fissi (blu) + nuovo ambiente (verde) + marcatori porta di collegamento (arancione)
- Zoom pinch + pan single-finger
- Hit-test ray-casting per selezione ambienti fissi
- `applyTransform(_:_:)` — applica il world transform corrente al nuovo ambiente

**`RoomComposerViewController: UIViewController`**:
- Input: `parentRoomId`, `newRoomId`, `newRoomName`, `linkKind`, `linkWidth`, `linkParentOpeningId`, `linkNewOpeningId`, `manager`
- `loadCompositionData()`:
  - BFS componente connessa del parent via `CompositionGraph`
  - Trasforma tutti i poligoni in world space
  - `findLinkOpening()`: match per ID esatto poi fallback kind+width (±0.12m)
  - `computeInitialWorldAlignment()`: allinea apertura del nuovo ambiente a quella del parent in world space
  - Anti-overlap: calcola dist centroidi per orientamento normale vs 180°-flippato, sceglie il più distante
  - Fallback senza link: worldOx = maxX_fissi + 3m
- Controlli: sposta ←→↑↓ (5cm), ruota ±1°/5°, snap 90°, undo 50 livelli
- Elimina stanza cascade: BFS subtree, ripristina aperture PENDING in UnlinkedOpeningStore, aggiorna JSON del parent
- `confirmSave()` → salva `RoomWorldTransform` nel CompositionGraph → `showContinueScanDialog`
- `showContinueScanDialog()` → "Sì": dismiss + `manager?.startContinuationScan()` / "No": `hub_sessionEnded=true`

## File modificati

### `ios/Plugin/ScanningViewController.swift`

**Nuove proprietà multi-room:**
```swift
var isContinuation: Bool = false
private var pendingClassification = [String: (ConnectionStatus, UnlinkedOpening?, Int)]()
private var pendingUnlinkedOpenings = [(String, UnlinkedOpening)]()
private var pendingLinkUpdates = [UnlinkedOpening]()
private var pendingComposerRoomId: String?
private var pendingComposerLinkKind/Width/ParentOpeningId/NewOpeningId: …
private var pendingLabels = [String: String]()
```

**`viewDidAppear`**: se `!isContinuation` e `hub_sessionEnded==true` → `clearSessionData()` + reset flag.

**`showOpeningTypeDialog()`** (riscritta):
- Carica `UnlinkedOpeningStore.shared.loadAll()`
- Voci dinamiche "↔ Collega a 'X' (0.90m)" per ogni apertura disponibile
- Voci fisse: "Porta d'ingresso (esterna)", "Finestra", "Porta interna — nuova stanza", "Portafinestra interna — nuova stanza"
- Titolo adattivo: "Collega apertura" se ci sono unlinked, altrimenti "Tipo apertura"

**`showPendingLabelDialog(kind:)`**: chiede "Verso quale ambiente porta?" con text field, poi chiama `spawnWithClassification(kind, .pending, nil, label)`.

**`spawnWithClassification(kind:status:target:customLabel:)`** (sostituisce `spawnOpening`):
- Crea `OpeningModel` con dimensioni dall'`UnlinkedOpening` target (se LINKED) o dai default del kind
- Registra `pendingClassification[opening.id] = (status, target, wallIdx)`

**`applyClassification(_ opening:)`** (chiamato da `confirmOpening`):
- `.external` → `openingMetadataMap` con EXTERNAL
- `.pending` → `openingMetadataMap` con PENDING + aggiunge a `pendingUnlinkedOpenings`
- `.linked` → `openingMetadataMap` con LINKED + `pendingLinkUpdates.append(target)` + popola `pendingComposer*`

**`doStopScan()`** (riscritta):
1. Costruisce roomData JSON con formato Composer (startPoint/endPoint, metadata aperture completo)
2. `RoomHistoryManager.shared.save(roomData:name:)` → savedRecord
3. Processa `pendingUnlinkedOpenings` → `UnlinkedOpeningStore.add(entry.withSourceRoomId(realRoomId))`
4. Processa `pendingLinkUpdates` → `RoomHistoryManager.updateOpeningMetadata` + `UnlinkedOpeningStore.remove`
5. Determina anchorId: composerRoomId ?? primo nel grafo ?? ultima stanza salvata
6. Se anchorId presente → presenta `RoomComposerViewController` da `hubTopViewController()`
7. Altrimenti (prima scan) → `showContinueScanDialog`

**`showContinueScanDialog()`**: alert post-dismiss, "Sì" → `manager?.startContinuationScan()`, "No" → `hub_sessionEnded=true`.

**`clearSessionData()`**: delegato a `RoomHistoryManager.clearAll()` + store clear + UserDefaults.

### `ios/Plugin/SpatialScanManager.swift`
- `topViewController()` (private) → `hubTopViewController()` (internal extension su UIApplication)
- Aggiunto `startContinuationScan()`: crea nuovo `ScanningViewController(isContinuation: true)`, lo wira ai callback manager, presenta da `hubTopViewController()`

## Flusso completo appartamento A→B→C

1. **Scan A**: nome stanza → cattura → ESPORTA
   - Nessuna stanza precedente → `showContinueScanDialog`
   - "Sì" → `startContinuationScan()`

2. **Scan B**: In opening mode, tap su muro → `showOpeningTypeDialog()`
   - "Porta interna — nuova stanza" → `showPendingLabelDialog` → PENDING ("cucina")
   - ESPORTA → salva B, aggiunge PENDING a UnlinkedOpeningStore
   - `anchorId` = A (già salvata) → presenta **RoomComposerViewController**
   - Composer allinea B ad A → "Conferma" → salva RoomWorldTransform → `showContinueScanDialog`
   - "Sì" → `startContinuationScan()`

3. **Scan C**: In opening mode, tap su muro → `showOpeningTypeDialog()`
   - Dialog mostra "↔ Collega a 'cucina' (0.90m)" (da UnlinkedOpeningStore)
   - Seleziona LINKED → `spawnWithClassification(.door, .linked, target_B)`
   - ESPORTA → salva C, `updateOpeningMetadata` su B (LINKED bilaterale), rimuove da store
   - Presenta Composer per C→B (con linkParentOpeningId e linkNewOpeningId)
   - Composer allinea C a B in world space con anti-overlap
   - "No" → `hub_sessionEnded=true`
   - Prossimo startScan → `clearSessionData()`

## Fix build
- `findLinkOpening` restituisce tupla non-optional `((…)?, (…)?)` → rimosso `if let (a,b) = ...` (errore Xcode "Initializer for conditional binding must have Optional type"), sostituito con `let (a,b) = ...; if let av = a, let bv = b`.

## Stato
M5 completo. Prossimo: M4 (FloorPlanExporter CoreGraphics + GlbExporter).
