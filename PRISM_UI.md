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

