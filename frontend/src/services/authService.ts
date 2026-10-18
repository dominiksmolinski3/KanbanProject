import { apiError } from './apiError';
import { deleteChecked, getJson, patchVoid, postJson, postVoid } from './http';
import type { ActiveDevice, Id, LoginResponse } from '../types/api';

const API_BASE_URL = '/api';

interface CaptchaFields {
  captchaToken?: string;
}

export interface Credentials extends CaptchaFields {
  email: string;
  password: string;
}

export interface SignupData extends Credentials {
  username: string;
  locale?: string;
}

const withCaptcha = <T extends CaptchaFields>(data: T) => {
  if (!data.captchaToken) {
    return { ...data };
  }
  const { captchaToken, ...rest } = data;
  return { ...rest, captcha: { token: captchaToken } };
};

export const authService = {
  register: async (userData: SignupData) => postVoid(`${API_BASE_URL}/auth/signup`, withCaptcha(userData)),

  login: async (credentials: Credentials) =>
    postJson<LoginResponse>(`${API_BASE_URL}/auth/login`, withCaptcha(credentials)),

  verifyAccount: async (verificationData: { email: string; verificationCode: string }): Promise<unknown> => {
    const response = await fetch(`${API_BASE_URL}/auth/verify`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
      },
      body: JSON.stringify(verificationData),
    });

    if (!response.ok) {
      throw await apiError(response);
    }

    const contentType = response.headers.get('content-type');
    if (contentType && contentType.includes('application/json')) {
      return await response.json();
    } else {
      return await response.text();
    }
  },

  requestPasswordReset: (email: string) => postVoid(`${API_BASE_URL}/auth/forgot-password`, { email }),

  resetPassword: (email: string, resetCode: string, newPassword: string) =>
    postVoid(`${API_BASE_URL}/auth/reset-password`, { email, resetCode, newPassword }),

  changePassword: (userId: Id, currentPassword: string, newPassword: string) =>
    patchVoid(`${API_BASE_URL}/users/${userId}/password`, { currentPassword, newPassword }),

  requestEmailChange: (userId: Id, newEmail: string, currentPassword: string) =>
    postVoid(`${API_BASE_URL}/users/${userId}/email-change`, { newEmail, currentPassword }),

  confirmEmailChange: (userId: Id, code: string) =>
    postJson<LoginResponse>(`${API_BASE_URL}/users/${userId}/email-change/confirm`, { code }),

  refresh: (refreshToken: string) => postJson<LoginResponse>(`${API_BASE_URL}/auth/refresh`, { refreshToken }),

  logout: (refreshToken: string) => postVoid(`${API_BASE_URL}/auth/logout`, { refreshToken }),

  listDevices: () => getJson<ActiveDevice[]>(`${API_BASE_URL}/devices`),

  endDevice: async (sessionId: number) => {
    await deleteChecked(`${API_BASE_URL}/devices/${sessionId}`);
  },

  resendVerificationCode: async (email: string) => {
    const response = await fetch(`${API_BASE_URL}/auth/resend?email=${encodeURIComponent(email)}`, {
      method: 'POST',
    });

    if (!response.ok) {
      throw await apiError(response);
    }

    return await response.text();
  }
};
