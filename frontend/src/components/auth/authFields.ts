export interface AuthFields {
  email: string;
  password: string;
  username: string;
  confirmPassword: string;
  verificationCode: string;
  resetEmail: string;
  resetCode: string;
  newPassword: string;
}

export const AUTH_DEFAULTS: AuthFields = {
  email: '',
  password: '',
  username: '',
  confirmPassword: '',
  verificationCode: '',
  resetEmail: '',
  resetCode: '',
  newPassword: '',
};
