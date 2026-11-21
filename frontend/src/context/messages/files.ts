import { messagesApi } from '../../services/messagesApi';
import { openFile, sealFile } from '../../crypto/attachments';
import type { FileRef } from '../../crypto/payload';

export async function uploadFiles(conversationId: number, files: File[]): Promise<FileRef[]> {
  const refs: FileRef[] = [];
  for (const file of files) {
    const sealed = await sealFile(new Uint8Array(await file.arrayBuffer()));
    const stored = await messagesApi.uploadBlob(conversationId, sealed.bytes);
    refs.push({ blobId: stored.id, key: sealed.key, name: file.name, type: file.type || 'application/octet-stream',
      size: file.size });
  }
  return refs;
}

export async function downloadFile(conversationId: number, ref: FileRef): Promise<Blob> {
  const plain = await openFile(ref.key, await messagesApi.downloadBlob(conversationId, ref.blobId));
  return new Blob([plain as BlobPart], { type: ref.type });
}
