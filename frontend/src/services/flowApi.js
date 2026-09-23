const FLOW = '/api/flow';

export const MAX_FLOW_DAYS = 180;

export const fetchFlowMetrics = async ({ boardId, from, to, start, done } = {}) => {
  const params = new URLSearchParams();
  const set = (key, value) => {
    if (value !== undefined && value !== null && value !== '') {
      params.set(key, String(value));
    }
  };
  set('boardId', boardId);
  set('from', from);
  set('to', to);
  set('start', start);
  set('done', done);

  const response = await fetch(`${FLOW}?${params.toString()}`);
  if (!response.ok) {
    throw new Error(`Error fetching the flow metrics: ${response.status}`);
  }
  return response.json();
};

export const defineFlow = async ({ boardId, start = null, done = null } = {}) => {
  const params = new URLSearchParams();
  if (boardId !== undefined && boardId !== null) {
    params.set('boardId', String(boardId));
  }
  const response = await fetch(`${FLOW}/definition?${params.toString()}`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ startColumnId: start, doneColumnId: done })
  });
  if (!response.ok) {
    throw new Error(`Error saving the flow definition: ${response.status}`);
  }
  return response.json();
};
