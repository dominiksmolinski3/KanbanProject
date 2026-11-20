/**
 * @jest-environment node
 */
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const SOURCE = fs.readFileSync(path.join(__dirname, '../../../public/push-sw.js'), 'utf8');

function load(search = '?title=Nowa+wiadomo%C5%9B%C4%87&body=Otw%C3%B3rz+Kanban') {
  const handlers = {};
  const shown = [];
  const opened = [];
  let windows = [];
  const self = {
    location: { href: `http://localhost/push-sw.js${search}` },
    addEventListener: (name, handler) => { handlers[name] = handler; },
    skipWaiting: jest.fn(),
    clients: {
      claim: jest.fn(),
      matchAll: async () => windows,
      openWindow: async (target) => { opened.push(target); },
    },
    registration: { showNotification: async (title, options) => { shown.push({ title, options }); } },
  };
  vm.runInNewContext(SOURCE, { self, URL, encodeURIComponent });
  const settle = async (event) => { handlers[event.type || 'x'](event); await Promise.all(event.waits); };
  const event = (type, extra = {}) => {
    const waits = [];
    return { type, waitUntil: (promise) => waits.push(promise), waits, ...extra };
  };
  return { self, handlers, shown, opened, settle, event, setWindows: (value) => { windows = value; } };
}

describe('the push worker', () => {
  test('shows the translated notice for a push and keeps the message out of it', async () => {
    const worker = load();
    const push = worker.event('push', { data: { json: () => ({ c: 12, s: 5 }) } });

    await worker.settle(push);

    expect(worker.shown).toEqual([{
      title: 'Nowa wiadomość',
      options: expect.objectContaining({ body: 'Otwórz Kanban', tag: 'conversation-12', data: { c: 12 } }),
    }]);
  });

  test('says nothing while a window of the app is in front', async () => {
    const worker = load();
    worker.setWindows([{ visibilityState: 'hidden' }, { visibilityState: 'visible' }]);

    await worker.settle(worker.event('push', { data: { json: () => ({ c: 1 }) } }));

    expect(worker.shown).toEqual([]);
  });

  test('a push without a readable payload still gets a generic notice', async () => {
    const worker = load('');

    await worker.settle(worker.event('push', { data: { json: () => { throw new Error('not json'); } } }));
    await worker.settle(worker.event('push', {}));

    expect(worker.shown.map((entry) => [entry.title, entry.options.tag])).toEqual([['Kanban', 'messages'], ['Kanban', 'messages']]);
  });

  test('a tap focuses an open window on that conversation, or opens one', async () => {
    const worker = load();
    const close = jest.fn();
    const navigate = jest.fn(async () => {});
    const focus = jest.fn(async () => {});
    worker.setWindows([{ navigate, focus }]);

    await worker.settle(worker.event('notificationclick', { notification: { close, data: { c: 12 } } }));

    expect(close).toHaveBeenCalled();
    expect(navigate).toHaveBeenCalledWith('/messages?c=12');
    expect(focus).toHaveBeenCalled();

    worker.setWindows([]);
    await worker.settle(worker.event('notificationclick', { notification: { close, data: null } }));
    expect(worker.opened).toEqual(['/messages']);
  });

  test('it takes over at once', async () => {
    const worker = load();

    worker.handlers.install();
    await worker.settle(worker.event('activate'));

    expect(worker.self.skipWaiting).toHaveBeenCalled();
    expect(worker.self.clients.claim).toHaveBeenCalled();
  });
});
