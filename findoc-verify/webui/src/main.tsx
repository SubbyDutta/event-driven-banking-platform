import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import './index.css'
import App from './App.tsx'
import { ConfirmProvider, ToastProvider } from './components/Toast'

(() => {
  const m = window.location.hash.match(/(?:^|[#&])key=([^&]+)/);
  if (m) {
    try {
      localStorage.setItem('findoc.apiKey', decodeURIComponent(m[1]));
    } catch {

    }
    history.replaceState(null, '', window.location.pathname + window.location.search);
  }
})();

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <ToastProvider>
      <ConfirmProvider>
        <App />
      </ConfirmProvider>
    </ToastProvider>
  </StrictMode>,
)
