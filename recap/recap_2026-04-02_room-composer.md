# Recap 2026-04-02 — v1.2 Room Composer (composizione planimetrica multi-stanza)

## Stato

**Branch:** `feature/room-composer` → da mergere su main dopo smoke test
**APK:** `builds/v1.2-room-composer/HubAgency-v1.2-room-composer-debug.apk`
**Build:** SUCCESS (1 warning preesistente, non introdotto da questa feature)

---

## Obiettivo

Editor leggero per allineare due stanze scansionate e comporre una planimetria multi-stanza,
a partire dall'allineamento automatico calcolato sull'apertura di collegamento.

---

## File toccati

| File | Tipo |
|---|---|
| `RoomHistoryManager.kt` | +`saveRoomData()` (scrive `hub_room_{id}.json`), +`loadRoomData(id)` |
| `RoomComposition.kt` | NUOVO — data class + save/create (persiste `hub_composition_{id}.json`) |
| `RoomComposerView.kt` | NUOVO — custom Canvas View: Room A (blu), Room B (verde), aperture (arancione) |
| `RoomComposerActivity.kt` | NUOVO — Activity editor: canvas + controlli + conferma |
| `ScanningActivity.kt` | +`offerComposer()`, catena `doStopScan` → `offerComposer` → `showContinueScanDialog` |
| `AndroidManifest.xml` | +dichiarazione `RoomComposerActivity` |

**Motore di capture: invariato al 100%.**
`PerimeterCapture`, `handlePerimeterTap`, `handleOpeningTap`, `screenToWorld`,
`FloorPlaneAnchor`, flow aperture esistente → non toccati.

---

## Regola di allineamento iniziale

Data apertura OA su muro di Room A e apertura OB su muro di Room B (stesso kind e larghezza ±12cm):

1. **Rotazione θ**: orienta Room B in modo che la normale del muro OB punti in direzione opposta alla normale di OA
   ```
   θ = atan2(-nA.z, -nA.x) - atan2(nB.z, nB.x)
   ```
2. **Traslazione** (tx, tz): centro di OB ruotato coincide con centro di OA
   ```
   cB_rot = rotate(cB, θ)
   (tx, tz) = cA - cB_rot
   ```

---

## Controlli editor

| Pulsante | Effetto |
|---|---|
| ← → ↑ ↓ | Sposta Room B di ±5cm per tap |
| ↺1° ↻1° | Ruota Room B di ±1° per tap |
| ↺5° ↻5° | Ruota Room B di ±5° per tap |
| Snap 90° | Arrotonda la rotazione al multiplo di 90° più vicino |
| Annulla | Torna senza salvare |
| Conferma | Salva `hub_composition_{id}.json` e torna |

---

## Persistenza

- **`hub_room_{id}.json`** — JSON grezzo di `buildResult()` salvato automaticamente da `RoomHistoryManager.save()` per ogni stanza con nome assegnato
- **`hub_composition_{id}.json`** — `RoomComposition` con roomAId, roomBId, offsetX, offsetZ, rotationDeg, confirmedAt

---

## Rischi di regressione

| Rischio | Soluzione |
|---|---|
| `offerComposer` compare su scan singola | `linkedOpeningSpec == null` → onDone() diretto, nessun dialog |
| `hub_room_{id}.json` non disponibile | AlertDialog informativo + finish, nessun crash |
| Flow singola stanza invariato | Verificato: `linkedOpeningSpec` è null → `offerComposer` chiama `onDone()` immediatamente |

---

## Checklist smoke test

- [ ] Scan singola: nessun prompt "Componi planimetria", flow identico a prima
- [ ] Scan multi-stanza (con spec): dopo naming compare "Vuoi comporre la planimetria?"
- [ ] "Non ora" → `showContinueScanDialog` normale
- [ ] "Sì, componi" → `RoomComposerActivity` si apre
- [ ] Canvas mostra Room A (blu) e Room B (verde) correttamente allineate inizialmente
- [ ] Apertura di collegamento: dot arancione visibile su entrambe le stanze
- [ ] ← → ↑ ↓: Room B si sposta visivamente di 5cm per tap
- [ ] ↺/↻ 1° e 5°: Room B ruota visivamente
- [ ] Snap 90°: rotazione agganciata al multiplo di 90° più vicino
- [ ] Annulla → nessun file scritto, torna al flow
- [ ] Conferma → `hub_composition_{id}.json` creato in filesDir
- [ ] `hub_room_{id}.json` presente in filesDir dopo ogni scan con nome
