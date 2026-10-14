"use strict";

(() => {
  const english = {
    skip: "Skip to report", profile: "Player profile", publicProfile: "Public player profile",
    publicUnavailable: "This public profile is unavailable.",
    publicScope: "Statistics shared by this player. Tracking settings affect recorded values.",
    publicLoading: "Loading public profile...", publicRetry: "Reload profile",
    publicFailed: "The profile could not be loaded. Wait a moment and try again.",
    publicEmpty: "This player has no recorded values to share yet.",
    statistics: "Statistics", statistic: "Statistic", value: "Value", details: "Detailed counters",
    bests: "Personal bests", lifetime: "Lifetime", period: "Period", day: "Day", week: "Week", month: "Month", session: "Session", empty: "No retained entries"
  };
  let messages = { ...english };
  let locale = "en-US";
  let generation = 0;
  let active = null;
  let refreshTimer = null;
  const openSections = new Set();
  const byId = id => document.getElementById(id);
  const tr = key => messages[key] || english[key] || key;
  const node = (tag, text, className) => {
    const element = document.createElement(tag);
    if (text !== undefined) element.textContent = String(text);
    if (className) element.className = className;
    return element;
  };
  const identifier = /^\/player\/([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})\/?$/i.exec(location.pathname)?.[1]?.toLowerCase();
  const statName = stat => messages[`stat.${stat.id || stat.stat}`] || stat.name || stat.id || stat.stat;
  const detailName = detail => messages[`details.counter.${detail.id}`] || detail.name || detail.id;

  function localize(catalog) {
    messages = { ...english };
    Object.entries(catalog.messages || {}).forEach(([key, value]) => {
      if (typeof value === "string") messages[key.replace(/^dashboard\./, "")] = value;
    });
    locale = (catalog.locale || "en_US").replaceAll("_", "-");
    try { new Intl.NumberFormat(locale); } catch { locale = "en-US"; }
    document.documentElement.lang = locale;
    document.documentElement.dir = locale.startsWith("he") ? "rtl" : "ltr";
    document.querySelectorAll("[data-text]").forEach(element => { element.textContent = tr(element.dataset.text); });
  }

  function table(headers, rows, label) {
    const wrapper = node("div", undefined, "table-wrap");
    wrapper.tabIndex = 0;
    wrapper.setAttribute("role", "region");
    wrapper.setAttribute("aria-label", tr(label));
    const table = node("table");
    const head = node("thead");
    const heading = node("tr");
    headers.forEach(key => { const cell = node("th", tr(key)); cell.scope = "col"; heading.append(cell); });
    head.append(heading);
    const body = node("tbody");
    rows.forEach(values => { const row = node("tr"); values.forEach(value => row.append(node("td", value))); body.append(row); });
    table.append(head, body);
    wrapper.append(table);
    return wrapper;
  }

  function render(profile) {
    byId("player-name").removeAttribute("data-text");
    byId("player-name").textContent = profile.name || profile.uuid;
    const report = byId("public-report");
    report.replaceChildren();
    const statistics = node("section", undefined, "report-section");
    statistics.append(node("h2", `${tr("statistics")} · ${tr("lifetime")}`));
    statistics.append(profile.statistics.length ? table(["statistic", "value"], profile.statistics.map(stat => [statName(stat), stat.formatted]), "statistics") : node("p", tr("publicEmpty"), "empty"));
    report.append(statistics);
    for (const [key, rows, headers] of [
      ["details", profile.details.map(detail => [detailName(detail), new Intl.NumberFormat(locale).format(BigInt(detail.value))]), ["statistic", "value"]],
      ["bests", profile.bests.map(best => [statName(best), tr(best.period), best.formatted]), ["statistic", "period", "value"]]
    ]) {
      const section = node("details");
      section.dataset.section = key;
      section.open = openSections.has(key);
      section.addEventListener("toggle", () => { if (!section.isConnected) return; if (section.open) openSections.add(key); else openSections.delete(key); });
      section.append(node("summary", `${tr(key)}${key === "details" ? ` · ${tr("lifetime")}` : ""}`));
      section.append(rows.length ? table(headers, rows, key) : node("p", tr("empty"), "muted"));
      report.append(section);
    }
  }

  async function request(path, signal) {
    const response = await fetch(path, { credentials: "omit", cache: "no-store", redirect: "error", referrerPolicy: "no-referrer", signal });
    if (!response.ok) throw new Error(response.status === 404 ? "publicUnavailable" : "publicFailed");
    return response.json();
  }

  async function load() {
    clearTimeout(refreshTimer);
    const focusedSection = document.activeElement?.closest("details")?.dataset.section;
    const current = ++generation;
    active?.abort();
    const controller = new AbortController();
    active = controller;
    const timeout = setTimeout(() => controller.abort(), 20000);
    byId("public-report").replaceChildren();
    byId("public-report").setAttribute("aria-busy", "true");
    byId("player-name").textContent = tr("profile");
    byId("public-status").textContent = tr("publicLoading");
    byId("public-status").classList.remove("error");
    try {
      if (!identifier) throw new Error("publicUnavailable");
      const catalog = await request("/api/public/messages", controller.signal);
      if (current !== generation) return;
      localize(catalog);
      byId("public-status").textContent = tr("publicLoading");
      const profile = await request(`/api/public/profile?${new URLSearchParams({ uuid: identifier })}`, controller.signal);
      if (current !== generation) return;
      render(profile);
      if (focusedSection && document.activeElement === document.body) {
        byId("public-report").querySelector(`details[data-section="${focusedSection}"] summary`)?.focus();
      }
      byId("public-status").textContent = "";
    } catch (error) {
      if (current !== generation) return;
      byId("public-report").replaceChildren();
      byId("player-name").textContent = tr("profile");
      byId("public-status").textContent = tr(error.message === "publicUnavailable" ? "publicUnavailable" : "publicFailed");
      byId("public-status").classList.add("error");
    } finally {
      clearTimeout(timeout);
      if (current === generation) {
        active = null;
        byId("public-report").removeAttribute("aria-busy");
        if (!document.hidden && identifier) refreshTimer = setTimeout(load, 30000);
      }
    }
  }

  byId("retry").addEventListener("click", load);
  document.addEventListener("visibilitychange", () => {
    clearTimeout(refreshTimer);
    if (document.hidden) { generation++; active?.abort(); active = null; byId("public-report").replaceChildren(); byId("player-name").textContent = tr("profile"); }
    else load();
  });
  window.addEventListener("pagehide", () => { clearTimeout(refreshTimer); generation++; active?.abort(); active = null; byId("public-report").replaceChildren(); byId("player-name").textContent = tr("profile"); });
  window.addEventListener("pageshow", event => { if (event.persisted) load(); });
  load();
})();
