# Recap 2026-04-02 — Milestone A: allineamento preview/confirm (snapFull fix)

## Stato

**Branch:** `feature/stable-points`
**Base commit:** `708058c` (tag: `v0.7-baseline-stable`)
**APK:** `builds/v0.7-stable-points/HubAgency-v0.7-milestoneA-debug.apk`
**Build:** SUCCESS
**Push main:** NO — in attesa di smoke test

---

## Problema risolto

### Divergenza strutturale preview/confirm

**Prima:**
- `livePreview()` → `snapAngleOnly()` → lunghezza = `rawLen` (distanza raw dal punto precedente)
- `addPoint()` → `snapFull()` → lunghezza = `roundTo(rawLen, 0.05f)` (quantizzata a 5cm)

Effetto: il segmento live mostrava ad es. 1.23m, il punto confermato finiva a 1.20m o 1.25m.
L'utente vedeva segmenti diversi da quelli salvati nel poligono finale. Contribuiva al percepito "trascinamento".

**Dopo:**
- `livePreview()` → `snapAngleOnly()` → lunghezza = `rawLen`
- `addPoint()` → `snapFull()` → lunghezza = `rawLen.coerceAtLeast(MIN_SEG_M)` ← **identica**

Preview e confirm ora producono la stessa lunghezza. Quello che vedi è quello che viene salvato.

---

## Cambio effettuato

**File:** `PerimeterCapture.kt` — **1 riga**

```kotlin
// Prima (riga 156):
val snapLen = roundTo(rawLen, LEN_QUANT_M).coerceAtLeast(LEN_QUANT_M)

// Dopo:
val snapLen = rawLen.coerceAtLeast(MIN_SEG_M)
```

**Nient'altro toccato.**

### Perché `coerceAtLeast(MIN_SEG_M)` e non `coerceAtLeast(LEN_QUANT_M)`:
`MIN_SEG_M = 0.15m` è il floor minimo già usato nel check `dist < MIN_SEG_M` in `addPoint()`.
`LEN_QUANT_M = 0.05m` era solo il gradino di quantizzazione, non ha senso come floor minimo in assenza di quantizzazione.
Coerenza: se il tap supera il check distanza minima (≥ 0.15m), il segmento salvato sarà almeno 0.15m.

---

## File toccati

| File | Cambio |
|---|---|
| `PerimeterCapture.kt` | 1 riga in `snapFull()` |

## File NON toccati

| File | Status |
|---|---|
| `ScanningActivity.kt` | NON toccato |
| `PerimeterRenderer.kt` | NON toccato |
| `FloorPlaneAnchor.kt` | NON toccato |
| `ReticleView.kt` | NON toccato |
| `screenToWorld()` | NON toccato |
| `handlePerimeterTap()` | NON toccato |

---

## Cosa rimane aperto (Milestone B — in standby)

Se il trascinamento persiste dopo questo smoke test, la causa residua è il **buffer lag di 267ms** in `ScanningActivity.kt`:
- `lastReticleWorld` = media degli ultimi 8 campioni ARCore
- Al tap, il punto viene piazzato dove era il reticolo ~267ms prima

Fix B pianificato: hit test fresco al tap con fallback a `lastReticleWorld`.
**Non applicato in questa milestone.**

---

## Checklist smoke test

- [ ] Traccia 4 punti in una stanza rettangolare nota
- [ ] Verifica che la lunghezza dei segmenti nel preview corrisponda ai segmenti confermati
- [ ] Verifica che i punti confermati rimangano fermi mentre cammini verso il punto successivo
- [ ] Verifica che l'export JSON mostri dimensioni plausibili e coerenti con la stanza reale
- [ ] Verifica che l'undo funzioni correttamente rimuovendo l'ultimo punto
