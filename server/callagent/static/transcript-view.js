/*
 * Renders a call transcript with its phone/card numbers highlighted.
 * Tap/click a number to see it in digits with copy and (for phones) call buttons;
 * copying, calling or tapping outside closes it.
 * The spoken text is shown unchanged, so a wrong guess can be checked against it.
 *
 * Usage (call = one item from GET /api/calls):
 *   <link rel="stylesheet" href="/static/transcript-view.css">
 *   <script src="/static/transcript-view.js"></script>
 *   CallAgent.renderTranscript(element, call.transcript, call.numbers);
 */
(function () {
  "use strict";

  function formatNumber(n) {
    var d = n.value;
    if (n.type === "card") return d.replace(/(\d{4})(?=\d)/g, "$1-");
    if (d.length === 11) return d.slice(0, 4) + " " + d.slice(4, 7) + " " + d.slice(7);
    return d;
  }

  function copyText(text) {
    if (navigator.clipboard && window.isSecureContext) {
      return navigator.clipboard.writeText(text);
    }
    var ta = document.createElement("textarea"); // fallback for http:// on the LAN
    ta.value = text;
    ta.style.position = "fixed";
    ta.style.opacity = "0";
    document.body.appendChild(ta);
    ta.select();
    try { document.execCommand("copy"); } finally { document.body.removeChild(ta); }
    return Promise.resolve();
  }

  var COPY_ICON =
    '<svg viewBox="0 0 24 24" width="16" height="16" aria-hidden="true"><path fill="currentColor" ' +
    'd="M16 1H4a2 2 0 0 0-2 2v14h2V3h12V1zm3 4H8a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h11a2 2 0 0 0 ' +
    '2-2V7a2 2 0 0 0-2-2zm0 16H8V7h11v14z"/></svg>';

  var CALL_ICON =
    '<svg viewBox="0 0 24 24" width="16" height="16" aria-hidden="true"><path fill="currentColor" ' +
    'd="M6.62 10.79a15.05 15.05 0 0 0 6.59 6.59l2.2-2.2a1 1 0 0 1 1.02-.24c1.12.37 2.33.57 3.57.57a1 1 0 0 1 ' +
    '1 1V20a1 1 0 0 1-1 1A17 17 0 0 1 3 4a1 1 0 0 1 1-1h3.5a1 1 0 0 1 1 1c0 1.25.2 2.45.57 3.57a1 1 0 0 1-.25 ' +
    '1.02l-2.2 2.2z"/></svg>';

  function makeNumber(spokenText, n) {
    var wrap = document.createElement("span");
    wrap.className = "ca-num ca-num-" + n.type + (n.exact ? "" : " ca-num-inexact");
    wrap.tabIndex = 0;
    wrap.textContent = spokenText;
    // "912 451 822 30" inside Persian text would show its groups in reverse order
    if (!/[؀-ۿ]/.test(spokenText.replace(/[۰-۹]/g, ""))) {
      wrap.dir = "ltr";
      wrap.style.unicodeBidi = "isolate";
    }

    var bubble = document.createElement("span");
    bubble.className = "ca-bubble";
    bubble.setAttribute("role", "tooltip");

    var digits = document.createElement("span");
    digits.className = "ca-digits";
    digits.dir = "ltr";
    digits.textContent = formatNumber(n);
    bubble.appendChild(digits);

    var btn = document.createElement("button");
    btn.type = "button";
    btn.className = "ca-copy";
    btn.title = "کپی";
    btn.innerHTML = COPY_ICON;
    btn.addEventListener("click", function (ev) {
      ev.stopPropagation();
      copyText(n.value).then(function () {
        // flash the green check, then close the bubble
        btn.classList.add("ca-copied");
        setTimeout(function () {
          wrap.classList.remove("ca-open");
          btn.classList.remove("ca-copied");
        }, 400);
      });
    });
    bubble.appendChild(btn);

    if (n.type === "phone") {
      // tel: link opens the phone dialer (or the PC's calling app)
      var call = document.createElement("a");
      call.className = "ca-call";
      call.href = "tel:" + n.value;
      call.title = "تماس";
      call.innerHTML = CALL_ICON;
      call.addEventListener("click", function (ev) {
        ev.stopPropagation();
        setTimeout(function () { wrap.classList.remove("ca-open"); }, 0);
      });
      bubble.appendChild(call);
    }

    if (!n.exact) {
      var warn = document.createElement("span");
      warn.className = "ca-warn";
      warn.textContent = "ارقام غیرعادی؛ با متن چک کنید";
      bubble.appendChild(warn);
    }

    // the bubble opens on tap/click only; taps inside it (e.g. selecting digits) keep it open
    wrap.addEventListener("click", function (ev) {
      if (bubble.contains(ev.target)) return;
      var open = wrap.classList.toggle("ca-open");
      document.querySelectorAll(".ca-num.ca-open").forEach(function (el) {
        if (el !== wrap) el.classList.remove("ca-open");
      });
      if (open) placeBubble(wrap, bubble);
    });

    wrap.appendChild(bubble);
    return wrap;
  }

  function renderTranscript(container, transcript, numbers) {
    container.textContent = "";
    container.classList.add("ca-transcript");
    container.dir = "rtl";
    var text = transcript || "";
    var pos = 0;
    (numbers || [])
      .slice()
      .sort(function (a, b) { return a.start - b.start; })
      .forEach(function (n) {
        if (n.start < pos) return; // overlapping spans: keep the first
        container.appendChild(document.createTextNode(text.slice(pos, n.start)));
        container.appendChild(makeNumber(text.slice(n.start, n.end), n));
        pos = n.end;
      });
    container.appendChild(document.createTextNode(text.slice(pos)));
  }

  /** Above the number (below if there is no room), kept inside the window. */
  function placeBubble(wrap, bubble) {
    var r = wrap.getClientRects()[0] || wrap.getBoundingClientRect(); // first line of a wrapped number
    var margin = 8, gap = 8;
    var w = bubble.offsetWidth, h = bubble.offsetHeight;
    var center = r.left + r.width / 2;
    var left = Math.max(margin, Math.min(center - w / 2, window.innerWidth - w - margin));
    var top = r.top - h - gap, below = false;
    if (top < margin) { top = r.bottom + gap; below = true; }
    bubble.style.left = left + "px";
    bubble.style.top = top + "px";
    bubble.style.setProperty("--ca-arrow", Math.max(12, Math.min(w - 12, center - left)) + "px");
    bubble.classList.toggle("ca-below", below);
  }

  function closeAll() {
    document.querySelectorAll(".ca-num.ca-open").forEach(function (el) { el.classList.remove("ca-open"); });
  }

  document.addEventListener("click", function (ev) {
    if (!ev.target.closest || ev.target.closest(".ca-num")) return;
    closeAll();
  });
  // a fixed bubble would drift away from its number while scrolling
  window.addEventListener("scroll", closeAll, true);
  window.addEventListener("resize", closeAll);

  window.CallAgent = window.CallAgent || {};
  window.CallAgent.renderTranscript = renderTranscript;
})();
