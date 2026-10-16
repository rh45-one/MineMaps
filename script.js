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

// 3. Multi-Zoom (Z16 / Z17 / Z18) Crisp 1:1 Interactive Map Showcase
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
  const placesDrawer = document.getElementById('demo-places-drawer');
  const placesClose = document.getElementById('demo-places-close');
  const hudSearchBtn = document.getElementById('hud-search-btn');
  const hudPlacesBtn = document.getElementById('hud-places-btn');
  const hudZoomBadge = document.getElementById('hud-zoom-badge');
  const hudCarPlacesBtn = document.getElementById('hud-car-places-btn');
  const hudCarZoomBtn = document.getElementById('hud-car-zoom-btn');
  const hudCarBannerBtn = document.getElementById('hud-car-banner-btn');
  const hudCarBannerText = document.getElementById('hud-car-banner-text');
  if (!viewport || !canvas || !mapImg) return;

  const MAP_W = 1280;
  const MAP_H = 960;
  const CENTER_LAT = 51.50070;
  const CENTER_LON = -0.12460;
  const ZOOMS = [16, 17, 18];

  let demoDevice = 'phone';
  let demoPack = 'original';
  let demoTime = 'day';
  let currentZoom = 17;
  let panX = -MAP_W / 2;
  let panY = -MAP_H / 2;
  let dragging = false;
  let startX = 0;
  let startY = 0;
  let lastWheelMs = 0;
  let droppedPins = []; // { lat, lon }

  // Web Mercator projection helpers matching :mapengine
  function lonToWorldX(lon) {
    return (lon + 180) / 360;
  }
  function latToWorldY(lat) {
    const rad = (lat * Math.PI) / 180;
    return (1 - Math.log(Math.tan(rad) + 1 / Math.cos(rad)) / Math.PI) / 2;
  }
  function worldXToLon(wx) {
    return wx * 360 - 180;
  }
  function worldYToLat(wy) {
    const n = Math.PI - 2 * Math.PI * wy;
    return (180 / Math.PI) * Math.atan(0.5 * (Math.exp(n) - Math.exp(-n)));
  }

  const centerWx = lonToWorldX(CENTER_LON);
  const centerWy = latToWorldY(CENTER_LAT);

  // Converts (lat, lon) to pixel coordinates (0..1280, 0..960) on the currentZoom image
  function latLonToPixel(lat, lon, z = currentZoom) {
    const worldSizePx = 256 * Math.pow(2, z);
    const dx = (lonToWorldX(lon) - centerWx) * worldSizePx;
    const dy = (latToWorldY(lat) - centerWy) * worldSizePx;
    return { x: MAP_W / 2 + dx, y: MAP_H / 2 + dy };
  }

  function pixelToLatLon(px, py, z = currentZoom) {
    const worldSizePx = 256 * Math.pow(2, z);
    const wx = centerWx + (px - MAP_W / 2) / worldSizePx;
    const wy = centerWy + (py - MAP_H / 2) / worldSizePx;
    return { lat: worldYToLat(wy), lon: worldXToLon(wx) };
  }

  function clampPan() {
    const vw = viewport.clientWidth;
    const vh = viewport.clientHeight;
    const maxOffX = Math.max(0, MAP_W / 2 - vw / 2);
    const maxOffY = Math.max(0, MAP_H / 2 - vh / 2);
    const offsetX = Math.max(-maxOffX, Math.min(maxOffX, panX + MAP_W / 2));
    const offsetY = Math.max(-maxOffY, Math.min(maxOffY, panY + MAP_H / 2));
    panX = Math.round(offsetX - MAP_W / 2);
    panY = Math.round(offsetY - MAP_H / 2);
  }

  function positionMarkers() {
    document.querySelectorAll('.demo-hotspot').forEach((hs) => {
      const lat = parseFloat(hs.getAttribute('data-lat') || '0');
      const lon = parseFloat(hs.getAttribute('data-lon') || '0');
      const pt = latLonToPixel(lat, lon);
      hs.style.left = `${pt.x}px`;
      hs.style.top = `${pt.y}px`;
      hs.style.display = pt.x >= 20 && pt.x <= MAP_W - 20 && pt.y >= 20 && pt.y <= MAP_H - 20 ? 'flex' : 'none';
    });

    pinsLayer.innerHTML = '';
    droppedPins.forEach((p) => {
      const pt = latLonToPixel(p.lat, p.lon);
      if (pt.x < 0 || pt.x > MAP_W || pt.y < 0 || pt.y > MAP_H) return;
      const pin = document.createElement('img');
      pin.src = 'assets/icons/icon-bookmark.png';
      pin.className = 'dropped-pin pixel';
      pin.style.left = `${pt.x}px`;
      pin.style.top = `${pt.y}px`;
      pinsLayer.appendChild(pin);
    });
  }

  function renderTransform() {
    clampPan();
    // Pure integer translate3d with no CSS scaling — 100% crisp 1:1 pixel art at every zoom level!
    canvas.style.transform = `translate3d(${panX}px, ${panY}px, 0)`;
  }

  function updateMapAsset() {
    mapImg.src = `assets/demo-london-z${currentZoom}-${demoTime}-${demoPack}.png`;
    if (hudZoomBadge) hudZoomBadge.textContent = `Z${currentZoom}`;
    if (hudCarZoomBtn) hudCarZoomBtn.textContent = `Z${currentZoom}`;
    positionMarkers();
    renderTransform();
  }

  function setZoom(nextZ) {
    if (!ZOOMS.includes(nextZ) || nextZ === currentZoom) return;
    // Keep the geographic point currently at the viewport center fixed when changing zoom
    const factor = Math.pow(2, nextZ - currentZoom);
    const offsetX = (panX + MAP_W / 2) * factor;
    const offsetY = (panY + MAP_H / 2) * factor;
    currentZoom = nextZ;
    panX = offsetX - MAP_W / 2;
    panY = offsetY - MAP_H / 2;
    updateMapAsset();
  }

  function centerOnLatLon(lat, lon) {
    const pt = latLonToPixel(lat, lon);
    panX = -pt.x;
    panY = -pt.y;
    renderTransform();
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

  // Texture selector
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

  // Lighting selector
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
    if (e.target.closest('button') || e.target.closest('#demo-info-card') || e.target.closest('#demo-places-drawer')) return;
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

  // Wheel zoom switches between real Z16, Z17, Z18 engine renders
  viewport.addEventListener('wheel', (e) => {
    e.preventDefault();
    const now = Date.now();
    if (now - lastWheelMs < 220) return;
    lastWheelMs = now;
    const idx = ZOOMS.indexOf(currentZoom);
    const nextIdx = e.deltaY < 0 ? Math.min(ZOOMS.length - 1, idx + 1) : Math.max(0, idx - 1);
    setZoom(ZOOMS[nextIdx]);
  }, { passive: false });

  // Double-click to drop a bookmark at constant screen size
  viewport.addEventListener('dblclick', (e) => {
    if (e.target.closest('button') || e.target.closest('#demo-info-card') || e.target.closest('#demo-places-drawer')) return;
    const rect = canvas.getBoundingClientRect();
    const px = e.clientX - rect.left;
    const py = e.clientY - rect.top;
    if (px < 0 || px > MAP_W || py < 0 || py > MAP_H) return;
    const ll = pixelToLatLon(px, py);
    droppedPins.push(ll);
    positionMarkers();

    cardTitle.textContent = 'Saved Bookmark';
    cardSub.textContent = 'Saved place · Dropped just now';
    cardCoords.textContent = `${ll.lat.toFixed(5)}, ${ll.lon.toFixed(5)}`;
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
      placesDrawer?.classList.add('hidden');
    });
  });

  // Interactive HUD buttons (Search bar, Saved Places button, Android Auto Places & Banner)
  const togglePlacesDrawer = () => {
    placesDrawer?.classList.toggle('hidden');
  };
  hudPlacesBtn?.addEventListener('click', togglePlacesDrawer);
  hudSearchBtn?.addEventListener('click', togglePlacesDrawer);
  hudCarPlacesBtn?.addEventListener('click', togglePlacesDrawer);
  placesClose?.addEventListener('click', () => placesDrawer?.classList.add('hidden'));

  document.querySelectorAll('.demo-place-item').forEach((item) => {
    item.addEventListener('click', () => {
      const lat = parseFloat(item.getAttribute('data-jump-lat') || '51.50070');
      const lon = parseFloat(item.getAttribute('data-jump-lon') || '-0.12460');
      const title = item.getAttribute('data-jump-title') || '';
      const sub = item.getAttribute('data-jump-sub') || '';
      centerOnLatLon(lat, lon);
      cardTitle.textContent = title;
      cardSub.textContent = sub;
      cardCoords.textContent = `${lat.toFixed(5)}, ${lon.toFixed(5)}`;
      infoCard.classList.remove('hidden');
      placesDrawer?.classList.add('hidden');
    });
  });

  const carSteps = [
    '<strong>250 m &middot; Turn right onto Westminster Bridge</strong> &middot; 6 min &middot; OSRM',
    '<strong>400 m &middot; Continue along Bridge Street</strong> &middot; 5 min &middot; OSRM',
    '<strong>180 m &middot; Turn left onto Parliament Street</strong> &middot; 3 min &middot; OSRM',
    '<strong>Arrive at Big Ben &amp; Palace of Westminster</strong> &middot; OSRM',
  ];
  let carStepIdx = 0;
  hudCarBannerBtn?.addEventListener('click', () => {
    carStepIdx = (carStepIdx + 1) % carSteps.length;
    if (hudCarBannerText) hudCarBannerText.innerHTML = carSteps[carStepIdx];
  });

  const cycleZoom = () => {
    const nextIdx = (ZOOMS.indexOf(currentZoom) + 1) % ZOOMS.length;
    setZoom(ZOOMS[nextIdx]);
  };
  hudCarZoomBtn?.addEventListener('click', cycleZoom);

  if (cardClose) {
    cardClose.addEventListener('click', () => infoCard.classList.add('hidden'));
  }

  document.getElementById('demo-zoom-in')?.addEventListener('click', () => {
    const idx = ZOOMS.indexOf(currentZoom);
    if (idx < ZOOMS.length - 1) setZoom(ZOOMS[idx + 1]);
  });
  document.getElementById('demo-zoom-out')?.addEventListener('click', () => {
    const idx = ZOOMS.indexOf(currentZoom);
    if (idx > 0) setZoom(ZOOMS[idx - 1]);
  });
  document.getElementById('demo-reset')?.addEventListener('click', () => {
    // Just like in the app: pressing Recenter when already centered cycles zoom (Z16 -> Z17 -> Z18)
    if (Math.abs(panX + MAP_W / 2) < 20 && Math.abs(panY + MAP_H / 2) < 20) {
      cycleZoom();
    }
    panX = -MAP_W / 2;
    panY = -MAP_H / 2;
    droppedPins = [];
    updateMapAsset();
  });

  // Preload all 12 zoom/time/pack renders so switching zoom levels is instantaneous
  ZOOMS.forEach((z) => {
    ['day', 'night'].forEach((t) => {
      ['original', 'minecraft'].forEach((p) => {
        const img = new Image();
        img.src = `assets/demo-london-z${z}-${t}-${p}.png`;
      });
    });
  });

  window.addEventListener('resize', renderTransform);
  updateMapAsset();
})();

// 4. Fetch latest release tag from GitHub API: shows "Download vX.Y.Z" (never both "vX.Y.Z" and "APK" together!)
fetch('https://api.github.com/repos/rh45-one/MineMaps/releases/latest')
  .then((r) => (r.ok ? r.json() : null))
  .then((data) => {
    if (!data || !data.tag_name) return;
    const tag = data.tag_name;
    const apkAsset = (data.assets || []).find((a) => a.name && a.name.endsWith('.apk'));
    document.querySelectorAll('[data-apk-label]').forEach((el) => {
      el.textContent = `Download ${tag}`;
    });
    const fn = document.getElementById('apk-filename');
    if (fn) fn.textContent = `MineMaps ${tag}`;
    if (apkAsset && apkAsset.browser_download_url) {
      document.querySelectorAll('[data-apk-link]').forEach((a) => {
        a.href = apkAsset.browser_download_url;
      });
    }
  })
  .catch(() => {});
