import React from 'react';
import { Navigate } from 'react-router-dom';
import { decodeToken, isTokenExpired, clearToken, isAdmin } from './utils/auth';

const PublicRoute = ({ children }) => {
  const decoded = decodeToken();

  if (decoded) {
    if (isTokenExpired(decoded)) {
      clearToken();
      return children;
    }
    return <Navigate to={isAdmin(decoded) ? '/admin' : '/user'} replace />;
  }

  return children;
};

export default PublicRoute;
