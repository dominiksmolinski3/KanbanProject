import axe from 'axe-core';

export async function axeViolations(node: Element = document.body): Promise<string[]> {
  const { violations } = await axe.run(node, {
    // jsdom lays nothing out, so contrast cannot be measured, and a lone component has no page landmarks.
    rules: { 'color-contrast': { enabled: false }, region: { enabled: false } },
  });
  return violations.map((violation) => `${violation.id}: ${violation.nodes.map((node) => node.html).join(' | ')}`);
}
