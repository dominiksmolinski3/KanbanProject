import { useState } from 'react';
import { useQueries, useQuery } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { MESSAGES_KEY } from '../../context/messages/queryKeys';
import { messagesApi, type Conversation } from '../../services/messagesApi';
import type { ChatMessageResults } from '../../types/api';
import Icon from '../Icon';
import { others } from './conversationTitle';

const EARLIER_KEY = [...MESSAGES_KEY, 'earlier'] as const;
const FIVE_MINUTES = 5 * 60 * 1000;

function earlierSource(conversation: Conversation, userId: number | null) {
  if (conversation.kind === 'BOARD' && conversation.boardId !== null) {
    const boardId = conversation.boardId;
    return { scope: ['board', boardId], read: (page: number) => messagesApi.earlierInBoard(boardId, page) };
  }
  const peer = conversation.kind === 'DIRECT' ? others(conversation, userId)[0] : undefined;
  if (peer) {
    return { scope: ['direct', peer.userId], read: (page: number) => messagesApi.earlierWith(peer.userId, page) };
  }
  return null;
}

export default function EarlierMessages({ conversation, userId }: { conversation: Conversation; userId: number | null }) {
  const { t, i18n } = useTranslation();
  const [open, setOpen] = useState(false);
  const [pages, setPages] = useState(1);
  const source = earlierSource(conversation, userId);
  const scope = source?.scope ?? [];

  const first = useQuery({
    queryKey: [...EARLIER_KEY, ...scope, 0],
    queryFn: () => (source as NonNullable<typeof source>).read(0),
    enabled: source !== null,
    retry: false,
    staleTime: FIVE_MINUTES,
  });
  const loaded = useQueries({
    queries: Array.from({ length: open ? pages : 0 }, (_, page) => ({
      queryKey: [...EARLIER_KEY, ...scope, page],
      queryFn: () => (source as NonNullable<typeof source>).read(page),
      staleTime: FIVE_MINUTES,
    })),
  });

  if (!source || !first.data || first.data.totalEntries === 0) return null;

  const results = loaded.map((query) => query.data).filter((data): data is ChatMessageResults => Boolean(data));
  const rows = results.flatMap((data) => data.messages).reverse();
  const last = results[results.length - 1];
  const more = Boolean(last) && last.page + 1 < last.totalPages;

  return (
    <section className="earlier" aria-label={t('messages.earlier.title')}>
      <button type="button" className="earlier-toggle" aria-expanded={open} onClick={() => setOpen((now) => !now)}>
        <Icon name={open ? 'chevron-down' : 'chevron-right'} size="sm" />
        {t('messages.earlier.title')}
      </button>
      {open && (
        <>
          <p className="messages-caption">{t('messages.earlier.hint')}</p>
          {more && (
            <button type="button" className="btn btn-secondary btn-sm" onClick={() => setPages((now) => now + 1)}>
              {t('messages.earlier.older')}
            </button>
          )}
          <ul className="earlier-list">
            {rows.map((row) => (
              <li key={row.id}>
                <span className="earlier-meta">
                  {row.sender} · {new Date(row.timestamp).toLocaleString(i18n.language, { dateStyle: 'medium', timeStyle: 'short' })}
                </span>
                <span className="earlier-text">{row.content}</span>
              </li>
            ))}
          </ul>
        </>
      )}
    </section>
  );
}
