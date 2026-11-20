import { useContext, useEffect, useState } from 'react';
import { Link, useLocation } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import KanbanContext from '../../context/KanbanContext';
import { useMessages } from '../../context/MessagesContext';
import type { Conversation } from '../../services/messagesApi';
import { useMessagingState } from '../devices/useMessagingKeys';
import Icon from '../Icon';
import Thread from './Thread';
import { conversationTitle, initials } from './conversationTitle';
import '../../styles/components/MessageDock.css';

const RECENT = 2;

const newestFirst = (a: Conversation, b: Conversation) => b.lastActivityAt.localeCompare(a.lastActivityAt);

export default function MessageDock() {
  const { t, i18n } = useTranslation();
  const { pathname } = useLocation();
  const { state } = useMessagingState();
  const { conversations, loading, selected, select, unread, userId, nameOf } = useMessages();
  const kanban = useContext(KanbanContext);
  const boardId = kanban?.activeBoardId ?? null;
  const [open, setOpen] = useState(false);
  const [chosen, setChosen] = useState<number | null>(null);
  const visible = open && !pathname.startsWith('/messages');
  const settled = !loading && (!kanban || boardId !== null);

  const channel = conversations.find((each) => each.kind === 'BOARD' && each.boardId === boardId) ?? null;
  const recent = conversations
    .filter((each) => each.kind !== 'BOARD' && each.me.state === 'ACTIVE' && !each.me.archived)
    .sort(newestFirst)
    .slice(0, RECENT);
  const tabs = channel ? [channel, ...recent] : recent;
  const shown = settled ? tabs.find((each) => each.id === chosen) ?? tabs[0] ?? null : null;
  const shownId = shown?.id ?? null;
  const selectedId = selected?.id ?? null;

  useEffect(() => {
    if (visible && state === 'ready' && settled && shownId !== null && shownId !== selectedId) select(shownId);
  }, [visible, state, settled, shownId, selectedId, select]);

  useEffect(() => {
    if (!visible) return undefined;
    return () => select(null);
  }, [visible, select]);

  if (state === 'off' || pathname.startsWith('/messages')) return null;

  const titleOf = (conversation: Conversation) =>
    conversationTitle(conversation, userId, nameOf(conversation), t, i18n.language);

  if (!visible) {
    return (
      <div className="dock-container">
        <button type="button" className="dock-toggle" onClick={() => { setChosen(null); setOpen(true); }}
          aria-label={t('messages.dock.open')} title={t('messages.dock.open')}>
          <Icon name="comment" size="lg" />
          {unread > 0 && <span className="dock-badge" aria-label={t('messages.list.unread', { count: unread })}>{unread}</span>}
        </button>
      </div>
    );
  }

  return (
    <div className="dock-container">
      <section className="dock-panel" aria-labelledby="dock-title">
        <div className="dock-header">
          <div className="dock-heading">
            <h3 id="dock-title">{shown ? titleOf(shown) : t('messages.title')}</h3>
            <span className="dock-subtitle">
              <Icon name="lock" size="sm" />
              {shown?.kind === 'BOARD'
                ? t('messages.dock.boardChannel', { count: shown.members.length })
                : t('messages.thread.encrypted')}
            </span>
          </div>
          {shown && (
            <Link to={`/messages?c=${shown.id}`} className="dock-icon-button" aria-label={t('messages.dock.openFull')}
              title={t('messages.dock.openFull')}>
              <Icon name="open" />
            </Link>
          )}
          <button type="button" className="dock-icon-button" onClick={() => { setOpen(false); setChosen(null); }}
            aria-label={t('messages.dock.close')} title={t('messages.dock.close')}>
            <Icon name="close" />
          </button>
        </div>

        {state === 'ready' ? (
          <>
            {tabs.length > 1 && (
              <div className="dock-tabs" role="group" aria-label={t('messages.dock.recent')}>
                {tabs.map((each) => (
                  <button key={each.id} type="button" className="dock-tab" aria-pressed={each.id === shownId}
                    onClick={() => setChosen(each.id)}>
                    {each.kind === 'BOARD' ? t('messages.dock.thisBoard') : (
                      <>
                        <span className="dock-tab-avatar" aria-hidden="true">{initials(titleOf(each))}</span>
                        {titleOf(each)}
                      </>
                    )}
                    {each.unread > 0 && <span className="conversation-unread">{each.unread}</span>}
                  </button>
                ))}
              </div>
            )}
            <div className="dock-body">
              {shown && selected ? <Thread key={selected.id} variant="dock" /> : (
                <p className="dock-note">{t(tabs.length ? 'app.loading' : 'messages.dock.none')}</p>
              )}
            </div>
          </>
        ) : (
          <div className="dock-gate">
            <p>{t(state === 'elsewhere' ? 'messages.gate.unlock' : state === 'loading' ? 'app.loading' : 'messages.gate.setUp')}</p>
            {state !== 'loading' && (
              <Link to="/sessions" className="btn btn-primary btn-sm">{t('messages.gate.open')}</Link>
            )}
          </div>
        )}
      </section>
    </div>
  );
}
