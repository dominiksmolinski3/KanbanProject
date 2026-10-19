import { useTranslation } from 'react-i18next';
import { useAuth } from '../context/AuthContext';
import type { User } from '../types/api';
import BoardMembers from './BoardMembers';
import Invitations from './Invitations';
import PersonRow from './people/PersonRow';
import { usePeople } from './people/usePeople';
import '../styles/components/Users.css';

function UsersManagement() {
  const { t } = useTranslation();
  const { user: currentUser } = useAuth();
  const { users, avatars, uploadAvatar, removeUser } = usePeople();

  const isMe = (person: User) => currentUser?.id != null && person.id === currentUser.id;
  const people = [...users].sort((a, b) => Number(isMe(b)) - Number(isMe(a)));

  return (
    <div className="page-shell users-page">
      <div className="page-head">
        <h1 className="page-title">{t('usersManagement.title')}</h1>
        <p className="page-lede">{t('usersManagement.intro')}</p>
      </div>

      <Invitations />

      <BoardMembers />

      <section className="page-panel people-panel" aria-labelledby="people-heading">
        <h2 className="page-panel-title" id="people-heading">{t('usersManagement.people')}</h2>
        <ul id="usersList" className="users-list">
          {people.map(person => (
            <PersonRow
              key={person.id}
              person={person}
              avatar={avatars[person.id]}
              isMe={isMe(person)}
              onUpload={uploadAvatar}
              onDelete={removeUser}
            />
          ))}
        </ul>
      </section>
    </div>
  );
}

export default UsersManagement;
