import { BrowserRouter, Navigate, Route, Routes, useLocation } from 'react-router';
import type { ReactNode } from 'react';
import { DataProvider } from './data/DataProvider';
import { ToastProvider } from './components/Toast';
import { AppShell } from './screens/AppShell';
import { FamilyHome } from './screens/FamilyHome';
import { FamilySettings } from './screens/FamilySettings';
import { JoinScreen } from './screens/Join';
import { OnboardingScreen } from './screens/Onboarding';
import { FinishSignIn, NotFound, RequireAuth, RootScreen } from './screens/Root';
import { SetupScreen } from './screens/Setup';
import { DeviceScreen } from './screens/device/DeviceScreen';

/** Routes shared by the real app ("/") and the fixture demo ("/demo"). */
export function AppRoutes() {
  return (
    <Routes>
      <Route index element={<RootScreen />} />
      <Route path="finish-sign-in" element={<FinishSignIn />} />
      <Route path="join/:code" element={<JoinScreen />} />
      <Route element={<RequireAuth />}>
        <Route element={<AppShell />}>
          <Route path="welcome" element={<OnboardingScreen />} />
          <Route path="f/:familyId" element={<FamilyHome />} />
          <Route path="f/:familyId/settings" element={<FamilySettings />} />
          <Route path="f/:familyId/d/:deviceId/setup" element={<SetupScreen />} />
          <Route path="f/:familyId/d/:deviceId" element={<DeviceScreen />} />
          <Route path="f/:familyId/d/:deviceId/:tab" element={<DeviceScreen />} />
        </Route>
      </Route>
      <Route path="*" element={<NotFound />} />
    </Routes>
  );
}

/** `?demo=1` anywhere opens the demo. */
function DemoQuery({ children }: { children: ReactNode }) {
  const location = useLocation();
  if (new URLSearchParams(location.search).get('demo') === '1') return <Navigate to="/demo" replace />;
  return children;
}

export function App() {
  return (
    <BrowserRouter>
      <ToastProvider>
        <Routes>
          <Route
            path="/demo/*"
            element={
              <DataProvider mode="demo" base="/demo">
                <AppRoutes />
              </DataProvider>
            }
          />
          <Route
            path="/*"
            element={
              <DemoQuery>
                <DataProvider mode="firebase" base="">
                  <AppRoutes />
                </DataProvider>
              </DemoQuery>
            }
          />
        </Routes>
      </ToastProvider>
    </BrowserRouter>
  );
}
