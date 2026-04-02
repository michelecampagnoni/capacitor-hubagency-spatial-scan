# Recap 2026-04-02 — Milestone stable-points (finale)

## Stato finale

**Branch:** `feature/stable-points` → merge su `main`
**Tag baseline:** `v0.7-baseline-stable` (commit `708058c`) — rollback garantito
**APK milestone:** `builds/v0.7-stable-points/HubAgency-v0.7-milestoneB-debug.apk`
**Build:** SUCCESS

---

## Cosa è stato fatto

### Milestone A — Allineamento preview/confirm (`PerimeterCapture.kt`)

**Problema:** `livePreview()` usava lunghezza raw, `addPoint()` quantizzava a 5cm.
Il segmento live mostrava ad es. 1.23m, il punto confermato finiva a 1.20m.
Divergenza strutturale reale, non solo visiva.

**Fix:** rimossa quantizzazione da `snapFull()`. Una riga cambiata.
```kotlin
// Prima:
val snapLen = roundTo(rawLen, LEN_QUANT_M).coerceAtLeast(LEN_QUANT_M)
// Dopo:
val snapLen = rawLen.coerceAtLeast(MIN_SEG_M)
```

### Milestone B — Hit test fresco al tap (`ScanningActivity.kt`)

**Problema:** `handlePerimeterTap()` leggeva `lastReticleWorld` = media 8 campioni (~267ms lag).
In movimento al tap, il punto veniva piazzato dove eri 267ms prima.

**Fix:** al tap si esegue un hit test fresco su `lastArFrame`/`lastArCamera`.
Se il hit fresco ha successo → punto istantaneo. Se fallisce → fallback a `lastReticleWorld`.
11 righe aggiunte in ScanningActivity.kt. Buffer e preview invariati.

---

## Root cause del problema residuo (documentata, non fixata per scelta)

Dopo A+B, il trascinamento residuo è causato da **ARCore world coordinate drift**.

I punti confermati sono immutabili in world space. La `viewMatrix` (da `camera.getViewMatrix()`)
cambia ogni frame. Quando ARCore fa una piccola relocalization durante il movimento (zone povere
di texture, pavimenti lisci, soffitti uniformi), il sistema di coordinate world si sposta
rispetto alla realtà fisica. Tutti i punti confermati appaiono traslati sullo schermo.

**Discriminante trovata dall'utente:** quando i wall overlay violet coincidono con le pareti
reali → ARCore tracking accurato → punti stabili. Quando non coincidono → drift in corso.

**Soluzione architetturale corretta (non implementata):** ARCore Anchors per-punto con lettura
infrequente (~333ms) per compensare drift senza micro-jitter a 60fps.

**Decisione:** non implementato. Il comportamento attuale è accettabile. L'utente deve capire
di muoversi mantenendo le pareti visibili e di aspettare che i wall overlay siano allineati
prima di confermare i punti.

---

## Come usare l'app correttamente (UX — da documentare)

1. **Aspetta il floor lock** (reticolo cyan = tracking attivo)
2. **Vai in un angolo fisico** — appoggia il telefono alla parete se possibile
3. **Aspetta che il wall overlay viola coincida con la parete reale** — solo allora tocca per P0
4. **Per ogni punto successivo:** cammina lentamente lungo la parete, mantieni le pareti
   visibili nella camera, aspetta che l'overlay si stabilizzi prima di toccare
5. **Le frecce viola sul pavimento** mostrano i due assi del segmento precedente — seguirle
   riduce il drift perché porta a stare vicino a superfici ben-texturizzate

---

## File toccati in questa sessione

| File | Modifica |
|---|---|
| `PerimeterCapture.kt` | 1 riga — `snapFull()` senza quantizzazione |
| `ScanningActivity.kt` | +2 campi, +1 riga `onDrawFrame`, +4 righe `handlePerimeterTap` |

## File NON toccati

`PerimeterRenderer.kt`, `FloorPlaneAnchor.kt`, `ReticleView.kt`, `screenToWorld()`,
`onDrawFrame()` (logica esistente), `PerimeterCapture` (state machine, close, getPolygon).

---

## Builds salvati

```
builds/v0.7-stable-points/
├── HubAgency-v0.7-milestoneA-debug.apk   ← solo snapFull fix
└── HubAgency-v0.7-milestoneB-debug.apk   ← snapFull + hit test fresco (versione finale)
```

## Rollback

```bash
git checkout v0.7-baseline-stable   # torna alla baseline pre-sessione
```
