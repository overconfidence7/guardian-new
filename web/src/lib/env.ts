/** Firebase web config from VITE_FIREBASE_* (see .env.example). */
const env = import.meta.env;

export const EMULATOR_PROJECT_ID = 'demo-guardian';

/** No API key, or VITE_USE_EMULATORS=true: talk to the local Emulator Suite. */
export const useEmulators: boolean = env.VITE_USE_EMULATORS === 'true' || !env.VITE_FIREBASE_API_KEY;

export const emulatorHost: string = env.VITE_EMULATOR_HOST || '127.0.0.1';

export const firebaseConfig = useEmulators
  ? {
      apiKey: env.VITE_FIREBASE_API_KEY || 'demo-api-key',
      authDomain: `${EMULATOR_PROJECT_ID}.firebaseapp.com`,
      projectId: EMULATOR_PROJECT_ID,
      storageBucket: `${EMULATOR_PROJECT_ID}.appspot.com`,
      messagingSenderId: env.VITE_FIREBASE_MESSAGING_SENDER_ID || '000000000000',
      appId: env.VITE_FIREBASE_APP_ID || '1:000000000000:web:0000000000000000',
    }
  : {
      apiKey: env.VITE_FIREBASE_API_KEY as string,
      authDomain: env.VITE_FIREBASE_AUTH_DOMAIN,
      projectId: env.VITE_FIREBASE_PROJECT_ID,
      storageBucket: env.VITE_FIREBASE_STORAGE_BUCKET,
      messagingSenderId: env.VITE_FIREBASE_MESSAGING_SENDER_ID,
      appId: env.VITE_FIREBASE_APP_ID,
    };

export const vapidKey: string | undefined = env.VITE_FIREBASE_VAPID_KEY || undefined;

export const FUNCTIONS_REGION = 'us-central1';
