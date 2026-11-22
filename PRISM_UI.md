# jNet Prism UI implementation

The launch screen now uses a Chromium WebView with the user's Prism JS v3 motion and SVG-refraction prototype, adapted to jNet's live Android app.

## Architecture
- PrismActivity.java: Android WebView shell, permissions, native actions, real HostService state, QR dialogs, clipboard, diagnostics, in-app updates.
- jnet-prism.html/css/js: Dashboard, Sharing, Activity, Settings tabs; fluid navigation, press physics, draggable sheet, live charts.
- prism-engine.js: velocity-aware spring primitives extracted from the user's existing FlClash Prism JS v3 prototype.
- prism-glass.js: browser SVG backdrop refraction from that same prototype.
- HostService/SocksProxy/SetupPortal are still native; only start timestamp and notification target changed.
- MainActivity.java remains available under Settings > Advanced for full classic controls.

## Motion and fidelity
This uses Prism JS v3 SpringValue, elasticPress and glass optics.
Floating dock, sheet emergence, and page transitions are spring-retargetable.
Drags from the sheet handle expand the sheet, with content scrolling after expansion.
Chromium SVG displacement does not equal Flutter/Impeller LiquidGlassHost pixel-for-pixel.
WebView engines may fall back to ordinary blur when SVG backdrop filters are unavailable.

## QA
Puppeteer smoke-tested 392x854 and 350x750 viewports: all four tabs and repeated tab switching, connected live-state input, settings sheet, no horizontal overflow, no script errors. Native hotspot and permission flows still require on-device acceptance tests.

## License/attribution
Reused Prism scripts identify GPL-3.0-only provenance and FlClash/prism_ui source. Preserve upstream copyright/license headers. Review GPL obligations before redistributing combined work.


## Android system bars and optical refraction (v0.1.31)

Keep the native FrameLayout WindowInsetsCompat padding from v0.1.30 for status
bar, display cutout, gesture/navigation bar and keyboard. That fix is independent
of glass optics.

The v0.1.30 Android-only CSS blur substitute was **not** real liquid glass.
v0.1.31 removes the substitute and restores the same live, SVG-displaced
backdrop used by the Prism JS v3 prototype.

Optical bug root cause:
- Chromium's feDisplacementMap decodes generated PNG channel values into
  linear RGB. The original displacement texture incorrectly wrote a neutral
  offset as sRGB 128; decoded it is approximately 0.216, not 0.5.
- With a 150px displacement scale that created roughly a 42px phantom shift,
  duplicated text, and transparent/cut-off regions.
- Neutral displacement must be encoded as sRGB about 188 (linear 0.5).
  Every displacement component is now encoded by the sRGB transfer function
  before the map is uploaded. Undefined texture outskirts are filled with
  a neutral backdrop value via feFlood and feComposite.
- The pill base and moving lens are separate sibling SVG backdrop-filter
  surfaces; the parent nav is not an intermediate backdrop root.
- Optical transfer uses a 4097-step LUT. A coalesced requestAnimationFrame
  scheduler initializes maps without the timer-based debounce starvation
  that left the dock transparent on first paint.

This is actual live SVG refraction, NOT an appearance-only blur.
It still cannot claim pixel-identical rendering to Flutter/Impeller and needs
an end-to-end Android WebView acceptance test on the target device.

## Persisted settings (v0.1.31)

- Selected Wi-Fi Direct vs Current Wi-Fi mode: Android SharedPreferences
  (selectedMode), plus WebView localStorage for instant first paint.
  Active HostService mode wins only when it is actually sharing.
- Current tab: saved in native preferences, restored on UI startup.
  Per-tab scroll positions remain when switching tabs.
- Mobile-data preference, Wi-Fi band, MB/s vs Mbps: native preferences.
- Wi-Fi Direct network password: existing private host preferences.
- SOCKS5 proxy password: new ProxySecretStore uses Android Keystore AES-GCM
  and private encrypted preferences. MainActivity and PrismActivity share it.
- Classic settings changes are reloaded on return to the Prism activity.
- Session traffic samples and logs are session data, not persistent settings.

## Regression checks

Puppeteer Android-user-agent, 392px and 350px layouts:
- Inspect real SVG feDisplacementMap (not a CSS blur fallback).
- High-contrast striped backdrop with the word "ALIGN": refracted text
  remains registered to the original image, with no shifted duplicate.
- Dock pill and lens both receive optical filters, and no ancestor
  backdrop-filter isolates the moving lens.
- Mode stays on Wi-Fi Direct across repeated inactive status heartbeats.
- Active service may override the selection only when it reports a recognized
  active mode. Mode/tabs save through the native bridge.
- Repeated tab switching, sheet opening/closing and document scrolling.
- No JS errors or horizontal overflow on narrow viewports.
- Android debug build, lint and unit tests via GitHub Actions.

The actual Android device needs verification for GPU refraction,
hotspot lifecycle, credentials, and system insets before claiming full parity.
