import { useCallback, useEffect, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { toast } from 'react-toastify';
import { useKanban } from '../../context/KanbanContext';
import { defineFlow, fetchFlowMetrics } from '../../services/flowApi';
import type { FlowMetrics, Id } from '../../types/api';

const isoDate = (date: Date) => date.toISOString().slice(0, 10);

export function useFlowMetrics() {
  const { activeBoardId } = useKanban();
  const { t } = useTranslation();

  const [days, setDays] = useState(30);
  const [start, setStart] = useState('');
  const [done, setDone] = useState('');
  const [data, setData] = useState<FlowMetrics | null>(null);
  const [loading, setLoading] = useState(true);
  const [failed, setFailed] = useState(false);
  const [revision, setRevision] = useState(0);
  const [saving, setSaving] = useState(false);

  const latest = useRef(0);

  const load = useCallback(async () => {
    const request = ++latest.current;
    setLoading(true);
    setFailed(false);
    try {
      const to = new Date();
      const from = new Date(to);
      from.setDate(from.getDate() - (days - 1));
      const result = await fetchFlowMetrics({
        boardId: activeBoardId, from: isoDate(from), to: isoDate(to), start, done
      });
      if (request === latest.current) {
        setData(result);
      }
    } catch (error) {
      if (request === latest.current) {
        console.error('Error fetching the flow metrics:', error);
        setFailed(true);
        setData(null);
      }
    } finally {
      if (request === latest.current) {
        setLoading(false);
      }
    }
  }, [activeBoardId, days, start, done]);

  useEffect(() => {
    load();
  }, [load, revision]);

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
      setStart('');
      setDone('');
      setRevision(value => value + 1);
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
