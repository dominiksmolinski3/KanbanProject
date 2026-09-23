import React from 'react';
import { render, screen, fireEvent, waitFor, within } from '@testing-library/react';
import '@testing-library/jest-dom';
import FlowMetrics from '../../components/FlowMetrics';
import { defineFlow, fetchFlowMetrics } from '../../services/flowApi';
import { toast } from 'react-toastify';

jest.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key, values) => (values ? `${key}:${JSON.stringify(values)}` : key)
  })
}));

jest.mock('react-toastify', () => ({ toast: { success: jest.fn(), error: jest.fn() } }));

jest.mock('../../services/flowApi', () => ({
  fetchFlowMetrics: jest.fn(),
  defineFlow: jest.fn(),
  MAX_FLOW_DAYS: 180
}));

const mockKanban = { activeBoardId: 3, activeBoard: { id: 3, owned: true } };

jest.mock('../../context/KanbanContext', () => ({
  useKanban: () => mockKanban
}));

const columns = [
  { id: 10, name: 'Todo', position: 1 },
  { id: 11, name: 'Doing', position: 2 },
  { id: 12, name: 'Done', position: 3 }
];

const metrics = (overrides = {}) => ({
  boardId: 3,
  from: '2026-09-08',
  to: '2026-09-10',
  startColumnId: null,
  doneColumnId: 12,
  definedStartColumnId: null,
  definedDoneColumnId: null,
  columns,
  cumulativeFlow: [
    { date: '2026-09-08', counts: [2, 1, 0] },
    { date: '2026-09-09', counts: [1, 1, 1] },
    { date: '2026-09-10', counts: [1, 0, 2] }
  ],
  cycleTime: { count: 2, averageHours: 30, medianHours: 12, p85Hours: 72 },
  samples: [
    { taskId: 5, title: 'Ship the release', doneAt: '2026-09-09T10:00:00', hours: 12 },
    { taskId: 6, title: 'Fix the login', doneAt: '2026-09-10T09:00:00', hours: 72 }
  ],
  throughput: [
    { date: '2026-09-08', count: 0 },
    { date: '2026-09-09', count: 1 },
    { date: '2026-09-10', count: 1 }
  ],
  ...overrides
});

/**
 * The flow screen draws what the server computed and nothing else: the numbers come from
 * `/api/flow`, and this suite checks they land in the tiles, the legend, the tables and the hover
 * layer, and that choosing a column asks the server again rather than re-deriving anything here.
 */
describe('FlowMetrics', () => {
  beforeEach(() => {
    fetchFlowMetrics.mockReset();
    defineFlow.mockReset();
    toast.success.mockReset();
    toast.error.mockReset();
    mockKanban.activeBoardId = 3;
    mockKanban.activeBoard = { id: 3, owned: true };
    console.error = jest.fn();
  });

  test('asks for the active board over the last thirty days by default', async () => {
    fetchFlowMetrics.mockResolvedValue(metrics());

    render(<FlowMetrics />);

    await waitFor(() => expect(fetchFlowMetrics).toHaveBeenCalled());
    const args = fetchFlowMetrics.mock.calls[0][0];
    expect(args.boardId).toBe(3);
    const span = (new Date(args.to) - new Date(args.from)) / 86400000;
    expect(span).toBe(29);
    expect(args.start).toBe('');
    expect(args.done).toBe('');
  });

  test('shows the summary tiles in hours below two days and in days above', async () => {
    fetchFlowMetrics.mockResolvedValue(metrics());

    const { container } = render(<FlowMetrics />);
    await screen.findByText('flow.cumulativeFlow');

    const tiles = within(container.querySelector('.flow-tiles'));
    expect(tiles.getByText('flow.hours:{"count":12}')).toBeInTheDocument();
    expect(tiles.getByText('flow.days:{"count":3}')).toBeInTheDocument();
    expect(tiles.getByText('flow.hours:{"count":30}')).toBeInTheDocument();
    expect(tiles.getByText('2')).toBeInTheDocument();
  });

  test('names every column in the legend, and each chart has a table', async () => {
    fetchFlowMetrics.mockResolvedValue(metrics());

    render(<FlowMetrics />);

    const legend = await screen.findByRole('list');
    expect(within(legend).getByText('Todo')).toBeInTheDocument();
    expect(within(legend).getByText('Done')).toBeInTheDocument();
    expect(screen.getAllByText('flow.showTable')).toHaveLength(2);
    expect(screen.getAllByText('Ship the release').length).toBeGreaterThan(0);
  });

  test('folds columns past eight into one band, keeping the ones nearest done', async () => {
    const many = Array.from({ length: 10 }, (_, i) => ({ id: 100 + i, name: `C${i}`, position: i + 1 }));
    fetchFlowMetrics.mockResolvedValue(metrics({
      columns: many,
      cumulativeFlow: [{ date: '2026-09-10', counts: [1, 1, 1, 0, 0, 0, 0, 0, 0, 4] }]
    }));

    render(<FlowMetrics />);

    const legend = await screen.findByRole('list');
    const items = within(legend).getAllByRole('listitem');
    expect(items).toHaveLength(8);
    expect(items[0]).toHaveTextContent('flow.otherColumns');
    expect(within(legend).queryByText('C2')).not.toBeInTheDocument();
    expect(within(legend).getByText('C9')).toBeInTheDocument();
  });

  test('choosing where work is done asks the server again with that column', async () => {
    fetchFlowMetrics.mockResolvedValue(metrics());

    render(<FlowMetrics />);
    await screen.findByText('flow.cumulativeFlow');

    fireEvent.change(screen.getAllByRole('combobox')[2], { target: { value: '11' } });

    await waitFor(() => expect(fetchFlowMetrics).toHaveBeenLastCalledWith(expect.objectContaining({ done: '11' })));
  });

  test('a longer window widens the request', async () => {
    fetchFlowMetrics.mockResolvedValue(metrics());

    render(<FlowMetrics />);
    await screen.findByText('flow.cumulativeFlow');

    fireEvent.change(screen.getAllByRole('combobox')[0], { target: { value: '90' } });

    await waitFor(() => {
      const args = fetchFlowMetrics.mock.calls[fetchFlowMetrics.mock.calls.length - 1][0];
      expect((new Date(args.to) - new Date(args.from)) / 86400000).toBe(89);
    });
  });

  test('hovering the cumulative flow shows that day, column by column', async () => {
    fetchFlowMetrics.mockResolvedValue(metrics());

    const { container } = render(<FlowMetrics />);
    await screen.findByText('flow.cumulativeFlow');

    const hit = container.querySelector('.flow-hit');
    hit.getBoundingClientRect = () => ({ left: 0, width: 720, top: 0, height: 240 });
    fireEvent.mouseMove(hit, { clientX: 700 });

    const tooltip = container.querySelector('.flow-tooltip');
    expect(tooltip).toBeInTheDocument();
    expect(within(tooltip).getByText('Done')).toBeInTheDocument();
    expect(within(tooltip).getByText('2')).toBeInTheDocument();

    fireEvent.mouseLeave(hit);
    expect(container.querySelector('.flow-tooltip')).not.toBeInTheDocument();
  });

  test('hovering a cycle-time dot names the card and how long it took', async () => {
    fetchFlowMetrics.mockResolvedValue(metrics());

    const { container } = render(<FlowMetrics />);
    await screen.findByText('flow.cycleTime');

    const dots = container.querySelectorAll('circle.flow-hit');
    fireEvent.mouseEnter(dots[1]);

    const tooltip = container.querySelector('.flow-tooltip');
    expect(within(tooltip).getByText('Fix the login')).toBeInTheDocument();
    expect(within(tooltip).getByText('flow.days:{"count":3}')).toBeInTheDocument();
    fireEvent.mouseLeave(dots[1]);
  });

  test('hovering a throughput day shows how many finished', async () => {
    fetchFlowMetrics.mockResolvedValue(metrics());

    const { container } = render(<FlowMetrics />);
    await screen.findByText('flow.throughput');

    const days = container.querySelectorAll('rect.flow-hit');
    fireEvent.mouseEnter(days[days.length - 1]);

    expect(within(container.querySelector('.flow-tooltip')).getByText('1')).toBeInTheDocument();
    fireEvent.mouseLeave(days[days.length - 1]);
  });

  test('says so when nothing finished instead of drawing an empty plot', async () => {
    fetchFlowMetrics.mockResolvedValue(metrics({
      samples: [],
      cycleTime: { count: 0, averageHours: null, medianHours: null, p85Hours: null }
    }));

    render(<FlowMetrics />);

    expect(await screen.findByText('flow.nothingFinished')).toBeInTheDocument();
    expect(screen.getAllByText('flow.none')).toHaveLength(3);
  });

  test('a board with no columns says so', async () => {
    fetchFlowMetrics.mockResolvedValue(metrics({ columns: [], cumulativeFlow: [{ date: '2026-09-10', counts: [] }] }));

    render(<FlowMetrics />);

    expect(await screen.findByText('flow.noColumns')).toBeInTheDocument();
  });

  test('an older answer arriving last does not overwrite the newer one', async () => {
    let answerFirst;
    fetchFlowMetrics
      .mockImplementationOnce(() => new Promise(resolve => { answerFirst = resolve; }))
      .mockResolvedValueOnce(metrics({ cycleTime: { count: 7, averageHours: 1, medianHours: 1, p85Hours: 1 } }));

    const { container, rerender } = render(<FlowMetrics />);
    fireEvent.change(screen.getAllByRole('combobox')[0], { target: { value: '90' } });
    rerender(<FlowMetrics />);

    await waitFor(() => expect(within(container.querySelector('.flow-tiles')).getByText('7')).toBeInTheDocument());
    answerFirst(metrics());
    await new Promise(resolve => setTimeout(resolve, 0));

    expect(within(container.querySelector('.flow-tiles')).getByText('7')).toBeInTheDocument();
  });

  test('the board resolving for the first time keeps a column already chosen; a real switch clears it', async () => {
    fetchFlowMetrics.mockResolvedValue(metrics());
    mockKanban.activeBoardId = null;

    const { rerender } = render(<FlowMetrics />);
    await screen.findByText('flow.cumulativeFlow');
    fireEvent.change(screen.getAllByRole('combobox')[2], { target: { value: '11' } });

    mockKanban.activeBoardId = 3;
    rerender(<FlowMetrics />);
    await waitFor(() => expect(fetchFlowMetrics).toHaveBeenLastCalledWith(expect.objectContaining({ boardId: 3, done: '11' })));

    mockKanban.activeBoardId = 4;
    rerender(<FlowMetrics />);
    await waitFor(() => expect(fetchFlowMetrics).toHaveBeenLastCalledWith(expect.objectContaining({ boardId: 4, done: '' })));
  });

  test('a failed request is a message, not a blank screen', async () => {
    fetchFlowMetrics.mockRejectedValue(new Error('500'));

    render(<FlowMetrics />);

    expect(await screen.findByText('flow.failed')).toBeInTheDocument();
  });

  describe("the board's own definition (FLOW-02)", () => {
    test('the default choice names the column the board defines, where it defines one', async () => {
      fetchFlowMetrics.mockResolvedValue(metrics({
        startColumnId: 11, doneColumnId: 11, definedStartColumnId: 11, definedDoneColumnId: 11
      }));

      render(<FlowMetrics />);

      const selects = await screen.findAllByRole('combobox');
      await waitFor(() => expect(within(selects[1]).getAllByRole('option')[0])
        .toHaveTextContent('flow.asTheBoardDefines:{"name":"Doing"}'));
      expect(within(selects[2]).getAllByRole('option')[0]).toHaveTextContent('flow.asTheBoardDefines:{"name":"Doing"}');
      // Matching the board already, so there is nothing to save - only a way back to the default.
      expect(screen.queryByText('flow.saveDefinition')).not.toBeInTheDocument();
      expect(screen.getByText('flow.resetDefinition')).toBeInTheDocument();
    });

    test('an unset board keeps the old wording and offers no reset', async () => {
      fetchFlowMetrics.mockResolvedValue(metrics());

      render(<FlowMetrics />);

      const selects = await screen.findAllByRole('combobox');
      await waitFor(() => expect(within(selects[2]).getAllByRole('option')[0]).toHaveTextContent('flow.lastColumn'));
      expect(within(selects[1]).getAllByRole('option')[0]).toHaveTextContent('flow.arrivalOnBoard');
      expect(screen.queryByText('flow.resetDefinition')).not.toBeInTheDocument();
    });

    test('the owner saves what the screen was computed with, and the screen reads it back', async () => {
      fetchFlowMetrics.mockResolvedValue(metrics({ startColumnId: 10, doneColumnId: 11 }));
      defineFlow.mockResolvedValue({ boardId: 3, startColumnId: 10, doneColumnId: 11 });

      render(<FlowMetrics />);
      await screen.findByText('flow.finished');
      // Nothing is offered until somebody picks something.
      expect(screen.queryByText('flow.saveDefinition')).not.toBeInTheDocument();
      fireEvent.change(screen.getAllByRole('combobox')[2], { target: { value: '11' } });
      fireEvent.click(await screen.findByText('flow.saveDefinition'));

      await waitFor(() => expect(defineFlow).toHaveBeenCalledWith({ boardId: 3, start: 10, done: 11 }));
      await waitFor(() => expect(toast.success).toHaveBeenCalledWith('flow.definitionSaved'));
      // Opened, picked, then read back as "as the board defines it" once saved.
      await waitFor(() => expect(fetchFlowMetrics).toHaveBeenCalledTimes(3));
      const reread = fetchFlowMetrics.mock.calls[2][0];
      expect(reread.start).toBe('');
      expect(reread.done).toBe('');
    });

    test('the reset sends nulls, which put both ends back on the default', async () => {
      fetchFlowMetrics.mockResolvedValue(metrics({ doneColumnId: 11, definedDoneColumnId: 11 }));
      defineFlow.mockResolvedValue({ boardId: 3, startColumnId: null, doneColumnId: null });

      render(<FlowMetrics />);
      fireEvent.click(await screen.findByText('flow.resetDefinition'));

      await waitFor(() => expect(defineFlow).toHaveBeenCalledWith({ boardId: 3, start: null, done: null }));
    });

    test('a refused save says so and changes nothing', async () => {
      fetchFlowMetrics.mockResolvedValue(metrics({ doneColumnId: 11 }));
      defineFlow.mockRejectedValue(new Error('403'));

      render(<FlowMetrics />);
      await screen.findByText('flow.finished');
      fireEvent.change(screen.getAllByRole('combobox')[2], { target: { value: '11' } });
      fireEvent.click(await screen.findByText('flow.saveDefinition'));

      await waitFor(() => expect(toast.error).toHaveBeenCalledWith('flow.definitionFailed'));
      // The pick survives the refusal: nothing was saved, so nothing is reset.
      expect(screen.getAllByRole('combobox')[2]).toHaveValue('11');
    });

    test('somebody who does not own the board is offered neither', async () => {
      mockKanban.activeBoard = { id: 3, owned: false };
      fetchFlowMetrics.mockResolvedValue(metrics({ doneColumnId: 11, definedDoneColumnId: 12 }));

      render(<FlowMetrics />);

      await screen.findByText('flow.finished');
      fireEvent.change(screen.getAllByRole('combobox')[2], { target: { value: '11' } });
      await waitFor(() => expect(fetchFlowMetrics).toHaveBeenCalledTimes(2));
      expect(screen.queryByText('flow.saveDefinition')).not.toBeInTheDocument();
      expect(screen.queryByText('flow.resetDefinition')).not.toBeInTheDocument();
    });
  });
});
