/* Worksync UI behaviour — progressive enhancement over server-rendered HTML.
   All handlers are attached via delegation so every page works identically. */
(function () {
  "use strict";

  /* 1. Submit feedback: disable the submit button while a form is posting and
     swap its label for a busy variant (form button[data-submit-label]). */
  document.addEventListener("submit", function (e) {
    var btn = e.target.querySelector("button[type=submit][data-submit-label]");
    if (!btn) return;
    var orig = btn.textContent;
    var busy = btn.getAttribute("data-submit-label");
    btn.disabled = true;
    btn.setAttribute("data-loading", "true");
    btn.textContent = busy;
    /* re-enable if the request is blocked (validation bubbles etc.) */
    setTimeout(function () {
      if (!document.body.contains(e.target)) return;
      if (e.defaultPrevented) {
        btn.disabled = false;
        btn.removeAttribute("data-loading");
        btn.textContent = orig;
      }
    }, 50);
  });

  /* 2. Inline confirmation for destructive forms: <form data-confirm="…"> */
  document.addEventListener("submit", function (e) {
    var form = e.target;
    if (!form.matches || !form.matches("form[data-confirm]")) return;
    var msg = form.getAttribute("data-confirm") || "Are you sure?";
    if (!window.confirm(msg)) e.preventDefault();
  }, true);

  /* 3. Show / hide password: <button type=button data-pw-toggle> inside .pwwrap */
  document.addEventListener("click", function (e) {
    var btn = e.target.closest ? e.target.closest("[data-pw-toggle]") : null;
    if (!btn) return;
    var wrap = btn.closest(".pwwrap");
    if (!wrap) return;
    var input = wrap.querySelector("input[type=password], input[type=text]");
    if (!input) return;
    var show = input.type === "password";
    input.type = show ? "text" : "password";
    btn.textContent = show ? "Hide" : "Show";
    input.focus();
  });

  /* 4. Live list filtering: input[data-list-filter] narrows rows whose
     [data-filter-value] (or text content) contains the query. */
  document.addEventListener("input", function (e) {
    var input = e.target;
    if (!input.matches || !input.matches("[data-list-filter]")) return;
    var scope = document.querySelector(input.getAttribute("data-list-scope") || "[data-filter-list]");
    if (!scope) return;
    var q = (input.value || "").trim().toLowerCase();
    var rows = scope.querySelectorAll("tbody tr");
    var visible = 0;
    rows.forEach(function (row) {
      var hay = (row.getAttribute("data-filter-value") || row.textContent || "").toLowerCase();
      var show = q === "" || hay.indexOf(q) !== -1;
      row.style.display = show ? "" : "none";
      if (show) visible++;
    });
    var none = scope.querySelector("[data-filter-empty]");
    if (none) none.style.display = visible === 0 ? "" : "none";
  });

  /* 5. Flash messages fade after 4s (prefers-reduced-motion handled in CSS). */
  document.addEventListener("DOMContentLoaded", function () {
    document.querySelectorAll(".flash").forEach(function (f) {
      setTimeout(function () {
        f.classList.add("fade");
        setTimeout(function () {
          if (f.parentNode) f.parentNode.removeChild(f);
        }, 620);
      }, 4200);
    });
  });

  /* 6. Relative timestamps: elements with data-ts (epoch millis) get a
     humanized local label. Server keeps an absolute attribute for a11y. */
  function rel(ms) {
    var diff = Date.now() - ms;
    if (diff < 0) diff = 0;
    var min = 60e3, hour = 3600e3, day = 86400e3;
    if (diff < min) return "just now";
    if (diff < hour) return Math.round(diff / min) + "m ago";
    if (diff < day) return Math.round(diff / hour) + "h ago";
    if (diff < 7 * day) return Math.round(diff / day) + "d ago";
    var d = new Date(ms);
    return d.toLocaleDateString(undefined, { month: "short", day: "numeric" });
  }
  document.addEventListener("DOMContentLoaded", function () {
    document.querySelectorAll("[data-ts]").forEach(function (el) {
      var ms = parseInt(el.getAttribute("data-ts"), 10);
      if (!isNaN(ms)) el.textContent = rel(ms);
    });
  });
})();
