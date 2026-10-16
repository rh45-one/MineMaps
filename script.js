// Interactive texture switcher in the hero showcase
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

// Fetch latest release tag from GitHub API so the download button shows the live version number
fetch('https://api.github.com/repos/rh45-one/MineMaps/releases/latest')
  .then((r) => (r.ok ? r.json() : null))
  .then((data) => {
    if (!data || !data.tag_name) return;
    const label = document.getElementById('download-label');
    const btn = document.getElementById('download-btn');
    if (label) label.textContent = `Download ${data.tag_name} APK`;
    const apkAsset = (data.assets || []).find((a) => a.name && a.name.endsWith('.apk'));
    if (apkAsset && apkAsset.browser_download_url && btn) {
      btn.href = apkAsset.browser_download_url;
    }
  })
  .catch(() => {});
