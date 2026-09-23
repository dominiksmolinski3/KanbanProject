// A board's flow: cumulative flow, cycle time and throughput over a window of days.
//
// Not paged - the answer is an aggregate - but bounded: the server refuses a window of more than
// 180 days with a 400 rather than clamping it, so the screen only ever offers windows inside that.
const FLOW = '/api/flow';

export const MAX_FLOW_DAYS = 180;

/**
 * `boardId` may be left out, which means the caller's own board. `start` and `done` are column
 * ids. Leaving either out means the board's own definition (FLOW-02), and where the board has none,
 * FEAT-07's rule: the last column for done, and a card's arrival on the board for start (a lead
 * time rather than a cycle time).
 */
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

/**
 * Stores the board's definition of start and done, which every later read without a choice of its
 * own uses. Owner only - the server answers 403 to anyone else. A null end goes back to the default.
 */
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
