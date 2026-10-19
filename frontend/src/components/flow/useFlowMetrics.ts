import { useEffect, useRef, useState } from 'react';
import { keepPreviousData, useQuery, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { toast } from 'react-toastify';
import { useKanban } from '../../context/KanbanContext';
import { defineFlow, fetchFlowMetrics } from '../../services/flowApi';
import type { Id } from '../../types/api';

const isoDate = (date: Date) => date.toISOString().slice(0, 10);

export function useFlowMetrics() {
  const { activeBoardId } = useKanban();
  const { t } = useTranslation();

  const [days, setDays] = useState(30);
  const [start, setStart] = useState('');
  const [done, setDone] = useState('');
  const [saving, setSaving] = useState(false);

  const queryClient = useQueryClient();
  const query = useQuery({
    queryKey: ['flow', activeBoardId ?? null, days, start, done],
    queryFn: async () => {
      try {
        const to = new Date();
        const from = new Date(to);
        from.setDate(from.getDate() - (days - 1));
        return await fetchFlowMetrics({
          boardId: activeBoardId, from: isoDate(from), to: isoDate(to), start, done
        });
      } catch (error) {
        console.error('Error fetching the flow metrics:', error);
        throw error;
      }
    },
    placeholderData: keepPreviousData,
  });
  const data = query.isError ? null : query.data ?? null;
  const loading = query.isFetching;
  const failed = query.isError;

  const shownBoard = useRef(activeBoardId);
  useEffect(() => {
    if (shownBoard.current !== null && shownBoard.current !== undefined && shownBoard.current !== activeBoardId) {
      setStart('');
      setDone('');
    }
    shownBoard.current = activeBoardId;
  }, [activeBoardId]);

  const columns = data?.columns || [];
  const nameOf = (id: Id | null | undefined) => columns.find(column => column.id === id)?.name;
  const differsFromBoard = data !== null && (start !== '' || done !== '')
    && (data.startColumnId !== data.definedStartColumnId || data.doneColumnId !== data.definedDoneColumnId);
  const hasDefinition = data !== null
    && (data.definedStartColumnId !== null || data.definedDoneColumnId !== null);

  const saveDefinition = async (definition: { start: Id | null; done: Id | null }) => {
    setSaving(true);
    try {
      await defineFlow({ boardId: activeBoardId, ...definition });
      toast.success(t('flow.definitionSaved'));
      await queryClient.invalidateQueries({ queryKey: ['flow'], refetchType: 'none' });
      if (start === '' && done === '') {
        await query.refetch();
      } else {
        setStart('');
        setDone('');
      }
    } catch (error) {
      console.error('Error saving the flow definition:', error);
      toast.error(t('flow.definitionFailed'));
    } finally {
      setSaving(false);
    }
  };

  return {
    days, setDays, start, setStart, done, setDone,
    data, loading, failed, saving,
    columns,
    definedStart: nameOf(data?.definedStartColumnId),
    definedDone: nameOf(data?.definedDoneColumnId),
    differsFromBoard, hasDefinition, saveDefinition,
  };
}

export type FlowState = ReturnType<typeof useFlowMetrics>;
