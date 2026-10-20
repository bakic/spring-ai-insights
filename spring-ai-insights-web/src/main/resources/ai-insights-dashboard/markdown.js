'use strict';

// A small Markdown reader for the messages of a conversation. It returns a plain tree of blocks and inline
// nodes that app.js turns into DOM nodes with textContent, so HTML in a message always stays text.
// Covers what assistants write: headings, paragraphs, lists, fenced code, quotes, rules, tables, emphasis,
// code spans and links.
(function (root) {
  const FENCE = /^( {0,3})(`{3,}|~{3,})[ \t]*([^\s`]*)[^`]*$/;
  const HEADING = /^ {0,3}(#{1,6})(?:[ \t]+(.*?))?(?:[ \t]+#+)?[ \t]*$/;
  const RULE = /^ {0,3}([-*_])(?:[ \t]*\1){2,}[ \t]*$/;
  const ITEM = /^( *)([-*+]|(\d{1,9})[.)])(?:[ \t]+(.*)|[ \t]*$)/;
  const QUOTE = /^ {0,3}> ?(.*)$/;
  const TABLE_SEPARATOR = /^ {0,3}\|?[ \t]*:?-+:?[ \t]*(?:\|[ \t]*:?-+:?[ \t]*)*\|?[ \t]*$/;
  const PUNCTUATION = /[!-/:-@[-`{-~]/;
  const WORD = /[\p{L}\p{N}]/u;
  const AUTOLINK = /^<((?:https?:\/\/|mailto:)[^\s<>]+)>/i;
  const BARE_URL = /^https?:\/\/[^\s<>]+/i;

  // Messages come from users and models, so the reader bounds its own work: past these limits text is shown as is.
  const MAX_MARKDOWN_LENGTH = 20000; // characters read as Markdown; the rest of a message stays plain text
  const MAX_DEPTH = 16; // nested quotes, list items, emphasis and link labels
  const MAX_EMPHASIS_CLOSERS = 32; // candidate closing markers tried per opening marker
  const MAX_LINK_LABEL = 1000; // characters scanned for the end of a link label
  const MAX_LINK_TARGET = 2000; // characters scanned for the end of a link target

  function parse(markdown) {
    if (markdown === null || markdown === undefined || markdown === '') {
      return [];
    }
    let source = String(markdown);
    let rest = '';
    if (source.length > MAX_MARKDOWN_LENGTH) {
      const cut = source.lastIndexOf('\n', MAX_MARKDOWN_LENGTH);
      const at = cut > 0 ? cut : MAX_MARKDOWN_LENGTH;
      rest = source.slice(at).trim();
      source = source.slice(0, at);
    }
    const lines = source.replace(/\r\n?/g, '\n').split('\n')
      .map((line) => line.replace(/^\t+/, (tabs) => '    '.repeat(tabs.length)));
    const out = blocks(lines, 0);
    if (rest) {
      out.push({ type: 'paragraph', inline: [{ type: 'text', text: rest }] });
    }
    return out;
  }

  function blank(line) {
    return line.trim() === '';
  }

  function indentOf(line) {
    return line.length - line.trimStart().length;
  }

  function isItem(line) {
    const m = ITEM.exec(line);
    return m !== null && m[1].length <= 3;
  }

  function isTableStart(lines, i) {
    return lines[i].includes('|') && i + 1 < lines.length
      && lines[i + 1].includes('|') && TABLE_SEPARATOR.test(lines[i + 1]);
  }

  function startsBlock(lines, i) {
    const line = lines[i];
    return FENCE.test(line) || HEADING.test(line) || RULE.test(line) || QUOTE.test(line) || isItem(line)
      || isTableStart(lines, i);
  }

  function blocks(lines, depth) {
    if (depth > MAX_DEPTH) {
      return [{ type: 'paragraph', inline: [{ type: 'text', text: lines.join('\n') }] }];
    }
    const out = [];
    let i = 0;
    while (i < lines.length) {
      const line = lines[i];
      let m;
      if (blank(line)) {
        i++;
      } else if ((m = FENCE.exec(line))) {
        i = fence(lines, i, m, out);
      } else if ((m = HEADING.exec(line))) {
        out.push({ type: 'heading', level: m[1].length, inline: inline(m[2] || '', false, 0) });
        i++;
      } else if (RULE.test(line)) {
        out.push({ type: 'rule' });
        i++;
      } else if (QUOTE.test(line)) {
        const quoted = [];
        while (i < lines.length && (m = QUOTE.exec(lines[i]))) {
          quoted.push(m[1]);
          i++;
        }
        out.push({ type: 'quote', blocks: blocks(quoted, depth + 1) });
      } else if (isItem(line)) {
        i = list(lines, i, out, depth);
      } else if (isTableStart(lines, i)) {
        i = table(lines, i, out);
      } else {
        const text = [line.trim()];
        i++;
        while (i < lines.length && !blank(lines[i]) && !startsBlock(lines, i)) {
          text.push(lines[i].trim());
          i++;
        }
        out.push({ type: 'paragraph', inline: inline(text.join('\n'), false, 0) });
      }
    }
    return out;
  }

  function fence(lines, i, m, out) {
    const indent = m[1].length;
    const marker = m[2];
    const closing = new RegExp(`^ {0,3}${marker[0] === '`' ? '`' : '~'}{${marker.length},}[ \\t]*$`);
    const body = [];
    i++;
    while (i < lines.length && !closing.test(lines[i])) {
      body.push(lines[i].slice(Math.min(indent, indentOf(lines[i]))));
      i++;
    }
    out.push({ type: 'code', lang: m[3], text: body.join('\n') });
    return i + 1;
  }

  function list(lines, i, out, depth) {
    const ordered = ITEM.exec(lines[i])[3] !== undefined;
    const start = ordered ? parseInt(ITEM.exec(lines[i])[3], 10) : 1;
    const sibling = (line) => isItem(line) && (ITEM.exec(line)[3] !== undefined) === ordered;
    const items = [];
    while (i < lines.length && sibling(lines[i])) {
      const m = ITEM.exec(lines[i]);
      const gap = lines[i].slice(m[1].length + m[2].length).match(/^[ \t]*/)[0].length;
      const contentIndent = m[1].length + m[2].length + (gap === 0 || gap > 4 ? 1 : gap);
      const content = [m[4] || ''];
      i++;
      while (i < lines.length) {
        const line = lines[i];
        if (blank(line)) {
          let next = i;
          while (next < lines.length && blank(lines[next])) {
            next++;
          }
          if (next < lines.length && indentOf(lines[next]) >= contentIndent) {
            for (; i < next; i++) {
              content.push('');
            }
            continue;
          }
          break;
        }
        if (indentOf(line) >= contentIndent) {
          content.push(line.slice(contentIndent));
        } else if (startsBlock(lines, i)) {
          break;
        } else {
          content.push(line.trim());
        }
        i++;
      }
      items.push(blocks(content, depth + 1));
      let next = i;
      while (next < lines.length && blank(lines[next])) {
        next++;
      }
      if (next < lines.length && sibling(lines[next])) {
        i = next;
      } else {
        break;
      }
    }
    out.push({ type: 'list', ordered, start, items });
    return i;
  }

  function cells(line) {
    let row = line.trim();
    if (row.startsWith('|')) {
      row = row.slice(1);
    }
    if (row.endsWith('|') && !row.endsWith('\\|')) {
      row = row.slice(0, -1);
    }
    return row.split(/(?<!\\)\|/).map((cell) => cell.trim().replace(/\\\|/g, '|'));
  }

  function table(lines, i, out) {
    const head = cells(lines[i]);
    const align = cells(lines[i + 1]).slice(0, head.length).map((spec) => {
      const left = spec.startsWith(':');
      const right = spec.endsWith(':');
      return left && right ? 'center' : left ? 'left' : right ? 'right' : null;
    });
    while (align.length < head.length) {
      align.push(null);
    }
    i += 2;
    const rows = [];
    while (i < lines.length && !blank(lines[i]) && !startsBlock(lines, i)) {
      const row = cells(lines[i]).slice(0, head.length);
      while (row.length < head.length) {
        row.push('');
      }
      rows.push(row.map((cell) => inline(cell, false, 0)));
      i++;
    }
    out.push({ type: 'table', align, head: head.map((cell) => inline(cell, false, 0)), rows });
    return i;
  }

  // ---- Inline --------------------------------------------------------------------------------------

  function inline(source, noLinks, depth) {
    if (depth > MAX_DEPTH) {
      return [{ type: 'text', text: String(source) }];
    }
    const out = [];
    let text = '';
    const flush = () => {
      if (text) {
        out.push({ type: 'text', text });
        text = '';
      }
    };
    const src = String(source);
    let i = 0;
    while (i < src.length) {
      const c = src[i];
      if (c === '\\' && i + 1 < src.length && PUNCTUATION.test(src[i + 1])) {
        text += src[i + 1];
        i += 2;
        continue;
      }
      if (c === '\n') {
        flush();
        out.push({ type: 'break' });
        i++;
        continue;
      }
      if (c === '`') {
        const run = src.slice(i).match(/^`+/)[0];
        const close = findRun(src, run, i + run.length);
        if (close < 0) {
          text += run;
          i += run.length;
          continue;
        }
        let code = src.slice(i + run.length, close).replace(/\n/g, ' ');
        if (code.length > 2 && code.startsWith(' ') && code.endsWith(' ') && code.trim() !== '') {
          code = code.slice(1, -1);
        }
        flush();
        out.push({ type: 'code', text: code });
        i = close + run.length;
        continue;
      }
      if (!noLinks && (c === '[' || (c === '!' && src[i + 1] === '['))) {
        const link = linkAt(src, c === '!' ? i + 1 : i);
        if (link) {
          flush();
          out.push({ type: 'link', href: link.href, children: inline(link.label, true, depth + 1) });
          i = link.end;
          continue;
        }
      }
      if (!noLinks && c === '<') {
        const m = AUTOLINK.exec(src.slice(i));
        if (m) {
          flush();
          out.push({ type: 'link', href: m[1], children: [{ type: 'text', text: m[1] }] });
          i += m[0].length;
          continue;
        }
      }
      if (!noLinks && (c === 'h' || c === 'H') && (i === 0 || !/[\p{L}\p{N}/]/u.test(src[i - 1]))) {
        const m = BARE_URL.exec(src.slice(i));
        if (m) {
          const url = trimUrl(m[0]);
          flush();
          out.push({ type: 'link', href: url, children: [{ type: 'text', text: url }] });
          i += url.length;
          continue;
        }
      }
      if (c === '*' || c === '_') {
        const emphasis = emphasisAt(src, i, noLinks, depth);
        if (emphasis) {
          flush();
          out.push(emphasis.node);
          i = emphasis.end;
          continue;
        }
      }
      text += c;
      i++;
    }
    flush();
    return out;
  }

  function findRun(src, run, from) {
    let at = src.indexOf(run, from);
    while (at >= 0) {
      const before = src[at - 1];
      const after = src[at + run.length];
      if (before !== '`' && after !== '`') {
        return at;
      }
      at = src.indexOf(run, at + 1);
    }
    return -1;
  }

  function emphasisAt(src, i, noLinks, depth) {
    const c = src[i];
    const double = src[i + 1] === c;
    const width = double ? 2 : 1;
    const marker = c.repeat(width);
    const first = src[i + width];
    if (first === undefined || /\s/.test(first) || first === c) {
      return null;
    }
    if (c === '_' && i > 0 && WORD.test(src[i - 1])) {
      return null;
    }
    let tried = 0;
    for (let at = src.indexOf(marker, i + width + 1); at >= 0 && tried < MAX_EMPHASIS_CLOSERS;
      at = src.indexOf(marker, at + 1), tried++) {
      if (/\s/.test(src[at - 1])) {
        continue;
      }
      if (!double && (src[at + 1] === c || src[at - 1] === c)) {
        continue;
      }
      if (c === '_' && at + width < src.length && WORD.test(src[at + width])) {
        continue;
      }
      const inner = src.slice(i + width, at);
      return {
        node: { type: double ? 'strong' : 'em', children: inline(inner, noLinks, depth + 1) },
        end: at + width
      };
    }
    return null;
  }

  function linkAt(src, open) {
    let depth = 0;
    let close = -1;
    const labelEnd = Math.min(src.length, open + MAX_LINK_LABEL);
    for (let k = open; k < labelEnd; k++) {
      const ch = src[k];
      if (ch === '\\') {
        k++;
      } else if (ch === '[') {
        depth++;
      } else if (ch === ']') {
        depth--;
        if (depth === 0) {
          close = k;
          break;
        }
      }
    }
    if (close < 0 || src[close + 1] !== '(') {
      return null;
    }
    let k = close + 2;
    while (src[k] === ' ') {
      k++;
    }
    let href = '';
    if (src[k] === '<') {
      const end = src.indexOf('>', k);
      if (end < 0) {
        return null;
      }
      href = src.slice(k + 1, end);
      k = end + 1;
    } else {
      let parens = 0;
      const begin = k;
      const targetEnd = Math.min(src.length, k + MAX_LINK_TARGET);
      for (; k < targetEnd; k++) {
        const ch = src[k];
        if (/\s/.test(ch)) {
          break;
        }
        if (ch === '(') {
          parens++;
        } else if (ch === ')') {
          if (parens === 0) {
            break;
          }
          parens--;
        }
      }
      href = src.slice(begin, k);
    }
    const title = /^[ \t]+(?:"[^"]*"|'[^']*')/.exec(src.slice(k));
    if (title) {
      k += title[0].length;
    }
    while (src[k] === ' ') {
      k++;
    }
    if (src[k] !== ')') {
      return null;
    }
    return { label: src.slice(open + 1, close), href, end: k + 1 };
  }

  function trimUrl(url) {
    let end = url.length;
    while (end > 0) {
      const ch = url[end - 1];
      if ('.,;:!?\'"*_'.includes(ch)) {
        end--;
      } else if (ch === ')') {
        const part = url.slice(0, end);
        if ((part.match(/\(/g) || []).length < (part.match(/\)/g) || []).length) {
          end--;
        } else {
          break;
        }
      } else {
        break;
      }
    }
    return url.slice(0, end);
  }

  // ---- Helpers -------------------------------------------------------------------------------------

  /** The address a link may open: web and mail addresses only; anything else is shown, not followed. */
  function safeHref(href) {
    return typeof href === 'string' && /^(?:https?:\/\/|mailto:)/i.test(href) ? href : null;
  }

  /** Every link target of a message, once each, in reading order. */
  function linkTargets(markdown) {
    const found = [];
    const visit = (node) => {
      if (Array.isArray(node)) {
        node.forEach(visit);
      } else if (node && typeof node === 'object') {
        if (node.type === 'link' && !found.includes(node.href)) {
          found.push(node.href);
        }
        for (const key of ['blocks', 'inline', 'children', 'items', 'head', 'rows']) {
          if (node[key]) {
            visit(node[key]);
          }
        }
      }
    };
    visit(parse(markdown));
    return found;
  }

  const api = { parse, safeHref, linkTargets };
  if (typeof module === 'object' && module.exports) {
    module.exports = api;
  } else {
    root.AiInsightsMarkdown = api;
  }
})(typeof globalThis !== 'undefined' ? globalThis : this);
