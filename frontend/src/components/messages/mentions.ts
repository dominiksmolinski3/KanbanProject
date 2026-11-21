export interface Person {
  userId: number;
  name: string;
}

export interface TextPart {
  text: string;
  mention: boolean;
}

const escape = (value: string) => value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');

const patternFor = (people: Person[]) => {
  const names = people.map((person) => escape(person.name)).sort((a, b) => b.length - a.length);
  return names.length ? new RegExp(`(^|\\s)@(${names.join('|')})(?=$|[\\s.,;:!?)])`, 'gu') : null;
};

export function findMentions(text: string, people: Person[]): number[] {
  const pattern = patternFor(people);
  if (!pattern) return [];
  const found = new Set<number>();
  for (const match of text.matchAll(pattern)) {
    const person = people.find((each) => each.name === match[2]);
    if (person) found.add(person.userId);
  }
  return [...found];
}

export function splitMentions(text: string, people: Person[]): TextPart[] {
  const pattern = patternFor(people);
  if (!pattern) return [{ text, mention: false }];
  const parts: TextPart[] = [];
  let last = 0;
  for (const match of text.matchAll(pattern)) {
    const start = (match.index ?? 0) + match[1].length;
    if (start > last) parts.push({ text: text.slice(last, start), mention: false });
    parts.push({ text: `@${match[2]}`, mention: true });
    last = start + match[2].length + 1;
  }
  if (last < text.length) parts.push({ text: text.slice(last), mention: false });
  return parts.length ? parts : [{ text, mention: false }];
}

export const mentionQuery = (text: string, caret: number): { query: string; start: number } | null => {
  const match = /(^|\s)@([^\s@]*)$/u.exec(text.slice(0, caret));
  return match ? { query: match[2], start: caret - match[2].length - 1 } : null;
};
