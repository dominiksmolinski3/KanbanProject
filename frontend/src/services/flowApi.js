// A board's flow: cumulative flow, cycle time and throughput over a window of days.
//
// Not paged - the answer is an aggregate - but bounded: the server refuses a window of more than
// 180 days with a 400 rather than clamping it, so the screen only ever offers windows inside that.
const FLOW = '/api/flow';

export const MAX_FLOW_DAYS = 180;

/**
 * `boardId` may be left out, which means the caller's own board. `start` and `done` are column
 * ids; leaving `done` out means the board's last column, and leaving `start` out measures from the
 * moment a card arrived on the board (a lead time rather than a cycle time).
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
