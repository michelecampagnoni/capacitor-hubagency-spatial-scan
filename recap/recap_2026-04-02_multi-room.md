# Recap 2026-04-02 — v1.1 Multi-room workflow

## Stato

**Branch:** `feature/multi-room-workflow` → da mergere su main dopo smoke test
**APK:** `builds/v1.1-multi-room/HubAgency-v1.1-multi-room-debug.apk`
**Build:** SUCCESS

---

## Obiettivo

Workflow guidato multi-stanza: al termine di ogni scan, offri la possibilità di
continuare con un altro ambiente e di riusare le misure di un'apertura di collegamento.

---

## File toccati

| File | Tipo |
|---|---|
| `LinkedOpeningSpec.kt` | NUOVO — dimensioni apertura da riusare, via Intent extras |
| `ScanningActivity.kt` | +import Intent, +3 campi spec, +lettura da Intent in onCreate, +banner in enterOpeningMode, +5 righe in spawnOpening, chain dialog in doStopScan, signature showNamingDialogAndSave, +5 nuovi metodi |

**Motore di capture: invariato al 100%.**
`PerimeterCapture.kt`, `PerimeterRenderer.kt`, `screenToWorld()`, `handlePerimeterTap()`,
`handleOpeningTap()`, `FloorPlaneAnchor.kt` → non toccati.

---

## Flow UX

```
[Scan completa]
    ↓
[Dialog nome stanza]
    ↓ (Salva o Salta)
[Dialog: "Vuoi scansionare un altro ambiente?"]
  → No  → finish normale
  → Sì  ↓
[Dialog: "L'ambiente si collega tramite un'apertura già definita?"]
  → No  → riavvio scan senza spec
  → Sì  ↓
[Lista porte/portafinestre della stanza appena scansionata]
  → selezione  → riavvio scan con LinkedOpeningSpec
  → "Senza collegamento"  → riavvio scan senza spec
    ↓
[Nuova ScanningActivity — enterOpeningMode]
[Banner: "Apertura collegata disponibile — Sì / No"]
  → Sì → prima apertura spawna con misure ereditate (editabile manualmente)
  → No → misure standard
```

---

## Come viene riusata l'apertura

`LinkedOpeningSpec` viaggia nell'Intent come extras individuali (no Parcelable).
In `spawnOpening()`, se `linkedSpecAccepted && !linkedSpecPlaced`:
- usa `spec.width / height / bottom` invece dei default del kind
- imposta `linkedSpecPlaced = true` → spec usata solo per il primo spawn
- l'apertura è comunque editabile manualmente tramite i controlli esistenti

---

## Rischi di regressione

| Rischio | Soluzione |
|---|---|
| onScanComplete emesso N volte | Comportamento atteso — una per stanza |
| Spec apertura più larga del muro | clampOpening() già esistente gestisce il caso |
| Intent senza spec (scan normale) | LinkedOpeningSpec.fromIntent() → null, tutto invariato |

---

## Checklist smoke test

- [ ] Scan senza spec: nessun banner in opening mode, flow identico a prima
- [ ] Fine scan → dialog nome → "Salta" → "No" a altro ambiente → finish normale
- [ ] "Sì" → "No collegamento" → nuova scan parte, no banner spec
- [ ] "Sì" → "Sì collegamento" → lista mostra solo porte/portafinestre
- [ ] Lista vuota (nessuna porta) → riavvio diretto senza spec
- [ ] Selezione apertura → nuova scan → banner "Apertura collegata disponibile"
- [ ] Banner "Sì" → spawn con dimensioni ereditate (width/height/bottom corretti)
- [ ] Banner "No" → spawn con dimensioni default del kind
- [ ] Apertura ereditata più larga del muro → clamping, nessun crash
- [ ] enableDepth preservato nel riavvio
- [ ] handleOpeningTap, capture engine, P0/P1, angolo snap: nessuna regressione
