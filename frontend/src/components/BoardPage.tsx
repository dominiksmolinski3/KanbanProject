import { useTranslation } from 'react-i18next';
import Bench from './Bench';
import Board from './Board';

function BoardPage() {
  const { t } = useTranslation();
  return (
    <div className="app">
      <h1 className="visually-hidden">{t('header.board')}</h1>
      <Bench />
      <Board />
    </div>
  );
}

export default BoardPage;
