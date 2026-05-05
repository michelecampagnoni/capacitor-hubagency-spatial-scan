# Handoff — Continua da qui, Claude Code

Sei Claude Code sul Mac con Xcode 15. Stai ricevendo una cartella (`HubAgencyNative`) che contiene un plugin Capacitor per iOS/Android già parzialmente implementato. Il tuo compito è continuare l'implementazione iOS dal milestone M3 in poi.

**Prima di fare qualsiasi cosa: leggi tutto in questo ordine.**

---

## 1. Leggi il piano di produzione

```
docs/ios-production-plan.md
```

Contiene:
- Le regole operative (cosa non toccare, come portare il codice)
- La descrizione dettagliata di ogni milestone (M1→M6)
- Le invarianti assolute (algoritmi che non si possono modificare)
- I rischi tecnici di M3 con le soluzioni
- La sequenza di lavoro

---

## 2. Leggi il recap di ciò che è già stato fatto

```
recap/recap_2026-05-04_m1-m2-ios-skeleton-and-core.md
```

Contiene:
- Lista completa dei file Swift già creati (M1 + M2)
- Decisioni prese (es. `FloatArray` → `SIMD3<Float>`, `Codable` per i modelli)
- Cosa devi fare su questo Mac prima di M3 (pod lint, compilazione, test permessi)
- Note critiche per M3

---

## 3. Leggi tutti gli altri recap

```
recap/
```

Leggi tutti i file `.md` nella cartella recap. Capiscono la storia completa del progetto Android (v1.24 → v1.26), le scelte architetturali, lo stato dell'algoritmo GPC, il piano iOS originale.

---

## 4. Contesto rapido (per non leggere tutto subito)

**Cos'è il progetto:**
Plugin Capacitor chiamato `capacitor-hubagency-spatial-scan` che implementa un motore di scansione planimetrica AR (Guided Perimeter Capture — GPC). L'utente punta il telefono in giro per una stanza e costruisce il perimetro tap-by-tap. Il risultato è un poligono floor plan + export PDF A3 + export GLB 3D.

**Stato Android:** completo e stabile alla versione v1.26. **Non toccare niente** in `android/`, `src/` TypeScript, o `package.json` radice.

**Stato iOS:** M1 e M2 completati su altro Mac (senza Xcode). I file Swift sono in `capacitor-plugin/ios/Plugin/`. Nessuna riga è stata compilata ancora — questo Mac è il primo a farlo.

**Regola principale:** port 1:1 della logica matematica e dello stato. Adattamento nativo per lifecycle, UI, storage, rendering. Minime modifiche, massima parità algoritmica con Android.

---

## 5. Struttura cartella rilevante

```
HubAgencyNative/
├── docs/
│   ├── ios-production-plan.md       ← LEGGILO PER PRIMO
│   ├── ios-milestone-prompts.md     ← prompt dettagliati per ogni milestone
│   └── HANDOFF_ALTRO_MAC.md         ← questo file
│
├── recap/
│   ├── recap_2026-05-04_m1-m2-ios-skeleton-and-core.md  ← stato attuale iOS
│   └── ... (altri recap Android — leggili tutti)
│
├── capacitor-plugin/
│   ├── ios/Plugin/                  ← file Swift già creati (M1+M2)
│   │   ├── SpatialScanPlugin.swift
│   │   ├── SpatialScanPlugin.m
│   │   ├── SpatialScanManager.swift
│   │   ├── FloorPlaneAnchor.swift
│   │   ├── PerimeterCapture.swift
│   │   ├── RoomRectifier.swift
│   │   └── Models/
│   │       ├── OpeningModel.swift
│   │       ├── OpeningMetadata.swift
│   │       ├── LinkedOpeningSpec.swift
│   │       ├── RoomRecord.swift
│   │       └── RoomExportData.swift
│   ├── android/src/main/java/it/hubagency/spatialscan/
│   │   └── ... (sorgenti Kotlin — leggili per capire cosa portare in Swift)
│   ├── capacitor-hubagency-spatial-scan.podspec
│   └── package.json
│
└── capacitor-test-app/              ← app host per test
```

---

## 6. Cosa fare adesso, in ordine

### Passo 0 — Setup (prima di tutto)

```bash
# Installa CocoaPods se non presente
sudo gem install cocoapods

# Verifica il podspec
cd HubAgencyNative/capacitor-plugin
pod lib lint capacitor-hubagency-spatial-scan.podspec --allow-warnings
```

Se il lint fallisce: leggi l'errore, correggilo nel `.podspec` o nei file Swift, rilinta.

### Passo 1 — Aggiungi NSCameraUsageDescription

Nel file `capacitor-test-app/ios/App/App/Info.plist` aggiungi:

```xml
<key>NSCameraUsageDescription</key>
<string>HubAgency Spatial Scan usa la fotocamera per la scansione AR della stanza.</string>
```

### Passo 2 — Compila e testa M1+M2

Apri `capacitor-test-app/ios/App/App.xcworkspace` in Xcode 15 (o crea il workspace se non esiste: `cd capacitor-test-app/ios && pod install`).

Compila su iPhone fisico. Verifica:
- Build senza errori
- `isSupported()` ritorna `true`
- `requestPermissions()` mostra il dialog camera di sistema
- Camera permission flow completo (granted/denied)

**Se ci sono errori di compilazione nei file M1/M2:** correggili prima di procedere. Leggi i file Kotlin equivalenti in `android/src/main/java/` per capire l'intento originale.

### Passo 3 — M3: ScanningViewController + Rendering

Questo è il **critical path** (8-12h). Leggi:
- `docs/ios-production-plan.md` → sezione M3 (formule, rischi, invarianti)
- `docs/ios-milestone-prompts.md` → sezione M3 (prompt dettagliato)
- `capacitor-plugin/android/src/main/java/it/hubagency/spatialscan/ScanningActivity.kt` (tutto)
- `capacitor-plugin/android/src/main/java/it/hubagency/spatialscan/PerimeterRenderer.kt`
- `capacitor-plugin/android/src/main/java/it/hubagency/spatialscan/OpeningRenderer.kt`

Da implementare:
- `ScanningViewController.swift` — ARSCNView + GLKView overlay, ARSessionDelegate, tap gesture, auto-switch FLOOR/TOP
- `PerimeterRenderer.swift` — GLES 2.0, goniometro ±65°, isteresi 0.20/0.10
- `OpeningRenderer.swift` — highlight ciano, fill fucsia, GL_LINE_STRIP

Le formule `screenToWorldFloorPlane` e `screenToWorldTopPlane` sono nel piano — non inventare, usa quelle esatte.

### Passo 4 — M4, M5, M6

Dopo M3 verificato su device fisico, procedi con M4 (export), M5 (multi-room), M6 (parità).
I prompt dettagliati sono in `docs/ios-milestone-prompts.md`.

---

## 7. Regole che non si cambiano mai

1. **Non modificare Android.** Nessuna modifica a `android/`, Kotlin, risorse Android.
2. **Non modificare l'API TypeScript pubblica.** Le firme di `startScan`, `stopScan`, `exportPdf`, `isSupported`, `requestPermissions` rimangono identiche.
3. **Modifiche a package/config solo se necessarie per iOS**, in commit separato.
4. **Invarianti algoritmiche** (dal piano):
   - Floor lock: 30 campioni, varianza < 0.0005, EMA α=0.002
   - Goniometro: settore ±65°, isteresi 0.20/0.10
   - RoomRectifier: SNAP_HARD_DEG=5°, MIN_EDGE_M=0.20m
   - screenToWorldFloorPlane/TopPlane: usa le formule esatte del piano
5. **Un recap per milestone** salvato in `recap/` con nome `recap_[data]_m[n]-ios-[nome].md`.

---

## 8. Se hai dubbi su un algoritmo

Leggi il file Kotlin equivalente in `capacitor-plugin/android/src/main/java/it/hubagency/spatialscan/`. Il codice Android è la fonte di verità. Il tuo lavoro è tradurre la logica in Swift, non reimmaginarla.

---

Buon lavoro. Inizia dal Passo 0.
