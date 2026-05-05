import { Capacitor } from '@capacitor/core';
import { SplashScreen } from '@capacitor/splash-screen';
import { SpatialScan } from 'capacitor-hubagency-spatial-scan';

let _shadow = null;

function log(msg, isError = false) {
  console.log('[SpatialScanTest]', msg);
}

function addPinchZoom(img) {
  let scale = 1, baseScale = 1;
  let translateX = 0, translateY = 0, baseTX = 0, baseTY = 0;
  let startDist = 0, startMidX = 0, startMidY = 0;
  let lastTap = 0;

  function applyTransform() {
    img.style.transform = `translate(${translateX}px, ${translateY}px) scale(${scale})`;
  }

  function dist(t) {
    const dx = t[0].clientX - t[1].clientX;
    const dy = t[0].clientY - t[1].clientY;
    return Math.hypot(dx, dy);
  }

  img.addEventListener('touchstart', (e) => {
    e.preventDefault();
    if (e.touches.length === 1) {
      // Double-tap to reset
      const now = Date.now();
      if (now - lastTap < 300) {
        scale = 1; translateX = 0; translateY = 0;
        applyTransform();
      }
      lastTap = now;
    }
    if (e.touches.length === 2) {
      startDist = dist(e.touches);
      baseScale = scale;
      baseTX = translateX; baseTY = translateY;
      startMidX = (e.touches[0].clientX + e.touches[1].clientX) / 2;
      startMidY = (e.touches[0].clientY + e.touches[1].clientY) / 2;
    }
  }, { passive: false });

  img.addEventListener('touchmove', (e) => {
    e.preventDefault();
    if (e.touches.length === 2) {
      const newDist = dist(e.touches);
      scale = Math.min(Math.max(baseScale * (newDist / startDist), 1), 6);
      const midX = (e.touches[0].clientX + e.touches[1].clientX) / 2;
      const midY = (e.touches[0].clientY + e.touches[1].clientY) / 2;
      translateX = baseTX + (midX - startMidX);
      translateY = baseTY + (midY - startMidY);
      applyTransform();
    }
  }, { passive: false });

  img.addEventListener('touchend', (e) => {
    if (e.touches.length < 2) {
      baseScale = scale; baseTX = translateX; baseTY = translateY;
    }
  });
}

async function runStartScan() {
  try {
    await SpatialScan.requestPermissions();
    await SpatialScan.startScan({ enableDepth: true, minScanDurationSeconds: 5 });

    const scanCompleteListener = await SpatialScan.addListener('onScanComplete', (result) => {
      scanCompleteListener.remove();
      log(`onScanComplete: walls=${result.walls?.length ?? 0}, area=${result.roomDimensions?.area?.toFixed(1)}m²`);

      const section      = _shadow.getElementById('last-survey-section');
      const container    = _shadow.getElementById('floorplan-container');
      const mvContainer  = _shadow.getElementById('model-viewer-container');
      const toggleRow    = _shadow.getElementById('view-toggle-row');
      const btn2d        = _shadow.getElementById('btn-view-2d');
      const btn3d        = _shadow.getElementById('btn-view-3d');
      const img          = _shadow.getElementById('floorplan-img');
      const modelViewer  = _shadow.getElementById('room-model-viewer');

      if (result.floorPlanPath) {
        img.src = Capacitor.convertFileSrc(result.floorPlanPath);
        addPinchZoom(img);
        container.style.display = 'block';
        section.style.display = 'block';
      }

      if (result.glbPath) {
        modelViewer.src = Capacitor.convertFileSrc(result.glbPath);
        toggleRow.style.display = 'flex';

        btn2d.addEventListener('click', () => {
          container.style.display = 'block';
          mvContainer.style.display = 'none';
          btn2d.classList.add('active');
          btn3d.classList.remove('active');
        });
        btn3d.addEventListener('click', () => {
          container.style.display = 'none';
          mvContainer.style.display = 'block';
          btn2d.classList.remove('active');
          btn3d.classList.add('active');
        });
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
        padding: max(env(safe-area-inset-top), 24px) 16px 16px;
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
      #floorplan-container {
        display: none;
        overflow: hidden;
        border-radius: 10px;
        border: 1px solid rgba(20, 215, 255, 0.2);
        touch-action: none;
      }
      #floorplan-img {
        display: block;
        width: 100%;
        touch-action: none;
        user-select: none;
        transform-origin: center center;
      }
      #view-toggle-row {
        display: none;
        gap: 8px;
        margin-top: 10px;
      }
      .btn-toggle {
        flex: 1;
        padding: 10px;
        border: 1px solid rgba(20, 215, 255, 0.35);
        border-radius: 8px;
        background: transparent;
        color: #14D7FF;
        font-size: 0.8em;
        font-weight: 600;
        letter-spacing: 1px;
        text-transform: uppercase;
        cursor: pointer;
      }
      .btn-toggle.active {
        background: rgba(20, 215, 255, 0.15);
      }
      #model-viewer-container {
        display: none;
        border-radius: 10px;
        overflow: hidden;
        border: 1px solid rgba(20, 215, 255, 0.2);
      }
      #room-model-viewer {
        width: 100%;
        height: 320px;
        background: #080c1a;
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
        <div id="floorplan-container">
          <img id="floorplan-img" alt="Ultimo Rilievo" />
        </div>
        <div id="model-viewer-container">
          <model-viewer id="room-model-viewer"
            camera-controls
            auto-rotate
            shadow-intensity="1">
          </model-viewer>
        </div>
        <div id="view-toggle-row">
          <button class="btn-toggle active" id="btn-view-2d">Planimetria 2D</button>
          <button class="btn-toggle" id="btn-view-3d">Modello 3D</button>
        </div>
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
