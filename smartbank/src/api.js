import axios from 'axios';
import { getToken, decodeToken, isTokenExpired, clearToken } from './utils/auth';

const API = axios.create({
  baseURL: '/api',
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
