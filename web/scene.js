// Flashlight Buddy scene renderer.
// Everything is drawn procedurally so the same logic ports 1:1 to the
// Android live wallpaper (Kotlin Canvas). Keep the two in sync.
(function (global) {
  'use strict';

  const TAU = Math.PI * 2;
  const clamp = (v, a, b) => Math.max(a, Math.min(b, v));
  const lerp = (a, b, t) => a + (b - a) * t;
  function rnd(i, k) {
    const s = Math.sin(i * 127.1 + k * 311.7) * 43758.5453;
    return s - Math.floor(s);
  }

  const DAY = {
    skyTop: '#7ec8f0', skyBot: '#d9f3ff',
    hill: '#6fbf73', hill2: '#4fa25a',
    trunk: '#7a5232', leaf: '#3f9a4a', leaf2: '#56b35d',
    ground: '#5aa845', groundTop: '#6cc055', dirt: '#8a6a43',
  };
  const NIGHT = {
    skyTop: '#04061a', skyBot: '#141d45',
    hill: '#1f4a32', hill2: '#173c29',
    trunk: '#5a3a22', leaf: '#2c7a3a', leaf2: '#3a8f45',
    ground: '#2f6b2c', groundTop: '#3d8436', dirt: '#5e4a33',
  };
  const FLOWERS = ['#ff6b8b', '#ffd93d', '#a78bfa', '#ffffff', '#ff9f43'];

  class Scene {
    constructor() {
      this.scroll = 0;        // world distance walked
      this.walkPhase = 0;
      this.facing = 1;        // 1 = right, -1 = left
      this.turn = 1;          // smoothed facing for the turn animation
      this.beamAngle = 0;     // radians, 0 = horizontal, + = down (canvas)
      this.night = 0;         // 0 = day/main background, 1 = flashlight scene
      this.dark = null;       // offscreen canvas for the darkness layer
    }

    // input: {torchOn, yaw (rad, relative), pitch (rad, + = up)}
    update(dt, input) {
      dt = Math.min(dt, 0.05);
      const target = input.torchOn ? 1 : 0;
      const fadeSpeed = 1.6;
      this.night = clamp(this.night + Math.sign(target - this.night) * dt * fadeSpeed, 0, 1);

      const dead = 12 * Math.PI / 180;
      if (input.yaw > dead) this.facing = 1;
      else if (input.yaw < -dead) this.facing = -1;
      this.turn += (this.facing - this.turn) * Math.min(1, dt * 10);

      const wantBeam = clamp(-input.pitch, -70 * Math.PI / 180, 65 * Math.PI / 180);
      this.beamAngle += (wantBeam - this.beamAngle) * Math.min(1, dt * 8);

      if (this.night > 0.5) {
        // walk slower when pointing the light steeply (looking around)
        const look = 1 - Math.min(1, Math.abs(this.beamAngle) / 1.2) * 0.6;
        const speed = 55 * look * Math.abs(this.turn);
        this.scroll += this.facing * speed * dt;
        this.walkPhase += dt * 7 * look;
      } else {
        this.walkPhase += (Math.round(this.walkPhase / Math.PI) * Math.PI - this.walkPhase) * Math.min(1, dt * 5);
      }
    }

    render(ctx, w, h, t) {
      const u = Math.min(h / 100, w / 50);
      const gy = h * 0.74;
      // main background (day)
      if (this.night < 1) this.drawWorld(ctx, w, h, u, gy, t, DAY, false);
      if (this.night > 0) {
        ctx.save();
        ctx.globalAlpha = this.night;
        this.drawWorld(ctx, w, h, u, gy, t, NIGHT, true);
        ctx.restore();
      }
    }

    drawWorld(ctx, w, h, u, gy, t, P, isNight) {
      const sx = this.scroll * u * 0.08; // world scroll in px
      // sky
      const sky = ctx.createLinearGradient(0, 0, 0, gy);
      sky.addColorStop(0, P.skyTop); sky.addColorStop(1, P.skyBot);
      ctx.fillStyle = sky; ctx.fillRect(0, 0, w, h);
      if (!isNight) {
        ctx.fillStyle = '#fff3b0';
        ctx.beginPath(); ctx.arc(w * 0.8, h * 0.16, u * 6, 0, TAU); ctx.fill();
        this.drawClouds(ctx, w, u, sx, t);
      }

      // hills
      this.drawHills(ctx, w, gy, u, sx * 0.15, P.hill2, 14, 0.7);
      this.drawHills(ctx, w, gy, u, sx * 0.3, P.hill, 8, 1.3);

      // trees
      const tw = u * 26, tsx = sx * 0.55;
      const first = Math.floor((tsx - tw) / tw), last = Math.ceil((tsx + w + tw) / tw);
      const owls = [];
      for (let i = first; i <= last; i++) {
        if (rnd(i, 1) < 0.3) continue;
        const x = i * tw + rnd(i, 2) * tw * 0.6 - tsx;
        const th = u * (22 + rnd(i, 3) * 16);
        this.drawTree(ctx, x, gy - u * 1.5, th, u, P, rnd(i, 4));
        if (isNight && rnd(i, 5) > 0.82) owls.push([x, gy - u * 1.5 - th * 0.55, i]);
      }

      // ground
      ctx.fillStyle = P.ground; ctx.fillRect(0, gy, w, h - gy);
      ctx.fillStyle = P.groundTop; ctx.fillRect(0, gy, w, u * 2);
      ctx.fillStyle = P.dirt;
      ctx.fillRect(0, gy + u * 6, w, u * 5);

      const gw = u * 9, gsx = sx;
      const g0 = Math.floor((gsx - gw) / gw), g1 = Math.ceil((gsx + w + gw) / gw);
      for (let i = g0; i <= g1; i++) {
        const x = i * gw + rnd(i, 7) * gw - gsx;
        const r = rnd(i, 8);
        if (r < 0.45) this.drawGrass(ctx, x, gy + u * 1.5, u, P);
        else if (r < 0.7) this.drawFlower(ctx, x, gy + u * 1.5, u, FLOWERS[Math.floor(rnd(i, 9) * FLOWERS.length)], t, i);
        else if (r < 0.82) this.drawMushroom(ctx, x, gy + u * 2, u);
        else if (r < 0.92) this.drawRock(ctx, x, gy + u * 2.5, u);
      }

      const cx = w * 0.5, footY = gy + u * 3.5;
      const hand = this.characterHand(cx, footY, u);

      if (isNight) {
        this.drawDarkness(ctx, w, h, u, hand, cx, footY);
        this.drawStars(ctx, w, gy, u, t, sx);
        this.drawMoon(ctx, w, h, u);
        this.drawBeamHaze(ctx, w, h, hand);
        for (const [ox, oy, i] of owls) this.drawOwlEyes(ctx, ox, oy, u, t, i);
        this.drawFireflies(ctx, w, gy, u, t, sx);
      }
      this.drawCharacter(ctx, cx, footY, u, t, isNight);
    }

    drawClouds(ctx, w, u, sx, t) {
      ctx.fillStyle = 'rgba(255,255,255,0.9)';
      for (let i = 0; i < 4; i++) {
        let x = ((i * w * 0.37 - sx * 0.05 + t * u * 0.6) % (w + u * 40));
        if (x < 0) x += w + u * 40;
        x -= u * 20;
        const y = u * (10 + i * 7 % 15);
        ctx.beginPath();
        ctx.arc(x, y, u * 4, 0, TAU);
        ctx.arc(x + u * 4.5, y - u * 2, u * 5, 0, TAU);
        ctx.arc(x + u * 9, y, u * 4, 0, TAU);
        ctx.fill();
      }
    }

    drawHills(ctx, w, gy, u, off, color, height, freq) {
      ctx.fillStyle = color;
      ctx.beginPath(); ctx.moveTo(0, gy);
      for (let x = 0; x <= w + 8; x += 8) {
        const k = (x + off) / (u * 30) * freq;
        const y = gy - u * height - Math.sin(k) * u * height * 0.5 - Math.sin(k * 2.3 + 1) * u * height * 0.25;
        ctx.lineTo(x, y);
      }
      ctx.lineTo(w, gy); ctx.closePath(); ctx.fill();
    }

    drawTree(ctx, x, base, th, u, P, r) {
      ctx.fillStyle = P.trunk;
      ctx.fillRect(x - u * 1.2, base - th * 0.35, u * 2.4, th * 0.35);
      const layers = 3;
      for (let k = 0; k < layers; k++) {
        const top = base - th + k * th * 0.2;
        const bw = u * (9 - k * 1.5) * (0.85 + r * 0.3);
        ctx.fillStyle = k % 2 ? P.leaf2 : P.leaf;
        ctx.beginPath();
        ctx.moveTo(x, top);
        ctx.lineTo(x + bw, top + th * 0.42);
        ctx.lineTo(x - bw, top + th * 0.42);
        ctx.closePath(); ctx.fill();
      }
    }

    drawGrass(ctx, x, y, u, P) {
      ctx.strokeStyle = P.groundTop; ctx.lineWidth = u * 0.5; ctx.lineCap = 'round';
      ctx.beginPath();
      for (let k = -1; k <= 1; k++) { ctx.moveTo(x + k * u * 0.6, y); ctx.lineTo(x + k * u * 1.2, y - u * 2.2); }
      ctx.stroke();
    }

    drawFlower(ctx, x, y, u, color, t, i) {
      const sway = Math.sin(t * 2 + i) * u * 0.3;
      ctx.strokeStyle = '#2e7d32'; ctx.lineWidth = u * 0.4;
      ctx.beginPath(); ctx.moveTo(x, y); ctx.lineTo(x + sway, y - u * 3); ctx.stroke();
      ctx.fillStyle = color;
      for (let k = 0; k < 5; k++) {
        const a = k / 5 * TAU;
        ctx.beginPath(); ctx.arc(x + sway + Math.cos(a) * u * 0.8, y - u * 3 + Math.sin(a) * u * 0.8, u * 0.6, 0, TAU); ctx.fill();
      }
      ctx.fillStyle = '#ffd54f';
      ctx.beginPath(); ctx.arc(x + sway, y - u * 3, u * 0.5, 0, TAU); ctx.fill();
    }

    drawMushroom(ctx, x, y, u) {
      ctx.fillStyle = '#f5ead6'; ctx.fillRect(x - u * 0.6, y - u * 2, u * 1.2, u * 2);
      ctx.fillStyle = '#e53935';
      ctx.beginPath(); ctx.arc(x, y - u * 2, u * 1.9, Math.PI, 0); ctx.closePath(); ctx.fill();
      ctx.fillStyle = '#fff';
      ctx.beginPath(); ctx.arc(x - u * 0.8, y - u * 2.8, u * 0.35, 0, TAU); ctx.arc(x + u * 0.6, y - u * 3.2, u * 0.3, 0, TAU); ctx.fill();
    }

    drawRock(ctx, x, y, u) {
      ctx.fillStyle = '#8d939c';
      ctx.beginPath(); ctx.ellipse(x, y - u * 0.8, u * 2.2, u * 1.4, 0, Math.PI, 0); ctx.closePath(); ctx.fill();
    }

    // ---- character: a shih tzu standing on its hind legs ----------------
    // Geometry is in "u" units, origin at the feet, facing right.
    pose(u) {
      const p = this.walkPhase;
      return { swing: Math.sin(p) * 0.45, bob: Math.abs(Math.sin(p)) * u * 0.6 };
    }

    // world position of the flashlight lens
    characterHand(cx, footY, u) {
      const { bob } = this.pose(u);
      const sxw = this.turn;
      const shX = u * 1.6, shY = -u * 10.6 - bob;
      const len = u * 8.6;
      const a = this.beamAngle;
      const lx = shX + Math.cos(a) * len, ly = shY + Math.sin(a) * len;
      return { x: cx + lx * sxw, y: footY + ly, dir: Math.atan2(Math.sin(a), Math.cos(a) * Math.sign(sxw || 1)) };
    }

    drawCharacter(ctx, cx, footY, u, t, isNight) {
      const { swing, bob } = this.pose(u);
      const dim = isNight;
      const C = {
        fur: dim ? '#ddd3c2' : '#f6eee0',
        furShade: dim ? '#c4b8a4' : '#e2d6c2',
        tan: dim ? '#b5844f' : '#cf9a5f',
        ear: dim ? '#33241c' : '#3f2c22',
        earTop: dim ? '#8f6640' : '#a97a4c',
        nose: '#161214',
        iris: '#5a3420',
        collar: dim ? '#d65a1a' : '#f26b1d',
        collar2: dim ? '#e0b52e' : '#ffd23f',
        collar3: dim ? '#b8322c' : '#d9402f',
        metal: dim ? '#a9adb3' : '#c9ccd1',
        tika: dim ? '#b8394c' : '#d9455c',
      };
      const ell = (x, y, rx, ry, rot = 0) => { ctx.beginPath(); ctx.ellipse(x, y, rx, ry, rot, 0, TAU); ctx.fill(); };
      ctx.save();
      ctx.translate(cx, footY);
      ctx.scale(this.turn, 1);
      ctx.lineCap = 'round';

      ctx.fillStyle = 'rgba(0,0,0,0.25)';
      ell(0, 0, u * 5, u * 1);
      ctx.translate(0, -bob);

      // tail: a fluffy plume that wags
      const wag = Math.sin(t * 9) * 0.35;
      ctx.save();
      ctx.translate(-u * 3.2, -u * 7.5); ctx.rotate(-0.25 + wag);
      for (let k = 0; k < 6; k++) {
        ctx.fillStyle = k > 3 ? C.tan : C.furShade;
        ell(-u * k * 0.35 + u * k * k * 0.06, -u * k * 1.05, u * (1.5 - k * 0.1), u * (1.3 - k * 0.08));
      }
      ctx.restore();

      // hind legs (stubby, fluffy)
      const leg = (ang, color) => {
        ctx.save(); ctx.translate(u * 0.2, -u * 4.6); ctx.rotate(-ang);
        ctx.fillStyle = color;
        ctx.beginPath(); ctx.roundRect(-u * 1.5, 0, u * 3, u * 4.6, u * 1.4); ctx.fill();
        ell(u * 0.5, u * 4.3, u * 1.9, u * 1.0);
        ctx.restore();
      };
      leg(-swing, C.furShade);
      // back front paw, swinging
      ctx.strokeStyle = C.furShade; ctx.lineWidth = u * 2.2;
      ctx.beginPath(); ctx.moveTo(-u * 0.6, -u * 10.4); ctx.lineTo(-u * 0.6 - Math.sin(swing) * u * 3, -u * 7.2); ctx.stroke();
      leg(swing, C.fur);

      // body
      ctx.fillStyle = C.fur;
      ell(0, -u * 8.2, u * 4.4, u * 4.9);
      ctx.fillStyle = C.tan;
      ell(-u * 2.6, -u * 9.6, u * 1.8, u * 2.6, 0.3);
      ctx.fillStyle = C.fur;
      for (let k = -3; k <= 3; k++) ell(k * u * 1.2, -u * 3.9 + Math.abs(k) * u * 0.25, u * 1.0, u * 0.9);

      // collar (braided, with a ring and a bell)
      ctx.save();
      ctx.translate(u * 0.9, -u * 12.4); ctx.rotate(0.12);
      ctx.fillStyle = C.collar;
      ctx.beginPath(); ctx.roundRect(-u * 3.6, -u * 0.65, u * 7.2, u * 1.3, u * 0.6); ctx.fill();
      ctx.strokeStyle = C.collar3; ctx.lineWidth = u * 0.25;
      for (let k = -3; k <= 3; k++) { ctx.beginPath(); ctx.moveTo(k * u - u * 0.3, -u * 0.5); ctx.lineTo(k * u + u * 0.3, u * 0.5); ctx.stroke(); }
      ctx.fillStyle = C.collar2; ctx.fillRect(-u * 0.9, -u * 0.7, u * 0.8, u * 1.4);
      ctx.strokeStyle = C.metal; ctx.lineWidth = u * 0.3;
      ctx.beginPath(); ctx.arc(u * 1.6, u * 1.4, u * 0.75, 0, TAU); ctx.stroke();
      ctx.fillStyle = '#d4a93a'; ell(u * 2.9, u * 1.0, u * 0.45, u * 0.45);
      ctx.fillStyle = C.collar; ctx.fillRect(u * 3.2, u * 0.3, u * 0.25, u * 1.1); ctx.fillRect(u * 3.6, u * 0.3, u * 0.25, u * 1.0);
      ctx.restore();

      // head (3/4 view, looking right)
      const hx = u * 1.6, hy = -u * 17.2;
      // far ear
      ctx.fillStyle = C.ear; ell(hx + u * 4.6, hy + u * 1.8, u * 1.6, u * 3.6, -0.25);
      // head fluff
      ctx.fillStyle = C.fur;
      ell(hx, hy, u * 5.2, u * 4.9);
      ctx.fillStyle = C.fur;
      for (let k = 0; k < 5; k++) ell(hx - u * 2 + k * u, hy - u * 4.6 + Math.abs(k - 2) * u * 0.3, u * 0.9, u * 0.7);
      // tan eye patches
      ctx.fillStyle = C.tan;
      ell(hx + u * 0.4, hy - u * 0.6, u * 2.3, u * 2.0, -0.2);
      ell(hx + u * 3.5, hy - u * 0.8, u * 1.7, u * 1.8, 0.2);
      // red tika on the forehead
      ctx.fillStyle = C.tika; ell(hx + u * 1.9, hy - u * 3.3, u * 0.55, u * 0.8);
      // near ear: tan at the top, dark brown hanging down
      ctx.fillStyle = C.earTop; ell(hx - u * 3.6, hy + u * 0.2, u * 2.0, u * 2.6, 0.2);
      ctx.fillStyle = C.ear; ell(hx - u * 3.9, hy + u * 2.4, u * 1.9, u * 3.4, 0.15);
      // eyes, looking along the beam
      const ex = Math.cos(this.beamAngle) * u * 0.35, ey = Math.sin(this.beamAngle) * u * 0.35;
      for (const [ox, r] of [[u * 0.6, 1.25], [u * 3.6, 1.05]]) {
        const x = hx + ox, y = hy - u * 0.6;
        ctx.fillStyle = '#fbf6ee'; ell(x, y, u * r * 1.05, u * r * 1.1);
        ctx.fillStyle = C.iris; ell(x + ex, y + ey, u * r * 0.85, u * r * 0.9);
        ctx.fillStyle = '#120c0a'; ell(x + ex, y + ey, u * r * 0.45, u * r * 0.5);
        ctx.fillStyle = '#fff'; ell(x + ex + u * r * 0.3, y + ey - u * r * 0.35, u * r * 0.22, u * r * 0.22);
      }
      // muzzle and beard
      ctx.fillStyle = C.fur;
      ell(hx + u * 2.5, hy + u * 2.3, u * 2.6, u * 2.0);
      ell(hx + u * 2.3, hy + u * 3.9, u * 2.2, u * 1.4);
      ctx.strokeStyle = C.furShade; ctx.lineWidth = u * 0.3;
      ctx.beginPath(); ctx.moveTo(hx + u * 1.3, hy + u * 3.4); ctx.quadraticCurveTo(hx + u * 2.4, hy + u * 2.8, hx + u * 3.5, hy + u * 3.4); ctx.stroke();
      // nose
      ctx.fillStyle = C.nose; ell(hx + u * 2.5, hy + u * 1.4, u * 1.15, u * 0.85);
      ctx.fillStyle = 'rgba(255,255,255,0.35)'; ell(hx + u * 2.2, hy + u * 1.1, u * 0.35, u * 0.2);

      // front paw holding the flashlight at beamAngle
      const shX = u * 1.6, shY = -u * 10.6;
      const a = this.beamAngle;
      const handX = shX + Math.cos(a) * u * 4.2, handY = shY + Math.sin(a) * u * 4.2;
      ctx.strokeStyle = C.fur; ctx.lineWidth = u * 2.3;
      ctx.beginPath(); ctx.moveTo(shX, shY); ctx.lineTo(handX, handY); ctx.stroke();
      ctx.save();
      ctx.translate(handX, handY); ctx.rotate(a);
      ctx.fillStyle = '#4a5568'; ctx.fillRect(-u * 0.5, -u * 0.65, u * 3.0, u * 1.3);
      ctx.fillStyle = '#718096'; ctx.fillRect(u * 2.4, -u * 1.0, u * 1.4, u * 2.0);
      ctx.fillStyle = this.night > 0.5 && isNight ? '#fffbe0' : '#cbd5e0';
      ctx.fillRect(u * 3.7, -u * 0.9, u * 0.4, u * 1.8);
      ctx.restore();
      ctx.fillStyle = C.fur; ell(handX, handY, u * 1.25, u * 1.15);
      ctx.strokeStyle = C.furShade; ctx.lineWidth = u * 0.2;
      for (const d of [-0.4, 0, 0.4]) { ctx.beginPath(); ctx.moveTo(handX + u * 0.6, handY + d * u); ctx.lineTo(handX + u * 1.1, handY + d * u); ctx.stroke(); }

      ctx.restore();
    }

    // ---- night effects ---------------------------------------------------
    beamPath(ctx, hand, len, spread) {
      ctx.beginPath();
      ctx.moveTo(hand.x, hand.y);
      ctx.arc(hand.x, hand.y, len, hand.dir - spread, hand.dir + spread);
      ctx.closePath();
    }

    drawDarkness(ctx, w, h, u, hand, cx, footY) {
      if (!this.dark) this.dark = document.createElement('canvas');
      const d = this.dark;
      if (d.width !== ctx.canvas.width || d.height !== ctx.canvas.height) {
        d.width = ctx.canvas.width; d.height = ctx.canvas.height;
      }
      const dc = d.getContext('2d');
      dc.setTransform(ctx.getTransform());
      dc.globalCompositeOperation = 'source-over';
      dc.clearRect(0, 0, w, h);
      dc.fillStyle = 'rgba(2,3,12,0.94)';
      dc.fillRect(0, 0, w, h);
      dc.globalCompositeOperation = 'destination-out';
      const len = Math.hypot(w, h) * 0.85;
      const flicker = 0.97 + Math.random() * 0.03;
      const g = dc.createRadialGradient(hand.x, hand.y, 0, hand.x, hand.y, len);
      g.addColorStop(0, `rgba(0,0,0,${flicker})`);
      g.addColorStop(0.45, 'rgba(0,0,0,0.85)');
      g.addColorStop(1, 'rgba(0,0,0,0)');
      dc.fillStyle = g;
      this.beamPath(dc, hand, len, 0.36); dc.fill();
      dc.globalAlpha = 0.5;
      this.beamPath(dc, hand, len, 0.46); dc.fill();
      dc.globalAlpha = 1;
      // soft spill around the character
      const sp = dc.createRadialGradient(cx, footY - u * 11, 0, cx, footY - u * 11, u * 18);
      sp.addColorStop(0, 'rgba(0,0,0,0.55)'); sp.addColorStop(1, 'rgba(0,0,0,0)');
      dc.fillStyle = sp;
      dc.beginPath(); dc.arc(cx, footY - u * 11, u * 18, 0, TAU); dc.fill();
      dc.globalCompositeOperation = 'source-over';
      ctx.save(); ctx.setTransform(1, 0, 0, 1, 0, 0);
      ctx.drawImage(d, 0, 0);
      ctx.restore();
    }

    drawBeamHaze(ctx, w, h, hand) {
      const len = Math.hypot(w, h) * 0.85;
      ctx.save();
      ctx.globalCompositeOperation = 'lighter';
      const g = ctx.createRadialGradient(hand.x, hand.y, 0, hand.x, hand.y, len);
      g.addColorStop(0, 'rgba(255,240,190,0.28)');
      g.addColorStop(0.5, 'rgba(255,230,160,0.06)');
      g.addColorStop(1, 'rgba(255,230,160,0)');
      ctx.fillStyle = g;
      this.beamPath(ctx, hand, len, 0.34); ctx.fill();
      ctx.restore();
    }

    drawStars(ctx, w, gy, u, t, sx) {
      for (let i = 0; i < 70; i++) {
        let x = (rnd(i, 11) * w * 1.5 - sx * 0.02) % w; if (x < 0) x += w;
        const y = rnd(i, 12) * gy * 0.75;
        const a = 0.4 + 0.6 * Math.abs(Math.sin(t * (0.6 + rnd(i, 13) * 2) + i));
        ctx.fillStyle = `rgba(255,255,240,${a * 0.9})`;
        ctx.beginPath(); ctx.arc(x, y, u * (0.15 + rnd(i, 14) * 0.25), 0, TAU); ctx.fill();
      }
    }

    drawMoon(ctx, w, h, u) {
      const x = w * 0.82, y = h * 0.13, r = u * 4.5;
      const g = ctx.createRadialGradient(x, y, r * 0.5, x, y, r * 3);
      g.addColorStop(0, 'rgba(220,230,255,0.25)'); g.addColorStop(1, 'rgba(220,230,255,0)');
      ctx.fillStyle = g; ctx.beginPath(); ctx.arc(x, y, r * 3, 0, TAU); ctx.fill();
      ctx.save();
      ctx.beginPath(); ctx.arc(x, y, r, 0, TAU); ctx.clip();
      ctx.fillStyle = '#eef2ff';
      ctx.beginPath(); ctx.arc(x, y, r, 0, TAU);
      ctx.moveTo(x + r * 0.45 + r * 0.9, y - r * 0.2);
      ctx.arc(x + r * 0.45, y - r * 0.2, r * 0.9, 0, TAU);
      ctx.fill('evenodd');
      ctx.restore();
    }

    drawOwlEyes(ctx, x, y, u, t, i) {
      const blink = (Math.sin(t * 0.7 + i * 3) > 0.97) ? 0.15 : 1;
      ctx.fillStyle = '#ffe14d';
      for (const ox of [-u * 0.9, u * 0.9]) {
        ctx.beginPath(); ctx.ellipse(x + ox, y, u * 0.6, u * 0.6 * blink, 0, 0, TAU); ctx.fill();
      }
      ctx.fillStyle = '#000';
      for (const ox of [-u * 0.9, u * 0.9]) {
        ctx.beginPath(); ctx.ellipse(x + ox, y, u * 0.25, u * 0.25 * blink, 0, 0, TAU); ctx.fill();
      }
    }

    drawFireflies(ctx, w, gy, u, t, sx) {
      ctx.save();
      ctx.globalCompositeOperation = 'lighter';
      for (let i = 0; i < 14; i++) {
        let x = (rnd(i, 21) * w * 2 - sx * 0.8 + Math.sin(t * 0.5 + i) * u * 6) % (w * 1.2);
        if (x < 0) x += w * 1.2;
        const y = gy - u * (4 + rnd(i, 22) * 30) + Math.sin(t * 0.9 + i * 2) * u * 3;
        const a = Math.max(0, Math.sin(t * 1.5 + i * 1.7));
        const g = ctx.createRadialGradient(x, y, 0, x, y, u * 2);
        g.addColorStop(0, `rgba(210,255,120,${a})`); g.addColorStop(1, 'rgba(210,255,120,0)');
        ctx.fillStyle = g; ctx.beginPath(); ctx.arc(x, y, u * 2, 0, TAU); ctx.fill();
      }
      ctx.restore();
    }
  }

  // Orientation helper: converts DeviceOrientation (alpha, beta, gamma in
  // degrees) to the direction the phone's back (where the torch is) points.
  // Returns {azimuth, elevation} in radians.
  function torchDirection(alpha, beta, gamma) {
    const d = Math.PI / 180;
    const a = (alpha || 0) * d, b = (beta || 0) * d, g = (gamma || 0) * d;
    const ca = Math.cos(a), sa = Math.sin(a), cb = Math.cos(b), sb = Math.sin(b), cg = Math.cos(g), sg = Math.sin(g);
    // -Z axis of the device in earth frame (x east, y north, z up)
    const vx = -(ca * sg + sa * sb * cg);
    const vy = -(sa * sg - ca * sb * cg);
    const vz = -(cb * cg);
    return { azimuth: Math.atan2(vx, vy), elevation: Math.asin(clamp(vz, -1, 1)) };
  }

  global.FlashScene = { Scene, torchDirection };
})(window);
