const API_BASE_URL = '/api';

export const authService = {
  register: async (userData) => {
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

  listDevices: async () => {
    const response = await fetch(`${API_BASE_URL}/auth/devices`);

    if (!response.ok) {
      const errorData = await response.text();
      throw new Error(errorData || 'Could not load the active sessions');
    }

    return await response.json();
  },

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