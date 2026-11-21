import { findMentions, mentionQuery, splitMentions } from '../../components/messages/mentions';

const people = [
  { userId: 2, name: 'Ania Kowalska' },
  { userId: 3, name: 'Ania' },
  { userId: 4, name: 'Bartek (QA)' },
];

describe('mentions', () => {
  test('finds each person once, preferring the longest name', () => {
    expect(findMentions('Hi @Ania Kowalska and @Bartek (QA), also @Ania Kowalska', people)).toEqual([2, 4]);
    expect(findMentions('@Ania, look', people)).toEqual([3]);
  });

  test('ignores an @ inside a word, a name that is not a person and an empty list', () => {
    expect(findMentions('mail me@Ania.pl or @Nobody', people)).toEqual([]);
    expect(findMentions('@Ania', [])).toEqual([]);
  });

  test('splits a text into plain and mentioned parts', () => {
    expect(splitMentions('Hey @Ania Kowalska: ping', people)).toEqual([
      { text: 'Hey ', mention: false },
      { text: '@Ania Kowalska', mention: true },
      { text: ': ping', mention: false },
    ]);
    expect(splitMentions('no one here', people)).toEqual([{ text: 'no one here', mention: false }]);
    expect(splitMentions('x', [])).toEqual([{ text: 'x', mention: false }]);
  });

  test('reads the name being typed before the caret', () => {
    expect(mentionQuery('Hello @An', 9)).toEqual({ query: 'An', start: 6 });
    expect(mentionQuery('@', 1)).toEqual({ query: '', start: 0 });
    expect(mentionQuery('Hello @An there', 15)).toBeNull();
    expect(mentionQuery('mail me@An', 10)).toBeNull();
  });
});
