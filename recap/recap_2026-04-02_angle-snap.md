# Recap 2026-04-02 — v0.8 angle snap 90°/45° post-scan

## Stato

**Branch:** `feature/angle-snap` → da mergere su main dopo smoke test
**APK:** `builds/v0.8-angle-snap/HubAgency-v0.8-angle-snap-debug.apk`
**Build:** SUCCESS

---

## Cosa è stato fatto

### Obiettivo

Correggere automaticamente gli angoli quasi-ortogonali o quasi-45° nell'output finale
(planimetria, GLB, JSON), senza toccare la cattura AR in tempo reale.

### Algoritmo (RoomRectifier)

1. **Dominant axis**: direzione del lato più lungo del poligono (misura più affidabile)
2. **8 candidati snap**: dominantAxis + k × 45° per k = 0..7
   → coprono 0°, 45°, 90°, 135°, 180°, 225°, 270°, 315° relativi all'asse dominante
3. **Per ogni lato**:
   - lunghezza < 0.20m → SKIP (lato corto, angolo inaffidabile)
   - deviation dal candidato più vicino ≤ 5° → SNAP (rumore di misura)
   - deviation > 5° → KEEP (geometria reale o irregolarità intenzionale)
4. **Ricostruzione greedy** da v[0]: `v[i+1] = v[i] + cos(snapped[i]) * len[i]`
5. **Distribuzione errore di chiusura** linearmente su v[0]..v[n-1]

### Esempi pratici

| Angolo misurato | Deviation | Azione |
|---|---|---|
| 88° | 2° da 90° | SNAP → 90° |
| 47° | 2° da 45° | SNAP → 45° |
| 93° | 3° da 90° | SNAP → 90° |
| 80° | 10° da 90° | KEEP → 80° |
| 55° | 10° da 45°/90° | KEEP → 55° |

---

## File toccati

| File | Modifica |
|---|---|
| `RoomRectifier.kt` | `nearestAxisAngle()`: Array(4)+π/2 → Array(8)+π/4 |
| `ScanningActivity.kt` | +1 riga in `buildRoomModel()`, +2 righe in `buildResult()` |

**Tutto il path AR di capture: invariato al 100%.**
Milestone A (snapFull) e Milestone B (hit test fresco) preservati intatti.

---

## Dove entra la rettifica

- **`buildRoomModel()`** — chiamata da `enterOpeningMode()`: le aperture vengono
  posizionate su muri già rettificati
- **`buildResult()`** — export finale: JSON `floor.vertices`, planimetria (FloorPlanExporter),
  GLB (GlbExporter) usano tutti il poligono rettificato

La rettifica NON entra mai durante la scan live.

---

## Checklist smoke test

- [ ] Stanza rettangolare: angoli nell'export ≈ 90° anche se misurati a 87°-93°
- [ ] Stanza con parete diagonale reale (>5° da 45°/90°): angolo preservato invariato
- [ ] Preview AR durante capture: nessuna variazione rispetto a prima
- [ ] Export JSON `floor.vertices` coerente con `walls` start/end points
- [ ] Logcat `RoomRectifier`: log "snapped N / M edges" presenti e sensati
