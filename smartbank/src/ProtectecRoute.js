import React from 'react';
import { Navigate } from 'react-router-dom';
import { decodeToken, isTokenExpired, clearToken, getRole } from './utils/auth';

const ProtectedRoute = ({ children, role }) => {
  const decoded = decodeToken();

  if (!decoded) {
    clearToken();
    return <Navigate to="/login" replace />;
  }

  if (isTokenExpired(decoded)) {
    clearToken();
    return <Navigate to="/login" replace />;
  }

  if (role) {
    const userRole = getRole(decoded);
    if (!userRole.includes(role.toUpperCase())) {
      const redirectPath = userRole.includes('ADMIN') ? '/admin' : '/user';
      return <Navigate to={redirectPath} replace />;
    }
  }

  return children;
};

export default ProtectedRoute;
