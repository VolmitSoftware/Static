"use strict";

(() => {
  const english = {
    skip: "Skip to report", operator: "Operator dashboard", logout: "Sign out", readOnly: "Read-only server reports",
    signIn: "Open your dashboard", tokenHint: "Enter the access token from your server's dashboard/access-token file.",
    token: "Access token", tokenNote: "The token stays in this tab's memory. Closing or reloading the page signs you out.",
    connect: "Connect", overview: "Participation", players: "Players", rankings: "Rankings", refresh: "Refresh report",
    period: "Period", day: "Day", week: "Week", month: "Month", session: "Session", lifetime: "Lifetime", offset: "Windows ago", apply: "Apply",
    search: "Player name or UUID", searchAction: "Search players", statistic: "Statistic", previous: "Previous page", next: "Next page",
    footer: "Statistics collected by this server. Retention and tracking settings affect these reports.",
    loading: "Loading report...", connecting: "Connecting to your server...", expired: "Access was rejected or revoked. Enter a valid token to reconnect.",
    failed: "The report could not be loaded. Check the connection and refresh the report.", invalid: "These filters are outside the server's configured limits. Adjust them and try again.",
    limited: "Too many requests. Wait a moment, then refresh the report.", signedOut: "Signed out. Report data and the token have been cleared from this tab.",
    unique: "Unique players", newPlayers: "First observed in this window", returning: "Returning players", unknown: "Unknown earlier history",
    sessions: "Retained sessions", median: "Median eligible duration", samples: "Completed quit sessions", open: "Open", shutdown: "Shutdown", interrupted: "Interrupted",
    medianScope: "The median uses each retained quit session's full eligible duration when it ends in this window. Open, shutdown and interrupted sessions are excluded.",
    retained: "Retained session history only. Older activity may be missing. Connections count even when statistics tracking excludes their activity.",
    retentionLimited: "Session retention can limit this window. Pruning may reduce participant counts and median samples.",
    noParticipation: "No retained sessions overlap this window. This does not prove there were no players.",
    noSamples: "No completed quit sessions in this window", savedProfiles: "Saved profiles", refreshed: "Report refreshed", timezone: "History time zone",
    player: "Player", recordedSince: "Recorded since", updated: "Last statistic update", rank: "Rank", value: "Value", gap: "Gap to next position",
    noPlayers: "No saved players match this search. Try a different name or UUID.", noRankings: "No eligible entries for this statistic and period.",
    rankingScope: "Rankings use tracked statistics. Excluded profiles do not appear in the ranking.", excluded: "Excluded profiles", total: "Total", page: "Page",
    profile: "Player profile", back: "Back to players", statistics: "Statistics", details: "Detailed counters", bests: "Personal bests", history: "Period history",
    started: "Started", ended: "Ended", eligible: "Eligible", active: "Active", afk: "AFK", outcome: "End reason", quit: "Quit",
    empty: "No retained entries", notFound: "This saved player could not be found. Return to the player directory.",
    historyLimit: "Only the newest retained sessions and history windows are shown, up to the report limit.",
    inProgress: "In progress", recovered: "Recovered", observation: "Observed", seconds: "seconds", noDate: "Not recorded",
    detailRankings: "Detailed rankings", seasons: "Seasons", milestones: "Milestones", comparison: "Compare activity",
    playerUuid: "Player UUID", otherUuid: "Second player UUID (optional)", windows: "Windows", selectPlayer: "Enter a player UUID, or open this report from a player profile.",
    target: "Target", progress: "Progress", completed: "Completed", scheduled: "Scheduled", archived: "Archived", status: "Status", participants: "Participants",
    standings: "Standings", winners: "Winners", noSeasons: "No seasons have been configured.", missing: "Not retained", partial: "Partial window",
    comparisonScope: "Retained calendar observations only. Missing windows are gaps, not zero. Partial windows may cover only part of the period.",
    chart: "Recorded values by window", chartTable: "Chart data", noMilestones: "No milestone targets are available for this player.", historical: "Historical reconciliation"
  };
  let messages = { ...english };
  let locale = "en-US";
  let zone = "UTC";
  let token = "";
  let meta = null;
  let generation = 0;
  let loginGeneration = 0;
  let controllers = new Set();
  let state = { view: "overview", page: 0, uuid: null, profilePeriod: "lifetime" };
  const views = ["overview", "players", "rankings", "detailRankings", "seasons", "milestones", "comparison"];
  const byId = id => document.getElementById(id);
  const tr = key => messages[key] || english[key] || key;
  const statName = stat => messages[`stat.${stat.stat || stat.id}`] || stat.name || stat.stat;
  const detailName = detail => messages[`details.counter.${detail.id}`] || detail.name;
  const node = (tag, text, className) => {
    const result = document.createElement(tag);
    if (text !== undefined && text !== null) result.textContent = String(text);
    if (className) result.className = className;
    return result;
  };
  const number = value => new Intl.NumberFormat(locale, { maximumFractionDigits: 2 }).format(value);
  const date = value => {
    if (value === null || value === undefined || Number(value) < 0) return tr("noDate");
    const instant = new Date(Number(value));
    if (!Number.isFinite(instant.getTime())) return tr("noDate");
    return new Intl.DateTimeFormat(locale, { dateStyle: "medium", timeStyle: "short", timeZone: zone }).format(instant);
  };
  const duration = value => `${number(Number(value) / 1000)} ${tr("seconds")}`;
  const period = value => tr(String(value).toLowerCase());
  const status = (id, key, error = false) => { byId(id).textContent = key ? tr(key) : ""; byId(id).classList.toggle("error", error); };

  function localize() {
    document.documentElement.lang = locale;
    document.documentElement.dir = locale.startsWith("he") ? "rtl" : "ltr";
    document.querySelectorAll("[data-text]").forEach(element => { element.textContent = tr(element.dataset.text); });
    document.querySelector("nav").setAttribute("aria-label", tr("operator"));
  }

  function clearSession(reason) {
    token = "";
    meta = null;
    generation++;
    loginGeneration++;
    controllers.forEach(controller => controller.abort());
    controllers.clear();
    byId("token").value = "";
    byId("report").replaceChildren();
    byId("title").textContent = "";
    byId("zone").textContent = "";
    byId("page-summary").textContent = "";
    byId("rankings-filters").elements.stat.replaceChildren();
    byId("detailRankings-filters").elements.counter.replaceChildren();
    byId("comparison-filters").reset();
    byId("comparison-filters").elements.stat.replaceChildren();
    byId("milestones-filters").reset();
    byId("players-filters").reset();
    byId("overview-filters").reset();
    byId("pagination").hidden = true;
    byId("workspace").hidden = true;
    byId("logout").hidden = true;
    byId("login").hidden = false;
    byId("login-form").querySelector("button").disabled = false;
    byId("report").removeAttribute("aria-busy");
    state = { view: "overview", page: 0, uuid: null, profilePeriod: "lifetime" };
    status("status", "");
    status("login-status", reason, reason === "expired");
  }

  async function api(path, attempt = 0) {
    const controller = new AbortController();
    const activeGeneration = loginGeneration;
    controllers.add(controller);
    const timeout = setTimeout(() => controller.abort(), 20000);
    try {
      const response = await fetch(`api/${path}`, { headers: { Authorization: `Bearer ${token}` }, credentials: "omit", cache: "no-store", signal: controller.signal, redirect: "error" });
      if (activeGeneration !== loginGeneration) throw new DOMException("Aborted", "AbortError");
      if (response.status === 401 || response.status === 403) {
        clearSession("expired");
        byId("token").focus();
        throw new DOMException("Aborted", "AbortError");
      }
      if (response.status === 202) {
        if (attempt >= 8) throw new Error("failed");
        await new Promise(resolve => setTimeout(resolve, 1000));
        if (activeGeneration !== loginGeneration) throw new DOMException("Aborted", "AbortError");
        return await api(path, attempt + 1);
      }
      if (!response.ok) {
        const error = new Error(response.status === 400 ? "invalid" : response.status === 404 ? "notFound" : response.status === 429 ? "limited" : "failed");
        throw error;
      }
      return await response.json();
    } finally {
      clearTimeout(timeout);
      controllers.delete(controller);
    }
  }

  byId("login-form").addEventListener("submit", async event => {
    event.preventDefault();
    const supplied = byId("token").value.trim();
    if (!supplied) return;
    clearSession("");
    token = supplied;
    const request = loginGeneration;
    byId("login-form").querySelector("button").disabled = true;
    status("login-status", "connecting");
    try {
      const result = await api("meta");
      const catalog = await api("messages");
      if (request !== loginGeneration) return;
      meta = result;
      locale = (catalog.locale || "en_US").replaceAll("_", "-");
      try { new Intl.NumberFormat(locale); } catch { locale = "en-US"; }
      zone = meta.historyZone || "UTC";
      messages = { ...english };
      Object.entries(catalog.messages || {}).forEach(([key, value]) => { if (typeof value === "string") messages[key.replace(/^dashboard\./, "")] = value; });
      localize();
      const choices = byId("rankings-filters").elements.stat;
      choices.replaceChildren(...meta.statistics.map(stat => { const option = node("option", statName(stat)); option.value = stat.id; return option; }));
      if (meta.statistics.some(stat => stat.id === "online_time")) choices.value = "online_time";
      byId("comparison-filters").elements.stat.replaceChildren(...Array.from(choices.options, option => option.cloneNode(true)));
      byId("comparison-filters").elements.stat.value = choices.value;
      byId("detailRankings-filters").elements.counter.replaceChildren(...meta.detailKeys.map(detail => { const option = node("option", detailName(detail)); option.value = detail.id; return option; }));
      byId("login").hidden = true;
      byId("workspace").hidden = false;
      byId("logout").hidden = false;
      status("login-status", "");
      showView("overview");
    } catch (error) {
      if (request === loginGeneration) { const key = Object.hasOwn(english, error.message) ? error.message : "failed"; clearSession(key); status("login-status", key, true); }
    } finally {
      if (request === loginGeneration) byId("login-form").querySelector("button").disabled = false;
    }
  });

  function showView(view) {
    state = { ...state, view, page: 0, uuid: null };
    document.querySelectorAll("[data-view]").forEach(button => { if (button.dataset.view === view) button.setAttribute("aria-current", "page"); else button.removeAttribute("aria-current"); });
    for (const name of views) { const form = byId(`${name}-filters`); if (form) form.hidden = name !== view; }
    byId("title").textContent = tr(view);
    byId("main").focus();
    load();
  }

  function appendDefinition(list, label, value) {
    const entry = node("div");
    entry.append(node("dt", tr(label)), node("dd", value));
    list.append(entry);
  }

  function summary(entries) {
    const list = node("dl", null, "summary-list");
    entries.forEach(([label, value]) => appendDefinition(list, label, value));
    return list;
  }

  function table(headers, rows) {
    const wrapper = node("div", null, "table-wrap");
    wrapper.tabIndex = 0;
    wrapper.setAttribute("role", "region");
    wrapper.setAttribute("aria-label", byId("title").textContent);
    const element = node("table");
    const head = node("thead");
    const heading = node("tr");
    headers.forEach(key => { const cell = node("th", typeof key === "string" ? tr(key) : key.text); cell.scope = "col"; heading.append(cell); });
    head.append(heading);
    const body = node("tbody");
    rows.forEach(values => {
      const row = node("tr");
      values.forEach(value => { const cell = node("td"); if (value instanceof Node) cell.append(value); else cell.textContent = String(value ?? ""); row.append(cell); });
      body.append(row);
    });
    element.append(head, body);
    wrapper.append(element);
    return wrapper;
  }

  function playerLink(entry) {
    const button = node("button", entry.name || entry.uuid, "player-link");
    button.type = "button";
    button.addEventListener("click", () => openProfile(entry.uuid));
    return button;
  }

  function windowLine(window) {
    return node("p", `${date(window.startedAt)} / ${date(window.endsAt)}`, "window-line");
  }

  function overview(data, report) {
    const info = data.participation;
    zone = info.historyZone;
    report.append(windowLine(info.window));
    if (!info.uniquePlayers) report.append(node("p", tr("noParticipation"), "empty"));
    const block = node("div", null, "participation");
    const total = node("div");
    total.append(node("p", number(info.uniquePlayers), "primary-number"), node("p", tr("unique"), "number-caption"));
    const cohorts = node("dl", null, "cohorts");
    appendDefinition(cohorts, "newPlayers", number(info.newPlayers));
    appendDefinition(cohorts, "returning", number(info.returningPlayers));
    appendDefinition(cohorts, "unknown", number(info.unknownCohortPlayers));
    block.append(total, cohorts);
    report.append(block);
    const sessions = node("section", null, "report-section");
    sessions.append(node("h2", tr("sessions")), summary([["median", info.medianSessionMillis === null ? tr("noSamples") : duration(info.medianSessionMillis)], ["samples", number(info.completedSessions)], ["open", number(info.openSessions)], ["shutdown", number(info.shutdownSessions)], ["interrupted", number(info.interruptedSessions)]]), node("p", tr("medianScope"), "muted"));
    report.append(sessions, node("p", tr("retained"), "notice"));
    if (info.retentionLimited) report.append(node("p", tr("retentionLimited"), "notice warning"));
    report.append(node("p", `${tr("savedProfiles")}: ${number(data.playerCount)} · ${tr("refreshed")}: ${date(info.refreshedAt)}`, "report-meta"));
  }

  function paginate(data) {
    byId("pagination").hidden = false;
    byId("previous").disabled = data.page <= 0;
    byId("next").disabled = (data.page + 1) * data.size >= data.total;
    byId("page-summary").textContent = `${tr("page")} ${number(data.page + 1)} / ${number(Math.max(1, Math.ceil(data.total / data.size)))} · ${tr("total")}: ${number(data.total)}`;
  }

  function players(data, report) {
    if (!data.entries.length) report.append(node("p", tr("noPlayers"), "empty"));
    else report.append(table(["player", "recordedSince", "updated"], data.entries.map(entry => [playerLink(entry), date(entry.recordedSince), date(entry.updatedAt)])));
    paginate(data);
  }

  function rankings(data, report) {
    if (data.window && String(data.period).toLowerCase() !== "lifetime") report.append(windowLine(data.window));
    if (!data.entries.length) report.append(node("p", tr("noRankings"), "empty"));
    else report.append(table(["rank", "player", "value", "gap"], data.entries.map(entry => [number(entry.rank), playerLink(entry), entry.formatted, entry.gapToNext === null ? "" : number(entry.gapToNext)])));
    report.append(node("p", tr("rankingScope"), "notice"), node("p", `${tr("excluded")}: ${number(data.excludedCount)} · ${tr("refreshed")}: ${date(data.refreshedAt)} · ${tr("recordedSince")}: ${date(data.recordedSince)}`, "report-meta"));
    paginate(data);
  }

  function disclosure(label, child) {
    const element = node("details");
    const scope = label === "details" || label === "sessions" ? ` · ${tr("lifetime")}` : "";
    element.append(node("summary", tr(label) + scope), child);
    return element;
  }

  function profile(data, report) {
    const top = node("div", null, "profile-top");
    const identity = node("div");
    identity.append(node("h2", data.name || data.uuid), node("p", data.uuid, "profile-id"));
    const label = node("label", tr("period"));
    const select = node("select");
    select.id = "profile-period";
    ["lifetime", "day", "week", "month"].forEach(value => { const option = node("option", period(value)); option.value = value; select.append(option); });
    select.value = state.profilePeriod;
    select.addEventListener("change", () => { state.profilePeriod = select.value; load({ restoreProfilePeriodFocus: true }); });
    label.append(select);
    top.append(identity, label);
    report.append(top, summary([["recordedSince", date(data.recordedSince)], ["updated", date(data.updatedAt)]]));
    const actions = node("div", null, "profile-actions");
    for (const view of ["milestones", "comparison"]) {
      const button = node("button", tr(view));
      button.addEventListener("click", () => { byId(`${view}-filters`).elements.uuid.value = data.uuid; showView(view); });
      actions.append(button);
    }
    report.append(actions);
    const stats = node("section", null, "report-section");
    stats.append(node("h2", tr("statistics")), table(["statistic", "value"], data.statistics.map(stat => [statName(stat), stat.formatted])));
    report.append(stats);
    const empty = () => node("p", tr("empty"), "muted");
    report.append(disclosure("details", data.details.length ? table(["statistic", "value", "recordedSince"], data.details.map(detail => [detailName(detail), number(BigInt(detail.value)), date(detail.recordedSince)])) : empty()));
    report.append(disclosure("bests", data.bests.length ? table(["statistic", "period", "value", "observation"], data.bests.map(best => {
      const name = statName(meta.statistics.find(stat => stat.id === best.stat) || best);
      return [name, period(best.period), best.formatted, `${date(best.observedAt)}${best.inProgress ? ` · ${tr("inProgress")}` : ""}${best.recovered ? ` · ${tr("recovered")}` : ""}`];
    })) : empty()));
    report.append(disclosure("sessions", data.sessions.length ? table(["started", "ended", "eligible", "active", "afk", "outcome"], data.sessions.map(session => [date(session.startedAt), session.endedAt === null || Number(session.endedAt) <= 0 ? tr("open") : date(session.endedAt), duration(session.eligibleMillis), duration(session.activeMillis), duration(session.afkMillis), tr(String(session.endReason).toLowerCase())])) : empty()));
    const history = node("div");
    data.history.forEach(item => {
      const entry = node("section", null, "history-row");
      entry.append(node("h3", `${period(item.window.period)} · ${date(item.window.startedAt)}`), summary(item.statistics.map(stat => [statName(stat), stat.formatted])));
      history.append(entry);
    });
    report.append(disclosure("history", data.history.length ? history : empty()), node("p", `${tr("historyLimit")} ${tr("sessions")}: ${number(data.sessions.length)} / ${number(data.sessionsTotal)}. ${tr("history")}: ${number(data.history.length)} / ${number(data.historyTotal)}.`, "notice"));
    const back = node("button", tr("back"));
    back.addEventListener("click", () => showView("players"));
    report.append(back);
  }

  function openProfile(uuid) {
    state.uuid = uuid;
    state.view = "profile";
    state.profilePeriod = "lifetime";
    views.forEach(name => { const form = byId(`${name}-filters`); if (form) form.hidden = true; });
    document.querySelectorAll("[data-view]").forEach(button => button.removeAttribute("aria-current"));
    byId("title").textContent = tr("profile");
    byId("main").focus();
    load();
  }

  function detailRankings(data, report) {
    report.append(node("p", tr("lifetime"), "window-line"));
    if (!data.entries.length) report.append(node("p", tr("noRankings"), "empty"));
    else report.append(table(["rank", "player", "value", "gap"], data.entries.map(entry => [number(entry.rank), playerLink(entry), number(BigInt(entry.value)), number(BigInt(entry.gapToNext))])));
    const recordedSince = Number(data.recordedSince) > 0 ? date(data.recordedSince) : tr("noDate");
    report.append(node("p", `${tr("refreshed")}: ${date(data.refreshedAt)} · ${tr("recordedSince")}: ${recordedSince}`, "report-meta"));
    paginate(data);
  }

  function seasons(data, report) {
    if (!data.entries.length) report.append(node("p", tr("noSeasons"), "empty"));
    else report.append(table(["seasons", "status", "statistic", "started", "ended", "participants"], data.entries.map(entry => {
      const button = node("button", entry.name, "player-link");
      button.addEventListener("click", () => { state.view = "season"; state.season = entry.id; state.page = 0; byId("title").textContent = entry.name; load(); });
      return [button, tr(entry.status.toLowerCase()), statName(entry), date(entry.startedAt), date(entry.endsAt), number(entry.participantCount)];
    })));
    paginate(data);
  }

  function season(data, report) {
    report.append(summary([["status", tr(data.season.status.toLowerCase())], ["statistic", statName(data.season)], ["started", date(data.season.startedAt)], ["ended", date(data.season.endsAt)], ["participants", number(data.season.participantCount)]]));
    if (data.winnersTotal > 0) {
      const winners = node("section", null, "report-section");
      winners.append(node("h2", `${tr("winners")} (${number(data.winners.length)} / ${number(data.winnersTotal)})`), table(["player", "value"], data.winners.map(entry => [playerLink(entry), entry.formatted])));
      report.append(winners);
    }
    report.append(node("h2", tr("standings")));
    rankings(data.ranking, report);
    const back = node("button", tr("seasons"));
    back.addEventListener("click", () => showView("seasons"));
    report.append(back);
  }

  function milestones(data, report) {
    report.append(node("h2", data.name), summary([["completed", `${number(data.completedTotal)} / ${number(data.total)}`]]));
    if (!data.entries.length) report.append(node("p", tr("noMilestones"), "empty"));
    else report.append(table(["statistic", "target", "progress", "status", "observation"], data.entries.map(entry => [statName(entry), entry.thresholdFormatted, entry.formatted, `${tr(entry.completed ? "completed" : "inProgress")}${entry.historical ? ` · ${tr("historical")}` : ""}${entry.archived ? ` · ${tr("archived")}` : ""}`, entry.completedAt === null ? "" : date(entry.completedAt)])));
    paginate(data);
  }

  function comparison(data, report) {
    zone = data.historyZone;
    const descriptor = meta.statistics.find(stat => stat.id === data.stat) || { id: data.stat };
    report.append(node("h2", `${statName(descriptor)} · ${period(data.period)}`), node("p", tr("comparisonScope"), "notice"));
    const namespace = "http://www.w3.org/2000/svg";
    const svgNode = (tag, attributes, text) => {
      const element = document.createElementNS(namespace, tag);
      Object.entries(attributes).forEach(([key, value]) => element.setAttribute(key, String(value)));
      if (text !== undefined) element.textContent = text;
      return element;
    };
    const graph = svgNode("svg", { viewBox: "0 0 640 250", role: "img", "aria-labelledby": "comparison-chart-title", class: "comparison-chart" });
    graph.append(svgNode("title", { id: "comparison-chart-title" }, `${tr("chart")}: ${statName(descriptor)}`));
    const observed = data.players.flatMap(player => player.points.filter(point => point !== null));
    const highest = observed.reduce((best, point) => best === null || point.value > best.value ? point : best, null);
    const max = highest?.value > 0 ? highest.value : 1;
    const x = index => 70 + index * (540 / Math.max(1, data.windows.length - 1));
    const y = value => 210 - value / max * 175;
    graph.append(svgNode("line", { x1: 70, x2: 610, y1: 210, y2: 210, class: "chart-axis" }));
    if (highest?.value > 0) graph.append(svgNode("text", { x: 70, y: 20, "text-anchor": "start", class: "chart-label" }, highest.formatted));
    graph.append(svgNode("text", { x: 60, y: 215, "text-anchor": "end", class: "chart-label" }, "0"));
    const legend = node("ul", null, "chart-legend");
    data.players.forEach((player, playerIndex) => {
      legend.append(node("li", player.name, `chart-series-${playerIndex}`));
      let segment = [];
      const flush = () => { if (segment.length > 1) graph.append(svgNode("polyline", { points: segment.join(" "), class: `chart-series-${playerIndex}` })); segment = []; };
      player.points.forEach((point, index) => {
        if (point === null) { flush(); return; }
        segment.push(`${x(index)},${y(point.value)}`);
        graph.append(svgNode("circle", { cx: x(index), cy: y(point.value), r: playerIndex === 0 ? 4 : 2.5, class: `chart-series-${playerIndex}` }));
      });
      flush();
    });
    data.windows.forEach((window, index) => { graph.append(svgNode("text", { x: x(index), y: 235, "text-anchor": "middle", class: "chart-label" }, number(index + 1))); });
    if (observed.length) report.append(graph, legend);
    else report.append(node("p", tr("empty"), "empty"));
    report.append(node("h3", tr("chartTable")));
    report.append(table(["windows", "started", ...data.players.map(player => ({ text: player.name }))], data.windows.map((window, index) => [number(index + 1), date(window.startedAt), ...data.players.map(player => {
      const point = player.points[index];
      return point === null ? tr("missing") : `${point.formatted}${Number(point.recordedSince) > Number(window.startedAt) || index === data.windows.length - 1 ? ` · ${tr("partial")}` : ""} · ${tr("recordedSince")}: ${date(point.recordedSince)}`;
    })])));
  }

  async function load(options = {}) {
    if (!token) return;
    const request = ++generation;
    const view = state.view;
    const report = byId("report");
    report.replaceChildren();
    report.setAttribute("aria-busy", "true");
    byId("pagination").hidden = true;
    status("status", "loading");
    let path;
    if (view === "overview") {
      const form = byId("overview-filters");
      path = `overview?${new URLSearchParams({ period: form.elements.period.value, offset: form.elements.offset.value })}`;
    } else if (view === "players") path = `players?${new URLSearchParams({ q: byId("players-filters").elements.query.value.trim(), page: state.page, size: 25 })}`;
    else if (view === "rankings") {
      const form = byId("rankings-filters");
      path = `rankings?${new URLSearchParams({ stat: form.elements.stat.value, period: form.elements.period.value, page: state.page, size: 25 })}`;
    } else if (view === "detailRankings") path = `detail-rankings?${new URLSearchParams({ counter: byId("detailRankings-filters").elements.counter.value, page: state.page, size: 25 })}`;
    else if (view === "seasons") path = `seasons?${new URLSearchParams({ page: state.page, size: 25 })}`;
    else if (view === "season") path = `season?${new URLSearchParams({ id: state.season, page: state.page, size: 25 })}`;
    else if (view === "milestones" || view === "comparison") {
      const form = byId(`${view}-filters`);
      if (!form.elements.uuid.value.trim()) { status("status", "selectPlayer"); report.removeAttribute("aria-busy"); return; }
      const params = new URLSearchParams({ uuid: form.elements.uuid.value.trim() });
      if (view === "comparison") {
        for (const key of ["stat", "period", "count"]) params.set(key, form.elements[key].value);
        if (form.elements.other.value.trim()) params.set("other", form.elements.other.value.trim());
      } else { params.set("page", state.page); params.set("size", 25); }
      path = `${view}?${params}`;
    } else path = `profile?${new URLSearchParams({ uuid: state.uuid, period: state.profilePeriod })}`;
    try {
      const data = await api(path);
      if (request !== generation || !token) return;
      ({ overview, players, rankings, profile, detailRankings, seasons, season, milestones, comparison })[view](data, report);
      if (view === "profile" && options.restoreProfilePeriodFocus === true && document.activeElement === document.body) {
        byId("profile-period").focus();
      }
      byId("zone").textContent = `${tr("timezone")}: ${zone}`;
      status("status", "");
    } catch (error) {
      if (request === generation && token) status("status", Object.hasOwn(english, error.message) ? error.message : "failed", true);
    } finally {
      if (request === generation) report.removeAttribute("aria-busy");
    }
  }

  document.querySelectorAll("[data-view]").forEach(button => button.addEventListener("click", () => showView(button.dataset.view)));
  for (const view of views) byId(`${view}-filters`)?.addEventListener("submit", event => { event.preventDefault(); state.page = 0; load(); });
  byId("previous").addEventListener("click", () => { state.page = Math.max(0, state.page - 1); load(); });
  byId("next").addEventListener("click", () => { state.page++; load(); });
  byId("refresh").addEventListener("click", load);
  byId("logout").addEventListener("click", () => { clearSession("signedOut"); byId("token").focus(); });
  window.addEventListener("pagehide", () => clearSession("signedOut"));
})();
