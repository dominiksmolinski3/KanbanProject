import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import BenchShell from './bench/BenchShell';
import BenchUserCard from './bench/BenchUserCard';
import { useBenchUsers } from './bench/useBenchUsers';
import '../styles/components/Bench.css';

function Bench() {
  const { t } = useTranslation();
  const [isOpen, setIsOpen] = useState(false);
  const { users, loading, error, avatars, saveWipLimit } = useBenchUsers();

  const toggle = () => setIsOpen(!isOpen);

  if (loading) {
    return (
      <BenchShell isOpen={isOpen} onToggle={toggle}>
        <div className="loading">{t('bench.loading')}</div>
      </BenchShell>
    );
  }

  if (error) {
    return (
      <BenchShell isOpen={isOpen} onToggle={toggle}>
        <div className="error">{error}</div>
      </BenchShell>
    );
  }

  return (
    <BenchShell isOpen={isOpen} onToggle={toggle}>
      <div className="user-list">
        {users.map(user => (
          <BenchUserCard key={user.id} user={user} avatar={avatars[user.id]} onSaveWipLimit={saveWipLimit} />
        ))}
      </div>
    </BenchShell>
  );
}

export default Bench;
