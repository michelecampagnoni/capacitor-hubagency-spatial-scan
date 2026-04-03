import { Capacitor } from '@capacitor/core';
import { SplashScreen } from '@capacitor/splash-screen';
import { SpatialScan } from 'capacitor-hubagency-spatial-scan';

// riferimento al shadow root, impostato al connectedCallback
let _shadow = null;

function log(msg, isError = false) {
  console.log('[SpatialScanTest]', msg);
  if (!_shadow) return;
  const el = _shadow.getElementById('log');
  if (!el) return;
  const line = document.createElement('div');
  line.style.cssText = `padding:4px 0; border-bottom:1px solid #eee; color:${isError ? '#c00' : '#111'}; font-size:0.85em; word-break:break-all;`;
  line.textContent = `[${new Date().toLocaleTimeString()}] ${msg}`;
  el.prepend(line);
}

async function runIsSupported() {
  log('→ isSupported() chiamato');
  try {
    const result = await SpatialScan.isSupported();
    log(`← isSupported() = ${JSON.stringify(result)}`);
  } catch (e) {
    log(`← isSupported() ERRORE: ${e.message}`, true);
  }
}

async function runRequestPermissions() {
  log('→ requestPermissions() chiamato');
  try {
    const result = await SpatialScan.requestPermissions();
    log(`← requestPermissions() = ${JSON.stringify(result)}`);
  } catch (e) {
    log(`← requestPermissions() ERRORE: ${e.message}`, true);
  }
}

async function runStartScan() {
  log('→ startScan() chiamato');
  try {
    await SpatialScan.startScan({ enableDepth: true, minScanDurationSeconds: 5 });
    log('← startScan() avviato — in attesa di onScanComplete…');

    // Auto-ricevi il risultato quando l'utente preme "Ferma" nell'Activity AR
    const scanCompleteListener = await SpatialScan.addListener('onScanComplete', (result) => {
      scanCompleteListener.remove();
      log(`← [AUTO] onScanComplete: walls=${result.walls?.length ?? 0}, area=${result.roomDimensions?.area?.toFixed(1)}m²`);

      // Floorplan PNG
      if (result.floorPlanPath) {
        const webUrl = Capacitor.convertFileSrc(result.floorPlanPath);
        log(`← floorPlanPath → ${webUrl}`);
        const img = _shadow.getElementById('floorplan-img');
        if (img) { img.src = webUrl; img.style.display = 'block'; }
      }

      // GLB
      if (result.glbPath) {
        log(`← glbPath = ${result.glbPath}`);
      }
    });
  } catch (e) {
    log(`← startScan() ERRORE: ${e.message}`, true);
  }
}

async function runStopScan() {
  log('→ stopScan() chiamato');
  try {
    const result = await SpatialScan.stopScan();
    log(`← stopScan() = ${JSON.stringify(result)}`);
  } catch (e) {
    log(`← stopScan() ERRORE: ${e.message}`, true);
  }
}

async function runGetScanStatus() {
  log('→ getScanStatus() chiamato');
  try {
    const result = await SpatialScan.getScanStatus();
    log(`← getScanStatus() = ${JSON.stringify(result)}`);
  } catch (e) {
    log(`← getScanStatus() ERRORE: ${e.message}`, true);
  }
}

window.customElements.define(
  'capacitor-welcome',
  class extends HTMLElement {
    constructor() {
      super();
      const root = this.attachShadow({ mode: 'open' });
      root.innerHTML = `
    <style>
      :host {
        font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif;
        display: block;
        width: 100%;
        height: 100%;
        box-sizing: border-box;
      }
      .header {
        background: #1a1a2e;
        color: #fff;
        padding: 16px;
        text-align: center;
      }
      .header h1 { margin: 0; font-size: 1em; letter-spacing: 1px; text-transform: uppercase; }
      .header p { margin: 4px 0 0; font-size: 0.75em; opacity: 0.7; }
      .buttons {
        display: flex;
        flex-direction: column;
        gap: 10px;
        padding: 16px;
      }
      button {
        padding: 14px;
        border: none;
        border-radius: 8px;
        font-size: 0.95em;
        font-weight: 600;
        cursor: pointer;
        color: #fff;
      }
      #btn-supported   { background: #16213e; }
      #btn-permissions { background: #0f3460; }
      #btn-start       { background: #2d6a4f; }
      #btn-stop        { background: #c1121f; }
      #btn-status      { background: #4a4e69; }
      .log-container {
        padding: 0 16px 16px;
      }
      .log-title {
        font-size: 0.8em;
        font-weight: bold;
        text-transform: uppercase;
        color: #555;
        margin-bottom: 6px;
      }
      #log {
        background: #f9f9f9;
        border: 1px solid #ddd;
        border-radius: 6px;
        padding: 8px;
        height: 280px;
        overflow-y: auto;
        font-family: monospace;
      }
      #floorplan-img {
        display: none;
        width: 100%;
        border-radius: 8px;
        margin-top: 12px;
        border: 1px solid #ddd;
      }
    </style>
    <div>
      <div class="header">
        <h1>SpatialScan Plugin Test</h1>
        <p>ARCore — M3 Test Suite</p>
      </div>
      <div class="buttons">
        <button id="btn-supported">isSupported()</button>
        <button id="btn-permissions">requestPermissions()</button>
        <button id="btn-start">startScan()</button>
        <button id="btn-stop">stopScan()</button>
        <button id="btn-status">getScanStatus()</button>
      </div>
      <div class="log-container">
        <div class="log-title">Output</div>
        <div id="log"></div>
        <img id="floorplan-img" alt="Planimetria" />
      </div>
    </div>
    `;
    }

    connectedCallback() {
      SplashScreen.hide();
      _shadow = this.shadowRoot;
      const shadow = this.shadowRoot;
      shadow.getElementById('btn-supported').addEventListener('click', runIsSupported);
      shadow.getElementById('btn-permissions').addEventListener('click', runRequestPermissions);
      shadow.getElementById('btn-start').addEventListener('click', runStartScan);
      shadow.getElementById('btn-stop').addEventListener('click', runStopScan);
      shadow.getElementById('btn-status').addEventListener('click', runGetScanStatus);
      log('App pronta. Premi un bottone per testare il plugin.');
    }
  },
);

window.customElements.define(
  'capacitor-welcome-titlebar',
  class extends HTMLElement {
    constructor() {
      super();
      this.attachShadow({ mode: 'open' }).innerHTML = '<slot></slot>';
    }
  },
);
