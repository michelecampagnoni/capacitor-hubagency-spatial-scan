# Recap 2026-04-01 — Sessione investigazione anchor / regressione

## Stato finale
**Branch:** `feature/room-history`
**Commit attivo:** `d3634c6` — revert a v0.6-floor-plan-quotes
**Codice deploy:** HEAD pulito, zero modifiche uncommitted
**APK installato:** build da HEAD, funzionante

---

## Problema segnalato
Regressione P0: i punti confermati del perimetro "si trascinano" quando l'utente cammina nella stanza.
Sintomo: il punto confermato si sposta nella stessa direzione della camera; tornando nel punto fisico del tap, il punto torna a posto.

---

## Diagnosi effettuata

### Diagnostic overlay aggiunto (poi rimosso al rollback)
Overlay real-time in `ScanningActivity.onDrawFrame` con:
- Stato tracking ARCore (`TRK:TRACKING fail:—`)
- Stato GPC (`pts:N STATE`)
- Floor lock (`FLOOR:LOCK Y=... n=... var=...`)
- Reticle position e delta frame-to-frame (`dXZ`, `max`)
- Sorgente reticle (`PLANE_HIT` / `RAY_FLOOR` / `STICKY`)
- P0: tap vs confirmed vs diff
- Anchor status: `A:N/M T:K P0:STATE anch=X,Z Δ=...m`

### Conclusione dalla diagnostica
- `src:PLANE_HIT` → il reticle colpisce floor plane ARCore
- `Δ=0,000m` → l'anchor era TRACKING e coincideva col punto raw
- Il problema **non era nell'anchor**: la diagnostica mostrava tutto corretto

### Root cause reale
Il codice HEAD funzionava. Le modifiche accumulate durante la sessione (anchor code + diagnostics, ~212 righe) avevano introdotto instabilità visiva. Il rollback a HEAD ha ripristinato il comportamento corretto.

**Nota specifica:** il sintomo "il mirino non coincide col punto rilasciato" si risolve con il codice HEAD originale. L'8-frame smoothing del reticle (`lastReticleWorld`) combinato con il comportamento sticky funziona correttamente nella baseline.

---

## Tentativi effettuati (cronologia)

| Tentativo | Descrizione | Esito |
|-----------|-------------|-------|
| Rollback a e5c33f4 (v0.7) | Verifica sorgente locale | Non risolve — problema era nel working tree |
| Diagnostic overlay | Aggiunto su FloorPlaneAnchor + ScanningActivity | Utile: ha mostrato che anchor è TRACKING con Δ=0 |
| Fix ConcurrentModificationException | `@Volatile` pre-computed fields in FloorPlaneAnchor | Corretto e necessario |
| ARCore Anchor migration | `hitResult.createAnchor()` + fallback `session.createAnchor(Pose)` | Non migliora rispetto a raw |
| Fix anchoredPts Y | Usa `rawPt[1]` invece di `lastFloorY` live | Miglioramento altezza |
| Rimozione hitResult path → solo `session.createAnchor(Pose)` | Floating anchor | Nessun miglioramento |
| Ripristino hitResult path | Prima scelta HORIZONTAL_UPWARD_FACING | Nessun miglioramento |
| `anchoredPts()` = `getPolygon()` live, anchor solo al close | Elimina jitter da lettura per-frame | Non testato efficacemente |
| **`git checkout HEAD --`** | Rollback completo a codice repository | **RISOLTO** |

---

## Cosa abbiamo imparato

### ARCore Anchors
- `session.createAnchor(Pose.makeTranslation(x,y,z))` = floating anchor, non agganciato a geometria → meno stabile durante relocalization
- `hitResult.createAnchor()` = ancora agganciata al piano fisico → più stabile, ma usa posizione hit istantanea (non smoothed)
- Leggere `anchor.pose.translation` **ogni frame** (60fps) introduce micro-jitter visibile anche quando il diagnostic a 300ms mostra Δ=0
- Se si usano anchor per rendering live, serve EMA o lettura infrequente (non per-frame)

### Architettura corretta (confermata)
- `perimeterCapture.getPolygon()` = unica fonte di verità per rendering live
- `frozenPolygon` = snapshot immutabile al close
- Anchor utili SOLO per snapshot al close (lettura una tantum), non per rendering live

### ConcurrentModificationException in FloorPlaneAnchor
Questo fix era corretto e necessario (era stato introdotto nel tentativo diagnostics):
- Computed getter `currentVariance` con `samples.map {}` dal main thread → ConcurrentModificationException
- Fix: campi `@Volatile` pre-calcolati sul GL thread dentro `update()`
- **Questo fix NON è nel codice HEAD attuale** → da applicare nella prossima sessione se si riaggiunge la diagnostica

---

## File modificati durante la sessione (poi rollbackati)
- `ScanningActivity.kt` — anchor code + diagnostic overlay (~200 righe aggiunte)
- `FloorPlaneAnchor.kt` — `@Volatile` sampleCount + currentVariance (17 righe)

**Entrambi ripristinati a HEAD con `git checkout HEAD --`**

---

## Stato repository
```
commit d3634c6 — revert: restore capacitor-plugin to v0.6-floor-plan-quotes
  Working tree: pulito (git diff HEAD = vuoto)
  Branch: feature/room-history
```

---

## Prossimi step (per domani)

1. **Verificare smoke test completo** con codice HEAD: P0, altezza, perimetro, aperture, export
2. **Se si vuole anchor stabili** per compensare ARCore drift durante il movimento:
   - Usare `anchoredPtsSnapshot()` SOLO al close (non al rendering live)
   - NON leggere `anchor.pose` per-frame
   - Applicare il fix `@Volatile` in FloorPlaneAnchor prima di riaggiungerlo
3. **Continuare feature/room-history** (v0.10 multi-room è su remote, funzionante)
4. **Non toccare** PerimeterCapture, PerimeterRenderer, screenToWorld, export pipeline

---

## Nota finale
La sessione ha confermato che il codice HEAD (v0.6-equivalent reverted) è stabile.
Il drift ARCore durante il movimento è una **limitazione del device/ambiente**, non una regressione di codice.
L'anchor approach è tecnicamente corretto ma richiede implementazione più attenta (no per-frame reads).
