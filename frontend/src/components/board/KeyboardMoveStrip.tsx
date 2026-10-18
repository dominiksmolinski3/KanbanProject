import { useTranslation } from 'react-i18next';
import Icon from '../Icon';

const ESCAPE_KEY = 'Esc';

export default function KeyboardMoveStrip({ message }: { message: string }) {
  const { t } = useTranslation();

  return (
    <div className="keyboard-move-strip" aria-hidden="true">
      <Icon name="info" size="sm" />
      <span className="keyboard-move-strip-text">{message}</span>
      <span className="keyboard-move-keys">
        <kbd>{t('board.keyboardMove.keySpace')}</kbd> {t('board.keyboardMove.drop')}
        <kbd>{ESCAPE_KEY}</kbd> {t('board.keyboardMove.cancel')}
      </span>
    </div>
  );
}
