const state = { map: null, issues: [], services: new Map(), cy: null, activeIssue: null, context: { area: null, areas: [], links: {} }, areaNames: [] };

const AREA_COLORS = ["#38bdf8", "#a3e635", "#f0abfc", "#fbbf24", "#2dd4bf", "#fb923c"];

const $ = (selector) => document.querySelector(selector);

const esc = (value) => String(value ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[c]);

const serviceId = (name) => "s:" + name;
const topicId = (name) => "t:" + name;
const areaId = (name) => "a:" + name;

function areaColor(area) {
  const index = state.areaNames.indexOf(area);
  return index < 0 ? color("ghost") : AREA_COLORS[index % AREA_COLORS.length];
}

function areaBadge(area) {
  if (!area) return "";
  return `<span class="badge area" style="--c: ${areaColor(area)}">${esc(area)}</span>`;
}

function serviceRef(name, area) {
  const known = area ?? state.services.get(name)?.area;
  return serviceButton(name) + (known && known !== state.context.area ? areaBadge(known) : "");
}

async function getJson(url) {
  const response = await fetch(url);
  if (!response.ok) {
    throw new Error(url + " returned " + response.status);
  }
  return response.json();
}

function color(name) {
  return getComputedStyle(document.documentElement).getPropertyValue("--" + name).trim();
}

function sourceLink(serviceName, location) {
  if (!location) {
    return "";
  }
  const service = state.services.get(serviceName);
  const match = /^(.*?):(\d+)$/.exec(location);
  if (!service?.repository || !match) {
    return `<span class="mono">${esc(location)}</span>`;
  }
  const commit = service.commitSha && service.commitSha !== "unknown" && !service.commitSha.startsWith("jar:") ? service.commitSha : "HEAD";
  const blob = service.repository.includes("gitlab") ? "/-/blob/" : "/blob/";
  const url = `${service.repository}${blob}${commit}/${match[1]}#L${match[2]}`;
  return `<a class="mono" href="${esc(url)}" target="_blank" rel="noopener">${esc(location)}</a>`;
}

function repositoryLink(service) {
  if (!service?.repository) {
    return `<span class="muted">repositório desconhecido</span>`;
  }
  return `<a href="${esc(service.repository)}" target="_blank" rel="noopener">${esc(service.repository.replace(/^https?:\/\//, ""))}</a>`;
}

function serviceButton(name) {
  return `<button type="button" class="link" data-service="${esc(name)}">${esc(name)}</button>`;
}

function topicButton(name) {
  return `<button type="button" class="link" data-topic="${esc(name)}">${esc(name)}</button>`;
}

function buildElements(map) {
  const elements = [];
  const nodeIds = new Set();
  const areas = [...new Set(map.services.map((s) => s.area).filter(Boolean))];
  for (const area of areas) {
    const classes = ["area"];
    if (state.context.area === area) classes.push("home");
    else if (state.context.area) classes.push("foreign");
    elements.push({ data: { id: areaId(area), label: area, kind: "area", name: area, color: areaColor(area) }, classes: classes.join(" ") });
  }
  const addService = (name) => {
    if (nodeIds.has(serviceId(name))) {
      return;
    }
    const service = state.services.get(name) ?? { name, indexed: false };
    nodeIds.add(serviceId(name));
    const classes = ["service"];
    if (service.external) classes.push("external");
    else if (!service.indexed && !service.contractSource) classes.push("ghost");
    if (service.contractSource) classes.push("contract");
    const data = { id: serviceId(name), label: name, kind: "service", name, w: Math.max(90, name.length * 7.6 + 28), color: areaColor(service.area) };
    if (service.area) data.parent = areaId(service.area);
    elements.push({ data, classes: classes.join(" ") });
  };
  map.services.forEach((s) => addService(s.name));
  map.topics.forEach((t) => {
    nodeIds.add(topicId(t));
    elements.push({ data: { id: topicId(t), label: t, kind: "topic", name: t }, classes: "topic" });
  });

  const observedCalls = new Map(map.observedCalls.map((o) => [o.source + ">" + o.target, o]));
  for (const call of map.calls) {
    addService(call.source);
    addService(call.target);
    const protocols = call.protocols.length ? call.protocols : ["http"];
    const observed = observedCalls.get(call.source + ">" + call.target);
    observedCalls.delete(call.source + ">" + call.target);
    for (const protocol of protocols) {
      const via = protocol === "graphql" ? ["graphql"] : call.via.filter((v) => v !== "graphql");
      elements.push({
        data: {
          id: `c:${call.source}>${call.target}:${protocol}`, source: serviceId(call.source), target: serviceId(call.target),
          kind: "call", protocol, caller: call.source, callee: call.target, label: via.join(" · ") || protocol, observed: observed ?? null,
        },
        classes: ["call", protocol, call.protocols.length ? "" : "loose", observed ? "confirmed" : ""].join(" ").trim(),
      });
    }
  }
  for (const observed of observedCalls.values()) {
    addService(observed.source);
    addService(observed.target);
    elements.push({
      data: {
        id: `r:${observed.source}>${observed.target}`, source: serviceId(observed.source), target: serviceId(observed.target),
        kind: "runtime", caller: observed.source, callee: observed.target, label: observed.origin + (observed.count ? " · " + observed.count : ""), observed,
      },
      classes: "runtime",
    });
  }

  const declared = new Set(map.consumes.map((c) => c.topic + ">" + c.service));
  for (const p of map.publishes) {
    elements.push({ data: { id: `p:${p.service}>${p.topic}`, source: serviceId(p.service), target: topicId(p.topic), kind: "publish", label: p.via }, classes: "kafka" });
  }
  const seen = new Set(map.observedConsuming.map((o) => o.topic + ">" + o.service));
  for (const c of map.consumes) {
    const key = c.topic + ">" + c.service;
    elements.push({
      data: { id: `k:${key}`, source: topicId(c.topic), target: serviceId(c.service), kind: "consume", label: c.via },
      classes: "kafka" + (seen.has(key) ? " confirmed" : ""),
    });
  }
  for (const o of map.observedConsuming) {
    const key = o.topic + ">" + o.service;
    if (declared.has(key)) continue;
    addService(o.service);
    elements.push({ data: { id: `o:${key}`, source: topicId(o.topic), target: serviceId(o.service), kind: "observed-consume", label: "visto no Kafka" }, classes: "runtime kafka-runtime" });
  }
  return elements;
}

function cyStyle() {
  const font = '"Inter", system-ui, -apple-system, "Segoe UI", Roboto, sans-serif';
  return [
    { selector: "core", style: { "active-bg-opacity": 0, "selection-box-opacity": 0 } },
    {
      selector: "node",
      style: {
        label: "data(label)", "font-family": font, "font-size": 12, color: color("text"), "text-wrap": "wrap", "text-max-width": 170,
        "overlay-opacity": 0, "outline-width": 0, "outline-color": color("accent"), "outline-opacity": 0.7, "outline-offset": 4,
      },
    },
    {
      selector: "node.service",
      style: {
        shape: "round-rectangle", width: "data(w)", height: 40, "background-fill": "linear-gradient",
        "background-gradient-stop-colors": `${color("service")} ${color("service-2")}`, "background-gradient-direction": "to-bottom-right",
        color: color("service-text"), "text-valign": "center", "text-halign": "center", "font-weight": 600, "font-size": 13,
        "border-width": 1, "border-color": "#ffffff", "border-opacity": 0.25,
      },
    },
    {
      selector: "node.service.ghost",
      style: {
        "background-fill": "solid", "background-color": color("ghost"), "background-opacity": 0.08, "border-width": 1.5, "border-style": "dashed",
        "border-color": color("ghost"), "border-opacity": 1, color: color("muted"),
      },
    },
    {
      selector: "node.service.contract",
      style: { "background-fill": "solid", "background-color": color("service"), "background-opacity": 0.12, "border-color": color("service"), "border-opacity": 1, "border-width": 1.5, color: color("text") },
    },
    {
      selector: "node.service.external",
      style: {
        "background-fill": "solid", "background-color": "data(color)", "background-opacity": 0.1, "border-width": 1.5, "border-style": "solid",
        "border-color": "data(color)", "border-opacity": 0.75, color: color("text"),
      },
    },
    {
      selector: "node.area",
      style: {
        shape: "round-rectangle", "background-color": "data(color)", "background-opacity": 0.04, "border-width": 1, "border-style": "dashed",
        "border-color": "data(color)", "border-opacity": 0.4, padding: 34, label: "data(label)", "text-transform": "uppercase",
        "text-valign": "top", "text-halign": "center", "text-margin-y": -6, color: "data(color)", "font-size": 12.5, "font-weight": 700,
        "text-opacity": 0.85,
      },
    },
    { selector: "node.area.home", style: { "background-opacity": 0.075, "border-style": "solid", "border-opacity": 0.65, "border-width": 1.2 } },
    { selector: "node.area.foreign", style: { "background-opacity": 0.025, "border-opacity": 0.3, "text-opacity": 0.6 } },
    { selector: "node.area.hover, node.area.focus", style: { "outline-width": 0, "border-opacity": 0.9 } },
    {
      selector: "node.topic",
      style: {
        shape: "ellipse", width: 20, height: 20, "background-fill": "radial-gradient", "background-gradient-stop-colors": `#ffffff ${color("topic")} ${color("topic")}`,
        "background-gradient-stop-positions": "0% 40% 100%", "border-width": 0,
        "text-valign": "bottom", "text-margin-y": 12, color: color("muted"), "font-size": 11.5,
      },
    },
    { selector: "node.hover, node.focus", style: { "outline-width": 1.5 } },
    { selector: "node.topic.hover, node.topic.focus", style: { color: color("text") } },
    {
      selector: "edge",
      style: {
        width: 1.6, "curve-style": "bezier", "control-point-step-size": 56, "target-arrow-shape": "triangle", "arrow-scale": 0.75,
        "line-cap": "round", opacity: 0.45, "overlay-opacity": 0,
        label: "data(label)", "font-family": font, "font-size": 10, color: color("text"), "text-opacity": 0, "text-rotation": "autorotate",
        "text-background-color": "#0d1220", "text-background-opacity": 0.9, "text-background-padding": "3px", "text-background-shape": "round-rectangle",
      },
    },
    { selector: "edge.http", style: { "line-color": color("http"), "target-arrow-color": color("http") } },
    { selector: "edge.graphql", style: { "line-color": color("graphql"), "target-arrow-color": color("graphql") } },
    { selector: "edge.kafka", style: { "line-color": color("kafka"), "target-arrow-color": color("kafka") } },
    { selector: "edge.loose", style: { "line-style": "dotted" } },
    { selector: "edge.runtime", style: { "line-color": color("runtime"), "target-arrow-color": color("runtime"), "line-style": "dashed", "line-dash-pattern": [1, 7], width: 3 } },
    { selector: "edge.issue-warning", style: { "line-color": color("warning"), "target-arrow-color": color("warning"), opacity: 0.95 } },
    { selector: "edge.issue-error", style: { "line-color": color("error"), "target-arrow-color": color("error"), opacity: 0.95 } },
    { selector: "edge.focus, edge.hover", style: { "text-opacity": 1, opacity: 1, width: 2.4, "z-index": 10 } },
    { selector: ".faded", style: { opacity: Number(color("fade")) } },
    { selector: "edge.faded", style: { "text-opacity": 0 } },
    { selector: ".hidden", style: { display: "none" } },
  ];
}

const reducedMotion = () => window.matchMedia("(prefers-reduced-motion: reduce)").matches;

function rgba(hex, alpha) {
  const value = hex.replace("#", "");
  const n = parseInt(value.length === 3 ? value.split("").map((c) => c + c).join("") : value, 16);
  return `rgba(${(n >> 16) & 255}, ${(n >> 8) & 255}, ${n & 255}, ${Math.max(0, Math.min(1, alpha))})`;
}

function hash(text) {
  let h = 0;
  for (let i = 0; i < text.length; i++) h = (h * 31 + text.charCodeAt(i)) | 0;
  return (Math.abs(h) % 1000) / 1000;
}

function fxCanvas(className) {
  const canvas = document.createElement("canvas");
  canvas.className = "fx " + className;
  $("#graph").insertAdjacentElement(className === "fx-back" ? "beforebegin" : "afterend", canvas);
  return canvas;
}

function sizeCanvas(canvas, ratio) {
  const host = $("#graph");
  const cssWidth = host.clientWidth;
  const cssHeight = host.clientHeight;
  const w = Math.round(cssWidth * ratio);
  const h = Math.round(cssHeight * ratio);
  if (canvas.width !== w || canvas.height !== h) {
    canvas.width = w;
    canvas.height = h;
    canvas.style.width = cssWidth + "px";
    canvas.style.height = cssHeight + "px";
  }
  const ctx = canvas.getContext("2d");
  ctx.setTransform(ratio, 0, 0, ratio, 0, 0);
  ctx.clearRect(0, 0, cssWidth, cssHeight);
  return ctx;
}

function edgeColor(edge, palette) {
  if (edge.hasClass("issue-error")) return palette.error;
  if (edge.hasClass("issue-warning")) return palette.warning;
  if (edge.hasClass("runtime")) return palette.runtime;
  if (edge.hasClass("graphql")) return palette.graphql;
  if (edge.hasClass("kafka")) return palette.kafka;
  return palette.http;
}

function animateFlow() {
  const back = fxCanvas("fx-back");
  const front = fxCanvas("fx-front");
  const palette = Object.fromEntries(["service", "service-2", "topic", "http", "graphql", "kafka", "runtime", "warning", "error"].map((k) => [k, color(k)]));
  const still = reducedMotion();
  const step = (time) => {
    const cy = state.cy;
    const ratio = window.devicePixelRatio || 1;
    const glow = sizeCanvas(back, ratio);
    const sparks = sizeCanvas(front, ratio);
    if (cy && !cy.destroyed()) {
      const zoom = cy.zoom();
      const breathe = still ? 0 : Math.sin(time / 1400);
      glow.globalCompositeOperation = "lighter";
      cy.nodes(":visible").forEach((n) => {
        if (n.hasClass("ghost") || n.isParent()) return;
        const alpha = n.numericStyle("opacity");
        if (alpha < 0.02) return;
        const p = n.renderedPosition();
        const boost = n.hasClass("hover") ? 1.5 : n.hasClass("focus") ? 1.25 : 1;
        const issue = n.hasClass("issue-error") ? palette.error : n.hasClass("issue-warning") ? palette.warning : null;
        const base = issue ?? (n.hasClass("topic") ? palette.topic : n.hasClass("external") ? n.data("color") : palette.service);
        const radius = (Math.max(n.renderedWidth(), n.renderedHeight()) * 0.85 + 26 * zoom) * boost * (1 + breathe * 0.05);
        const gradient = glow.createRadialGradient(p.x, p.y, 0, p.x, p.y, radius);
        gradient.addColorStop(0, rgba(base, 0.38 * alpha * boost));
        gradient.addColorStop(0.45, rgba(n.hasClass("topic") || n.hasClass("external") || issue ? base : palette["service-2"], 0.12 * alpha * boost));
        gradient.addColorStop(1, rgba(base, 0));
        glow.fillStyle = gradient;
        glow.beginPath();
        glow.arc(p.x, p.y, radius, 0, Math.PI * 2);
        glow.fill();
      });
      if (!still) {
        sparks.globalCompositeOperation = "lighter";
        cy.edges(":visible").forEach((e) => {
          const alpha = e.numericStyle("opacity");
          if (alpha < 0.05) return;
          const s = e.renderedSourceEndpoint();
          const t = e.renderedTargetEndpoint();
          const m = e.renderedMidpoint();
          const c = { x: 2 * m.x - (s.x + t.x) / 2, y: 2 * m.y - (s.y + t.y) / 2 };
          const length = Math.hypot(t.x - s.x, t.y - s.y);
          if (length < 4) return;
          const active = e.hasClass("hover") || e.hasClass("focus");
          const count = Math.max(1, Math.min(5, Math.round(length / 110))) + (active ? 1 : 0);
          const period = (e.hasClass("runtime") ? 3600 : 2400) * Math.max(0.6, length / 260);
          const hue = edgeColor(e, palette);
          const size = Math.max(1.4, Math.min(3.2, 2.2 * zoom)) * (active ? 1.35 : 1);
          sparks.shadowColor = hue;
          sparks.shadowBlur = 14;
          sparks.fillStyle = rgba("#ffffff", 0.9 * Math.min(1, alpha * 1.6));
          for (let k = 0; k < count; k++) {
            const u = (time / period + k / count + hash(e.id())) % 1;
            const a = (1 - u) * (1 - u);
            const b = 2 * (1 - u) * u;
            const d = u * u;
            const x = a * s.x + b * c.x + d * t.x;
            const y = a * s.y + b * c.y + d * t.y;
            const fade = Math.sin(u * Math.PI);
            sparks.globalAlpha = fade;
            sparks.beginPath();
            sparks.arc(x, y, size, 0, Math.PI * 2);
            sparks.fill();
          }
          sparks.globalAlpha = 1;
        });
        const pulse = 2.6 + Math.sin(time / 240) * 1.2;
        cy.edges(".issue-warning, .issue-error").style("width", pulse);
      }
    }
    requestAnimationFrame(step);
  };
  requestAnimationFrame(step);
}

function matchAspect(cy) {
  const nodes = cy.nodes(":childless");
  if (nodes.length < 3) return;
  const box = nodes.boundingBox();
  const target = cy.width() / Math.max(cy.height(), 1);
  const current = box.w / Math.max(box.h, 1);
  const sx = Math.min(2.2, Math.max(0.45, Math.sqrt(target / current)));
  const sy = 1 / sx;
  const cx = (box.x1 + box.x2) / 2;
  const cyc = (box.y1 + box.y2) / 2;
  cy.batch(() => nodes.forEach((n) => {
    const p = n.position();
    n.position({ x: cx + (p.x - cx) * sx, y: cyc + (p.y - cyc) * sy });
  }));
}

function arrangeAreas(cy) {
  const parents = cy.nodes(":parent");
  if (parents.empty()) return false;
  const units = [];
  parents.forEach((p) => units.push({ nodes: p.children(), pad: 46 }));
  cy.nodes(":childless").filter((n) => !n.isChild()).forEach((n) => units.push({ nodes: n, pad: 16 }));
  const box = (u) => {
    const b = u.nodes.boundingBox();
    return { x1: b.x1 - u.pad, y1: b.y1 - u.pad, x2: b.x2 + u.pad, y2: b.y2 + u.pad };
  };
  const shift = (u, dx, dy) => u.nodes.forEach((n) => {
    const p = n.position();
    n.position({ x: p.x + dx, y: p.y + dy });
  });
  cy.batch(() => {
    const all = cy.nodes(":childless").boundingBox();
    const target = cy.width() / Math.max(cy.height(), 1);
    const sx = Math.min(2.4, Math.max(0.5, Math.sqrt(target / (all.w / Math.max(all.h, 1)))));
    const cx = (all.x1 + all.x2) / 2;
    const cyc = (all.y1 + all.y2) / 2;
    units.forEach((u) => {
      const b = box(u);
      const ux = (b.x1 + b.x2) / 2;
      const uy = (b.y1 + b.y2) / 2;
      shift(u, (ux - cx) * (sx - 1), (uy - cyc) * (1 / sx - 1));
    });
    for (let iteration = 0; iteration < 200; iteration++) {
      let moved = false;
      for (let i = 0; i < units.length; i++) {
        for (let j = i + 1; j < units.length; j++) {
          const a = box(units[i]);
          const b = box(units[j]);
          const ox = Math.min(a.x2, b.x2) - Math.max(a.x1, b.x1);
          const oy = Math.min(a.y2, b.y2) - Math.max(a.y1, b.y1);
          if (ox <= 0 || oy <= 0) continue;
          moved = true;
          const dirX = (a.x1 + a.x2) / 2 <= (b.x1 + b.x2) / 2 ? -1 : 1;
          const dirY = (a.y1 + a.y2) / 2 <= (b.y1 + b.y2) / 2 ? -1 : 1;
          const heavy = (u) => (u.nodes.length > 1 ? 1 : 0);
          const share = heavy(units[i]) === heavy(units[j]) ? 0.5 : heavy(units[i]) ? 0.2 : 0.8;
          if (ox * target < oy) {
            shift(units[i], dirX * (ox + 2) * share, 0);
            shift(units[j], -dirX * (ox + 2) * (1 - share), 0);
          } else {
            shift(units[i], 0, dirY * (oy + 2) * share);
            shift(units[j], 0, -dirY * (oy + 2) * (1 - share));
          }
        }
      }
      if (!moved) break;
    }
  });
  return true;
}

function intro(cy) {
  if (!arrangeAreas(cy)) matchAspect(cy);
  cy.fit(undefined, 70);
  if (reducedMotion() || cy.nodes().empty()) return;
  const zoom = cy.zoom();
  const pan = { ...cy.pan() };
  const box = cy.nodes().boundingBox();
  const center = { x: (box.x1 + box.x2) / 2, y: (box.y1 + box.y2) / 2 };
  const nodes = cy.nodes(":childless").sort((a, b) => b.degree() - a.degree());
  const targets = new Map(nodes.map((n) => [n.id(), { ...n.position() }]));
  cy.batch(() => {
    nodes.forEach((n) => n.position({ x: center.x + (Math.random() - 0.5) * 60, y: center.y + (Math.random() - 0.5) * 60 }));
    cy.elements().style("opacity", 0);
  });
  const startZoom = zoom * 0.55;
  const rendered = { x: center.x * zoom + pan.x, y: center.y * zoom + pan.y };
  cy.viewport({ zoom: startZoom, pan: { x: rendered.x - center.x * startZoom, y: rendered.y - center.y * startZoom } });
  cy.animate({ zoom, pan }, { duration: 2200, easing: "ease-in-out-cubic" });
  nodes.forEach((n, i) => n.delay(200 + i * 90).animate(
    { position: targets.get(n.id()), style: { opacity: 1 } }, { duration: 1300, easing: "ease-out-cubic" }));
  const edgesAt = 900 + nodes.length * 90;
  cy.nodes(":parent").delay(700 + nodes.length * 60).animate({ style: { opacity: 1 } }, { duration: 1200, easing: "ease-in-out-sine" });
  cy.edges().delay(edgesAt).animate({ style: { opacity: 0.8 } }, { duration: 900, easing: "ease-in-out-sine" });
  setTimeout(() => { if (!cy.destroyed()) cy.elements().removeStyle("opacity"); }, edgesAt + 1000);
}

function issueTargets(issue) {
  const d = issue.details ?? {};
  const cy = state.cy;
  const nodes = new Set();
  const edges = new Set();
  const between = (a, b) => cy.edges().filter((e) => e.source().id() === a && e.target().id() === b).forEach((e) => edges.add(e.id()));
  if (["http", "graphql", "runtime"].includes(issue.area) && d.caller && d.target) {
    nodes.add(serviceId(d.caller));
    nodes.add(serviceId(d.target));
    cy.edges().filter((e) => e.data("caller") === d.caller && e.data("callee") === d.target
      && (issue.area === "runtime" || e.data("protocol") === issue.area)).forEach((e) => edges.add(e.id()));
  }
  if (d.topic) {
    nodes.add(topicId(d.topic));
    if (issue.area === "schema") {
      if (d.producer && d.producer !== "null") between(serviceId(d.producer), topicId(d.topic));
      if (d.consumer && d.consumer !== "null") between(topicId(d.topic), serviceId(d.consumer));
    }
  }
  return { nodes: [...nodes].filter((id) => cy.getElementById(id).nonempty()), edges: [...edges] };
}

function markIssues() {
  const cy = state.cy;
  cy.elements().removeClass("issue-warning issue-error");
  for (const issue of state.issues) {
    if (issue.severity !== "error" && issue.severity !== "warning") continue;
    if (issue.details?.targetIndexed === false || issue.details?.callerIndexed === false) continue;
    const targets = issueTargets(issue);
    const cls = "issue-" + issue.severity;
    const apply = (id) => {
      const element = cy.getElementById(id);
      if (!element.hasClass("issue-error")) element.addClass(cls);
    };
    targets.edges.forEach(apply);
    if (!targets.edges.length) targets.nodes.filter((id) => id.startsWith("t:")).forEach(apply);
  }
}

function focus(nodeIds, edgeIds) {
  const cy = state.cy;
  cy.elements().removeClass("faded focus");
  if (!nodeIds && !edgeIds) return;
  let keep = cy.collection();
  (nodeIds ?? []).forEach((id) => { keep = keep.union(cy.getElementById(id)); });
  if (edgeIds) {
    edgeIds.forEach((id) => {
      const e = cy.getElementById(id);
      keep = keep.union(e).union(e.connectedNodes());
    });
  } else {
    keep = keep.union(keep.nodes().edgesWith(keep.nodes()));
  }
  keep = keep.union(keep.nodes().parents());
  cy.elements().not(keep).addClass("faded");
  keep.not(":parent").addClass("focus");
}

function applyFilters() {
  const cy = state.cy;
  const off = [...document.querySelectorAll("[data-filter]")].filter((i) => !i.checked).map((i) => i.dataset.filter);
  cy.elements().removeClass("hidden");
  off.forEach((f) => cy.elements("." + f).addClass("hidden"));
  if (off.includes("kafka")) cy.nodes(".topic").addClass("hidden");
}

function renderStats() {
  const indexed = state.map.services.filter((s) => s.indexed).length;
  const external = state.map.services.filter((s) => s.external).length;
  const errors = state.issues.filter((i) => i.severity === "error").length;
  const warnings = state.issues.filter((i) => i.severity === "warning").length;
  const scope = state.context.area ? `${indexed} serviços na área · ${external} de outras áreas` : `${indexed} serviços indexados`;
  $("#stats").textContent = `${scope} · ${state.map.topics.length} tópicos · ${errors} erros · ${warnings} avisos`;
  $("#issue-count").textContent = errors + warnings || "";
}

function renderContext() {
  const { area, areas, links } = state.context;
  const chip = $("#area-chip");
  chip.hidden = !area && !areas.length;
  chip.textContent = area ? "área " + area : "empresa";
  chip.style.setProperty("--c", area ? areaColor(area) : color("accent"));
  const entries = Object.entries(links ?? {});
  $("#area-nav").hidden = entries.length < 2;
  $("#area-nav").innerHTML = entries.map(([name, url]) => {
    const current = name === (area ?? "empresa");
    const tint = name === "empresa" ? color("accent") : areaColor(name);
    return `<a href="${esc(url)}" class="${current ? "current" : ""}" style="--c: ${tint}">${esc(name)}</a>`;
  }).join("");
  document.title = area ? `System Graph · ${area}` : "System Graph";
}

function areaInfo(name) {
  return (state.context.areas ?? []).find((a) => a.area === name) ?? { area: name, teams: [], services: [], callsAreas: [] };
}

function showArea(name) {
  const info = areaInfo(name);
  const members = state.map.services.filter((s) => s.area === name);
  const crossing = state.map.calls.filter((c) => {
    const from = state.services.get(c.source)?.area;
    const to = state.services.get(c.target)?.area;
    return from && to && from !== to && (from === name || to === name);
  });
  const home = name === state.context.area;
  $("#details").innerHTML = `
    <h2>${esc(name)}</h2>
    <div class="meta">${areaBadge(name)}${home ? `<span class="badge">esta área</span>` : state.context.area ? `<span class="badge">outra área</span>` : ""}</div>
    ${section("Times", info.teams, (t) => `<div>${esc(t)}</div>`)}
    ${section("Serviços", members, (s) => `<div class="row">${serviceButton(s.name)}${s.external ? `<span class="muted">detalhe no MCP da área</span>` : ""}</div>
      <div class="sub">${esc(s.team ?? "")}</div>`)}
    ${section("Chamadas entre áreas", crossing, (c) => `<div class="row">${serviceRef(c.source)}<span class="muted">→</span>${serviceRef(c.target)}</div>
      <div class="sub">${esc((c.protocols.length ? c.protocols : ["http"]).join(", "))} · ${esc(c.operations)} operação(ões)</div>`)}
    ${info.callsAreas?.length ? `<p class="guidance">Usa ${esc(info.contractsUsedFromOtherAreas)} contrato(s) de ${info.callsAreas.map(areaBadge).join(" ")}</p>` : ""}
    ${!home && state.context.area ? `<p class="muted">Aqui aparecem só os serviços desta área que falam com ${esc(state.context.area)}. O resto está no grafo da própria área.</p>` : ""}`;
}

function renderIssues() {
  const order = { error: 0, warning: 1, info: 2 };
  const issues = [...state.issues].sort((a, b) => order[a.severity] - order[b.severity]);
  if (!issues.length) {
    $("#issues").innerHTML = `<p class="muted">Nenhum problema de contrato encontrado.</p>`;
    return;
  }
  $("#issues").innerHTML = `<ul class="list">${issues.map((issue, i) => `
    <li class="issue ${esc(issue.severity)}" data-issue="${i}">
      <div class="row"><span class="badge ${esc(issue.severity)}">${esc(issue.severity)}</span><span class="badge">${esc(issue.area)}</span></div>
      <div>${esc(issue.message)}</div>
      ${issue.details?.location && issue.details?.caller ? `<div class="sub">${sourceLink(issue.details.caller, issue.details.location)}</div>` : ""}
    </li>`).join("")}</ul>`;
  $("#issues").querySelectorAll("[data-issue]").forEach((li) => li.addEventListener("click", () => {
    const issue = issues[Number(li.dataset.issue)];
    $("#issues").querySelectorAll(".issue").forEach((x) => x.classList.toggle("active", x === li));
    const targets = issueTargets(issue);
    const edges = targets.edges.length ? targets.edges
      : targets.nodes.flatMap((id) => state.cy.getElementById(id).connectedEdges().map((e) => e.id()));
    focus(targets.nodes, edges);
    state.cy.animate({ fit: { eles: state.cy.elements(".focus"), padding: 110 }, duration: 850, easing: "ease-in-out-cubic" });
  }));
}

function section(title, items, render) {
  if (!items || !items.length) return "";
  return `<h3>${esc(title)}</h3><ul class="list">${items.map((item) => `<li>${render(item)}</li>`).join("")}</ul>`;
}

function notes(list) {
  return section("Notas", list, (n) => `<div>${esc(n.text)}</div><div class="sub">${esc(n.author)} · ${esc(n.status)}</div>`);
}

function protocolBadge(protocol) {
  return `<span class="badge ${protocol === "graphql" ? "graphql" : "http"}">${protocol === "graphql" ? "GraphQL" : "HTTP"}</span>`;
}

function endpointLabel(e) {
  return e.protocol === "graphql" ? `${e.method} ${e.path}` : `${e.method ?? "?"} ${e.path ?? "(sem path)"}`;
}

async function showService(name) {
  const data = await getJson("api/services/" + encodeURIComponent(name));
  if (data.error) {
    $("#details").innerHTML = `<p class="alert">${esc(data.error)}</p>`;
    return;
  }
  const service = state.services.get(name) ?? {};
  const badges = [
    areaBadge(data.area),
    data.team ? `<span class="badge">${esc(data.team)}</span>` : "",
    data.origin === "hub" ? `<span class="badge">via hub</span>` : data.indexed ? `<span class="badge">indexado</span>` : data.contractSource ? "" : `<span class="badge warning">não indexado</span>`,
    data.contractSource ? `<span class="badge">contrato ${esc(data.contractSource)}</span>` : "",
    data.graphqlSchema ? `<span class="badge graphql">GraphQL</span>` : "",
    data.commitSha ? `<span class="badge mono">${esc(data.commitSha)}</span>` : "",
  ].join("");
  $("#details").innerHTML = `
    <h2>${esc(name)}</h2>
    <div class="meta">${badges}</div>
    <div>${repositoryLink(service.repository ? service : data)}</div>
    ${data.origin === "hub" ? `<p class="guidance">Serviço da área ${esc(data.area)}. O hub só guarda os contratos e as chamadas entre áreas; as chamadas internas de ${esc(data.area)} estão no grafo daquela área.</p>` : ""}
    ${section("Expõe", data.exposes, (e) => `
      <div class="row">${protocolBadge(e.protocol)}<span class="mono">${esc(endpointLabel(e))}</span>${e.source === "openapi" ? `<span class="badge">openapi</span>` : ""}</div>
      <div class="sub">${e.calledBy.length ? "chamado por " + e.calledBy.map((n) => serviceRef(n)).join(", ") : "nenhum chamador conhecido"}</div>`)}
    ${section("Chama", data.calls, (c) => `
      <div class="row">${protocolBadge(c.protocol)}<span class="mono">${esc(endpointLabel(c))}</span><span class="muted">em</span>${serviceRef(c.service)}</div>
      <div class="sub">${esc(c.via)} · confiança ${esc(c.confidence)} · ${sourceLink(name, c.location)}</div>`)}
    ${section("Publica", data.publishes, (p) => `
      <div class="row"><span class="badge kafka">Kafka</span>${topicButton(p.topic)}</div>
      <div class="sub">${esc(p.via)} · ${esc(p.payloadType)}${p.consumers.length ? " · consumido por " + p.consumers.map((n) => serviceRef(n)).join(", ") : ""}</div>`)}
    ${section("Consome", data.consumes, (c) => `
      <div class="row"><span class="badge kafka">Kafka</span>${topicButton(c.topic)}</div>
      <div class="sub">${esc(c.via)} · grupo ${esc(c.group ?? "-")} · ${esc(c.payloadType)}${c.producers.length ? " · publicado por " + c.producers.map((n) => serviceRef(n)).join(", ") : ""}</div>`)}
    ${section("Chamadas vistas em runtime", data.observedCalls, (o) => `
      <div class="row"><span class="badge runtime">${esc(o.source)}</span>${serviceButton(o.service)}${o.count ? `<span class="muted">${esc(o.count)}×</span>` : ""}</div>`)}
    ${section("Chamado em runtime por", data.observedCallers, (o) => `
      <div class="row"><span class="badge runtime">${esc(o.source)}</span>${serviceButton(o.service)}${o.count ? `<span class="muted">${esc(o.count)}×</span>` : ""}</div>`)}
    ${notes(data.notes)}`;
  await loadContracts(name, data);
}

async function showTopic(name) {
  const data = await getJson("api/topics/" + encodeURIComponent(name));
  if (data.error) {
    $("#details").innerHTML = `<p class="alert">${esc(data.error)}</p>`;
    return;
  }
  $("#details").innerHTML = `
    <h2>${esc(name)}</h2>
    <div class="meta"><span class="badge kafka">tópico Kafka</span></div>
    ${section("Produtores", data.producers, (p) => `
      <div class="row">${serviceRef(p.service, p.area)}<span class="muted">${esc(p.via)}</span></div>
      <div class="sub">${esc(p.payloadType)} · ${sourceLink(p.service, p.location)}</div>`)}
    ${section("Consumidores declarados no código", data.declaredConsumers, (c) => `
      <div class="row">${serviceRef(c.service, c.area)}<span class="muted">${esc(c.via)} · grupo ${esc(c.group ?? "-")}</span></div>
      <div class="sub">${esc(c.payloadType)} · ${sourceLink(c.service, c.location)}</div>`)}
    ${section("Vistos no Kafka", data.observedConsumers, (o) => `
      <div class="row">${serviceButton(o.service)}<span class="muted">${esc(o.activeMembers)} membros · ${esc(o.state)}</span></div>`)}
    ${notes(data.notes)}
    <p><button type="button" class="ghost" data-impact-topic="${esc(name)}">Ver impacto de mudar este evento</button></p>`;
}

async function showEdge(edge) {
  const d = edge.data();
  if (d.kind === "call") {
    const data = await getJson("api/services/" + encodeURIComponent(d.caller));
    const calls = (data.calls ?? []).filter((c) => c.service === d.callee && (c.protocol ?? "http") === d.protocol);
    $("#details").innerHTML = `
      <h2>${esc(d.caller)} → ${esc(d.callee)}</h2>
      <div class="meta">${protocolBadge(d.protocol)}${d.observed ? `<span class="badge runtime">confirmado por ${esc(d.observed.origin)}</span>` : ""}</div>
      ${calls.length ? section("Chamadas", calls, (c) => `
        <div class="row"><span class="mono">${esc(endpointLabel(c))}</span></div>
        <div class="sub">${esc(c.via)} · confiança ${esc(c.confidence)} · ${sourceLink(d.caller, c.location)}</div>`)
      : `<p class="muted">Dependência sem endpoint identificado (URL montada em runtime ou path não resolvido).</p>`}`;
    return;
  }
  if (d.kind === "runtime") {
    $("#details").innerHTML = `
      <h2>${esc(d.caller)} → ${esc(d.callee)}</h2>
      <div class="meta"><span class="badge runtime">visto por ${esc(d.observed.origin)}</span></div>
      <p>Chamada vista em runtime sem correspondente no código de ${serviceButton(d.caller)}. Pode ser URL montada em runtime, biblioteca interna ou client gerado.</p>`;
    return;
  }
  const topic = edge.source().data("kind") === "topic" ? edge.source().data("name") : edge.target().data("name");
  await showTopic(topic);
}

function affectedFromImpact(result) {
  const names = new Set();
  if (result.kind === "kafka-topic") {
    (result.affectedServices ?? []).forEach((a) => names.add(a.service));
    (result.producers ?? []).forEach((p) => names.add(p.service));
  } else if (result.kind === "graphql-field") {
    (result.affectedServices ?? []).forEach((a) => names.add(a.service));
  } else {
    (result.endpoints ?? []).forEach((e) => (e.callers ?? []).forEach((c) => names.add(c.service)));
  }
  return [...names];
}

function renderImpact(result) {
  if (result.error) {
    $("#details").innerHTML = `<h2>Impacto</h2><p class="alert">${esc(result.error)}</p>${result.hint ? `<p class="muted">${esc(result.hint)}</p>` : ""}`;
    return;
  }
  let body = "";
  if (result.kind === "kafka-topic") {
    body = section("Serviços afetados", result.affectedServices, (a) => `
      <div class="row">${serviceRef(a.service, a.area)}${a.seenInKafka ? `<span class="badge runtime">visto no Kafka</span>` : ""}</div>
      <div class="sub">${a.declaredIn ? `${esc(a.payloadType)} · ${sourceLink(a.service, a.declaredIn)}` : "não declarado no código"}</div>`)
      + section("Produtores", result.producers, (p) => `<div class="row">${serviceButton(p.service)}</div>`)
      + section("Problemas de payload", (result.schemaIssues ?? []).filter((s) => s.severity !== "info"), (s) => `
      <div class="row"><span class="badge ${esc(s.severity)}">${esc(s.severity)}</span></div><div>${esc(s.problem)}</div>`);
  } else if (result.kind === "graphql-field") {
    body = section("Clients que selecionam o campo", result.affectedServices, (a) => `
      <div class="row">${serviceRef(a.service, a.area)}<span class="muted">${esc((a.operations ?? []).join(", "))}</span></div>
      <div class="sub">${sourceLink(a.service, a.location)}</div>`)
      + section("Outros clients GraphQL (não usam o campo)", result.otherGraphQlClients, (a) => `
      <div class="row">${serviceButton(a.service)}</div><div class="sub">${esc(a.reason)}</div>`);
  } else {
    body = (result.endpoints ?? []).map((e) => `
      <h3 class="mono">${esc(e.method)} ${esc(e.path)}</h3>
      ${e.callers.length ? `<ul class="list">${e.callers.map((c) => `<li>
        <div class="row">${serviceRef(c.service, c.area)}<span class="muted">${esc(c.via)} · confiança ${esc(c.confidence)}</span></div>
        <div class="sub">${sourceLink(c.service, c.location)}</div>
        ${c.fieldsUsed?.length ? `<div class="sub">campos usados: <span class="mono">${esc(c.fieldsUsed.join(", "))}</span></div>` : ""}
        ${c.documentErrors?.length ? `<div class="sub alert">${esc(c.documentErrors.join("; "))}</div>` : ""}
      </li>`).join("")}</ul>` : `<p class="muted">Nenhum chamador conhecido.</p>`}`).join("");
  }
  const affected = affectedFromImpact(result);
  const areas = result.affectedAreas ?? [];
  const areasBlock = areas.length ? `
    <div class="areas-alert">
      <div class="row"><strong>Afeta ${areas.length} outra(s) área(s)</strong></div>
      <ul class="list">${areas.map((a) => `<li><div class="row">${areaBadge(a.area)}<span>${esc(a.teams.join(", ") || "time não informado")}</span></div>
        <div class="sub">${a.services.map((n) => serviceButton(n)).join(", ")}</div></li>`).join("")}</ul>
      <div class="sub">Combine a mudança com esses times antes de publicar.</div>
    </div>` : "";
  $("#details").innerHTML = `
    <h2>Impacto: ${esc(result.contract)}</h2>
    <div class="meta"><span class="badge">${esc(result.kind)}</span><span class="badge">dono ${esc(result.service)}</span>${areaBadge(result.ownerArea)}
      <span class="badge ${affected.length ? "warning" : ""}">${affected.length} serviço(s) afetado(s)</span></div>
    ${areasBlock}
    ${body || `<p class="muted">Nenhum serviço afetado.</p>`}
    ${result.guidance ? `<p class="guidance">${esc(result.guidance)}</p>` : ""}
    ${notes(result.notes)}`;
}

async function runImpact(service, contract) {
  if (!service || !contract) return;
  switchTab("details");
  const result = await getJson(`api/impact?service=${encodeURIComponent(service)}&contract=${encodeURIComponent(contract)}`);
  renderImpact(result);
  if (result.error) {
    focus(null, null);
    return;
  }
  const affected = affectedFromImpact(result).map(serviceId);
  const ids = [serviceId(service), ...affected];
  const topic = state.cy.getElementById(topicId(contract));
  if (topic.nonempty()) ids.push(topic.id());
  const edges = state.cy.edges().filter((e) => {
    const s = e.source().id();
    const t = e.target().id();
    if (topic.nonempty()) return (s === topic.id() || t === topic.id()) && (ids.includes(s) && ids.includes(t));
    return affected.includes(s) && t === serviceId(service) && e.data("kind") === "call";
  }).map((e) => e.id());
  focus(ids, edges);
  state.cy.animate({ fit: { eles: state.cy.elements(".focus"), padding: 110 }, duration: 850, easing: "ease-in-out-cubic" });
}

async function loadContracts(name, overview) {
  $("#impact-service").value = name;
  const data = overview ?? await getJson("api/services/" + encodeURIComponent(name));
  const options = new Set();
  (data.exposes ?? []).forEach((e) => options.add(endpointLabel(e)));
  (data.publishes ?? []).forEach((p) => options.add(p.topic));
  (data.consumes ?? []).forEach((c) => options.add(c.topic));
  $("#contracts").innerHTML = [...options].map((o) => `<option value="${esc(o)}">`).join("");
}

function switchTab(tab) {
  document.querySelectorAll(".tabs button").forEach((b) => b.classList.toggle("active", b.dataset.tab === tab));
  $("#details").hidden = tab !== "details";
  $("#issues").hidden = tab !== "issues";
}

function renderWelcome() {
  const problems = state.issues.filter((i) => i.severity === "error" || i.severity === "warning");
  const links = state.map.calls.length + state.map.publishes.length + state.map.consumes.length + state.map.observedCalls.length;
  const area = state.context.area;
  const areas = state.context.areas ?? [];
  const title = area ? `Área ${area}` : areas.length ? "Mapa da empresa" : "Mapa dos serviços";
  const subtitle = area
    ? `Detalhe completo dos serviços de ${area}. Os serviços de outras áreas aparecem só quando falam com esta, e vêm do hub.`
    : areas.length
      ? "Cada área mantém o próprio grafo. Aqui ficam os contratos publicados e as chamadas entre áreas."
      : "Clique num serviço, tópico ou conexão. Escolha um serviço e um contrato no topo para ver quem sente a mudança.";
  $("#details").innerHTML = `
    <h2 class="hero">${esc(title)}</h2>
    <p class="hero-sub">${esc(subtitle)}</p>
    <div class="kpis">
      <div class="kpi"><b>${state.map.services.filter((s) => s.indexed).length}</b><small>serviços</small></div>
      <div class="kpi"><b>${links}</b><small>conexões</small></div>
      <div class="kpi ${problems.length ? "alert-kpi" : ""}"><b>${problems.length}</b><small>problemas</small></div>
    </div>
    ${!area && areas.length ? section("Áreas", areas, (a) => `
      <div class="row"><button type="button" class="link" data-area="${esc(a.area)}">${esc(a.area)}</button>${areaBadge(a.area)}<span class="muted">${esc(a.services.length)} serviço(s)</span></div>
      <div class="sub">${esc(a.teams.join(", "))}</div>`) : ""}
    ${section("Serviços", state.map.services.filter((s) => !area || s.area === area || !s.external), (s) => `
      <div class="row">${serviceRef(s.name)}${s.indexed || s.external ? "" : s.contractSource ? `<span class="badge">${esc(s.contractSource)}</span>` : `<span class="badge warning">não indexado</span>`}${s.graphql ? `<span class="badge graphql">GraphQL</span>` : ""}</div>
      <div class="sub">${s.team ? esc(s.team) + " · " : ""}${repositoryLink(s)}</div>`)}
    ${problems.length ? `<p class="guidance">${problems.length} problema(s) de contrato. Veja a aba Problemas.</p>` : ""}`;
}

async function load() {
  const [map, issues, context] = await Promise.all([getJson("api/map"), getJson("api/issues"), getJson("api/context")]);
  state.context = { area: context.area, areas: context.areas ?? [], links: context.links ?? {} };
  state.areaNames = [...new Set([...state.context.areas.map((a) => a.area), ...map.services.map((s) => s.area).filter(Boolean)])].sort();
  renderContext();
  state.map = map;
  state.issues = issues.issues ?? [];
  state.services = new Map(map.services.map((s) => [s.name, s]));
  const elements = buildElements(map);
  $("#empty").hidden = elements.length > 0;
  if (state.cy) state.cy.destroy();
  state.cy = cytoscape({
    container: $("#graph"),
    elements,
    style: cyStyle(),
    wheelSensitivity: 0.3,
    autounselectify: true,
    minZoom: 0.2,
    maxZoom: 1.7,
    layout: {
      name: "cose", animate: false, padding: 40, randomize: true, nodeDimensionsIncludeLabels: true, componentSpacing: 80,
      nodeRepulsion: (n) => (n.hasClass("topic") ? 900000 : 2600000), nodeOverlap: 60, nestingFactor: 0.7, gravityCompound: 3, gravityRangeCompound: 1.2,
      idealEdgeLength: (e) => 90 + 22 * Math.min(e.source().degree(), e.target().degree()),
      edgeElasticity: (e) => (e.hasClass("runtime") ? 40 : 110), gravity: 1.4, gravityRange: 2.4, numIter: 4000, coolingFactor: 0.97,
    },
  });
  state.cy.on("mouseover", "node, edge", (evt) => {
    evt.target.addClass("hover");
    if (evt.target.isNode()) evt.target.connectedEdges().addClass("hover");
    $("#graph").style.cursor = "pointer";
  });
  state.cy.on("mouseout", "node, edge", (evt) => {
    evt.target.removeClass("hover");
    if (evt.target.isNode()) evt.target.connectedEdges().removeClass("hover");
    $("#graph").style.cursor = "";
  });
  state.cy.on("tap", "node", (evt) => {
    const d = evt.target.data();
    switchTab("details");
    if (evt.target.isParent()) focus(evt.target.children().map((n) => n.id()), evt.target.children().connectedEdges().map((e) => e.id()));
    else focus([evt.target.id()], evt.target.connectedEdges().map((e) => e.id()));
    if (d.kind === "service") showService(d.name);
    else if (d.kind === "area") showArea(d.name);
    else showTopic(d.name);
  });
  state.cy.on("tap", "edge", (evt) => {
    switchTab("details");
    focus(null, [evt.target.id()]);
    showEdge(evt.target);
  });
  state.cy.on("tap", (evt) => {
    if (evt.target === state.cy) {
      focus(null, null);
      renderWelcome();
    }
  });
  markIssues();
  applyFilters();
  intro(state.cy);
  renderStats();
  renderIssues();
  renderWelcome();
  const select = $("#impact-service");
  const current = select.value;
  select.innerHTML = `<option value="">serviço…</option>` + map.services.filter((s) => s.indexed || s.contractSource || s.external)
    .map((s) => `<option value="${esc(s.name)}">${esc(s.name)}</option>`).join("");
  if (current && state.services.has(current)) select.value = current;
}

document.addEventListener("click", (evt) => {
  const target = evt.target.closest("[data-service], [data-topic], [data-impact-topic], [data-area]");
  if (!target) return;
  if (target.dataset.area) {
    const node = state.cy.getElementById(areaId(target.dataset.area));
    if (node.nonempty()) {
      focus(node.children().map((n) => n.id()), node.children().connectedEdges().map((e) => e.id()));
      state.cy.animate({ fit: { eles: node, padding: 90 }, duration: 850, easing: "ease-in-out-cubic" });
    }
    showArea(target.dataset.area);
  } else if (target.dataset.service) {
    const node = state.cy.getElementById(serviceId(target.dataset.service));
    if (node.nonempty()) focus([node.id()], node.connectedEdges().map((e) => e.id()));
    switchTab("details");
    showService(target.dataset.service);
  } else if (target.dataset.topic) {
    const node = state.cy.getElementById(topicId(target.dataset.topic));
    if (node.nonempty()) focus([node.id()], node.connectedEdges().map((e) => e.id()));
    showTopic(target.dataset.topic);
  } else {
    const topic = target.dataset.impactTopic;
    const producer = state.map.publishes.find((p) => p.topic === topic)?.service ?? state.map.consumes.find((c) => c.topic === topic)?.service;
    if (producer) {
      $("#impact-service").value = producer;
      $("#impact-contract").value = topic;
      runImpact(producer, topic);
    }
  }
});

$("#impact-form").addEventListener("submit", (evt) => {
  evt.preventDefault();
  runImpact($("#impact-service").value, $("#impact-contract").value.trim());
});

$("#impact-service").addEventListener("change", (evt) => {
  if (evt.target.value) loadContracts(evt.target.value);
});

$("#clear").addEventListener("click", () => {
  $("#impact-contract").value = "";
  focus(null, null);
  state.cy.animate({ fit: { padding: 90 }, duration: 850, easing: "ease-in-out-cubic" });
  renderWelcome();
});

$("#reload").addEventListener("click", () => load().catch(showError));

document.querySelectorAll("[data-filter]").forEach((input) => input.addEventListener("change", applyFilters));

document.querySelectorAll(".tabs button").forEach((button) => button.addEventListener("click", () => switchTab(button.dataset.tab)));

const bar = $(".bar");
new ResizeObserver(() => {
  const bottom = window.innerWidth > 900 ? bar.getBoundingClientRect().bottom + 8 : 0;
  document.documentElement.style.setProperty("--graph-top", bottom + "px");
  state.cy?.resize();
}).observe(bar);

animateFlow();

function showError(error) {
  $("#details").innerHTML = `<p class="alert">Não foi possível ler o grafo: ${esc(error.message)}</p><p class="muted">O Neo4j está no ar?</p>`;
}

load().catch(showError);
