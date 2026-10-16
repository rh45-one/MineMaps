// 1. Mouse-reactive hero parallax + subtle 3D card tilt & spotlight glow
const root = document.documentElement;
window.addEventListener('pointermove', (e) => {
  root.style.setProperty('--mx', `${e.clientX}px`);
  root.style.setProperty('--my', `${e.clientY}px`);
  const nx = (e.clientX / window.innerWidth - 0.5) * 2;
  const ny = (e.clientY / window.innerHeight - 0.5) * 2;
  root.style.setProperty('--px', `${(-nx * 14).toFixed(1)}px`);
  root.style.setProperty('--py', `${(-ny * 14).toFixed(1)}px`);
}, { passive: true });

document.querySelectorAll('.tilt-card').forEach((card) => {
  card.addEventListener('pointermove', (e) => {
    if (e.pointerType === 'touch') return;
    const rect = card.getBoundingClientRect();
    const x = e.clientX - rect.left;
    const y = e.clientY - rect.top;
    const rx = ((y / rect.height - 0.5) * -3.2).toFixed(2);
    const ry = ((x / rect.width - 0.5) * 3.2).toFixed(2);
    card.style.setProperty('--cx', `${x}px`);
    card.style.setProperty('--cy', `${y}px`);
    card.style.setProperty('--rx', `${rx}deg`);
    card.style.setProperty('--ry', `${ry}deg`);
    card.style.setProperty('--spot-opacity', '1');
  });
  card.addEventListener('pointerleave', () => {
    card.style.setProperty('--rx', '0deg');
    card.style.setProperty('--ry', '0deg');
    card.style.setProperty('--spot-opacity', '0');
  });
});

// 2. Scroll-reveal observer
const revealObserver = new IntersectionObserver((entries) => {
  entries.forEach((entry) => {
    if (entry.isIntersecting) {
      entry.target.classList.add('visible');
      revealObserver.unobserve(entry.target);
    }
  });
}, { threshold: 0.1 });

document.querySelectorAll('.reveal').forEach((el) => revealObserver.observe(el));

// 3. Unified Interactive Showcase (Device: Phone / Tablet / Android Auto + Textures + Day/Night)
(() => {
  const viewport = document.getElementById('demo-viewport');
  const canvas = document.getElementById('demo-canvas');
  const mapImg = document.getElementById('demo-map-img');
  const pinsLayer = document.getElementById('demo-pins-layer');
  const infoCard = document.getElementById('demo-info-card');
  const cardTitle = document.getElementById('demo-card-title');
  const cardSub = document.getElementById('demo-card-sub');
  const cardCoords = document.getElementById('demo-card-coords');
  const cardClose = document.getElementById('demo-card-close');
  if (!viewport || !canvas || !mapImg) return;

  const MAP_W = 1600;
  const MAP_H = 1200;
  let demoDevice = 'phone';
  let demoPack = 'original';
  let demoTime = 'day';
  let scale = 1.0;
  let panX = -MAP_W / 2;
  let panY = -MAP_H / 2;
  let dragging = false;
  let startX = 0;
  let startY = 0;

  function clampPan() {
    const vw = viewport.clientWidth;
    const vh = viewport.clientHeight;
    const halfW = (MAP_W * scale) / 2;
    const halfH = (MAP_H * scale) / 2;
    const maxOffX = Math.max(0, halfW - vw / 2);
    const maxOffY = Math.max(0, halfH - vh / 2);
    const offsetX = Math.max(-maxOffX, Math.min(maxOffX, panX + MAP_W / 2));
    const offsetY = Math.max(-maxOffY, Math.min(maxOffY, panY + MAP_H / 2));
    panX = offsetX - MAP_W / 2;
    panY = offsetY - MAP_H / 2;
  }

  function renderTransform() {
    clampPan();
    canvas.style.transform = `translate3d(${panX}px, ${panY}px, 0) scale(${scale})`;
  }

  function updateMapAsset() {
    mapImg.src = `assets/demo-london-${demoTime}-${demoPack}.png`;
  }

  // Device selector (Phone / Tablet / Android Auto)
  document.querySelectorAll('[data-demo-device]').forEach((btn) => {
    btn.addEventListener('click', () => {
      demoDevice = btn.getAttribute('data-demo-device') || 'phone';
      document.querySelectorAll('[data-demo-device]').forEach((b) => {
        const on = b === btn;
        b.classList.toggle('active', on);
        b.setAttribute('aria-selected', on ? 'true' : 'false');
      });
      viewport.classList.remove('device-phone', 'device-tablet', 'device-car');
      viewport.classList.add(`device-${demoDevice}`);
      setTimeout(renderTransform, 50);
      setTimeout(renderTransform, 340);
    });
  });

  // Texture selector (MineMaps Original / Minecraft*)
  document.querySelectorAll('[data-demo-pack]').forEach((btn) => {
    btn.addEventListener('click', () => {
      demoPack = btn.getAttribute('data-demo-pack') || 'original';
      document.querySelectorAll('[data-demo-pack]').forEach((b) => {
        const on = b === btn;
        b.classList.toggle('active', on);
        b.setAttribute('aria-selected', on ? 'true' : 'false');
      });
      updateMapAsset();
    });
  });

  // Lighting selector (Day / Night)
  document.querySelectorAll('[data-demo-time]').forEach((btn) => {
    btn.addEventListener('click', () => {
      demoTime = btn.getAttribute('data-demo-time') || 'day';
      document.querySelectorAll('[data-demo-time]').forEach((b) => {
        const on = b === btn;
        b.classList.toggle('active', on);
        b.setAttribute('aria-selected', on ? 'true' : 'false');
      });
      updateMapAsset();
    });
  });

  // Drag to pan
  viewport.addEventListener('pointerdown', (e) => {
    if (e.target.closest('button') || e.target.closest('#demo-info-card')) return;
    dragging = true;
    startX = e.clientX - panX;
    startY = e.clientY - panY;
    viewport.classList.add('dragging');
    viewport.setPointerCapture(e.pointerId);
  });

  viewport.addEventListener('pointermove', (e) => {
    if (!dragging) return;
    panX = e.clientX - startX;
    panY = e.clientY - startY;
    renderTransform();
  });

  const endDrag = () => {
    dragging = false;
    viewport.classList.remove('dragging');
  };
  viewport.addEventListener('pointerup', endDrag);
  viewport.addEventListener('pointercancel', endDrag);

  // Wheel zoom
  viewport.addEventListener('wheel', (e) => {
    e.preventDefault();
    const factor = e.deltaY < 0 ? 1.18 : 1 / 1.18;
    scale = Math.max(0.75, Math.min(2.4, scale * factor));
    renderTransform();
  }, { passive: false });

  // Double-click to drop a diamond bookmark on the map
  viewport.addEventListener('dblclick', (e) => {
    if (e.target.closest('button') || e.target.closest('#demo-info-card')) return;
    const rect = canvas.getBoundingClientRect();
    const xPct = ((e.clientX - rect.left) / rect.width) * 100;
    const yPct = ((e.clientY - rect.top) / rect.height) * 100;
    if (xPct < 0 || xPct > 100 || yPct < 0 || yPct > 100) return;
    const pin = document.createElement('img');
    pin.src = 'assets/icons/icon-diamond.png';
    pin.className = 'dropped-pin pixel';
    pin.style.left = `${xPct}%`;
    pin.style.top = `${yPct}%`;
    pinsLayer.appendChild(pin);

    const lat = (51.5042 - (yPct / 100) * 0.0070).toFixed(5);
    const lon = (-0.1325 + (xPct / 100) * 0.0158).toFixed(5);
    cardTitle.textContent = 'Saved Bookmark (Diamond)';
    cardSub.textContent = 'Saved place · Dropped just now';
    cardCoords.textContent = `${lat}, ${lon}`;
    infoCard.classList.remove('hidden');
  });

  // Landmark hotspots
  document.querySelectorAll('.demo-hotspot').forEach((hs) => {
    hs.addEventListener('click', (e) => {
      e.stopPropagation();
      cardTitle.textContent = hs.getAttribute('data-title') || '';
      cardSub.textContent = hs.getAttribute('data-sub') || '';
      cardCoords.textContent = hs.getAttribute('data-coords') || '';
      infoCard.classList.remove('hidden');
    });
  });

  if (cardClose) {
    cardClose.addEventListener('click', () => infoCard.classList.add('hidden'));
  }

  document.getElementById('demo-zoom-in')?.addEventListener('click', () => {
    scale = Math.min(2.4, scale * 1.25);
    renderTransform();
  });
  document.getElementById('demo-zoom-out')?.addEventListener('click', () => {
    scale = Math.max(0.75, scale / 1.25);
    renderTransform();
  });
  document.getElementById('demo-reset')?.addEventListener('click', () => {
    scale = 1.0;
    panX = -MAP_W / 2;
    panY = -MAP_H / 2;
    pinsLayer.innerHTML = '';
    renderTransform();
  });

  window.addEventListener('resize', renderTransform);
  renderTransform();
})();

// 4. Fetch latest release tag from GitHub API so hero/footer download buttons and APK filename update automatically
fetch('https://api.github.com/repos/rh45-one/MineMaps/releases/latest')
  .then((r) => (r.ok ? r.json() : null))
  .then((data) => {
    if (!data || !data.tag_name) return;
    const tag = data.tag_name;
    const apkAsset = (data.assets || []).find((a) => a.name && a.name.endsWith('.apk'));
    document.querySelectorAll('[data-apk-label]').forEach((el) => {
      el.textContent = `Download ${tag} APK`;
    });
    const fn = document.getElementById('apk-filename');
    if (fn) fn.textContent = apkAsset && apkAsset.name ? apkAsset.name : `MineMaps-${tag}.apk`;
    if (apkAsset && apkAsset.browser_download_url) {
      document.querySelectorAll('[data-apk-link]').forEach((a) => {
        a.href = apkAsset.browser_download_url;
      });
    }
  })
  .catch(() => {});
