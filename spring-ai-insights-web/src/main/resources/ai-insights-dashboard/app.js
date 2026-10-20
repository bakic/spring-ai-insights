'use strict';

// Every value from the API is rendered with textContent or setAttribute, never parsed as HTML.
(() => {
  const SVG_NS = 'http://www.w3.org/2000/svg';
  const DAY_MS = 86400000;
  const PERIODS = { '24h': 1, '7d': 7, '30d': 30, '90d': 90 };
  const PERIOD_TEXT = {
    '24h': 'in the last 24 hours', '7d': 'in the last 7 days', '30d': 'in the last 30 days', '90d': 'in the last 90 days'
  };

  const state = {
    config: null,
    app: null,
    period: '7d',
    tenant: '',
    zone: Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC'
  };

  const page = document.getElementById('page');
  let renderToken = 0;

  // ---- DOM helpers -------------------------------------------------------------------------------

  function setAttrs(node, attrs) {
    for (const [key, value] of Object.entries(attrs || {})) {
      if (value === null || value === undefined || value === false) {
        continue;
      }
      if (key === 'text') {
        node.textContent = String(value);
      } else {
        node.setAttribute(key, value === true ? '' : String(value));
      }
    }
  }

  function append(node, children) {
    for (const child of children.flat(Infinity)) {
      if (child === null || child === undefined || child === false) {
        continue;
      }
      node.append(child instanceof Node ? child : document.createTextNode(String(child)));
    }
  }

  function el(tag, attrs, ...children) {
    const node = document.createElement(tag);
    setAttrs(node, attrs);
    append(node, children);
    return node;
  }

  function svg(tag, attrs, ...children) {
    const node = document.createElementNS(SVG_NS, tag);
    setAttrs(node, attrs);
    append(node, children);
    return node;
  }

  function clear(node) {
    while (node.firstChild) {
      node.firstChild.remove();
    }
  }

  // ---- Formatting --------------------------------------------------------------------------------

  const integer = new Intl.NumberFormat();
  const oneDecimal = new Intl.NumberFormat(undefined, { maximumFractionDigits: 1 });
  const twoDecimals = new Intl.NumberFormat(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 });
  const percent = new Intl.NumberFormat(undefined, { style: 'percent', maximumFractionDigits: 1 });
  const compactNumber = new Intl.NumberFormat(undefined, { notation: 'compact', maximumFractionDigits: 1 });
  let moneyCoarse = null;
  let moneyFine = null;

  function setCurrency(currency) {
    try {
      moneyCoarse = new Intl.NumberFormat(undefined, { style: 'currency', currency, maximumFractionDigits: 2 });
      moneyFine = new Intl.NumberFormat(undefined,
        { style: 'currency', currency, minimumFractionDigits: 2, maximumFractionDigits: 6 });
    } catch (e) {
      const plain = new Intl.NumberFormat(undefined, { maximumFractionDigits: 6 });
      moneyCoarse = moneyFine = { format: (n) => `${plain.format(n)} ${currency}` };
    }
  }

  function money(value) {
    if (value === null || value === undefined) {
      return '—';
    }
    const n = Number(value);
    return (Math.abs(n) >= 1 || n === 0 ? moneyCoarse : moneyFine).format(n);
  }

  function moneyAxis(value) {
    return Number(value) >= 1 ? moneyCoarse.format(value) : moneyFine.format(Number(Number(value).toPrecision(2)));
  }

  // Headline figures: two significant digits below one unit, so a small cost stays readable at a glance.
  function moneyHeadline(value) {
    return value === null || value === undefined ? '—' : moneyAxis(value);
  }

  function count(n) {
    return integer.format(n);
  }

  function ratio(part, whole) {
    return whole > 0 ? part / whole : 0;
  }

  function share(part, whole) {
    return whole > 0 ? percent.format(part / whole) : '—';
  }

  function duration(ms) {
    if (ms === null || ms === undefined) {
      return '—';
    }
    return ms < 1000 ? `${integer.format(Math.round(ms))} ms` : `${oneDecimal.format(ms / 1000)} s`;
  }

  function dateTime(iso) {
    return new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'short', timeZone: state.zone })
      .format(new Date(iso));
  }

  function dayKey(date) {
    return new Intl.DateTimeFormat('en-CA', { year: 'numeric', month: '2-digit', day: '2-digit', timeZone: state.zone })
      .format(date);
  }

  function dayLabel(date) {
    return new Intl.DateTimeFormat(undefined, { month: 'short', day: 'numeric', timeZone: state.zone }).format(date);
  }

  function shortId(id) {
    return id.length > 14 ? `${id.slice(0, 12)}…` : id;
  }

  // ---- API ---------------------------------------------------------------------------------------

  async function api(path, params) {
    const query = new URLSearchParams();
    for (const [key, value] of Object.entries(params || {})) {
      if (value) {
        query.set(key, value);
      }
    }
    const qs = query.toString();
    const response = await fetch(`api/${path}${qs ? `?${qs}` : ''}`,
      { headers: { Accept: 'application/json' }, credentials: 'same-origin' });
    let body = null;
    try {
      body = await response.json();
    } catch (e) {
      body = null;
    }
    if (!response.ok) {
      throw new Error(body && body.error ? body.error : `The server answered with status ${response.status}.`);
    }
    if (body === null) {
      throw new Error('The server did not answer with data. Your session may have expired: reload the page to sign in.');
    }
    return body;
  }

  function filterParams() {
    const to = new Date();
    const from = new Date(to.getTime() - PERIODS[state.period] * DAY_MS);
    return {
      app: state.app, from: from.toISOString(), to: to.toISOString(), tenant: state.tenant.trim(), zone: state.zone
    };
  }

  /** The period of the same length that ends where this one starts. */
  function previousParams(params) {
    const from = new Date(params.from);
    return { ...params, from: new Date(from.getTime() - PERIODS[state.period] * DAY_MS).toISOString(), to: params.from };
  }

  function rangeText(params) {
    const format = new Intl.DateTimeFormat(undefined, { day: 'numeric', month: 'short', year: 'numeric', timeZone: state.zone });
    const from = new Date(params.from);
    const to = new Date(params.to);
    const range = typeof format.formatRange === 'function' ? format.formatRange(from, to)
      : `${format.format(from)} – ${format.format(to)}`;
    return `${range} · ${state.zone}`;
  }

  // ---- Building blocks ---------------------------------------------------------------------------

  function pageHead(title, description, back) {
    return el('header', { class: 'page-head' },
      back ? el('a', { class: 'back', href: back.href, text: back.text }) : null,
      el('h1', { text: title }),
      description ? el('p', { text: description }) : null);
  }

  function section(title, note, ...content) {
    return el('section', { class: 'section' }, el('h2', { text: title }),
      note ? el('p', { class: 'note', text: note }) : null, content);
  }

  function empty(text) {
    return el('p', { class: 'empty', text });
  }

  function errorBox(message) {
    return el('div', { class: 'error', role: 'alert' },
      el('strong', { text: 'This page could not be loaded.' }), el('span', { text: message }));
  }

  /** Change against the previous period; tone says whether a rise is good, bad or neutral. */
  function delta(current, previous, tone) {
    if (!previous) {
      return current > 0 ? { text: 'none in the previous period', cls: '' } : null;
    }
    const change = (current - previous) / previous;
    if (Math.abs(change) < 0.0005) {
      return { text: 'same as the previous period', cls: '' };
    }
    const rising = change > 0;
    let cls = '';
    if (tone === 'up-is-good') {
      cls = rising ? 'good' : 'bad';
    } else if (tone === 'up-is-bad') {
      cls = rising ? 'bad' : 'good';
    }
    return { text: `${rising ? 'up' : 'down'} ${percent.format(Math.abs(change))} on the previous period`, cls };
  }

  /** Change of a rate in percentage points; a rise is bad. */
  function rateDelta(part, whole, previousPart, previousWhole) {
    if (!whole || !previousWhole) {
      return null;
    }
    const points = (ratio(part, whole) - ratio(previousPart, previousWhole)) * 100;
    if (Math.abs(points) < 0.05) {
      return { text: 'same as the previous period', cls: '' };
    }
    return {
      text: `${points > 0 ? 'up' : 'down'} ${oneDecimal.format(Math.abs(points))} pts on the previous period`,
      cls: points > 0 ? 'bad' : 'good'
    };
  }

  function ledger(items) {
    return el('dl', { class: 'ledger' }, items.map((item) => el('div', { class: 'kpi' },
      el('dt', { text: item.label }),
      el('dd', null,
        el('span', { class: 'num', text: item.value }),
        item.delta ? el('span', { class: `delta ${item.delta.cls}`, text: item.delta.text }) : null,
        item.hint ? el('span', { class: 'hint', text: item.hint }) : null))));
  }

  /** columns: { label, cell(row) -> string | Node, num } */
  function table(columns, rows, emptyText) {
    if (!rows || rows.length === 0) {
      return empty(emptyText);
    }
    return el('div', { class: 'scroll' }, el('table', { class: columns.length > 5 ? 'wide' : null },
      el('thead', null, el('tr', null, columns.map((c) => el('th', { class: c.num ? 'num' : null, scope: 'col', text: c.label })))),
      el('tbody', null, rows.map((row) => el('tr', null,
        columns.map((c) => el('td', { class: c.num ? 'num' : null }, c.cell(row))))))));
  }

  function barChart(items, options) {
    const figure = el('figure', { class: 'figure' }, el('figcaption', { text: options.label }));
    if (items.length === 0 || items.every((item) => item.value === 0)) {
      figure.append(empty(options.emptyText || 'Nothing to chart in this period.'));
      return figure;
    }
    const width = 640;
    const height = 200;
    const top = 18;
    const bottom = 24;
    const max = Math.max(...items.map((item) => item.value));
    const slot = width / items.length;
    const barWidth = Math.max(1, slot * 0.7);
    const plot = height - top - bottom;
    const chart = svg('svg', {
      viewBox: `0 0 ${width} ${height}`, class: `chart ${options.tone || ''}`, role: 'img', 'aria-label': options.label
    });
    chart.append(svg('line', { class: 'grid', x1: 0, x2: width, y1: top, y2: top }));
    chart.append(svg('text', { x: 0, y: top - 6, text: (options.axis || options.format)(max) }));
    const every = Math.ceil(items.length / 12);
    items.forEach((item, index) => {
      const barHeight = item.value > 0 ? Math.max(1, plot * (item.value / max)) : 0;
      const x = index * slot + (slot - barWidth) / 2;
      chart.append(svg('rect', { class: 'bar', x, y: top + plot - barHeight, width: barWidth, height: barHeight },
        svg('title', { text: `${item.label}: ${options.format(item.value)}` })));
      if (index % every === 0) {
        chart.append(svg('text', { x: x + barWidth / 2, y: height - 6, 'text-anchor': 'middle', text: item.label }));
      }
    });
    chart.append(svg('line', { class: 'axis', x1: 0, x2: width, y1: top + plot, y2: top + plot }));
    figure.append(chart);
    return figure;
  }

  /** One entry per calendar day of the period in the browser's zone, including days without turns. */
  function dailySeries(buckets, params) {
    const byDay = new Map(buckets.map((bucket) => [dayKey(new Date(bucket.start)), bucket]));
    const days = [];
    const seen = new Set();
    const end = new Date(params.to).getTime();
    for (let t = new Date(params.from).getTime(); t < end; t += 3600000) {
      const date = new Date(t);
      const key = dayKey(date);
      if (!seen.has(key)) {
        seen.add(key);
        const bucket = byDay.get(key);
        days.push({
          label: dayLabel(date),
          turns: bucket ? bucket.turns : 0,
          activeUsers: bucket ? bucket.activeUsers : 0,
          cost: bucket ? Number(bucket.cost) : 0
        });
      }
    }
    return days;
  }

  function conversationHref(app, id) {
    return `#conversation/${encodeURIComponent(app)}/${encodeURIComponent(id)}`;
  }

  function conversationLink(id, app) {
    return el('a', { href: conversationHref(app || state.app, id), title: id, text: shortId(id) });
  }

  function user(id) {
    return id ? el('span', { class: 'id', title: id, text: shortId(id) }) : el('span', { class: 'muted', text: 'Anonymous' });
  }

  function badge(value, text) {
    return el('span', { class: `badge ${value}`, text: text || value.toLowerCase() });
  }

  function shareBar(part, whole) {
    const bar = el('span', { class: 'share', 'aria-hidden': 'true' });
    bar.style.width = `${Math.round(ratio(part, whole) * 100)}%`;
    return bar;
  }

  const TURN_COLUMNS = [
    { label: 'Started', cell: (t) => dateTime(t.startedAt) },
    { label: 'User', cell: (t) => user(t.userId) },
    { label: 'Tenant', cell: (t) => t.tenantId || el('span', { class: 'muted', text: '—' }) },
    { label: 'Outcome', cell: (t) => badge(t.outcome) },
    { label: 'Latency', num: true, cell: (t) => duration(t.latencyMs) },
    { label: 'Tool calls', num: true, cell: (t) => count(t.toolCalls) },
    { label: 'Searches', num: true, cell: (t) => count(t.retrievals) },
    { label: 'Cost', num: true, cell: (t) => money(t.cost) },
    { label: 'Conversation', cell: (t) => conversationLink(t.conversationId) }
  ];

  function plural(n, one, many) {
    return `${count(n)} ${n === 1 ? one : many}`;
  }

  /** Signed change for the meter table: words and signs only, the colour is reserved for failures. */
  function change(current, previous) {
    if (!previous) {
      return current > 0 ? 'new' : '—';
    }
    const c = (current - previous) / previous;
    if (Math.abs(c) < 0.0005) {
      return 'same';
    }
    return `${c > 0 ? '+' : '−'}${percent.format(Math.abs(c))}`;
  }

  function pointsChange(now, before) {
    if (!now.turns || !before.turns) {
      return { text: '—', cls: '' };
    }
    const points = (ratio(now.errorTurns, now.turns) - ratio(before.errorTurns, before.turns)) * 100;
    if (Math.abs(points) < 0.05) {
      return { text: 'same', cls: '' };
    }
    return { text: `${points > 0 ? '+' : '−'}${oneDecimal.format(Math.abs(points))} pts`, cls: points > 0 ? 'bad' : 'good' };
  }

  function pairedBars(current, previous) {
    const max = Math.max(current, previous);
    const bars = el('span', { class: 'paired', 'aria-hidden': 'true' },
      el('i', { class: 'now' }), el('i', { class: 'then' }));
    bars.children[0].style.width = `${max > 0 ? Math.max(current > 0 ? 2 : 0, Math.round(100 * current / max)) : 0}%`;
    bars.children[1].style.width = `${max > 0 ? Math.max(previous > 0 ? 2 : 0, Math.round(100 * previous / max)) : 0}%`;
    return bars;
  }

  /** rows: { label, now, before, value(n) -> string, change: { text, cls } } */
  function meterTable(rows) {
    return el('table', { class: 'meter' },
      el('caption', { class: 'sr-only', text: 'Readings for this period and the previous one' }),
      el('thead', null, el('tr', null,
        el('th', { scope: 'col', text: 'Reading' }),
        el('th', { scope: 'col', class: 'num', text: 'This period' }),
        el('th', { scope: 'col', class: 'num', text: 'Previous' }),
        el('th', { scope: 'col', class: 'num', text: 'Change' }),
        el('th', { scope: 'col', class: 'bars' }, el('span', { class: 'sr-only', text: 'Comparison' })))),
      el('tbody', null, rows.map((row) => el('tr', null,
        el('th', { scope: 'row', text: row.label }),
        el('td', { class: 'num', text: row.value(row.now) }),
        el('td', { class: 'num prev', text: row.value(row.before) }),
        el('td', { class: `num change ${row.change.cls || ''}`, text: row.change.text }),
        el('td', { class: 'bars' }, pairedBars(row.now, row.before))))));
  }

  function legend() {
    return el('p', { class: 'legend' },
      el('span', null, el('i', { class: 'now', 'aria-hidden': 'true' }), 'This period'),
      el('span', null, el('i', { class: 'then', 'aria-hidden': 'true' }), 'Previous period'));
  }

  /** This period against the previous one, day by day, on one shared scale. */
  function pairedChart(current, previous, options) {
    const figure = el('figure', { class: 'figure sheet' }, el('figcaption', { text: options.label }), legend());
    if (current.every((v) => v.value === 0) && previous.every((v) => v === 0)) {
      figure.append(empty(options.emptyText));
      return figure;
    }
    const width = 640;
    const height = 190;
    const top = 18;
    const bottom = 24;
    const plot = height - top - bottom;
    const max = Math.max(...current.map((d) => d.value), ...previous);
    const slot = width / current.length;
    const pairWidth = Math.max(2, slot * 0.72);
    const bar = pairWidth / 2;
    const chart = svg('svg', { viewBox: `0 0 ${width} ${height}`, class: 'chart paired-chart', role: 'img', 'aria-label': options.label });
    chart.append(svg('line', { class: 'grid', x1: 0, x2: width, y1: top, y2: top }));
    chart.append(svg('text', { x: 0, y: top - 6, text: (options.axis || options.format)(max) }));
    const every = Math.ceil(current.length / 12);
    current.forEach((day, index) => {
      const before = previous[index] || 0;
      const x = index * slot + (slot - pairWidth) / 2;
      const h = (v) => (v > 0 ? Math.max(1, plot * (v / max)) : 0);
      chart.append(svg('rect', { class: 'now', x, y: top + plot - h(day.value), width: bar, height: h(day.value) },
        svg('title', { text: `${day.label}: ${options.format(day.value)}` })));
      chart.append(svg('rect', { class: 'then', x: x + bar, y: top + plot - h(before), width: bar, height: h(before) },
        svg('title', { text: `Same day of the previous period: ${options.format(before)}` })));
      if (index % every === 0) {
        chart.append(svg('text', { x: x + pairWidth / 2, y: height - 6, 'text-anchor': 'middle', text: day.label }));
      }
    });
    chart.append(svg('line', { class: 'axis', x1: 0, x2: width, y1: top + plot, y2: top + plot }));
    figure.append(chart);
    return figure;
  }

  /** The one plain-language answer the statement leads with, built from the readings only. */
  function overviewSummary(now) {
    if (now.turns === 0) {
      return `Nobody used the assistant ${PERIOD_TEXT[state.period]}.`;
    }
    const conversations = plural(now.conversations, 'conversation', 'conversations');
    let who;
    if (now.activeUsers === 0) {
      who = `The assistant held ${conversations} ${PERIOD_TEXT[state.period]}, all anonymous.`;
    } else {
      const anonymous = now.anonymousTurns ? `, plus ${plural(now.anonymousTurns, 'anonymous turn', 'anonymous turns')}` : '';
      who = `${plural(now.activeUsers, 'signed-in user', 'signed-in users')} had ${conversations} with the assistant `
        + `${PERIOD_TEXT[state.period]}${anonymous}.`;
    }
    const failures = now.errorTurns === 0 ? 'Every turn succeeded'
      : `${count(now.errorTurns)} of ${plural(now.turns, 'turn', 'turns')} failed`;
    const searches = now.retrievalTurns === 0 ? 'no turn searched the knowledge base'
      : `${share(now.retrievalTurns, now.turns)} of turns searched the knowledge base`;
    return `${who} ${failures}; ${searches}.`;
  }

  function costRow(label, value) {
    return el('div', { class: 'row' }, el('dt', { text: label }), el('dd', { text: value }));
  }

  // ---- Pages -------------------------------------------------------------------------------------

  /**
   * Says which models have no price and why: prices match the model name exactly, so a configured name that
   * differs by a suffix, a prefix or a variant leaves the model unpriced. Null when every model is priced.
   */
  function pricingNotice(data) {
    const missing = data.unpricedModels || [];
    if (missing.length === 0) {
      return null;
    }
    const configured = data.configuredModels || [];
    return el('section', { class: 'pricing-notice', role: 'alert', 'aria-label': 'Models without a price' },
      el('h2', { text: `${plural(missing.length, 'model has', 'models have')} no price, so their cost is missing` }),
      el('p', null, 'Prices are matched on the exact model name, as the provider reports it. Update ',
        el('code', { text: 'ai-insights.pricing.models' }), ' so that each name below is listed exactly as written.'),
      el('ul', null, missing.map((m) => el('li', null,
        el('code', { text: m.model }), ` used in ${plural(m.calls, 'call', 'calls')}`,
        m.similar
          ? [' · you configured ', el('code', { text: m.similar }), ', which is not the same name']
          : ' · no similar name is configured'))),
      el('p', { class: 'muted' }, configured.length
        ? ['Configured now: ', configured.flatMap((model, k) => (k ? [', ', el('code', { text: model })] : [el('code', { text: model })]))]
        : 'No model is configured under ai-insights.pricing.models.'));
  }

  async function overview() {
    const params = filterParams();
    const [data, earlier, spend] = await Promise.all([
      api('overview', params), api('overview', previousParams(params)), api('cost', params)]);
    const now = data.current;
    const before = data.previous;
    const days = dailySeries(data.days, params);
    const previousDays = dailySeries(earlier.days, previousParams(params));
    const tokens = (u) => u.inputTokens + u.outputTokens;
    const failed = (u) => ratio(u.errorTurns, u.turns);
    const priced = now.turns - now.unpricedTurns;
    return [
      pageHead('Overview', `What the assistant cost and how it was used ${PERIOD_TEXT[state.period]}.`),
      pricingNotice(spend),
      el('section', { class: 'statement sheet', 'aria-label': 'Statement for the period' },
        el('div', { class: 'due' },
          el('h2', { text: 'Cost this period' }),
          el('p', { class: 'amount', text: moneyHeadline(now.cost) }),
          el('p', { class: 'muted', text: priced > 0
            ? `${plural(now.turns, 'turn', 'turns')} · ${moneyHeadline(Number(now.cost) / priced)} per priced turn`
            : plural(now.turns, 'turn', 'turns') }),
          now.unpricedTurns ? el('p', { class: 'warn', text: `${plural(now.unpricedTurns, 'turn has', 'turns have')} no price: `
            + 'see the warning above.' }) : null,
          el('dl', { class: 'month' },
            costRow('This month so far', moneyHeadline(spend.monthToDate)),
            costRow('Estimated month end', spend.monthEndProjection === null ? 'after day one' : moneyHeadline(spend.monthEndProjection))),
),
        el('div', { class: 'reads' },
          el('p', { class: 'answer', text: overviewSummary(now) }),
          meterTable([
            { label: 'Turns', now: now.turns, before: before.turns, value: count, change: { text: change(now.turns, before.turns) } },
            { label: 'Conversations', now: now.conversations, before: before.conversations, value: count,
              change: { text: change(now.conversations, before.conversations) } },
            { label: 'Signed-in users', now: now.activeUsers, before: before.activeUsers, value: count,
              change: { text: change(now.activeUsers, before.activeUsers) } },
            { label: 'Tokens', now: tokens(now), before: tokens(before), value: (n) => compactNumber.format(n),
              change: { text: change(tokens(now), tokens(before)) } },
            { label: 'Cost', now: Number(now.cost), before: Number(before.cost), value: moneyHeadline,
              change: { text: change(Number(now.cost), Number(before.cost)) } },
            { label: 'Failed turns', now: failed(now), before: failed(before),
              value: (r) => percent.format(r), change: pointsChange(now, before) }
          ]))),
      el('div', { class: 'modules' },
        pairedChart(days.map((d) => ({ label: d.label, value: d.turns })), previousDays.map((d) => d.turns),
          { label: 'Turns per day', format: count, emptyText: 'No turns in this period or the one before.' }),
        el('section', { class: 'sheet' },
          el('h2', { text: 'Cost by model' }),
          el('p', { class: 'note', text: 'Priced with your ai-insights.pricing catalog.' }),
          table([
            { label: 'Model', cell: (m) => m.model },
            { label: 'Calls', num: true, cell: (m) => count(m.calls) },
            { label: 'Cost', num: true, cell: (m) => (m.unpricedCalls === m.calls ? 'no price' : money(m.cost)) }
          ], spend.models, 'No model calls in this period.'))),
      el('section', { class: 'sheet' },
        el('h2', { text: 'Recent turns' }),
        el('p', { class: 'note', text: 'The 50 newest turns. Open a conversation to see every step of it.' }),
        table(TURN_COLUMNS, data.recentTurns,
          'No turns in this period. Turns appear here a few seconds after someone chats with the assistant.'))
    ];
  }

  async function usage() {
    const params = filterParams();
    const data = await api('usage', params);
    const returning = Math.max(data.activeUsers - data.newUsers, 0);
    const days = dailySeries(data.days, params);
    return [
      pageHead('Usage', `Who talks to the assistant ${PERIOD_TEXT[state.period]}, and when.`),
      ledger([
        { label: 'Daily active users', value: count(data.dailyActiveUsers), hint: 'last 24 hours' },
        { label: 'Weekly active users', value: count(data.weeklyActiveUsers), hint: 'last 7 days' },
        { label: 'Monthly active users', value: count(data.monthlyActiveUsers), hint: 'last 30 days' },
        { label: 'New users', value: count(data.newUsers), hint: `${count(returning)} returning` },
        { label: 'Turns per user', value: oneDecimal.format(data.turnsPerUser) },
        { label: 'Turns per conversation', value: oneDecimal.format(data.turnsPerConversation) }
      ]),
      el('div', { class: 'pair' },
        barChart(days.map((d) => ({ label: d.label, value: d.activeUsers })),
          { label: 'Active users per day', format: count, emptyText: 'No users in this period.' }),
        barChart(data.turnsByHourOfDay.map((n, hour) => ({ label: `${hour}h`, value: n })),
          { label: `Turns by hour of day (${state.zone})`, format: count, emptyText: 'No turns in this period.' })),
      section('Most active users', 'User IDs are pseudonymised: the same person always has the same ID.',
        table([
          { label: 'User', cell: (u) => user(u.userId) },
          { label: 'Turns', num: true, cell: (u) => count(u.turns) },
          { label: 'Conversations', num: true, cell: (u) => count(u.conversations) },
          { label: 'Cost', num: true, cell: (u) => money(u.cost) },
          { label: 'Last seen', cell: (u) => dateTime(u.lastSeen) }
        ], data.topUsers, 'No signed-in users in this period.'))
    ];
  }

  async function cost() {
    const params = filterParams();
    const data = await api('cost', params);
    return [
      pageHead('Cost', `What the assistant cost ${PERIOD_TEXT[state.period]}, and where the month is heading.`),
      pricingNotice(data),
      ledger([
        { label: 'Cost in period', value: moneyHeadline(data.total) },
        { label: 'Per conversation', value: moneyHeadline(data.perConversation) },
        { label: 'This month so far', value: moneyHeadline(data.monthToDate), hint: 'calendar month, all periods' },
        {
          label: 'Month-end projection', value: moneyHeadline(data.monthEndProjection),
          hint: data.monthEndProjection === null ? 'available after the first day of the month' : 'at the current pace'
        },
        {
          label: 'Turns without a price', value: count(data.unpricedTurns),
          hint: data.unpricedTurns ? 'see the warning above' : 'every turn is priced'
        }
      ]),
      el('div', { class: 'pair' },
        barChart(dailySeries(data.days, params).map((d) => ({ label: d.label, value: d.cost })),
          { label: 'Cost per day', format: money, axis: moneyAxis, tone: 'ochre', emptyText: 'No priced turns in this period.' }),
        barChart(data.models.filter((m) => m.cost !== null && Number(m.cost) > 0)
          .map((m) => ({ label: m.model, value: Number(m.cost) })),
          { label: 'Cost by model', format: money, axis: moneyAxis, tone: 'ochre', emptyText: 'No priced model call in this period.' })),
      section('By model', null, table([
        { label: 'Model', cell: (m) => m.model },
        { label: 'Calls', num: true, cell: (m) => count(m.calls) },
        { label: 'Input tokens', num: true, cell: (m) => count(m.inputTokens) },
        { label: 'Output tokens', num: true, cell: (m) => count(m.outputTokens) },
        { label: 'Cached tokens read', num: true, cell: (m) => count(m.cacheReadTokens) },
        { label: 'Cost', num: true, cell: (m) => (m.unpricedCalls === m.calls ? '—' : money(m.cost)) },
        { label: 'Calls without a price', num: true, cell: (m) => count(m.unpricedCalls) }
      ], data.models, 'No model calls in this period.')),
      el('div', { class: 'pair' },
        section('By tenant', null, table([
          { label: 'Tenant', cell: (t) => t.tenantId },
          { label: 'Turns', num: true, cell: (t) => count(t.turns) },
          { label: 'Users', num: true, cell: (t) => count(t.activeUsers) },
          { label: 'Cost', num: true, cell: (t) => money(t.cost) }
        ], data.tenants, 'No tenant was recorded in this period.')),
        section('Most expensive users', null, table([
          { label: 'User', cell: (u) => user(u.userId) },
          { label: 'Turns', num: true, cell: (u) => count(u.turns) },
          { label: 'Cost', num: true, cell: (u) => money(u.cost) }
        ], data.topUsers, 'No signed-in users in this period.')))
    ];
  }

  function coverageSection(coverage) {
    const note = 'Documents of your knowledge bases that no search returned in this period.';
    if (coverage.length === 0) {
      return section('Never retrieved', note, empty('List the documents of an Amazon Bedrock Knowledge Base with '
        + 'ai-insights.knowledge.bedrock.knowledge-base-ids, or define a KnowledgeCorpusCatalog bean, to see them here.'));
    }
    return section('Never retrieved', note, coverage.map((c) => {
      if (c.documents === null) {
        return el('div', { class: 'coverage' }, el('h3', { text: c.store }), empty('Catalog not loaded yet.'));
      }
      const more = c.neverRetrieved - c.neverRetrievedSample.length;
      return el('div', { class: 'coverage' },
        el('h3', { text: c.store }),
        el('p', { text: `${count(c.neverRetrieved)} of ${count(c.documents)} documents never retrieved` }),
        c.neverRetrievedSample.length ? el('ul', { class: 'ids' },
          c.neverRetrievedSample.map((id) => el('li', { class: 'id', text: id }))) : null,
        more > 0 ? el('p', { class: 'muted', text: `and ${count(more)} more` }) : null);
    }));
  }

  async function knowledge() {
    const data = await api('knowledge', filterParams());
    if (data.retrievals === 0) {
      return [
        pageHead('Knowledge base', `How often answers used your documents ${PERIOD_TEXT[state.period]}.`),
        empty('No knowledge base searches in this period. Searches are recorded when the assistant queries a '
          + 'Spring AI VectorStore, for example through a retrieval advisor or a tool.'),
        coverageSection(data.coverage)
      ];
    }
    return [
      pageHead('Knowledge base', `How often answers used your documents ${PERIOD_TEXT[state.period]}.`),
      ledger([
        {
          label: 'Turns that searched', value: share(data.retrievalTurns, data.turns),
          hint: `${count(data.retrievalTurns)} of ${count(data.turns)} turns`
        },
        { label: 'Searches', value: count(data.retrievals) },
        {
          label: 'Searches with no result', value: share(data.emptyRetrievals, data.retrievals),
          hint: `${count(data.emptyRetrievals)} found nothing`
        }
      ]),
      section('By store', null, table([
        { label: 'Store', cell: (s) => s.store },
        { label: 'Searches', num: true, cell: (s) => count(s.queries) },
        { label: 'No result', num: true, cell: (s) => share(s.emptyQueries, s.queries) },
        { label: 'Average results', num: true, cell: (s) => oneDecimal.format(s.avgResultCount) },
        { label: 'Average best score', num: true, cell: (s) => (s.avgTopScore === null ? '—' : twoDecimals.format(s.avgTopScore)) }
      ], data.stores, 'No store was searched in this period.')),
      el('div', { class: 'pair' },
        section('Most returned documents', null, documentTable(data.topDocuments)),
        section('Least returned documents', 'Returned at least once, fewest times first.',
          documentTable(data.leastDocuments))),
      coverageSection(data.coverage)
    ];
  }

  function documentTable(documents) {
    return table([
      { label: 'Document', cell: (d) => el('span', { class: 'id', text: d.documentId }) },
      { label: 'Store', cell: (d) => d.store },
      { label: 'Times returned', num: true, cell: (d) => count(d.hits) }
    ], documents, 'Searches in this period returned no document IDs.');
  }

  async function tools() {
    const data = await api('tools', filterParams());
    const most = data.tools.length ? data.tools[0].calls : 0;
    return [
      pageHead('Tools', `Which tools the model called ${PERIOD_TEXT[state.period]}. Most used first, least used last.`),
      table([
        { label: 'Tool', cell: (t) => [t.tool, shareBar(t.calls, most)] },
        { label: 'Calls', num: true, cell: (t) => count(t.calls) },
        { label: 'Turns', num: true, cell: (t) => count(t.turns) },
        { label: 'Errors', num: true, cell: (t) => count(t.errors) },
        { label: 'Error rate', num: true, cell: (t) => share(t.errors, t.calls) },
        { label: 'Average duration', num: true, cell: (t) => duration(t.avgDurationMs) },
        { label: 'p95 duration', num: true, cell: (t) => duration(t.p95DurationMs) },
        { label: 'Repeated in a turn', num: true, cell: (t) => count(t.repeatedCalls) }
      ], data.tools, 'The model called no tool in this period.'),
      section('Never called in this period', 'Tools offered to the model that it did not call once.',
        data.neverCalled.length
          ? el('ul', { class: 'ids' }, data.neverCalled.map((name) => el('li', { text: name })))
          : empty(data.offeredRecorded ? 'All offered tools were called.' : 'No offered tools recorded in this period.'))
    ];
  }

  const ABANDON_REASONS = {
    ERROR: 'Last turn failed',
    CANCELLED: 'Last turn cancelled',
    NEGATIVE_FEEDBACK: 'Last answer rated not helpful'
  };

  async function quality() {
    const data = await api('quality', filterParams());
    const s = data.summary;
    const q = data.quality;
    const rated = q.positiveFeedback + q.negativeFeedback;
    return [
      pageHead('Quality', `How well the assistant answered ${PERIOD_TEXT[state.period]}.`),
      ledger([
        {
          label: 'Positive ratings', value: share(q.positiveFeedback, rated),
          hint: rated ? `${count(q.positiveFeedback)} helpful, ${count(q.negativeFeedback)} not helpful` : 'no ratings yet'
        },
        { label: 'Failed turns', value: share(s.errorTurns, s.turns), hint: `${count(s.errorTurns)} of ${count(s.turns)}` },
        { label: 'Cancelled turns', value: share(s.cancelledTurns, s.turns), hint: `${count(s.cancelledTurns)} of ${count(s.turns)}` },
        {
          label: 'Abandoned conversations', value: share(data.abandoned.count, s.conversations),
          hint: `${count(data.abandoned.count)} of ${count(s.conversations)} conversations`
        },
        { label: 'Average latency', value: duration(s.avgLatencyMs) },
        { label: 'Time to first token', value: duration(s.avgTimeToFirstTokenMs), hint: 'streamed answers' }
      ]),
      el('div', { class: 'pair' },
        section('Why answers ended', 'Finish reasons reported by the model. LENGTH means the answer was cut off.', table([
          { label: 'Finish reason', cell: (k) => k.key },
          { label: 'Count', num: true, cell: (k) => count(k.count) }
        ], q.finishReasons, 'No finish reason was reported in this period.')),
        section('Errors', null, table([
          { label: 'Error type', cell: (k) => k.key },
          { label: 'Turns', num: true, cell: (k) => count(k.count) }
        ], q.errorTypes, 'No turn failed in this period.'))),
      section('Recent negative ratings', 'Open the conversation to see what the assistant answered.', table([
        { label: 'Rated', cell: (f) => dateTime(f.createdAt) },
        { label: 'User', cell: (f) => user(f.userId) },
        { label: 'Comment', cell: (f) => f.comment || el('span', { class: 'muted', text: 'No comment' }) },
        { label: 'Conversation', cell: (f) => conversationLink(f.conversationId, f.application) }
      ], data.recentNegative, 'No negative rating in this period.')),
      section('Abandoned conversations', 'Conversations that went quiet right after a failed, cancelled or '
        + 'badly rated answer. Newest first.', table([
        { label: 'Last turn', cell: (a) => dateTime(a.lastTurnAt) },
        { label: 'User', cell: (a) => user(a.userId) },
        { label: 'Why', cell: (a) => ABANDON_REASONS[a.reason] || a.reason },
        { label: 'Conversation', cell: (a) => conversationLink(a.conversationId) }
      ], data.abandoned.recent, 'No conversation was abandoned in this period.'))
    ];
  }

  async function conversation(app, id) {
    if (!app || !id) {
      throw new Error('This conversation link is incomplete.');
    }
    const data = await api('conversation', { app, id });
    const back = { href: '#overview', text: 'Back to the overview' };
    if (data.turns.length === 0) {
      return [
        pageHead('Conversation', null, back),
        empty(`No turns found for conversation ${id} in ${app}. It may be older than the retention period.`)
      ];
    }
    const feedback = new Map(data.feedback.map((f) => [f.turnId, f]));
    // A conversation too long to show comes as its latest turns; the cards are numbered within the whole.
    const total = Math.max(data.totalTurns || 0, data.turns.length);
    const hidden = total - data.turns.length;
    const subtitle = hidden > 0
      ? `Showing the latest ${plural(data.turns.length, 'turn', 'turns')} of ${count(total)} in ${app}, oldest first.`
      : `${plural(data.turns.length, 'turn', 'turns')} in ${app}, oldest first.`;
    return [
      pageHead('Conversation', subtitle, back),
      hidden > 0
        ? el('p', { class: 'warn', role: 'status',
          text: `The ${plural(hidden, 'earlier turn is', 'earlier turns are')} not shown. The figures below cover the turns shown.` })
        : null,
      conversationSummary(app, id, data.turns, data.feedback),
      el('ol', { class: 'turns' }, data.turns.map((t, k) => turnCard(t, hidden + k + 1, total, feedback.get(t.turnId))))
    ];
  }

  function sum(items, value) {
    return items.reduce((total, item) => total + Number(value(item) || 0), 0);
  }

  function conversationSummary(app, id, turns, ratings) {
    const calls = turns.flatMap((t) => t.modelCalls);
    const priced = turns.filter((t) => t.totalCost !== null && t.totalCost !== undefined);
    const total = sum(priced, (t) => t.totalCost);
    const failed = turns.filter((t) => t.outcome === 'ERROR').length;
    const first = turns[0];
    const last = turns[turns.length - 1];
    const lastEnd = new Date(new Date(last.startedAt).getTime() + Number(last.latencyMs || 0));
    const users = [...new Set(turns.map((t) => t.userId).filter(Boolean))];
    const tenants = [...new Set(turns.map((t) => t.tenantId).filter(Boolean))];
    const up = ratings.filter((r) => r.rating === 'UP').length;
    const down = ratings.length - up;
    const row = (label, ...value) => el('div', { class: 'row' }, el('dt', { text: label }), el('dd', null, value));
    return el('section', { class: 'sheet statement convo' },
      el('div', { class: 'due' },
        el('h2', { text: 'Cost of the conversation' }),
        el('p', { class: 'amount', text: priced.length ? money(total) : '—' }),
        el('p', { text: `${plural(turns.length, 'turn', 'turns')} · ${priced.length ? money(total / priced.length) : '—'} per turn` }),
        priced.length < turns.length
          ? el('p', { class: 'warn', text: `${plural(turns.length - priced.length, 'turn has', 'turns have')} no price.` })
          : null,
        el('dl', { class: 'month' },
          row('Tokens in', count(sum(calls, (c) => c.inputTokens))),
          row('Tokens out', count(sum(calls, (c) => c.outputTokens))))),
      el('dl', { class: 'reads convo-facts' },
        row('Started', el('time', { datetime: first.startedAt, text: dateTime(first.startedAt) })),
        row('Last answer', el('time', { datetime: lastEnd.toISOString(), text: dateTime(lastEnd.toISOString()) })),
        row(users.length > 1 ? 'Users' : 'User', users.length ? users.map((u) => user(u)) : user(null)),
        row('Tenant', tenants.length ? tenants.join(', ') : el('span', { class: 'muted', text: 'None' })),
        row('Outcome', failed ? el('span', { class: 'change bad', text: `${plural(failed, 'failed turn', 'failed turns')}` })
          : el('span', { text: 'Every turn succeeded' })),
        row('Feedback', ratings.length ? `${count(up)} helpful, ${count(down)} not helpful`
          : el('span', { class: 'muted', text: 'None given' })),
        row('Steps', `${plural(calls.length, 'model call', 'model calls')}, `
          + `${plural(sum(turns, (t) => t.toolCalls.length), 'tool call', 'tool calls')}, `
          + `${plural(sum(turns, (t) => t.retrievals.length), 'search', 'searches')}`),
        row('Conversation ID', el('code', { class: 'id', text: id }))));
  }

  function turnCard(t, number, total, rating) {
    const cited = t.assistantMessage ? window.AiInsightsMarkdown.linkTargets(t.assistantMessage) : [];
    const facts = [
      el('time', { datetime: t.startedAt, text: dateTime(t.startedAt) }),
      badge(t.outcome),
      el('span', { title: 'Time to the complete answer', text: duration(t.latencyMs) }),
      t.timeToFirstTokenMs !== null && t.timeToFirstTokenMs !== undefined
        ? el('span', { title: 'Time to the first streamed token', text: `first token ${duration(t.timeToFirstTokenMs)}` }) : null,
      el('span', { text: t.streaming ? 'streamed' : 'single answer' }),
      el('span', { class: 'cost', text: money(t.totalCost) }),
      rating ? badge(rating.rating, rating.rating === 'UP' ? 'rated helpful' : 'rated not helpful') : null,
      t.traceId ? traceLink(t.traceId) : null
    ];
    return el('li', { class: `turn-card ${t.outcome}` },
      el('header', { class: 'turn-bar' },
        el('h2', { text: total > 1 ? `Turn ${number} of ${total}` : 'Turn' }),
        el('p', { class: 'turn-facts' }, facts)),
      el('div', { class: 'turn-body' },
        el('div', { class: 'transcript' }, transcript(t, rating)),
        el('aside', { class: 'receipt', 'aria-label': `What turn ${number} used` },
          modelCalls(t.modelCalls),
          toolCalls(t.toolCalls),
          searches(t.retrievals, cited))));
  }

  function transcript(t, rating) {
    const parts = [];
    if (t.userMessage) {
      parts.push(el('div', { class: 'said user' },
        el('p', { class: 'who', text: 'User' }), el('p', { class: 'plain', text: t.userMessage })));
    }
    if (t.assistantMessage) {
      parts.push(answer(t.assistantMessage));
    }
    if (parts.length === 0) {
      parts.push(el('p', { class: 'muted', text: 'Messages are not recorded (ai-insights.capture-content is off).' }));
    }
    if (t.outcome !== 'SUCCESS') {
      parts.push(el('p', { class: `stop ${t.outcome}` },
        el('strong', { text: t.outcome === 'ERROR' ? 'The turn failed' : 'The turn was cancelled' }),
        t.errorType ? el('span', { text: ` with ${t.errorType}.` }) : '.'));
    }
    if (rating && rating.comment) {
      parts.push(el('blockquote', { class: 'rating-comment' },
        el('p', { class: 'who', text: rating.rating === 'UP' ? 'Feedback: helpful' : 'Feedback: not helpful' }),
        el('p', { class: 'plain', text: rating.comment })));
    }
    return parts;
  }

  // The answer as the user read it: Markdown rendered as elements, long answers folded until asked.
  function answer(text) {
    const body = el('div', { class: 'md' }, window.AiInsightsMarkdown.parse(text).map(mdBlock));
    const toggle = el('button', { type: 'button', class: 'unfold', 'aria-expanded': 'false', hidden: true,
      text: 'Show the whole answer' });
    const box = el('div', { class: 'said assistant folded' }, el('p', { class: 'who', text: 'Assistant' }), body, toggle);
    toggle.addEventListener('click', () => {
      const open = box.classList.toggle('folded') === false;
      toggle.setAttribute('aria-expanded', String(open));
      toggle.textContent = open ? 'Show less' : 'Show the whole answer';
      if (!open) {
        box.scrollIntoView({ block: 'nearest' });
      }
    });
    requestAnimationFrame(() => requestAnimationFrame(() => {
      if (body.scrollHeight > body.clientHeight + 8) {
        toggle.hidden = false;
      } else {
        box.classList.remove('folded');
      }
    }));
    return box;
  }

  function mdBlock(block) {
    switch (block.type) {
      case 'heading':
        return el(`h${Math.min(block.level + 2, 6)}`, null, mdInline(block.inline));
      case 'paragraph':
        return el('p', null, mdInline(block.inline));
      case 'list':
        return el(block.ordered ? 'ol' : 'ul', { start: block.ordered && block.start !== 1 ? block.start : null },
          block.items.map((item) => el('li', null, item.map(mdBlock))));
      case 'code':
        return el('figure', { class: 'code' },
          block.lang ? el('figcaption', { text: block.lang }) : null,
          el('pre', { tabindex: '0' }, el('code', { text: block.text })));
      case 'quote':
        return el('blockquote', null, block.blocks.map(mdBlock));
      case 'rule':
        return el('hr');
      case 'table': {
        const align = (k) => (block.align[k] ? `al-${block.align[k]}` : null);
        return el('div', { class: 'scroll' }, el('table', null,
          el('thead', null, el('tr', null, block.head.map((cell, k) => el('th', { class: align(k) }, mdInline(cell))))),
          el('tbody', null, block.rows.map((row) => el('tr', null,
            row.map((cell, k) => el('td', { class: align(k) }, mdInline(cell))))))));
      }
      default:
        return null;
    }
  }

  function mdInline(nodes) {
    return nodes.map((node) => {
      switch (node.type) {
        case 'text':
          return node.text;
        case 'break':
          return el('br');
        case 'code':
          return el('code', { text: node.text });
        case 'strong':
          return el('strong', null, mdInline(node.children));
        case 'em':
          return el('em', null, mdInline(node.children));
        case 'link': {
          const href = window.AiInsightsMarkdown.safeHref(node.href);
          return href
            ? el('a', { href, target: '_blank', rel: 'noopener noreferrer', title: href }, mdInline(node.children))
            : el('span', { class: 'ref', title: node.href }, mdInline(node.children));
        }
        default:
          return null;
      }
    });
  }

  /** A readable name for a model id; Bedrock ARNs keep only their resource name. The full id stays in the title. */
  function modelName(model) {
    if (!model) {
      return '—';
    }
    if (!model.startsWith('arn:')) {
      return model;
    }
    const resource = model.slice(model.lastIndexOf(':') + 1);
    const [type, ...name] = resource.split('/');
    return /inference-profile/.test(type) ? `profile ${name.join('/')}` : name.join('/') || resource;
  }

  /** A document id as a name and the folder it sits in. */
  function documentName(id) {
    const path = String(id).replace(/^[a-z][a-z0-9+.-]*:\/\/[^/]*\/?/i, '').replace(/[?#].*$/, '');
    const parts = path.split('/').filter(Boolean);
    if (parts.length === 0) {
      return { name: String(id), folder: '' };
    }
    return { name: parts[parts.length - 1], folder: parts.slice(-3, -1).join('/') };
  }

  function receiptPart(title, summary, ...content) {
    return el('section', { class: 'part' },
      el('h3', null, title, summary ? el('span', { class: 'muted', text: summary }) : null), content);
  }

  /** A model's provider and full id/ARN, shown in full rather than hidden behind a hover title. */
  function modelIdentity(provider, model) {
    return el('p', { class: 'model' },
      el('span', { class: 'model-name', text: modelName(model) }),
      el('span', { class: 'model-full', text: `${provider}: ${model}` }));
  }

  function modelCalls(calls) {
    if (calls.length === 0) {
      return null;
    }
    const cached = calls.some((c) => c.cacheReadTokens > 0 || c.cacheWriteTokens > 0);
    const cachedCell = (c) => el('td', { class: 'num' },
      c.cacheReadTokens > 0 ? el('span', { class: 'cache-read', title: 'tokens read from cache', text: count(c.cacheReadTokens) }) : null,
      c.cacheWriteTokens > 0 ? el('span', { class: 'cache-write', title: 'tokens written to cache', text: count(c.cacheWriteTokens) }) : null,
      c.cacheReadTokens === 0 && c.cacheWriteTokens === 0 ? '—' : null);
    const total = calls.length > 1 ? el('tfoot', null, el('tr', null,
      el('th', { scope: 'row', text: 'Total' }),
      el('td', { class: 'num', text: count(sum(calls, (c) => c.inputTokens)) }),
      el('td', { class: 'num', text: count(sum(calls, (c) => c.outputTokens)) }),
      cached ? cachedCell({
        cacheReadTokens: sum(calls, (c) => c.cacheReadTokens),
        cacheWriteTokens: sum(calls, (c) => c.cacheWriteTokens),
      }) : null,
      el('td', { class: 'num', text: duration(sum(calls, (c) => c.durationMs)) }),
      el('td', { class: 'num', text: calls.every((c) => c.cost === null) ? '—' : money(sum(calls, (c) => c.cost)) }))) : null;
    const models = [...new Set(calls.map((c) => c.model))];
    return receiptPart('Model calls', ` ${count(calls.length)}`,
      models.length === 1 ? modelIdentity(calls[0].provider, models[0]) : null,
      el('table', { class: 'calls' },
        el('thead', null, el('tr', null,
          el('th', { text: '#' }), el('th', { class: 'num', text: 'In' }), el('th', { class: 'num', text: 'Out' }),
          cached ? el('th', { class: 'num', text: 'Cached' }) : null,
          el('th', { class: 'num', text: 'Time' }), el('th', { class: 'num', text: 'Cost' }))),
        el('tbody', null, calls.map((c, k) => el('tr', { class: c.errorType ? 'failed' : null },
          el('th', { scope: 'row' }, String(k + 1),
            models.length > 1 ? modelIdentity(c.provider, c.model) : null,
            c.errorType ? el('span', { class: 'fail-text', text: `failed: ${c.errorType}` }) : null),
          el('td', { class: 'num', text: count(c.inputTokens) }),
          el('td', { class: 'num', text: count(c.outputTokens) }),
          cached ? cachedCell(c) : null,
          el('td', { class: 'num', text: duration(c.durationMs) }),
          el('td', { class: 'num', text: money(c.cost) })))),
        total));
  }

  function toolCalls(calls) {
    if (calls.length === 0) {
      return null;
    }
    return receiptPart('Tool calls', ` ${count(calls.length)}`,
      el('ol', { class: 'steps' }, calls.map((c) => el('li', { class: c.outcome === 'ERROR' ? 'failed' : null },
        el('code', { text: c.name }),
        el('span', { class: 'muted', text: duration(c.durationMs) }),
        c.outcome === 'ERROR' ? el('span', { class: 'fail-text', text: c.errorType ? `failed: ${c.errorType}` : 'failed' }) : null))));
  }

  function searches(retrievals, cited) {
    if (retrievals.length === 0) {
      return null;
    }
    const top = Math.max(1, ...retrievals.flatMap((r) => r.scores));
    return receiptPart('Knowledge base searches', ` ${count(retrievals.length)}`, retrievals.map((r) => {
      const scored = r.scores.length === r.documentIds.length;
      const citedHere = r.documentIds.filter((d) => cited.includes(d)).length;
      return el('div', { class: 'search' },
        el('p', { class: 'search-head' },
          el('span', { text: r.store }),
          el('span', { class: 'muted', text: ` · ${plural(r.resultCount, 'result', 'results')} · ${duration(r.durationMs)}` }),
          citedHere ? el('span', { class: 'cited-count', text: ` · ${count(citedHere)} cited` }) : null),
        r.documentIds.length === 0 ? el('p', { class: 'muted', text: 'No document came back.' })
          : el('ol', { class: 'docs' }, r.documentIds.map((d, k) => {
            const doc = documentName(d);
            const score = scored ? r.scores[k] : null;
            const bar = el('span', { class: 'score-bar', 'aria-hidden': 'true' }, el('i'));
            bar.firstChild.style.width = `${score === null ? 0 : Math.max(2, Math.round(100 * score / top))}%`;
            return el('li', { class: cited.includes(d) ? 'cited' : null, title: d },
              el('span', { class: 'doc' },
                el('span', { class: 'doc-name', text: doc.name }),
                doc.folder ? el('span', { class: 'doc-folder', text: doc.folder }) : null),
              cited.includes(d) ? el('span', { class: 'tag', text: 'cited' }) : el('span'),
              el('span', { class: 'score' }, score === null ? '' : twoDecimals.format(score), bar));
          })));
    }));
  }

  function traceLink(traceId) {
    const template = state.config.traceUrl;
    if (!template) {
      return el('span', { class: 'id trace', title: `Trace ${traceId}`, text: `trace ${shortId(traceId)}` });
    }
    return el('a', {
      class: 'id trace', href: template.split('{traceId}').join(encodeURIComponent(traceId)),
      target: '_blank', rel: 'noopener noreferrer', title: `Open trace ${traceId}`, text: `trace ${shortId(traceId)}`
    });
  }

  // ---- Navigation and filters --------------------------------------------------------------------

  const PAGES = { overview, usage, cost, knowledge, tools, quality };

  function route(focus) {
    refreshTenants();
    const hash = location.hash.replace(/^#/, '') || 'overview';
    const [name, ...rest] = hash.split('/');
    document.getElementById('f-zone').textContent = rangeText(filterParams());
    for (const link of document.querySelectorAll('.rail a')) {
      if (link.dataset.page === name) {
        link.setAttribute('aria-current', 'page');
      } else {
        link.removeAttribute('aria-current');
      }
    }
    const render = name === 'conversation'
      ? () => conversation(decodeURIComponent(rest[0] || ''), decodeURIComponent(rest[1] || ''))
      : PAGES[name] || overview;
    const token = ++renderToken;
    // Keep the current page in place, dimmed, until the next one is ready: nothing collapses or jumps.
    page.setAttribute('aria-busy', 'true');
    if (!page.firstChild) {
      page.append(el('p', { class: 'loading', text: 'Loading…' }));
    }
    Promise.resolve()
      .then(render)
      .then((nodes) => {
        if (token === renderToken) {
          clear(page);
          append(page, [nodes]);
          page.removeAttribute('aria-busy');
          if (focus) {
            window.scrollTo(0, 0);
            page.focus({ preventScroll: true });
          }
        }
      })
      .catch((error) => {
        if (token === renderToken) {
          clear(page);
          page.removeAttribute('aria-busy');
          page.append(errorBox(error.message));
        }
      });
  }

  // The tenants of the selected application and period, busiest first; typing still accepts any tenant.
  let tenantsKey = null;

  function refreshTenants() {
    const params = filterParams();
    const key = `${state.app}|${state.period}`;
    if (key === tenantsKey) {
      return;
    }
    tenantsKey = key;
    api('tenants', { app: params.app, from: params.from, to: params.to, zone: params.zone })
      .then((data) => {
        if (key !== tenantsKey) {
          return;
        }
        const list = document.getElementById('f-tenant-list');
        clear(list);
        for (const t of data.tenants) {
          list.append(el('option', { value: t.tenantId, label: `${count(t.turns)} ${t.turns === 1 ? 'turn' : 'turns'}` }));
        }
        document.getElementById('f-tenant').title = data.truncated
          ? `The ${data.tenants.length} busiest tenants are listed. Type to filter on any other.`
          : '';
      })
      .catch(() => {
        tenantsKey = null;
      });
  }

  function setupFilters(config) {
    const select = document.getElementById('f-app');
    const apps = config.applications.includes(config.application)
      ? config.applications : [config.application, ...config.applications];
    for (const app of apps) {
      select.append(el('option', { value: app, text: app }));
    }
    select.value = state.app;
    select.addEventListener('change', () => {
      state.app = select.value;
      route(false);
    });
    for (const radio of document.querySelectorAll('input[name="period"]')) {
      radio.addEventListener('change', () => {
        state.period = radio.value;
        route(false);
      });
    }
    const tenant = document.getElementById('f-tenant');
    const applyTenant = () => {
      if (tenant.value !== state.tenant) {
        state.tenant = tenant.value;
        route(false);
      }
    };
    tenant.addEventListener('change', applyTenant);
    // Picking a listed tenant or clearing the field applies at once; other typing applies on Enter or leaving.
    tenant.addEventListener('input', () => {
      const listed = Array.from(document.getElementById('f-tenant-list').options).some((o) => o.value === tenant.value);
      if (tenant.value === '' || listed) {
        applyTenant();
      }
    });
    document.getElementById('filters').addEventListener('submit', (event) => event.preventDefault());
    const railApp = document.getElementById('rail-app');
    railApp.textContent = state.app;
    select.addEventListener('change', () => {
      railApp.textContent = state.app;
    });
  }

  async function start() {
    try {
      state.config = await api('config');
    } catch (error) {
      clear(page);
      page.append(errorBox(error.message));
      return;
    }
    state.app = state.config.application;
    setCurrency(state.config.currency);
    document.getElementById('banner').hidden = !state.config.perInstance;
    setupFilters(state.config);
    window.addEventListener('hashchange', () => route(true));
    route(false);
  }

  start();
})();
