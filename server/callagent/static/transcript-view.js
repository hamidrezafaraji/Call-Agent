/*
 * Renders a call transcript with its phone/card numbers highlighted.
 * Hover (or tap) a number to see it in digits with a copy button.
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

  function makeNumber(spokenText, n) {
    var wrap = document.createElement("span");
    wrap.className = "ca-num ca-num-" + n.type + (n.exact ? "" : " ca-num-inexact");
    wrap.tabIndex = 0;
    wrap.textContent = spokenText;

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
        btn.classList.add("ca-copied");
        btn.title = "کپی شد";
        setTimeout(function () { btn.classList.remove("ca-copied"); btn.title = "کپی"; }, 1500);
      });
    });
    bubble.appendChild(btn);

    if (!n.exact) {
      var warn = document.createElement("span");
      warn.className = "ca-warn";
      warn.textContent = "ارقام غیرعادی؛ با متن چک کنید";
      bubble.appendChild(warn);
    }

    // tap support for touch screens (hover covers the mouse)
    wrap.addEventListener("click", function () {
      var open = wrap.classList.toggle("ca-open");
      document.querySelectorAll(".ca-num.ca-open").forEach(function (el) {
        if (el !== wrap) el.classList.remove("ca-open");
      });
      if (open) wrap.focus();
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

  document.addEventListener("click", function (ev) {
    if (!ev.target.closest || ev.target.closest(".ca-num")) return;
    document.querySelectorAll(".ca-num.ca-open").forEach(function (el) { el.classList.remove("ca-open"); });
  });

  window.CallAgent = window.CallAgent || {};
  window.CallAgent.renderTranscript = renderTranscript;
})();
