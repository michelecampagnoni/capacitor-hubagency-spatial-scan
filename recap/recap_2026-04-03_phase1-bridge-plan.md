# Recap Pre-Fase 1 — Bridge Plugin / HubAgency — 2026-04-03

## Stato git pre-lavori

**Branch:** `main`
**Commit corrente:** `2b38df8`
**APK di riferimento (invariato):** `builds/v1.2-room-composer/HubAgency-v1.2-room-composer-debug.apk`
**Stato origin/main:** allineato ✓

---

## Checklist pre-sessione

- [x] Repo locale verificata — nessuna modifica non tracciata
- [x] Commit backup su main (`22710ff` + fix `2b38df8`)
- [x] Push su origin/main riuscito
- [x] APK v1.2 presente — nessuna build necessaria prima di iniziare
- [x] Recap redatto

---

## Obiettivo Fase 1 — Bridge Plugin → HubAgency

### Separazione delle responsabilità

| Livello | Responsabilità |
|---|---|
| **Plugin (engine)** | Scansione AR, poligono, aperture, export GLB, export PNG planimetria, canonical JSON |
| **Host app (HubAgency)** | Storage Supabase, naming stanze, versioning, ACL, retention |

### Contratto di output del plugin (nuovo)

```ts
ScanResult {
  scanId:         string           // UUID usato anche nei nomi file temp
  success:        boolean
  walls:          Wall[]           // con openings[] per parete
  floor:          FloorPolygon
  roomDimensions: RoomDimensions
  scanMetadata:   ScanMetadata
  glbFileUri?:    string           // file:// path temp — host app legge e carica
  previewImageUri?: string         // file:// path temp — PNG planimetria
}
```

Nuovo metodo API:
```ts
disposeTempAssets(options: { scanId: string }): Promise<void>
// HubAgency chiama questo dopo aver caricato i file su Supabase
```

### Modifiche pianificate

#### `definitions.ts`
- Rinomina `floorPlanPath` → `previewImageUri`
- Rinomina `glbPath` → `glbFileUri`
- Aggiunge `scanId: string`
- Aggiunge metodo `disposeTempAssets`
- Aggiunge `ThemeOptions` in `ScanOptions` (placeholder per Fase 4)

#### `SpatialScanPlugin.kt`
- Aggiunge `@PluginMethod fun disposeTempAssets()`

#### `ScanningActivity.kt`
- `doStopScan()`: rimuove la catena `showNamingDialogAndSave → offerComposer → showContinueScanDialog`
- `showConnectionDialog()`: rimuove il picker stanze da `RoomHistoryManager` (rimane solo "Esterna / Interna")
- `buildResult()`: aggiunge `scanId` (UUID), usa `scanId` nei nomi file temp, rinomina chiavi output

#### File rimossi dal plugin (spostati alla responsabilità della test app):
- `RoomHistoryManager.kt` — storage persistente
- `RoomRecord.kt` — data class storage
- `RoomComposition.kt` — composizione multi-stanza
- `RoomComposerActivity.kt` — UI multi-stanza
- `RoomComposerView.kt` — Canvas multi-stanza
- `LinkedOpeningSpec.kt` — spec multi-stanza

#### `AndroidManifest.xml`
- Rimuove `<activity ... RoomComposerActivity ...>`

#### `GlbExporter.kt` / `FloorPlanExporter.kt`
- Aggiungono parametro `scanId` nei nomi file (`scan_{scanId}.glb`, `floorplan_{scanId}.png`)

#### `web.ts`
- Aggiunge stub `disposeTempAssets`

---

## File invarianti (non toccare)

- `PerimeterCapture.kt`
- `PerimeterRenderer.kt`
- `screenToWorld()`
- `handlePerimeterTap()` / `handleOpeningTap()`
- `FloorPlaneAnchor.kt`
- `GlbExporter.kt` (solo aggiunta parametro scanId)
- `FloorPlanExporter.kt` (solo aggiunta parametro scanId)
- Tutta la logica ARCore / OpenGL

---

## Workflow sessione (protocollo)

1. Sviluppo su branch `feature/phase1-bridge`
2. Merge su `main` al completamento
3. Build APK `builds/v1.3-bridge/HubAgency-v1.3-bridge-debug.apk`
4. Smoke test con telefono connesso
5. Se OK → commit + push + recap finale
