# Frontend

React 19 + Vite 8. Setup, scripts and the E2E prerequisites are in the
[root README](../README.md#frontend). This file covers how the board UI is built.

## Dependencies

The board redesign adds **no runtime or dev dependencies**. The design system is plain CSS
custom properties, the icons are CSS shapes, and the micro-interactions are CSS transitions. The
drag-and-drop is the HTML5 API the board already used. The bundle budget
(`npm run check:bundle-size`, 150 kB gzipped for the initial load) is unaffected, because the board
styles load with the lazily loaded board route, not with the sign-in screen
(`signInStylesLoadEagerly.test.js`).

## Board layout: data and presentation

| Layer | Files | What it does |
| --- | --- | --- |
| State | `src/context/KanbanContext.jsx` | Fetching, mutations, drag-and-drop handlers, live sync. Unchanged by the redesign. |
| Derived model (pure) | `src/board/boardModel.js`, `src/board/cardModel.js` | Per-column/per-lane counts and WIP state, the tasks in each cell, priority read from labels, deadline state, initials and label hues. No React, so each is tested directly in `src/__tests__/board/`. |
| View hooks | `src/board/useCollapsedLanes.js`, `src/board/useUserAvatar.js` | Which swimlanes are collapsed (per board, in `localStorage`), and a session-wide avatar cache with one request per account. |
| Presentation | `Board.jsx`, `Task.jsx`, `TaskCardMeta.jsx`, `WipMeter.jsx`, `AvatarStack.jsx` | Rendering only. They read the model and call context actions. |

The board still renders as a `<table>`: columns are workflow stages and rows are swimlanes, which
is the data model (`Task` sits at a column and an optional row). The Cypress suite and the keyboard
move depend on that structure (`th`, `tr`, `.grid-row-header`, `.grid-cell[data-column-id][data-row-id]`),
so the redesign changes how it looks and leaves the structure alone.

### What the board shows

- **WIP signal per column and swimlane.** `WipMeter` shows `count/limit`. The header turns amber
  at 80% of the limit (`wip-near`) and red past it (`wip-over`, plus the old `wip-exceeded` class
  so existing selectors still match). Columns also get a capacity bar driven by `--wip-fill`. As
  before, limits are advisory: only per-user limits are enforced by the server.
- **Collapsible swimlanes.** The chevron in a lane header collapses it to a count of hidden cards
  per cell. A collapsed cell still accepts drops. The state is kept per board under
  `kanban.collapsedLanes.<boardId>`. Unreadable storage means every lane is open.
- **Cards.** Priority is read from labels (`Urgent`/`Critical`/`P0`, `High`/`P1`, `Medium`/`P2`,
  `Low`/`P3`, with or without the word "priority"). The most severe one becomes a coloured pill and
  the rest stay as label micro-pills: up to three are shown, then `+N`. A label colour chosen in the
  task panel (`localStorage.labelColors`) is used when present. Otherwise the colour comes from a
  hash of the label text. Cards also show a due-date chip (red when overdue, amber within 48 hours,
  neutral after that), the count of open subtasks, and a stack of up to three assignee avatars with
  initials as the fallback.
- **Drag feedback.** The drag image is a tilted, raised copy of the card. The card left behind
  turns into a dimmed placeholder, and the cell or header under the pointer is highlighted
  (`.drop-target`, toggled on the element without a React re-render).

### Theming: design tokens

`src/styles/components/BoardTokens.css` defines the board's tokens as `--kb-*` custom properties
on `:root`:

| Group | Tokens |
| --- | --- |
| Surfaces | `--kb-canvas`, `--kb-lane`, `--kb-lane-strong`, `--kb-header`, `--kb-card`, `--kb-card-hover`, `--kb-border`, `--kb-border-strong` |
| Text | `--kb-text`, `--kb-text-muted`, `--kb-text-subtle` |
| Accent and status | `--kb-accent`, `--kb-accent-soft`, `--kb-accent-ring`, `--kb-ok`, `--kb-warn`, `--kb-warn-soft`, `--kb-danger`, `--kb-danger-soft` |
| Priority | `--kb-priority-urgent`, `--kb-priority-high`, `--kb-priority-medium`, `--kb-priority-low`, `--kb-pill-tint` |
| Labels | `--kb-label-s`, `--kb-label-l` (saturation and lightness applied to each label's hue) |
| Shape, depth and motion | `--kb-radius-lane`, `--kb-radius-card`, `--kb-radius-pill`, `--kb-shadow-card`, `--kb-shadow-hover`, `--kb-shadow-lift`, `--kb-blur`, `--kb-ease`, `--kb-fast`, `--kb-normal` |
| Sizing | `--kb-column-width`, `--kb-lane-header-width`, `--kb-cell-max-height` (optional, default `70vh`) |

Dark values apply under `prefers-color-scheme: dark` unless the root carries
`data-theme="light"`, and always under `data-theme="dark"`. This is the same convention
`FlowMetrics.css` uses, so an explicit theme switch can be added later without touching the
components. Headers use a translucent surface with `backdrop-filter`. Motion is turned off under
`prefers-reduced-motion`, and hover-only controls stay visible on touch devices (`hover: none`).

### Accessibility

- Every card still has the keyboard move (Space to pick up, the arrow keys to choose a cell,
  Space or Enter to drop, Escape to cancel), announced through the board's live region.
- Colour is never the only signal. The WIP meter, due chip and subtask count each carry
  visually hidden text, and icon buttons carry an `aria-label`.
- The lane toggle is a real `<button>` with `aria-expanded`.

### i18n

All new strings are keys in all nine bundles (`board.wip.*`, `board.lane.*`,
`taskActions.priority.*`, `taskActions.due*`, `taskActions.subtasksOpen`,
`taskActions.assignees`, `taskActions.moreAssignees`, `taskActions.moreLabels`). `i18n.test.js`
now also fails when a literal `t('some.key')` names a key the English bundle does not have. That
check found `row.cannotDeleteLast`, which had never been added to any bundle.
