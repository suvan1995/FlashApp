(function () {
  'use strict';
  const { Scene, torchDirection } = window.FlashScene;
  const canvas = document.getElementById('c');
  const ctx = canvas.getContext('2d');
  const btn = document.getElementById('torch');
  const label = document.getElementById('torchLabel');
  const hint = document.getElementById('hint');
  const recenterBtn = document.getElementById('recenter');

  const scene = new Scene();
  const input = { torchOn: false, yaw: 0, pitch: 0 };
  let baseAz = null, lastAz = 0, hasSensor = false;
  let track = null;           // MediaStreamTrack holding the real torch
  let w = 0, h = 0;

  function resize() {
    const dpr = Math.min(window.devicePixelRatio || 1, 2);
    w = window.innerWidth; h = window.innerHeight;
    canvas.width = Math.round(w * dpr); canvas.height = Math.round(h * dpr);
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
  }
  window.addEventListener('resize', resize); resize();

  // ---- orientation ------------------------------------------------------
  const wrap = (a) => Math.atan2(Math.sin(a), Math.cos(a));
  function onOrientation(e) {
    if (e.alpha == null && e.beta == null) return;
    hasSensor = true;
    const { azimuth, elevation } = torchDirection(e.alpha, e.beta, e.gamma);
    lastAz = azimuth;
    if (baseAz == null) baseAz = azimuth;
    input.yaw = wrap(azimuth - baseAz);
    input.pitch = elevation;
  }
  async function enableSensors() {
    try {
      if (typeof DeviceOrientationEvent !== 'undefined' && DeviceOrientationEvent.requestPermission) {
        await DeviceOrientationEvent.requestPermission();
      }
    } catch (_) { /* iOS denied; pointer fallback still works */ }
    window.addEventListener('deviceorientation', onOrientation);
  }
  enableSensors();
  recenterBtn.addEventListener('click', () => { baseAz = lastAz; input.yaw = 0; });

  // Pointer fallback (desktop / no gyroscope): drag to aim.
  canvas.addEventListener('pointermove', (e) => {
    if (hasSensor) return;
    if (e.pointerType !== 'mouse' && e.buttons === 0) return;
    const dx = e.clientX - w / 2, dy = e.clientY - h * 0.6;
    input.yaw = dx > 20 ? 1 : dx < -20 ? -1 : input.yaw;
    input.pitch = Math.atan2(-dy, Math.abs(dx) + 40);
  });

  // ---- flashlight ------------------------------------------------------
  function setTorchState(on, source) {
    if (on === input.torchOn) return;
    input.torchOn = on;
    if (on) baseAz = lastAz;
    document.body.classList.toggle('on', on);
    label.textContent = on ? 'Flashlight off' : 'Flashlight on';
    if (on) {
      hint.textContent = hasSensor
        ? 'Point your phone left, right, up or down. Your buddy will follow.'
        : 'Drag on the screen to point the flashlight.';
    } else {
      hint.textContent = 'Tap the button to switch on your flashlight.';
    }
    if (source !== 'native' && navigator.vibrate) navigator.vibrate(15);
  }

  async function webTorch(on) {
    try {
      if (on) {
        if (!track) {
          const stream = await navigator.mediaDevices.getUserMedia({ video: { facingMode: { ideal: 'environment' } } });
          track = stream.getVideoTracks()[0];
        }
        const caps = track.getCapabilities ? track.getCapabilities() : {};
        if (!caps.torch) throw new Error('no torch');
        await track.applyConstraints({ advanced: [{ torch: true }] });
      } else if (track) {
        try { await track.applyConstraints({ advanced: [{ torch: false }] }); } catch (_) {}
        track.stop(); track = null;
      }
      return true;
    } catch (err) {
      if (track) { track.stop(); track = null; }
      return false;
    }
  }

  // Native bridge (Android wrapper). It reports the *system* flashlight state,
  // so turning the torch on from quick settings starts the animation too.
  const native = window.Capacitor && window.Capacitor.Plugins && window.Capacitor.Plugins.TorchWatcher;
  if (native) {
    native.addListener('torchChanged', (s) => setTorchState(!!s.enabled, 'native'));
    native.getState().then((s) => setTorchState(!!s.enabled, 'native')).catch(() => {});
  }

  btn.addEventListener('click', async () => {
    const want = !input.torchOn;
    if (native) {
      try { await native.setTorch({ enabled: want }); return; } catch (_) { /* fall through */ }
    }
    setTorchState(want, 'web');
    const ok = await webTorch(want);
    if (want && !ok) hint.textContent = 'Demo mode: this browser could not switch on the real flashlight, but the animation still works.';
  });

  // ---- loop ------------------------------------------------------------
  let last = performance.now();
  function frame(now) {
    const dt = (now - last) / 1000; last = now;
    scene.update(dt, input);
    scene.render(ctx, w, h, now / 1000);
    requestAnimationFrame(frame);
  }
  requestAnimationFrame(frame);

  if ('serviceWorker' in navigator && location.protocol === 'https:') {
    navigator.serviceWorker.register('sw.js').catch(() => {});
  }
})();
