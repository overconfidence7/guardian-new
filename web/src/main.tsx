import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import '@fontsource-variable/figtree';
import '@fontsource-variable/fraunces';
import './styles/tokens.css';
import './styles/base.css';
import './styles/components.css';
import './styles/phone.css';
import './styles/screens.css';
import { App } from './App';
import { registerServiceWorker } from './lib/pwa';

registerServiceWorker();

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <App />
  </StrictMode>,
);
