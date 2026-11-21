import { deleteChecked, getJson, postJson } from './http';

const API_BASE_URL = '/api';

export interface PushConfig {
  webPushKey: string | null;
  fcm: boolean;
}

export type PushKind = 'WEBPUSH' | 'FCM';

export interface PushEndpointBody {
  deviceId: number;
  kind: PushKind;
  endpoint: string;
  p256dh?: string;
  auth?: string;
}

export const pushApi = {
  config: () => getJson<PushConfig>(`${API_BASE_URL}/push/config`),

  register: (body: PushEndpointBody) => postJson<{ id: number }>(`${API_BASE_URL}/push/endpoints`, body),

  remove: (id: number) => deleteChecked(`${API_BASE_URL}/push/endpoints/${id}`),
};
