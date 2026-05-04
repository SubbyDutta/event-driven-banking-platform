import { jwtDecode } from 'jwt-decode';

const TOKEN_KEY = 'token';

export function getToken() {
  try {
    return localStorage.getItem(TOKEN_KEY);
  } catch {
    return null;
  }
}

export function setToken(token) {
  try {
    if (typeof token !== 'string' || token.length < 10 || token.length > 4096) return false;
    localStorage.setItem(TOKEN_KEY, token);
    return true;
  } catch {
    return false;
  }
}

export function clearToken() {
  try {
    localStorage.removeItem(TOKEN_KEY);
  } catch {

  }
}

export function decodeToken(token = getToken()) {
  if (!token) return null;
  try {
    return jwtDecode(token);
  } catch {
    return null;
  }
}

export function isTokenExpired(payload) {
  if (!payload || !payload.exp) return false;
  return payload.exp < Date.now() / 1000;
}

export function getRole(payload = decodeToken()) {
  if (!payload) return null;
  const raw = payload.role || payload.roles || payload.authorities || payload.roleName || '';
  return String(raw).toUpperCase();
}

export function isAdmin(payload = decodeToken()) {
  return getRole(payload).includes('ADMIN');
}

export function logout(redirect = '/login') {
  clearToken();
  if (typeof window !== 'undefined' && window.location.pathname !== redirect) {
    window.location.replace(redirect);
  }
}

export function safeUsername(payload = decodeToken()) {
  if (!payload) return 'User';
  const raw = payload.sub || payload.username || payload.user || payload.name || 'User';
  return String(raw).slice(0, 64);
}
