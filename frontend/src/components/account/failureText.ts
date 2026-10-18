import type { ApiError } from '../../services/apiError';
import type { Translate } from '../../context/kanban/types';

const ERROR_KEYS: Record<string, string> = {
  WRONG_PASSWORD: 'account.errors.wrongPassword',
  EMAIL_UNCHANGED: 'account.errors.unchanged',
  INVALID_VERIFICATION_CODE: 'account.errors.invalidCode',
  VERIFICATION_CODE_EXPIRED: 'account.errors.expiredCode',
  VALIDATION_ERROR: 'account.errors.invalid',
};

export const failureText = (t: Translate, error: unknown): string =>
  t(ERROR_KEYS[(error as ApiError).code ?? ''] || 'account.errors.generic');
