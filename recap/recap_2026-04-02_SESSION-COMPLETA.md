# Recap Sessione Completa — 2026-04-02

## Stato finale

**Branch:** `main`
**Ultimo commit:** `3859164`
**APK di riferimento:** `builds/v1.2-room-composer/HubAgency-v1.2-room-composer-debug.apk`

---

## Cronologia milestone della sessione

### v0.9 — Room History (naming dialog + storage persistente)
**Branch:** `feature/room-history-v2`

**Problema:** nessuna persistenza delle stanze scansionate.

**Soluzione:**
- `RoomRecord.kt` (NUOVO) — data class immutabile con id, name, timestamp, area, height, wallCount, openingCount, floorPlanPath, glbPath
- `RoomHistoryManager.kt` (NUOVO) — singleton, `filesDir/hub_rooms.json`, API: save/loadAll/delete
- `ScanningActivity.kt` — dialog "Nome stanza" al termine della scan (AlertDialog + EditText), wrappato in `showNamingDialogAndSave()`

---

### v0.9.1 — Fix testo bianco su bianco nel dialog
**Branch:** `feature/room-history-v2`

**Bug:** `setTextColor(Color.WHITE)` su AlertDialog a sfondo bianco rendeva invisibile il testo digitato.

**Fix:** rimossi `setTextColor` e `setHintTextColor` dall'EditText del dialog — usa colori di sistema.

---

### v1.0 — Opening Connection Metadata (classificazione aperture)
**Branch:** `feature/opening-connections`

**Obiettivo:** classificare ogni porta/portafinestra come esterna, interna-non-risolta, interna-collegata.

**Soluzione:**
- `OpeningMetadata.kt` (NUOVO) — openingId, wallId, isInternal, linkedRoomId, connectionLabel
- `ScanningActivity.kt` — dialog classificazione dopo `confirmOpening()` (solo DOOR/FRENCH_DOOR), picker stanza come secondo step se history non vuota
- `RoomRecord.kt` — aggiunto campo `openings: List<OpeningMetadata>` (retrocompatibile)
- `RoomHistoryManager.kt` — estrazione metadati aperture da JSON in `buildRecord()`
- Export JSON arricchito: `walls[i].openings[j]` guadagna `isInternal`, `linkedRoomId?`, `connectionLabel?`

---

### v1.1 — Multi-Room Workflow (scan consecutiva + spec apertura)
**Branch:** `feature/multi-room-workflow`

**Obiettivo:** al termine di ogni scan, guidare l'utente verso la scansione dell'ambiente successivo con riuso delle misure dell'apertura di collegamento.

**Soluzione:**
- `LinkedOpeningSpec.kt` (NUOVO) — kind, width, height, bottom + sourceRoomId/Name, viaggia via Intent extras
- `ScanningActivity.kt`:
  - `doStopScan()` → catena: naming → `showContinueScanDialog` → `showConnectionChoiceDialog` → `showOpeningPickerForRestart` → `restartWithSpec()`
  - `enterOpeningMode()` → banner offerta riuso spec se presente
  - `spawnOpening()` → usa dimensioni dalla spec al primo spawn se accettata
  - Lettura spec da Intent in `onCreate()`

**Flow:**
```
Fine scan → Nome → "Altro ambiente?" → "Collegato?" → Selezione apertura → Riavvio con spec
→ Banner spec in nuova scan → Primo spawn usa misure ereditate
```

---

### v1.2 — Room Composer (composizione planimetrica multi-stanza)
**Branch:** `feature/room-composer`

**Obiettivo:** editor guidato che allinea automaticamente due stanze scansionate e consente micro-aggiustamenti manuali.

**Soluzione:**
- `RoomHistoryManager.kt` — salva anche il JSON grezzo `hub_room_{id}.json` ad ogni scan nominata; `loadRoomData(id)` per caricarlo
- `RoomComposition.kt` (NUOVO) — offsetX, offsetZ, rotationRad; persiste in `hub_composition_{id}.json`
- `RoomComposerView.kt` (NUOVO) — custom Canvas View: Room A (blu), Room B (verde), aperture di collegamento (dot + linea tratteggiata arancione), bounding box auto-scaling
- `RoomComposerActivity.kt` (NUOVO) — Activity editor:
  - Allineamento iniziale automatico (rotazione normale opposta + traslazione centri aperture coincidenti)
  - Controlli: ←→↑↓ ±5cm, ↺↻ ±1°/5°, Snap 90°, Annulla/Conferma
- `ScanningActivity.kt` — `offerComposer()` alla fine di ogni scan collegata
- `AndroidManifest.xml` — `RoomComposerActivity` registrata

**Allineamento automatico:**
```
θ = atan2(-nA.z, -nA.x) - atan2(nB.z, nB.x)
(tx, tz) = cA - rotate(cB, θ)
```

---

## Invarianti rispettate in tutta la sessione

Le seguenti componenti NON sono state mai toccate:
- `PerimeterCapture.kt`
- `PerimeterRenderer.kt`
- `screenToWorld()`
- `handlePerimeterTap()`
- `handleOpeningTap()`
- `FloorPlaneAnchor.kt`
- Logica di conferma P0/P1
- Normalizzazione Y=0
- Flow aperture (placement, sizing, clamping)

---

## File nuovi introdotti in sessione

| File | Versione |
|---|---|
| `RoomRecord.kt` | v0.9 |
| `RoomHistoryManager.kt` | v0.9 |
| `OpeningMetadata.kt` | v1.0 |
| `LinkedOpeningSpec.kt` | v1.1 |
| `RoomComposition.kt` | v1.2 |
| `RoomComposerView.kt` | v1.2 |
| `RoomComposerActivity.kt` | v1.2 |

---

## APK prodotti in sessione

| Versione | Path |
|---|---|
| v0.9 | `builds/v0.9-room-history/HubAgency-v0.9-room-history-debug.apk` |
| v0.9.1 | `builds/v0.9.1-dialog-fix/HubAgency-v0.9.1-dialog-fix-debug.apk` |
| v1.0 | `builds/v1.0-opening-connections/HubAgency-v1.0-opening-connections-debug.apk` |
| v1.1 | `builds/v1.1-multi-room/HubAgency-v1.1-multi-room-debug.apk` |
| v1.2 | `builds/v1.2-room-composer/HubAgency-v1.2-room-composer-debug.apk` |

---

## Pending (rimandato a domani)

- **Visualizzazione nome stanza**: il nome salvato non appare da nessuna parte nell'UI (né durante la scan né nello storico). Va implementata una schermata/lista storico o almeno un feedback visivo post-salvataggio.
- **Planimetria completa post-composizione**: al termine della conferma nel composer non viene mostrata la planimetria multi-stanza finale. Da implementare come schermata di anteprima/export.

---

## Agenda prossima sessione — domande aperte

### 1. Plugin Capacitor distribuibile

La struttura `capacitor-plugin/` esiste già e `package.json` dichiara `capacitor-hubagency-spatial-scan`. Prima di procedere:

- **Distribuzione**: npm pubblico, npm privato/registry aziendale, o path locale (`file:../`)?
- **iOS**: il plugin deve supportare anche iOS in futuro, o per ora solo Android?
- **capacitor-test-app**: va tenuto come demo ufficiale del plugin o è usa-e-getta?

### 2. Migliorie UI

"Sono tante" — serve una lista in ordine di priorità. Esempi:
- schermata lista storico stanze
- il composer mostra la planimetria finale dopo conferma
- design coerente tra tutti i dialog (brand HubAgency)
- onboarding/tutorial primo avvio
- altro?

**→ Elenca le migliorie in ordine di priorità così le pianifichiamo.**

### 3. Mounting su HubAgency

- Dove si trova l'app HubAgency? GitHub repo separato?
- Che stack usa: React Native, Capacitor, web puro?
- Come si chiama il repo / come lo raggiungo?
