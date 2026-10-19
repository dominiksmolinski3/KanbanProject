import type { ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import Icon from '../Icon';

interface BenchShellProps {
  isOpen: boolean;
  onToggle: () => void;
  children: ReactNode;
}

export default function BenchShell({ isOpen, onToggle, children }: BenchShellProps) {
  const { t } = useTranslation();

  return (
    <div className={`bench-container ${isOpen ? 'open' : ''}`}>
      <button
        type="button"
        className="bench-toggle"
        onClick={onToggle}
        aria-expanded={isOpen}
        aria-label={t('bench.title')}
        title={t('bench.title')}
      >
        <Icon name={isOpen ? 'chevron-left' : 'people'} />
      </button>
      <div className="bench">
        <h3>{t('bench.title')}</h3>
        {children}
      </div>
    </div>
  );
}
