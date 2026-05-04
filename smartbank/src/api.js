import axios from 'axios';
import { getToken, decodeToken, isTokenExpired, clearToken } from './utils/auth';

// In dev, CRA's "proxy" field forwards /api → localhost:8080.
// In production, set REACT_APP_API_URL to the full backend URL (e.g. http://EIP:8080/api).
const API = axios.create({
  baseURL: process.env.REACT_APP_API_URL || '/api',
  timeout: 30000,
  headers: {
    Accept: 'application/json',
    'X-Requested-With': 'XMLHttpRequest',
  },
});

API.interceptors.request.use(
  (req) => {
    const token = getToken();
    if (token) {
      const payload = decodeToken(token);
      if (payload && isTokenExpired(payload)) {
        clearToken();
        if (typeof window !== 'undefined' && window.location.pathname !== '/login') {
          window.location.replace('/login');
        }
        return Promise.reject(new axios.Cancel('Session expired'));
      }
      req.headers.Authorization = `Bearer ${token}`;
    }
    return req;
  },
  (error) => Promise.reject(error)
);

API.interceptors.response.use(
  (res) => res,
  (error) => {
    const status = error?.response?.status;
    if (status === 401) {
      clearToken();
      if (typeof window !== 'undefined' && window.location.pathname !== '/login') {
        window.location.replace('/login');
      }
    }
    return Promise.reject(error);
  }
);

export default API;
