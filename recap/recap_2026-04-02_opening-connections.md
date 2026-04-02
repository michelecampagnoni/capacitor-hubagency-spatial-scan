# Recap 2026-04-02 — v1.0 Opening Connections (metadati collegamento aperture)

## Stato

**Branch:** `feature/opening-connections` → da mergere su main dopo smoke test
**APK:** `builds/v1.0-opening-connections/HubAgency-v1.0-opening-connections-debug.apk`
**Build:** SUCCESS

---

## Obiettivo

Permettere all'utente di classificare ogni porta/portafinestra come:
- esterna / non collegare
- interna — collegherò dopo
- interna — collega subito a una stanza esistente in history

## File toccati

| File | Tipo |
|---|---|
| `OpeningMetadata.kt` | NUOVO |
| `RoomRecord.kt` | +campo `openings: List<OpeningMetadata>` |
| `RoomHistoryManager.kt` | +estrazione metadati da JSON in `buildRecord()` |
| `ScanningActivity.kt` | +map field, modificata `confirmOpening()`, +2 metodi dialog, +serializzazione in `wallModelToObj()` |

**Motore di capture: invariato al 100%.**
`PerimeterCapture.kt`, `PerimeterRenderer.kt`, `screenToWorld()`, `handlePerimeterTap()`,
`FloorPlaneAnchor.kt`, `OpeningModel.kt` → non toccati.

---

## Nuovi dati

### `OpeningMetadata`
```
openingId       String   — == OpeningModel.id (chiave di join)
wallId          String   — == OpeningModel.wallId
isInternal      Boolean  — true = porta verso altro ambiente
linkedRoomId    String?  — UUID di RoomRecord.id (null = non risolto)
connectionLabel String?  — nome stanza collegata (null = non risolto)
```

---

## UX flow

1. Utente conferma una **Porta** o **Portafinestra** → `confirmOpening()` cattura la reference
2. Panel apertura si chiude normalmente (comportamento identico a prima)
3. Sul main thread: dialog "Tipo apertura" con RadioGroup:
   - `Esterna / non collegare`
   - `Interna — collegherò dopo`
   - `Interna — collega ora` *(solo se history non vuota)*
4. Se sceglie "Collega ora" → secondo dialog con lista stanze (nome + id)
5. Premere **Salta** su qualsiasi dialog → apertura salvata senza metadati

**Le finestre non mostrano il dialog** (non ha senso collegarle).

---

## Impatto export/storage

- **JSON `buildResult()`**: ogni `walls[i].openings[j]` guadagna i campi
  `isInternal`, `linkedRoomId?`, `connectionLabel?` se l'apertura è stata classificata.
  Aperture non classificate: JSON identico a prima (nessun campo aggiunto).
- **`hub_rooms.json`**: `RoomRecord.openings` lista serializzata. Record vecchi senza
  `openings` letti senza errori (`optJSONArray` → default `[]`).
- **GLB / PNG**: invariati.

---

## Rischi di regressione

| Rischio | Soluzione adottata |
|---|---|
| Dialog su GL thread | Dialog aperto solo in `mainHandler.post`, mai nel GL thread |
| Dialog blocca flow | Pulsante "Salta" sempre presente; `setCancelable(false)` impedisce solo back/tap esterno |
| Record vecchi rotti | `optJSONArray("openings")` con default `[]` in `fromJson()` |
| Apertura rimossa dopo classificazione | Metadati in `openingMetadataMap` rimangono (orfani) ma non entrano nell'export se l'opening è stata cancellata — nessun effetto visibile |

---

## Checklist smoke test

- [ ] Confermare porta → dialog "Tipo apertura" compare dopo chiusura panel
- [ ] Confermare finestra → nessun dialog
- [ ] Scegliere "Esterna" → export JSON `isInternal=false`, nessun `linkedRoomId`
- [ ] Scegliere "Interna — collegherò dopo" → `isInternal=true`, nessun `linkedRoomId`
- [ ] Scegliere "Interna — collega ora" con stanze in history → `linkedRoomId` e `connectionLabel` presenti
- [ ] Scegliere "Collega ora" e poi "Salta" nel picker → `isInternal=true`, `linkedRoomId=null`
- [ ] Premere "Salta" sul dialog principale → apertura senza metadati nell'export
- [ ] History vuota → opzione "collega ora" non compare nel primo dialog
- [ ] Record vecchi (senza `openings`) caricati senza crash
- [ ] Capture engine, P0/P1, angolo snap, planimetria: nessuna regressione
