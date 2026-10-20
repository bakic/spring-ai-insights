// Run with: node --test src/test/js/markdown.test.mjs (the Gradle check task does it).
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';

const require = createRequire(import.meta.url);
const { parse, safeHref, linkTargets } = require('../../main/resources/ai-insights-dashboard/markdown.js');

const text = (t) => ({ type: 'text', text: t });

test('a paragraph keeps its line breaks', () => {
  assert.deepEqual(parse('First line\nsecond line'), [
    { type: 'paragraph', inline: [text('First line'), { type: 'break' }, text('second line')] }
  ]);
});

test('blank lines separate paragraphs', () => {
  assert.deepEqual(parse('One\n\n\nTwo'), [
    { type: 'paragraph', inline: [text('One')] },
    { type: 'paragraph', inline: [text('Two')] }
  ]);
});

test('headings keep their level', () => {
  assert.deepEqual(parse('## Manual assignment\n### Steps ###'), [
    { type: 'heading', level: 2, inline: [text('Manual assignment')] },
    { type: 'heading', level: 3, inline: [text('Steps')] }
  ]);
});

test('a hash without a space is text', () => {
  assert.deepEqual(parse('#hashtag'), [{ type: 'paragraph', inline: [text('#hashtag')] }]);
});

test('bold, italic and inline code', () => {
  assert.deepEqual(parse('**Limit** the *group* with `userGroups` and __this__ or _that_'), [
    {
      type: 'paragraph', inline: [
        { type: 'strong', children: [text('Limit')] }, text(' the '),
        { type: 'em', children: [text('group')] }, text(' with '),
        { type: 'code', text: 'userGroups' }, text(' and '),
        { type: 'strong', children: [text('this')] }, text(' or '),
        { type: 'em', children: [text('that')] }
      ]
    }
  ]);
});

test('underscores inside words and lone stars are text', () => {
  assert.deepEqual(parse('set snake_case_name to 2 * 3'), [
    { type: 'paragraph', inline: [text('set snake_case_name to 2 * 3')] }
  ]);
});

test('code spans are not parsed further', () => {
  assert.deepEqual(parse('`**not bold** [no](link)`'), [
    { type: 'paragraph', inline: [{ type: 'code', text: '**not bold** [no](link)' }] }
  ]);
});

test('a backslash escapes markdown punctuation', () => {
  assert.deepEqual(parse('\\*literal\\*'), [{ type: 'paragraph', inline: [text('*literal*')] }]);
});

test('links keep their target and label', () => {
  assert.deepEqual(parse('**Source:** [Create a ticket](s3://bucket/docs/create-a-ticket.md) and <https://example.com/a>'), [
    {
      type: 'paragraph', inline: [
        { type: 'strong', children: [text('Source:')] }, text(' '),
        { type: 'link', href: 's3://bucket/docs/create-a-ticket.md', children: [text('Create a ticket')] },
        text(' and '),
        { type: 'link', href: 'https://example.com/a', children: [text('https://example.com/a')] }
      ]
    }
  ]);
});

test('a link target may contain parentheses', () => {
  assert.deepEqual(parse('[wiki](https://en.wikipedia.org/wiki/Bash_(Unix_shell))'), [
    {
      type: 'paragraph', inline: [
        { type: 'link', href: 'https://en.wikipedia.org/wiki/Bash_(Unix_shell)', children: [text('wiki')] }
      ]
    }
  ]);
});

test('bare web addresses become links without trailing punctuation', () => {
  assert.deepEqual(parse('See https://example.com/docs.'), [
    {
      type: 'paragraph', inline: [
        text('See '), { type: 'link', href: 'https://example.com/docs', children: [text('https://example.com/docs')] },
        text('.')
      ]
    }
  ]);
});

test('an image is kept as a link to its address, never loaded', () => {
  assert.deepEqual(parse('![chart](https://example.com/c.png)'), [
    { type: 'paragraph', inline: [{ type: 'link', href: 'https://example.com/c.png', children: [text('chart')] }] }
  ]);
});

test('HTML stays literal text', () => {
  assert.deepEqual(parse('<script>alert(1)</script>'), [
    { type: 'paragraph', inline: [text('<script>alert(1)</script>')] }
  ]);
});

test('a bullet list with inline content', () => {
  assert.deepEqual(parse('Sources:\n- [Field rules](s3://b/field-rules.md)\n* **Bold** item'), [
    { type: 'paragraph', inline: [text('Sources:')] },
    {
      type: 'list', ordered: false, start: 1, items: [
        [{ type: 'paragraph', inline: [{ type: 'link', href: 's3://b/field-rules.md', children: [text('Field rules')] }] }],
        [{ type: 'paragraph', inline: [{ type: 'strong', children: [text('Bold')] }, text(' item')] }]
      ]
    }
  ]);
});

test('an ordered list keeps its start number', () => {
  assert.deepEqual(parse('3. Open the ticket\n4) Assign it'), [
    {
      type: 'list', ordered: true, start: 3, items: [
        [{ type: 'paragraph', inline: [text('Open the ticket')] }],
        [{ type: 'paragraph', inline: [text('Assign it')] }]
      ]
    }
  ]);
});

test('indented items nest, and a loose list keeps one list', () => {
  assert.deepEqual(parse('1. Open\n   - the ticket\n   - the form\n\n2. Save'), [
    {
      type: 'list', ordered: true, start: 1, items: [
        [
          { type: 'paragraph', inline: [text('Open')] },
          {
            type: 'list', ordered: false, start: 1, items: [
              [{ type: 'paragraph', inline: [text('the ticket')] }],
              [{ type: 'paragraph', inline: [text('the form')] }]
            ]
          }
        ],
        [{ type: 'paragraph', inline: [text('Save')] }]
      ]
    }
  ]);
});

test('an indented line continues the list item', () => {
  assert.deepEqual(parse('- First\n  more of it\nAfter'), [
    {
      type: 'list', ordered: false, start: 1, items: [
        [{ type: 'paragraph', inline: [text('First'), { type: 'break' }, text('more of it'), { type: 'break' }, text('After')] }]
      ]
    }
  ]);
});

test('fenced code keeps its text and language verbatim', () => {
  assert.deepEqual(parse('Example:\n```json\n{\n  "id": "_assignee"\n}\n```\nDone'), [
    { type: 'paragraph', inline: [text('Example:')] },
    { type: 'code', lang: 'json', text: '{\n  "id": "_assignee"\n}' },
    { type: 'paragraph', inline: [text('Done')] }
  ]);
});

test('an unclosed fence runs to the end', () => {
  assert.deepEqual(parse('~~~\nstill code\n  indented'), [
    { type: 'code', lang: '', text: 'still code\n  indented' }
  ]);
});

test('fenced code inside a list item', () => {
  assert.deepEqual(parse('- Run:\n  ```bash\n  ./gradlew build\n  ```'), [
    {
      type: 'list', ordered: false, start: 1, items: [
        [
          { type: 'paragraph', inline: [text('Run:')] },
          { type: 'code', lang: 'bash', text: './gradlew build' }
        ]
      ]
    }
  ]);
});

test('block quotes and rules', () => {
  assert.deepEqual(parse('> Note: **careful**\n> second\n\n---'), [
    {
      type: 'quote', blocks: [
        { type: 'paragraph', inline: [text('Note: '), { type: 'strong', children: [text('careful')] }, { type: 'break' }, text('second')] }
      ]
    },
    { type: 'rule' }
  ]);
});

test('tables with alignment', () => {
  assert.deepEqual(parse('| Field | Required |\n|:------|:---:|\n| `title` | yes |\n| status | no |'), [
    {
      type: 'table', align: ['left', 'center'],
      head: [[text('Field')], [text('Required')]],
      rows: [
        [[{ type: 'code', text: 'title' }], [text('yes')]],
        [[text('status')], [text('no')]]
      ]
    }
  ]);
});

test('table rows are padded or cut to the header width', () => {
  assert.deepEqual(parse('a | b\n--- | ---\n1\n1 | 2 | 3'), [
    {
      type: 'table', align: [null, null],
      head: [[text('a')], [text('b')]],
      rows: [[[text('1')], []], [[text('1')], [text('2')]]]
    }
  ]);
});

test('a pipe line without a separator is a paragraph', () => {
  assert.deepEqual(parse('a | b'), [{ type: 'paragraph', inline: [text('a | b')] }]);
});

test('windows line endings and empty input', () => {
  assert.deepEqual(parse('One\r\nTwo'), [{ type: 'paragraph', inline: [text('One'), { type: 'break' }, text('Two')] }]);
  assert.deepEqual(parse(''), []);
  assert.deepEqual(parse(null), []);
});

test('only web and mail links are followed', () => {
  assert.equal(safeHref('https://example.com/a'), 'https://example.com/a');
  assert.equal(safeHref('HTTP://example.com'), 'HTTP://example.com');
  assert.equal(safeHref('mailto:support@example.com'), 'mailto:support@example.com');
  assert.equal(safeHref('javascript:alert(1)'), null);
  assert.equal(safeHref(' javascript:alert(1)'), null);
  assert.equal(safeHref('data:text/html,x'), null);
  assert.equal(safeHref('s3://bucket/doc.md'), null);
  assert.equal(safeHref('/relative'), null);
});

test('link targets lists every link of a message once', () => {
  assert.deepEqual(linkTargets('See [a](s3://b/a.md), [again](s3://b/a.md)\n- [b](https://x/b) `[no](s3://c)`'),
    ['s3://b/a.md', 'https://x/b']);
});

// Messages come from users and models: pathological input must neither freeze the page nor throw.
const within = (ms, fn) => {
  const start = process.hrtime.bigint();
  const result = fn();
  const elapsed = Number(process.hrtime.bigint() - start) / 1e6;
  assert.ok(elapsed < ms, `took ${elapsed.toFixed(0)} ms, expected under ${ms} ms`);
  return result;
};

test('unclosed emphasis markers parse in bounded time', () => {
  within(1000, () => parse('*a '.repeat(32000)));
  within(1000, () => parse('_a '.repeat(32000)));
  within(1000, () => parse('**a '.repeat(32000)));
});

test('unclosed link openers parse in bounded time', () => {
  within(1000, () => parse('[a]('.repeat(32000)));
  within(1000, () => parse('['.repeat(32000)));
  within(1000, () => parse('[a](<'.repeat(32000)));
});

test('deeply nested quotes, lists and emphasis do not overflow the stack', () => {
  assert.doesNotThrow(() => within(1000, () => parse('>'.repeat(20000) + ' deep')));
  assert.doesNotThrow(() => within(1000, () => parse(Array.from({ length: 3000 }, (_, n) => ' '.repeat(n) + '- x').join('\n'))));
  assert.doesNotThrow(() => within(1000, () => parse('*a '.repeat(500) + 'b' + ' a*'.repeat(500))));
});

test('long runs of backticks and heading markers parse in bounded time', () => {
  within(1000, () => parse('`a``'.repeat(8000)));
  within(1000, () => parse('# ' + ' #'.repeat(9000)));
});

test('text past the Markdown limit is kept as plain text', () => {
  const tail = '**not bold** '.repeat(3000);
  const blocks = parse('intro\n\n' + 'x\n\n'.repeat(8000) + tail);
  const last = blocks[blocks.length - 1];
  assert.equal(last.type, 'paragraph');
  assert.deepEqual(last.inline.map((node) => node.type), ['text']);
  assert.ok(last.inline[0].text.includes('**not bold**'));
});

test('nesting beyond the depth limit is shown as text', () => {
  const blocks = parse('>'.repeat(40) + ' deep');
  let node = blocks[0];
  let depth = 0;
  while (node.type === 'quote') {
    node = node.blocks[0];
    depth++;
  }
  assert.ok(depth <= 17, `depth ${depth}`);
  assert.equal(node.type, 'paragraph');
});
