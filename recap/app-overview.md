# HubAgency Spatial Scan — Descrizione App

## Cos'è
App mobile Android per il **rilievo planimetrico automatico di ambienti interni** tramite AR (Augmented Reality). Utilizza ARCore di Google per mappare lo spazio fisico in tempo reale attraverso la fotocamera, producendo una planimetria quotata con misure reali.

## A cosa serve
Permette a un operatore (tecnico, architetto, agente immobiliare) di rilevare le dimensioni di un ambiente semplicemente camminando lungo i muri con lo smartphone. Il risultato è una planimetria in formato PNG e un modello 3D in formato GLB, esportabili e integrabili in altri sistemi.

---

## Flusso di utilizzo

### 1. Home page
L'utente apre l'app e vede la schermata principale con il tasto **"Avvia Scansione"**. Se la permesso fotocamera non è ancora stato concesso, viene richiesto automaticamente.

### 2. Inizializzazione AR
L'app avvia l'activity AR con ARCore. Il sistema inizia a mappare il pavimento e rileva la geometria dell'ambiente tramite il feed della fotocamera. Un mirino centrale indica il punto di mira.

### 3. Posizionamento angolo iniziale
L'operatore si posiziona in un angolo della stanza e punta la telecamera verso la base del muro. Preme **✓ (Conferma Angolo)** per registrare il primo punto. L'app guida l'utente con testi contestuali in overlay sulla camera.

### 4. Scansione perimetro
L'operatore percorre i muri della stanza, confermando ogni angolo con il tasto **✓**. L'app traccia in tempo reale il perimetro rilevato con linee colorate sul feed AR. Il tasto **↩** permette di annullare l'ultimo punto o tornare indietro.

### 5. Chiusura poligono
Quando tutti gli angoli sono stati registrati, l'operatore chiude il poligono. L'app mostra un'anteprima della planimetria rilevata.

### 6. Impostazione altezza
L'operatore imposta l'altezza reale delle pareti tramite i tasti **↑ ↓** (frecce su/giù). Il valore è visualizzato in tempo reale al centro dello schermo. Premendo **✓** si conferma l'altezza.

### 7. Aperture (porte e finestre)
L'operatore punta un muro per selezionarlo, poi sceglie il tipo di apertura (Porta / Finestra / Portafinestra). Per ogni apertura può regolare:
- **Sposta** ← → : posizione laterale sul muro
- **Larghezza** ← → : larghezza dell'apertura
- **Altezza** ↓ ↑ : altezza dell'apertura
- **Quota** ↓ ↑ : altezza dal pavimento (solo finestre)

Ogni apertura viene confermata con **✓** o eliminata con **🗑**.

### 8. Esportazione
Completato il rilievo, l'operatore preme **Esporta**. L'app genera:
- **PNG** — planimetria quotata con misure di tutte le pareti, titolo "Ultimo Rilievo", scala grafica
- **GLB** — modello 3D dell'ambiente con aperture

### 9. Risultato in home
La planimetria generata appare nella sezione **"Ultimo Rilievo"** della home page, pronta per essere condivisa o inviata al sistema gestionale.

---

## Architettura tecnica

| Layer | Tecnologia |
|---|---|
| App container | Capacitor (bridge web ↔ native Android) |
| Frontend (home page) | HTML/CSS/JS vanilla via Vite |
| Plugin nativo | Kotlin — `capacitor-hubagency-spatial-scan` |
| AR engine | Google ARCore 1.44 |
| Rendering overlay | OpenGL ES 2.0 (GLSurfaceView) |
| Esportazione planimetria | Canvas Android (bitmap PNG) |
| Esportazione 3D | GLB custom exporter |

## Struttura repository

```
HubAgencyNative/
├── capacitor-plugin/        # Plugin Capacitor (libreria Kotlin)
│   └── android/src/         # ScanningActivity, PerimeterRenderer, FloorPlanExporter...
├── capacitor-test-app/      # App Capacitor (container web + Android)
│   ├── src/                 # Frontend HTML/JS
│   └── android/             # Progetto Android buildabile
├── android/                 # Copia sync del plugin (legacy)
├── builds/                  # APK debug per ogni versione
└── recap/                   # Note di sessione e documentazione
```
