# Recap 2026-04-02 — v0.9 Room History (naming dialog + persistent storage)

## Stato

**Branch:** `feature/room-history-v2` → da mergere su main dopo smoke test
**APK:** `builds/v0.9-room-history/HubAgency-v0.9-room-history-debug.apk`
**Build:** SUCCESS (1 warning: `Condition is always 'false'` in ScanningActivity.kt:1372 — preesistente, non introdotto da questa feature)

---

## Cosa è stato fatto

### Obiettivo

Al termine di ogni scansione, mostrare un dialog per assegnare un nome alla stanza
e salvare i metadati in un file JSON persistente in `filesDir`.

### Nuovi file

#### `RoomRecord.kt`
Data class immutabile serializzata come JSONObject:

| Campo | Tipo | Fonte |
|---|---|---|
| `id` | String (UUID) | generato al salvataggio |
| `name` | String | input utente |
| `timestamp` | Long (ms epoch) | `System.currentTimeMillis()` |
| `area` | Double (m²) | `roomDimensions.area` oppure `floor.area` |
| `height` | Double (m) | `roomDimensions.height` |
| `wallCount` | Int | `walls.length()` |
| `openingCount` | Int | somma `wall.openings.length()` su tutti i muri |
| `floorPlanPath` | String? | path assoluto PNG in cacheDir |
| `glbPath` | String? | path assoluto GLB in cacheDir |

#### `RoomHistoryManager.kt`
Singleton. Storage: `filesDir/hub_rooms.json` (JSONArray di RoomRecord).
Nessuna dipendenza esterna — solo `org.json` (incluso nell'Android SDK).

API pubblica:
- `save(context, result, name): RoomRecord?` — prepend in cima (più recente prima)
- `loadAll(context): List<RoomRecord>`
- `delete(context, id: String)`

### File modificati

#### `ScanningActivity.kt` — 2 interventi

1. **`doStopScan()`** — il blocco `mainHandler.post { ... }` è ora wrappato da
   `showNamingDialogAndSave(result) { ... }` così l'invocazione del callback
   avviene solo dopo che l'utente ha interagito col dialog.

2. **Nuovo metodo `showNamingDialogAndSave(result, onDone)`**:
   - Se `result.success == false` → chiama direttamente `onDone()` (no dialog)
   - Altrimenti mostra `AlertDialog` con `EditText` (hint: "es. salotto, cucina, corridoio")
   - **Salva** → chiama `RoomHistoryManager.save()`, poi `onDone()`
   - **Salta** → chiama solo `onDone()` (scan salvata normalmente, senza nome in history)
   - `setCancelable(false)` — impedisce chiusura accidentale con back/tap esterno

---

## File toccati

| File | Modifica |
|---|---|
| `RoomRecord.kt` | NUOVO |
| `RoomHistoryManager.kt` | NUOVO |
| `ScanningActivity.kt` | +import AlertDialog, doStopScan() wrappato, +showNamingDialogAndSave() |

**Capture engine: invariato al 100%.**
Milestone A (snapFull), Milestone B (fresh hit test), v0.8 (angle snap) preservati intatti.

---

## Checklist smoke test

- [ ] Al termine scan → dialog compare con titolo "Nome stanza" e campo testo vuoto
- [ ] Digitare nome + "Salva" → record salvato in `filesDir/hub_rooms.json`
- [ ] Premere "Salta" → scan completa normalmente, nessun record salvato
- [ ] Scan fallita (success=false) → nessun dialog, flow normale
- [ ] Campo vuoto + "Salva" → nome fallback "Stanza" salvato correttamente
- [ ] `RoomHistoryManager.loadAll()` restituisce i record in ordine più recente prima
- [ ] Area, altezza, wallCount, openingCount, floorPlanPath, glbPath presenti nel JSON
