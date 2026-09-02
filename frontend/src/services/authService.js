const API_BASE_URL = '/api';

export const authService = {
  register: async (userData) => {
    // map legacy captchaToken -> nested { captcha: { token } }
    const payload = { ...userData };
    if (payload.captchaToken) {
      payload.captcha = { token: payload.captchaToken };
      delete payload.captchaToken;
    }

    const response = await fetch(`${API_BASE_URL}/auth/signup`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
      },
      body: JSON.stringify(payload),
    });
    
    if (!response.ok) {
      const errorData = await response.text();
      throw new Error(errorData || 'Registration failed');
    }

    // 202 with no body, whether or not the address was new. Parsing this as JSON used to throw on
    // an empty response, and reading an id out of it was the other half of the enumeration leak.
    return undefined;
  },
  
  login: async (credentials) => {
    const payload = { ...credentials };
    if (payload.captchaToken) {
      payload.captcha = { token: payload.captchaToken };
      delete payload.captchaToken;
    }

    const response = await fetch(`${API_BASE_URL}/auth/login`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
      },
      body: JSON.stringify(payload),
    });
    
    if (!response.ok) {
      const errorData = await response.text();
      throw new Error(errorData || 'Invalid email or password');
    }
    
    return await response.json();
  },
  
  verifyAccount: async (verificationData) => {
    const response = await fetch(`${API_BASE_URL}/auth/verify`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
      },
      body: JSON.stringify(verificationData),
    });
    
    if (!response.ok) {
      const errorData = await response.text();
      throw new Error(errorData || 'Verification failed');
    }
    
    const contentType = response.headers.get('content-type');
    if (contentType && contentType.includes('application/json')) {
      return await response.json();
    } else {
      return await response.text();
    }
  },
  
  /**
   * Asks for a reset code. Answers 202 whether or not the address has an account, so there is
   * nothing in the response to branch on - and nothing for a caller to learn from it either.
   */
  requestPasswordReset: async (email) => {
    const response = await fetch(`${API_BASE_URL}/auth/forgot-password`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({ email }),
    });

    if (!response.ok) {
      const errorData = await response.text();
      throw new Error(errorData || 'Could not request a password reset');
    }
  },

  resetPassword: async (email, resetCode, newPassword) => {
    const response = await fetch(`${API_BASE_URL}/auth/reset-password`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({ email, resetCode, newPassword }),
    });

    if (!response.ok) {
      const errorData = await response.text();
      throw new Error(errorData || 'Could not reset the password');
    }
  },

  /** Authenticated: the interceptor attaches the token, since this path has no `/auth/` in it. */
  changePassword: async (userId, currentPassword, newPassword) => {
    const response = await fetch(`${API_BASE_URL}/users/${userId}/password`, {
      method: 'PATCH',
      headers: {
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({ currentPassword, newPassword }),
    });

    if (!response.ok) {
      const errorData = await response.text();
      throw new Error(errorData || 'Could not change the password');
    }
  },

  /**
   * Exchanges a refresh token for a new pair. The old one is spent by the time this returns, so
   * every caller has to go through `session.refreshSession`, which serialises them - presenting a
   * spent token is what the server reads as theft.
   */
  refresh: async (refreshToken) => {
    const response = await fetch(`${API_BASE_URL}/auth/refresh`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({ refreshToken }),
    });

    if (!response.ok) {
      const errorData = await response.text();
      throw new Error(errorData || 'Could not renew the session');
    }

    return await response.json();
  },

  /** Ends one session on the server. Answers 204 whether or not the token was still live. */
  logout: async (refreshToken) => {
    const response = await fetch(`${API_BASE_URL}/auth/logout`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({ refreshToken }),
    });

    if (!response.ok) {
      const errorData = await response.text();
      throw new Error(errorData || 'Could not end the session');
    }
  },

  /**
   * Every session the account can still use. Authenticated, unlike its neighbours here, so the
   * interceptor has to attach a token - see `PUBLIC_AUTH_PATHS` in `apiInterceptor.js`.
   */
  listDevices: async () => {
    const response = await fetch(`${API_BASE_URL}/auth/devices`);

    if (!response.ok) {
      const errorData = await response.text();
      throw new Error(errorData || 'Could not load the active sessions');
    }

    return await response.json();
  },

  /**
   * Ends one session by id. A 404 covers every reason it could not be ended - it was somebody
   * else's, or it had already lapsed - because the server refuses to say which.
   */
  endDevice: async (sessionId) => {
    const response = await fetch(`${API_BASE_URL}/auth/devices/${sessionId}`, {
      method: 'DELETE'
    });

    if (!response.ok) {
      const errorData = await response.text();
      throw new Error(errorData || 'Could not end that session');
    }
  },

  resendVerificationCode: async (email) => {
    const response = await fetch(`${API_BASE_URL}/auth/resend?email=${encodeURIComponent(email)}`, {
      method: 'POST',
    });
    
    if (!response.ok) {
      const errorData = await response.text();
      throw new Error(errorData || 'Failed to resend verification code');
    }
    
    return await response.text();
  }
};