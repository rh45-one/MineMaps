// 1. Hero preview texture switcher
document.querySelectorAll('[data-hero-pack]').forEach((btn) => {
  btn.addEventListener('click', () => {
    const pack = btn.getAttribute('data-hero-pack');
    document.querySelectorAll('[data-hero-pack]').forEach((b) => {
      const on = b === btn;
      b.classList.toggle('active', on);
      b.setAttribute('aria-selected', on ? 'true' : 'false');
    });
    const orig = document.getElementById('hero-shot-original');
    const mc = document.getElementById('hero-shot-minecraft');
    const cap = document.getElementById('hero-caption');
    if (pack === 'minecraft') {
      orig.classList.remove('active');
      mc.classList.add('active');
      cap.innerHTML = 'Shown with <strong>Optional In-App Minecraft Textures*</strong> &mdash; imported privately on your phone.';
    } else {
      mc.classList.remove('active');
      orig.classList.add('active');
      cap.innerHTML = 'Shown with <strong>MineMaps Original Art</strong> &mdash; crafted from scratch and bundled out of the box.';
    }
  });
});

// 2. Mouse-reactive hero parallax + subtle 3D card tilt & spotlight glow
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
    const rx = ((y / rect.height - 0.5) * -3.6).toFixed(2);
    const ry = ((x / rect.width - 0.5) * 3.6).toFixed(2);
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

// 3. Scroll-reveal observer
const revealObserver = new IntersectionObserver((entries) => {
  entries.forEach((entry) => {
    if (entry.isIntersecting) {
      entry.target.classList.add('visible');
      revealObserver.unobserve(entry.target);
    }
  });
}, { threshold: 0.1 });

document.querySelectorAll('.reveal').forEach((el) => revealObserver.observe(el));

// 4. Interactive Live Block Map Demo
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

  let demoPack = 'original';
  let demoTime = 'day';
  let selectedIcon = 'pin';
  let scale = 1.0;
  let panX = -640; // center of 1280px
  let panY = -480; // center of 960px
  let dragging = false;
  let startX = 0;
  let startY = 0;

  function clampPan() {
    const vw = viewport.clientWidth;
    const vh = viewport.clientHeight;
    const halfW = (1280 * scale) / 2;
    const halfH = (960 * scale) / 2;
    const maxOffX = Math.max(0, halfW - vw / 2);
    const maxOffY = Math.max(0, halfH - vh / 2);
    const offsetX = Math.max(-maxOffX, Math.min(maxOffX, panX + 640));
    const offsetY = Math.max(-maxOffY, Math.min(maxOffY, panY + 480));
    panX = offsetX - 640;
    panY = offsetY - 480;
  }

  function renderTransform() {
    clampPan();
    canvas.style.transform = `translate3d(${panX}px, ${panY}px, 0) scale(${scale})`;
  }

  function updateMapAsset() {
    mapImg.src = `assets/demo-rome-${demoTime}-${demoPack}.png`;
  }

  document.querySelectorAll('[data-demo-pack]').forEach((btn) => {
    btn.addEventListener('click', () => {
      demoPack = btn.getAttribute('data-demo-pack');
      document.querySelectorAll('[data-demo-pack]').forEach((b) => b.classList.toggle('active', b === btn));
      updateMapAsset();
    });
  });

  document.querySelectorAll('[data-demo-time]').forEach((btn) => {
    btn.addEventListener('click', () => {
      demoTime = btn.getAttribute('data-demo-time');
      document.querySelectorAll('[data-demo-time]').forEach((b) => b.classList.toggle('active', b === btn));
      updateMapAsset();
    });
  });

  document.querySelectorAll('[data-demo-icon]').forEach((btn) => {
    btn.addEventListener('click', () => {
      selectedIcon = btn.getAttribute('data-demo-icon');
      document.querySelectorAll('[data-demo-icon]').forEach((b) => b.classList.toggle('active', b === btn));
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
    scale = Math.max(0.85, Math.min(2.6, scale * factor));
    renderTransform();
  }, { passive: false });

  // Double-click to drop a bookmark icon on the map
  viewport.addEventListener('dblclick', (e) => {
    if (e.target.closest('button') || e.target.closest('#demo-info-card')) return;
    const rect = canvas.getBoundingClientRect();
    const xPct = ((e.clientX - rect.left) / rect.width) * 100;
    const yPct = ((e.clientY - rect.top) / rect.height) * 100;
    if (xPct < 0 || xPct > 100 || yPct < 0 || yPct > 100) return;
    const pin = document.createElement('img');
    pin.src = `assets/icons/icon-${selectedIcon}.png`;
    pin.className = 'dropped-pin pixel';
    pin.style.left = `${xPct}%`;
    pin.style.top = `${yPct}%`;
    pinsLayer.appendChild(pin);

    const lat = (41.8945 - (yPct / 100) * 0.0086).toFixed(5);
    const lon = (12.4855 + (xPct / 100) * 0.0134).toFixed(5);
    cardTitle.textContent = `Saved Place (${selectedIcon.replace('_', ' ')})`;
    cardSub.textContent = 'Custom Bookmark · Dropped just now';
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
    scale = Math.min(2.6, scale * 1.25);
    renderTransform();
  });
  document.getElementById('demo-zoom-out')?.addEventListener('click', () => {
    scale = Math.max(0.85, scale / 1.25);
    renderTransform();
  });
  document.getElementById('demo-reset')?.addEventListener('click', () => {
    scale = 1.0;
    panX = -640;
    panY = -480;
    pinsLayer.innerHTML = '';
    renderTransform();
  });

  window.addEventListener('resize', renderTransform);
  renderTransform();
})();

// 5. Fetch latest release tag from GitHub API so hero/footer download buttons and APK filename update automatically (navbar button stays clean without version text)
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
