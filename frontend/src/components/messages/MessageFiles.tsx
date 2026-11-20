import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { isInlineImage } from '../../crypto/attachments';
import type { FileRef } from '../../crypto/payload';
import { useMessages } from '../../context/MessagesContext';
import Icon from '../Icon';

const MAX_PREVIEW_BYTES = 4 * 1024 * 1024;

const sizeLabel = (size: number) => (size >= 1024 * 1024
  ? `${(size / (1024 * 1024)).toFixed(1)} MB`
  : `${Math.max(1, Math.round(size / 1024))} KB`);

function Preview({ file }: { file: FileRef }) {
  const { t } = useTranslation();
  const { download } = useMessages();
  const [source, setSource] = useState<string | null>(null);
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    let current = true;
    let url: string | null = null;
    download(file).then((blob) => {
      if (!current) return;
      url = URL.createObjectURL(blob);
      setSource(url);
    }, () => current && setFailed(true));
    return () => {
      current = false;
      if (url) URL.revokeObjectURL(url);
    };
  }, [download, file]);

  if (failed) return <p className="message-file-note">{t('messages.files.failed')}</p>;
  if (!source) return <p className="message-file-note">{t('app.loading')}</p>;
  return <img className="message-file-image" src={source} alt={file.name} />;
}

export default function MessageFiles({ files }: { files: FileRef[] }) {
  const { t } = useTranslation();
  const { download } = useMessages();
  const [busy, setBusy] = useState<number | null>(null);
  const [failed, setFailed] = useState<number | null>(null);

  const save = async (file: FileRef) => {
    setBusy(file.blobId);
    setFailed(null);
    try {
      const url = URL.createObjectURL(await download(file));
      const link = document.createElement('a');
      link.href = url;
      link.download = file.name;
      link.click();
      setTimeout(() => URL.revokeObjectURL(url), 1000);
    } catch {
      setFailed(file.blobId);
    } finally {
      setBusy(null);
    }
  };

  return (
    <ul className="message-files">
      {files.map((file) => (
        <li key={file.blobId}>
          {isInlineImage(file.type) && file.size <= MAX_PREVIEW_BYTES && <Preview file={file} />}
          <button type="button" className="message-file" disabled={busy === file.blobId}
            aria-label={t('messages.files.download', { name: file.name, size: sizeLabel(file.size) })}
            onClick={() => void save(file)}>
            <Icon name="file" size="sm" />
            <span className="message-file-name">{file.name}</span>
            <span className="message-file-size">{sizeLabel(file.size)}</span>
            <Icon name="download" size="sm" />
          </button>
          {failed === file.blobId && <p className="messages-error" role="alert">{t('messages.files.failed')}</p>}
        </li>
      ))}
    </ul>
  );
}
