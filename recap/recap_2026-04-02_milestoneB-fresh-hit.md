# Recap 2026-04-02 — Milestone B: hit test fresco al tap

## Stato

**Branch:** `feature/stable-points`
**APK:** `builds/v0.7-stable-points/HubAgency-v0.7-milestoneB-debug.apk`
**Build:** SUCCESS
**Push main:** NO — in attesa di smoke test

---

## Cosa è stato fatto

### Problema affrontato

`handlePerimeterTap()` leggeva `lastReticleWorld`, che è la media mobile degli ultimi 8 campioni
ARCore (~267ms a 30fps). In movimento, il punto veniva piazzato dove si trovava il reticolo
~267ms prima del tap, non dove puntava il telefono nel momento esatto della pressione.

### Fix applicato

Al momento del tap, si tenta un **hit test fresco** usando l'ultimo frame ARCore disponibile
(`lastArFrame` / `lastArCamera`). Se il hit test restituisce un risultato valido, quel valore
istantaneo viene usato direttamente. Se fallisce (pavimento non visibile, hit test non valido),
si ricade su `lastReticleWorld` — comportamento identico a quello precedente.

**Logica precisa:**
```
TAP
 ↓
fresh hit test su lastArFrame al centro schermo (forceFloor=true → Y = lastFloorY)
 ↓
successo?  → usa il valore fresco (zero lag)
fallback?  → usa lastReticleWorld (media 8 campioni, comportamento precedente)
 ↓
perimeterCapture.addPoint(x, y, z)
```

`lastReticleWorld` e il buffer di smoothing **non sono stati toccati** — continuano a
esistere e funzionare per il preview live. La loro utilità è invariata.

---

## File toccati

**Solo `ScanningActivity.kt`** — 3 punti:

| Modifica | Righe | Descrizione |
|---|---|---|
| +2 campi `@Volatile` | dopo riga 136 | `lastArFrame: Frame?` e `lastArCamera: Camera?` |
| +1 riga in `onDrawFrame()` | dopo `val camera = frame.camera` | `lastArFrame = frame; lastArCamera = camera` |
| +4 righe in `handlePerimeterTap()` | branch else | hit fresco con fallback |

**File NON toccati:** `PerimeterCapture.kt`, `PerimeterRenderer.kt`, `FloorPlaneAnchor.kt`,
`ReticleView.kt`, `screenToWorld()` (logica interna), `onDrawFrame()` (logica esistente).

---

## Cosa migliora (atteso)

### Su P2, P3, … (punti successivi al primo)

- Il punto confermato corrisponde alla posizione **istantanea** del reticolo al tap, non a
  quella di 267ms prima. L'effetto "hai confermato dove eri un attimo fa" dovrebbe ridursi
  o sparire nei casi in cui il telefono era in movimento al momento del tap.
- In combinazione con Milestone A (niente quantizzazione lunghezza), il punto confermato
  dovrebbe corrispondere esattamente a quello che il preview mostrava nell'ultimo frame.

### Su P0 (primo punto)

- P0 beneficia dello stesso hit test fresco: il valore salvato sarà la posizione ARCore
  più recente al momento del tap, senza il lag del buffer.
- **Attenzione**: questo migliora la *precisione di piazzamento* di P0, ma NON il
  comportamento visivo di P0 nella fase successiva (AWAIT_HEIGHT).

---

## Cosa NON è atteso che migliori

### Oscillazione visiva di P0 durante AWAIT_HEIGHT

Dopo che P0 è confermato, la app entra in AWAIT_HEIGHT. L'utente punta verso il soffitto.
In questa fase:
- Il floor plane hit test fallisce (pavimento fuori dal campo visivo)
- ARCore ha meno feature texture visibili (soffitto spesso uniforme)
- La `viewMatrix` che proietta tutti i punti world→schermo diventa meno stabile
- P0 è **correttamente salvato** in world space e non cambia mai, ma la sua posizione
  *sullo schermo* oscilla perché oscilla la viewMatrix

Questo effetto è indipendente da come il punto è stato piazzato. Fix B non lo tocca.

### Drift post-relocalization

Se ARCore fa una relocalization del coordinate system mentre l'utente si muove
(es. torna a vedere una zona già vista da una angolazione diversa), tutti i punti
già confermati possono apparire temporaneamente in posizione leggermente diversa.
Questo è un comportamento ARCore, non un bug del codice.

### Inconsistenza "alcuni si trascinano, altri no"

Se dopo Fix B alcuni punti sono ancora instabili e altri no, la causa residua è
quasi certamente la qualità del tracking ARCore nell'ambiente specifico (luce, texture
superfici, movimento camera) — non il lag del tap.

---

## Come interpretare lo smoke test

### Scenario A — il trascinamento sparisce completamente
→ La causa dominante era il lag del buffer. Milestone A + B hanno risolto.
→ Procedere con merge su main.

### Scenario B — il trascinamento migliora ma non sparisce
→ Fix B ha contribuito ma c'è una componente residua.
→ Analizzare: i punti instabili residui coincidono con situazioni di tracking difficile
  (soffitto, zone senza texture, movimento rapido)? Se sì, la residua è drift ARCore.
→ Decidere se accettare come limite noto o investigare anchor approach.

### Scenario C — nessuna differenza percepibile rispetto a Milestone A
→ Il lag del buffer non era la causa dominante nella sessione corrente.
→ La causa principale è il drift tracking (Scenario B confermato).
→ Non fare ulteriori fix code-side senza dati nuovi.

### Discriminante per P0 specificamente
- **Vedi P0 ballare DURANTE AWAIT_HEIGHT** (mentre punti il soffitto)?
  → Drift viewMatrix, non risolvibile con questo approccio.
- **P0 appare in posizione sbagliata DOPO essere tornato a guardare il pavimento**?
  → Potrebbe essere relocalization. Diagnostica ulteriore necessaria.
- **P0 è ora piazzato nel punto corretto al tap ma poi appare fermo**?
  → Fix B ha funzionato su P0. Il problema precedente era il buffer lag.
