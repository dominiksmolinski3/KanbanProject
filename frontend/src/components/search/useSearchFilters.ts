import { useCallback, useState, type Dispatch, type SetStateAction } from 'react';
import type { Id } from '../../types/api';

export function useSearchFilters() {
  const [term, setTerm] = useState('');
  const [labels, setLabels] = useState<string[]>([]);
  const [assignees, setAssignees] = useState<Id[]>([]);
  const [completed, setCompleted] = useState('');
  const [deadlineFrom, setDeadlineFrom] = useState('');
  const [deadlineTo, setDeadlineTo] = useState('');
  const [page, setPage] = useState(0);

  const changeFilter = useCallback((apply: () => void) => {
    setPage(0);
    apply();
  }, []);

  const toggleIn = <T>(setList: Dispatch<SetStateAction<T[]>>, value: T) =>
    changeFilter(() =>
      setList((list) => (list.includes(value) ? list.filter((entry) => entry !== value) : [...list, value]))
    );

  const clear = () =>
    changeFilter(() => {
      setTerm('');
      setLabels([]);
      setAssignees([]);
      setCompleted('');
      setDeadlineFrom('');
      setDeadlineTo('');
    });

  const hasFilters =
    term !== '' ||
    labels.length > 0 ||
    assignees.length > 0 ||
    completed !== '' ||
    deadlineFrom !== '' ||
    deadlineTo !== '';

  return {
    term, labels, assignees, completed, deadlineFrom, deadlineTo, page, hasFilters,
    setPage,
    changeTerm: (value: string) => changeFilter(() => setTerm(value)),
    changeCompleted: (value: string) => changeFilter(() => setCompleted(value)),
    changeDeadlineFrom: (value: string) => changeFilter(() => setDeadlineFrom(value)),
    changeDeadlineTo: (value: string) => changeFilter(() => setDeadlineTo(value)),
    toggleLabel: (label: string) => toggleIn(setLabels, label),
    toggleAssignee: (userId: Id) => toggleIn(setAssignees, userId),
    clear,
  };
}

export type SearchFilters = ReturnType<typeof useSearchFilters>;
