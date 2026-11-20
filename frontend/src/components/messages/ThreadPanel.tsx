import type { ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { useMessages } from '../../context/MessagesContext';
import type { ArchivedMessage } from '../../crypto/engine';
import Icon from '../Icon';
import Composer, { type Outgoing } from './Composer';
import type { Person } from './mentions';

interface ThreadPanelProps {
  root: ArchivedMessage;
  replies: ArchivedMessage[];
  people: Person[];
  readOnly: boolean;
  disabled: boolean;
  renderMessage: (message: ArchivedMessage) => ReactNode;
  onClose: () => void;
}

export default function ThreadPanel({ root, replies, people, readOnly, disabled, renderMessage, onClose }: ThreadPanelProps) {
  const { t } = useTranslation();
  const { send, notifyTyping } = useMessages();

  const post = async (text: string, outgoing: Outgoing) => {
    await send(text, null, { thread: root.id, mentions: outgoing.mentions, files: outgoing.files });
  };

  return (
    <aside className="thread-details thread-panel" aria-label={t('messages.thread.panel')}>
      <div className="thread-details-head">
        <h2>{t('messages.thread.panel')}</h2>
        <button type="button" className="btn-ghost" aria-label={t('messages.close')} onClick={onClose}>
          <Icon name="close" />
        </button>
      </div>
      <ol className="message-list thread-panel-list" aria-label={t('messages.thread.panelLog')}>
        {renderMessage(root)}
        <li className="message-day"><span>{t('messages.thread.replies', { count: replies.length })}</span></li>
        {replies.map(renderMessage)}
      </ol>
      {!readOnly && (
        <Composer disabled={disabled} draft={null} draftAuthor="" people={people} attachments
          onCancelDraft={() => undefined} onSend={post} onTyping={notifyTyping} />
      )}
    </aside>
  );
}
