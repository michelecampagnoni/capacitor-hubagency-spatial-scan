import { Capacitor } from '@capacitor/core';
import { SplashScreen } from '@capacitor/splash-screen';
import { SpatialScan } from 'capacitor-hubagency-spatial-scan';

let _shadow = null;

function log(msg, isError = false) {
  console.log('[SpatialScanTest]', msg);
}

async function runStartScan() {
  try {
    await SpatialScan.requestPermissions();
    await SpatialScan.startScan({ enableDepth: true, minScanDurationSeconds: 5 });

    const scanCompleteListener = await SpatialScan.addListener('onScanComplete', (result) => {
      scanCompleteListener.remove();
      log(`onScanComplete: walls=${result.walls?.length ?? 0}, area=${result.roomDimensions?.area?.toFixed(1)}m²`);

      if (result.floorPlanPath) {
        const webUrl = Capacitor.convertFileSrc(result.floorPlanPath);
        const img = _shadow.getElementById('floorplan-img');
        const section = _shadow.getElementById('last-survey-section');
        if (img) { img.src = webUrl; img.style.display = 'block'; }
        if (section) { section.style.display = 'block'; }
      }
    });
  } catch (e) {
    log(`startScan() ERRORE: ${e.message}`, true);
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
        min-height: 100%;
        box-sizing: border-box;
        background: #03040e;
        color: #ffffff;
      }
      .header {
        background: #03040e;
        border-bottom: 1px solid rgba(20, 215, 255, 0.25);
        padding: 24px 16px 16px;
        text-align: center;
      }
      .header h1 {
        margin: 0;
        font-size: 1.1em;
        letter-spacing: 2px;
        text-transform: uppercase;
        color: #14D7FF;
      }
      .header p {
        margin: 6px 0 0;
        font-size: 0.75em;
        color: rgba(170, 200, 255, 0.6);
      }
      .buttons {
        display: flex;
        flex-direction: column;
        align-items: center;
        padding: 32px 32px 24px;
      }
      #btn-start {
        width: 100%;
        max-width: 320px;
        padding: 18px;
        border: none;
        border-radius: 40px;
        font-size: 1.05em;
        font-weight: 700;
        letter-spacing: 1.5px;
        text-transform: uppercase;
        cursor: pointer;
        background: #B9127D;
        color: #fff;
        box-shadow: 0 0 18px rgba(185, 18, 125, 0.45);
      }
      #btn-start:active {
        transform: scale(0.97);
        box-shadow: 0 0 8px rgba(185, 18, 125, 0.3);
      }
      #last-survey-section {
        display: none;
        margin: 0 16px 32px;
      }
      .survey-label {
        font-size: 0.8em;
        font-weight: 700;
        letter-spacing: 1px;
        text-transform: uppercase;
        color: #5064B4;
        margin-bottom: 10px;
        padding-left: 4px;
      }
      #floorplan-img {
        display: none;
        width: 100%;
        border-radius: 10px;
        border: 1px solid rgba(20, 215, 255, 0.2);
      }
    </style>
    <div>
      <div class="header">
        <h1>Hub Agency · Rilievo</h1>
        <p>ARCore Spatial Scan</p>
      </div>
      <div class="buttons">
        <button id="btn-start">Avvia Scansione</button>
      </div>
      <div id="last-survey-section">
        <div class="survey-label">Ultimo Rilievo</div>
        <img id="floorplan-img" alt="Ultimo Rilievo" />
      </div>
    </div>
    `;
    }

    connectedCallback() {
      SplashScreen.hide();
      _shadow = this.shadowRoot;
      this.shadowRoot.getElementById('btn-start').addEventListener('click', runStartScan);
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
