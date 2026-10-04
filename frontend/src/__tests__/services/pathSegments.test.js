import fs from 'fs';
import path from 'path';
import { parse } from '@babel/parser';

const SERVICES = path.join(__dirname, '..', '..', 'services');

const ID = /^(this\.)?([a-z]\w*Id|id)$/;
const NON_TEXT = new Set(['position', 'completed', 'dailyFocus']);

const visit = (node, onTemplate) => {
  if (!node || typeof node.type !== 'string') return;
  if (node.type === 'TemplateLiteral') onTemplate(node);
  for (const key of Object.keys(node)) {
    const child = node[key];
    if (Array.isArray(child)) child.forEach((c) => visit(c, onTemplate));
    else if (child && typeof child.type === 'string') visit(child, onTemplate);
  }
};

const segmentsIn = (file) => {
  const source = fs.readFileSync(file, 'utf8');
  const ast = parse(source, { sourceType: 'module', plugins: ['jsx'] });
  const found = [];
  visit(ast.program, (literal) => {
    literal.expressions.forEach((expression, i) => {
      if (!literal.quasis[i].value.cooked.endsWith('/')) return;
      found.push({
        text: source.slice(expression.start, expression.end),
        expression,
        line: expression.loc.start.line,
      });
    });
  });
  return found;
};

const isSafe = ({ text, expression }) =>
  ID.test(text)
  || NON_TEXT.has(text)
  || (expression.type === 'CallExpression' && expression.callee.name === 'encodeURIComponent');

describe('service URL path segments', () => {
  const files = fs.readdirSync(SERVICES)
    .filter((name) => name.endsWith('.js'))
    .map((name) => path.join(SERVICES, name));

  test('the scan finds the id segments it is meant to check', () => {
    const all = files.flatMap(segmentsIn).map((s) => s.text);
    expect(all).toEqual(expect.arrayContaining(['taskId', 'commentId']));
  });

  test('no free text is put into a path segment, where ../ would reach another route', () => {
    const offenders = files.flatMap((file) =>
      segmentsIn(file)
        .filter((segment) => !isSafe(segment))
        .map((segment) => `${path.basename(file)}:${segment.line} \${${segment.text}}`));
    expect(offenders).toEqual([]);
  });
});
