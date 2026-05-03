import { BrowserRouter, Navigate, Route, Routes } from "react-router-dom";
import { AuthProvider, useAuth } from "./AuthContext";
import Layout from "./components/Layout";
import Login from "./pages/Login";
import Dashboard from "./pages/Dashboard";
import NewSubmission from "./pages/NewSubmission";
import ApplicationDetail from "./pages/ApplicationDetail";
import ApiKeys from "./pages/ApiKeys";
import AuditLog from "./pages/AuditLog";

function RequireAuth({ children }: { children: React.ReactNode }) {
  const { me, loading } = useAuth();
  if (loading) return (
    <div className="min-h-screen grid place-items-center bg-gray-50">
      <div className="text-gray-500 text-sm">Loading…</div>
    </div>
  );
  if (!me) return <Navigate to="/login" replace />;
  return <>{children}</>;
}

export default function App() {
  return (
    <BrowserRouter>
      <AuthProvider>
        <Routes>
          <Route path="/login" element={<Login />} />
          <Route path="/" element={<RequireAuth><Layout /></RequireAuth>}>
            <Route index element={<Dashboard />} />
            <Route path="new/:kind" element={<NewSubmission />} />
            <Route path="app/:id" element={<ApplicationDetail />} />
            <Route path="admin/keys" element={<ApiKeys />} />
            <Route path="admin/audit-log" element={<AuditLog />} />
          </Route>
          <Route path="*" element={<Navigate to="/" replace />} />
        </Routes>
      </AuthProvider>
    </BrowserRouter>
  );
}
