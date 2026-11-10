package com.jarvis.assistant.ui.home

import android.content.Context
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse

/**
 * Keeps the WebGL orb smooth on every phone without editing the orb assets.
 *
 * The orb pages are injected with a small governor at load time that:
 *  - caps rendering at 60 fps (on 90/120 Hz screens the orb used to render 90-120 heavy frames per second),
 *  - caps the render resolution (device pixel ratio) by device class instead of 3x,
 *  - watches real frame pacing and steps quality down (resolution, then frame rate) when the GPU can't keep up,
 *    and steps back up slowly with back-off, so it never flaps.
 */
object OrbPerformanceGovernor {

    private val PAGES = setOf("orb.html", "aurora_orb.html", "eleptical_orb.html")

    private val SCRIPT = """
<script>/* jarvis-perf-governor */
(function(){
  if (window.__jarvisGov) return; window.__jarvisGov = true;
  var realDpr = window.devicePixelRatio || 1;
  var mem = navigator.deviceMemory || 4, cores = navigator.hardwareConcurrency || 4;
  var top = (mem >= 6 && cores >= 8) ? 2 : 1.5;
  var LEVELS = [{d: top, f: 60}, {d: Math.min(top, 1.5), f: 60}, {d: 1, f: 45}, {d: 1, f: 30}, {d: 0.75, f: 30}];
  var level = 0, ema = 16.7, prevT = 0, bad = 0, good = 0, warm = 0, downs = 0, lastAdaptT = -1;
  try {
    Object.defineProperty(window, 'devicePixelRatio', {
      configurable: true,
      get: function () { return Math.min(realDpr, LEVELS[level].d); }
    });
  } catch (e) {}
  var raf = window.requestAnimationFrame.bind(window);
  var lastRun = new WeakMap();
  function setLevel(n) {
    if (n === level) return;
    level = n; ema = 16.7; bad = 0; good = 0;
    try { window.dispatchEvent(new Event('resize')); } catch (e) {}
  }
  function adapt(t) {
    if (t === lastAdaptT) return; lastAdaptT = t;
    if (document.hidden) { prevT = 0; return; }
    if (warm < 90) { warm++; prevT = t; return; }
    if (prevT) { var d = t - prevT; if (d > 0 && d < 250) ema = ema * 0.9 + d * 0.1; }
    prevT = t;
    if (ema > 24) { bad++; good = 0; }
    else if (ema < 18) { good++; if (bad > 0) bad--; }
    if (bad > 45 && level < LEVELS.length - 1) { downs++; setLevel(level + 1); }
    else if (level > 0 && good > 1200 * Math.min(8, 1 + downs)) { setLevel(level - 1); }
  }
  window.requestAnimationFrame = function (cb) {
    return raf(function tick(t) {
      adapt(t);
      if (typeof cb === 'function') {
        var gap = 1000 / LEVELS[level].f;
        var lr = lastRun.get(cb) || 0;
        if (lr && t - lr < gap - 2) { raf(tick); return; }
        lastRun.set(cb, t);
      }
      cb(t);
    });
  };
})();
</script>
"""

    /** Returns a patched copy of an orb page, or null to let the WebView load everything else normally. */
    fun intercept(appContext: Context, request: WebResourceRequest?): WebResourceResponse? {
        if (request == null || !request.isForMainFrame) return null
        val uri = request.url ?: return null
        if (uri.scheme != "file") return null
        val name = uri.path.orEmpty().removePrefix("/android_asset/")
        if (name !in PAGES) return null
        return try {
            val html = appContext.assets.open(name).bufferedReader(Charsets.UTF_8).use { it.readText() }
            val head = Regex("<head[^>]*>", RegexOption.IGNORE_CASE).find(html)
            val patched = if (head != null) {
                html.substring(0, head.range.last + 1) + SCRIPT + html.substring(head.range.last + 1)
            } else SCRIPT + html
            WebResourceResponse("text/html", "UTF-8", patched.byteInputStream(Charsets.UTF_8))
        } catch (_: Exception) {
            null
        }
    }
}
