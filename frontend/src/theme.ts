import { createTheme } from '@mui/material/styles';

export function buildTheme(mode: 'light' | 'dark') {
  const isDark = mode === 'dark';
  return createTheme({
    palette: {
      mode,
      primary: { main: isDark ? '#3D9BFF' : '#0B5CAB' },
      secondary: { main: isDark ? '#7CDEDC' : '#0F766E' },
      background: {
        default: isDark ? '#0B1220' : '#F3F6FA',
        paper: isDark ? '#121A2B' : '#FFFFFF',
      },
      success: { main: '#2E9B6A' },
      warning: { main: '#D97706' },
      error: { main: '#DC3D3D' },
    },
    typography: {
      fontFamily: '"IBM Plex Sans", "Segoe UI", sans-serif',
      h4: { fontFamily: '"IBM Plex Sans", sans-serif', fontWeight: 600 },
      h5: { fontFamily: '"IBM Plex Sans", sans-serif', fontWeight: 600 },
      h6: { fontWeight: 600 },
      button: { textTransform: 'none', fontWeight: 600 },
    },
    shape: { borderRadius: 10 },
    components: {
      MuiCssBaseline: {
        styleOverrides: {
          body: {
            backgroundImage: isDark
              ? 'radial-gradient(1200px 600px at 10% -10%, rgba(61,155,255,0.18), transparent), radial-gradient(900px 500px at 90% 0%, rgba(124,222,220,0.12), transparent)'
              : 'radial-gradient(1200px 600px at 10% -10%, rgba(11,92,171,0.08), transparent), linear-gradient(180deg, #F7FAFD 0%, #EEF3F9 100%)',
            backgroundAttachment: 'fixed',
          },
        },
      },
      MuiAppBar: {
        styleOverrides: {
          root: {
            backgroundImage: 'none',
            backdropFilter: 'blur(10px)',
          },
        },
      },
      MuiDrawer: {
        styleOverrides: {
          paper: {
            borderRight: isDark ? '1px solid rgba(255,255,255,0.06)' : '1px solid rgba(15,23,42,0.08)',
            backgroundImage: isDark
              ? 'linear-gradient(180deg, #10182A 0%, #0D1524 100%)'
              : 'linear-gradient(180deg, #FFFFFF 0%, #F7FAFD 100%)',
          },
        },
      },
    },
  });
}
