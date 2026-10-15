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
    return { text: `${rising ? '▲' : '▼'} ${percent.format(Math.abs(change))} vs previous period`, cls };
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
    return el('div', { class: 'scroll' }, el('table', null,
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

  // ---- Pages -------------------------------------------------------------------------------------

  async function overview() {
    const params = filterParams();
    const data = await api('overview', params);
    const now = data.current;
    const before = data.previous;
    const days = dailySeries(data.days, params);
    return [
      pageHead('Overview', `How the assistant was used ${PERIOD_TEXT[state.period]}, compared with the period before.`),
      ledger([
        { label: 'Turns', value: count(now.turns), delta: delta(now.turns, before.turns, 'neutral') },
        { label: 'Conversations', value: count(now.conversations), delta: delta(now.conversations, before.conversations, 'neutral') },
        { label: 'Active users', value: count(now.activeUsers), delta: delta(now.activeUsers, before.activeUsers, 'up-is-good') },
        {
          label: 'Cost', value: money(now.cost), delta: delta(Number(now.cost), Number(before.cost), 'neutral'),
          hint: now.unpricedTurns ? `${count(now.unpricedTurns)} turns have no price` : null
        },
        {
          label: 'Tokens', value: compactNumber.format(now.inputTokens + now.outputTokens),
          hint: `${compactNumber.format(now.inputTokens)} in, ${compactNumber.format(now.outputTokens)} out`
        },
        {
          label: 'Failed turns', value: share(now.errorTurns, now.turns),
          delta: delta(now.errorTurns, before.errorTurns, 'up-is-bad'), hint: `${count(now.errorTurns)} of ${count(now.turns)}`
        }
      ]),
      el('div', { class: 'pair' },
        barChart(days.map((d) => ({ label: d.label, value: d.turns })),
          { label: 'Turns per day', format: count, emptyText: 'No turns in this period.' }),
        barChart(days.map((d) => ({ label: d.label, value: d.cost })),
          { label: 'Cost per day', format: money, axis: moneyAxis, tone: 'ochre', emptyText: 'No priced turns in this period.' })),
      section('Recent turns', 'The 50 newest turns. Open a conversation to see every step of it.',
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
      ledger([
        { label: 'Cost in period', value: money(data.total) },
        { label: 'Per conversation', value: money(data.perConversation) },
        { label: 'This month so far', value: money(data.monthToDate), hint: 'calendar month, all periods' },
        {
          label: 'Month-end projection', value: money(data.monthEndProjection),
          hint: data.monthEndProjection === null ? 'available after the first day of the month' : 'at the current pace'
        },
        {
          label: 'Turns without a price', value: count(data.unpricedTurns),
          hint: data.unpricedTurns ? 'add their models under ai-insights.pricing' : 'every turn is priced'
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

  async function knowledge() {
    const data = await api('knowledge', filterParams());
    if (data.retrievals === 0) {
      return [
        pageHead('Knowledge base', `How often answers used your documents ${PERIOD_TEXT[state.period]}.`),
        empty('No knowledge base searches in this period. Searches are recorded when the assistant queries a '
          + 'Spring AI VectorStore, for example through a retrieval advisor or a tool.')
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
      section('Most returned documents', 'Documents that were never returned are not listed yet.', table([
        { label: 'Document', cell: (d) => el('span', { class: 'id', text: d.documentId }) },
        { label: 'Store', cell: (d) => d.store },
        { label: 'Times returned', num: true, cell: (d) => count(d.hits) }
      ], data.topDocuments, 'Searches in this period returned no document IDs.'))
    ];
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
        { label: 'Repeated in a turn', num: true, cell: (t) => count(t.repeatedCalls) }
      ], data.tools, 'The model called no tool in this period.')
    ];
  }

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
      ], data.recentNegative, 'No negative rating in this period.'))
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
    const total = data.turns.reduce((sum, t) => sum + (t.totalCost === null ? 0 : Number(t.totalCost)), 0);
    return [
      pageHead('Conversation', `${id} in ${app}: ${count(data.turns.length)} ${data.turns.length === 1 ? 'turn' : 'turns'}, ${money(total)}.`, back),
      el('ol', { class: 'timeline' }, data.turns.map((t) => turnItem(t, feedback.get(t.turnId))))
    ];
  }

  function turnItem(t, rating) {
    const facts = [];
    for (const call of t.modelCalls) {
      facts.push(fact('Model', `${call.model}: ${count(call.inputTokens)} tokens in, ${count(call.outputTokens)} out, `
        + `${duration(call.durationMs)}, ${money(call.cost)}${call.errorType ? `, failed with ${call.errorType}` : ''}`));
    }
    for (const tool of t.toolCalls) {
      facts.push(fact('Tool', `${tool.name}: ${duration(tool.durationMs)}`
        + (tool.outcome === 'ERROR' ? `, failed${tool.errorType ? ` with ${tool.errorType}` : ''}` : '')));
    }
    for (const r of t.retrievals) {
      const best = r.scores.length ? `, best score ${twoDecimals.format(r.scores[0])}` : '';
      const docs = r.documentIds.length ? `: ${r.documentIds.join(', ')}` : '';
      facts.push(fact('Search', `${r.store}, ${count(r.resultCount)} results${best}${docs}`));
    }
    const messages = [];
    if (t.userMessage) {
      messages.push(el('p', { class: 'msg user' }, el('span', { class: 'who', text: 'User' }), t.userMessage));
    }
    if (t.assistantMessage) {
      messages.push(el('p', { class: 'msg assistant' }, el('span', { class: 'who', text: 'Assistant' }), t.assistantMessage));
    }
    if (messages.length === 0) {
      messages.push(el('p', { class: 'muted', text: 'Messages are not recorded (ai-insights.capture-content is off).' }));
    }
    return el('li', { class: `turn ${t.outcome}` },
      el('div', { class: 'turn-head' },
        el('time', { datetime: t.startedAt, text: dateTime(t.startedAt) }),
        user(t.userId),
        badge(t.outcome),
        el('span', { class: 'muted', text: `${duration(t.latencyMs)}, ${money(t.totalCost)}` }),
        t.errorType ? el('span', { class: 'muted', text: t.errorType }) : null),
      messages,
      facts.length ? el('ul', { class: 'facts' }, facts) : null,
      rating ? el('p', { class: 'feedback' },
        badge(rating.rating, rating.rating === 'UP' ? 'helpful' : 'not helpful'),
        rating.comment ? ` ${rating.comment}` : null) : null);
  }

  function fact(kind, text) {
    return el('li', null, el('span', { class: 'kind', text: kind }), text);
  }

  // ---- Navigation and filters --------------------------------------------------------------------

  const PAGES = { overview, usage, cost, knowledge, tools, quality };

  function route(focus) {
    const hash = location.hash.replace(/^#/, '') || 'overview';
    const [name, ...rest] = hash.split('/');
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
    clear(page);
    page.append(el('p', { class: 'loading', text: 'Loading…' }));
    Promise.resolve()
      .then(render)
      .then((nodes) => {
        if (token === renderToken) {
          clear(page);
          append(page, [nodes]);
          if (focus) {
            page.focus();
          }
        }
      })
      .catch((error) => {
        if (token === renderToken) {
          clear(page);
          page.append(errorBox(error.message));
        }
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
    tenant.addEventListener('change', () => {
      state.tenant = tenant.value;
      route(false);
    });
    document.getElementById('filters').addEventListener('submit', (event) => event.preventDefault());
    document.getElementById('f-zone').textContent = `Times in ${state.zone}`;
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
