import { CssBaseline, ThemeProvider } from '@mui/material';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { useMemo, useState } from 'react';
import { BrowserRouter, Navigate, Route, Routes } from 'react-router-dom';
import { AppShell } from './layout/AppShell';
import { useDefaultAuth } from './pages/LoginGate';
import { DashboardPage } from './pages/DashboardPage';
import { GeneratorsPage } from './pages/GeneratorsPage';
import { GeneratorDetailPage } from './pages/GeneratorDetailPage';
import { RepositoryPage } from './pages/RepositoryPage';
import { ExecutionPage } from './pages/ExecutionPage';
import { HistoryPage } from './pages/HistoryPage';
import { SchedulerPage } from './pages/SchedulerPage';
import { SettingsPage } from './pages/SettingsPage';
import { buildTheme } from './theme';

const qc = new QueryClient();

function RoutedApp() {
  useDefaultAuth();
  const [mode, setMode] = useState<'light' | 'dark'>('dark');
  const theme = useMemo(() => buildTheme(mode), [mode]);

  return (
    <ThemeProvider theme={theme}>
      <CssBaseline />
      <BrowserRouter>
        <Routes>
          <Route element={<AppShell mode={mode} onToggleTheme={() => setMode((m) => (m === 'dark' ? 'light' : 'dark'))} />}>
            <Route path="/" element={<DashboardPage />} />
            <Route path="/generators" element={<GeneratorsPage />} />
            <Route path="/generators/:id" element={<GeneratorDetailPage />} />
            <Route path="/repository" element={<RepositoryPage />} />
            <Route path="/execution" element={<ExecutionPage />} />
            <Route path="/execution/:runId" element={<ExecutionPage />} />
            <Route path="/scheduler" element={<SchedulerPage />} />
            <Route path="/history" element={<HistoryPage />} />
            <Route path="/history/:runId" element={<HistoryPage />} />
            <Route path="/settings" element={<SettingsPage />} />
            <Route path="*" element={<Navigate to="/" replace />} />
          </Route>
        </Routes>
      </BrowserRouter>
    </ThemeProvider>
  );
}

export default function App() {
  return (
    <QueryClientProvider client={qc}>
      <RoutedApp />
    </QueryClientProvider>
  );
}
