import {
  AppBar, Box, Collapse, Divider, Drawer, IconButton, List, ListItemButton,
  ListItemIcon, ListItemText, Toolbar, Typography, useMediaQuery,
} from '@mui/material';
import {
  Dashboard, Dns, Key, AccountTree, History, Menu as MenuIcon,
  PlayCircle, Schedule, Settings, Brightness4, Brightness7, ExpandLess, ExpandMore,
} from '@mui/icons-material';
import { useState } from 'react';
import { Link as RouterLink, Outlet, useLocation } from 'react-router-dom';
import { motion } from 'framer-motion';

const drawerWidth = 260;

const nav = [
  { label: 'Dashboard', path: '/', icon: <Dashboard /> },
  {
    label: 'Resources',
    children: [
      { label: 'Generators', path: '/generators', icon: <Dns /> },
      { label: 'Secrets', path: '/secrets', icon: <Key /> },
    ],
  },
  {
    label: 'Testing',
    children: [
      { label: 'Repository', path: '/repository', icon: <AccountTree /> },
      { label: 'Execution', path: '/execution', icon: <PlayCircle /> },
      { label: 'Scheduler', path: '/scheduler', icon: <Schedule /> },
      { label: 'History', path: '/history', icon: <History /> },
    ],
  },
  { label: 'Settings', path: '/settings', icon: <Settings /> },
];

export function AppShell({ mode, onToggleTheme }: { mode: 'light' | 'dark'; onToggleTheme: () => void }) {
  const location = useLocation();
  const compact = useMediaQuery('(max-width:900px)');
  const [open, setOpen] = useState(!compact);
  const [sections, setSections] = useState<Record<string, boolean>>({ Resources: true, Testing: true });

  const drawer = (
    <Box sx={{ display: 'flex', flexDirection: 'column', height: '100%' }}>
      <Toolbar sx={{ gap: 1.5 }}>
        <Box sx={{
          width: 34, height: 34, borderRadius: 2,
          background: 'linear-gradient(135deg, #0B5CAB, #0F766E)',
          display: 'grid', placeItems: 'center', color: 'white', fontWeight: 800, fontSize: 14,
        }}>LT</Box>
        <Box>
          <Typography variant="subtitle1" sx={{ lineHeight: 1.1, fontWeight: 700 }}>JMeter Center</Typography>
          <Typography variant="caption" color="text.secondary">Performance Control</Typography>
        </Box>
      </Toolbar>
      <Divider />
      <List sx={{ px: 1, flex: 1 }}>
        {nav.map((item) => {
          if ('children' in item && item.children) {
            const expanded = sections[item.label] ?? true;
            return (
              <Box key={item.label}>
                <ListItemButton onClick={() => setSections((s) => ({ ...s, [item.label]: !expanded }))}>
                  <ListItemText primary={item.label} primaryTypographyProps={{ variant: 'overline', color: 'text.secondary' }} />
                  {expanded ? <ExpandLess fontSize="small" /> : <ExpandMore fontSize="small" />}
                </ListItemButton>
                <Collapse in={expanded}>
                  {item.children.map((child) => (
                    <ListItemButton
                      key={child.path}
                      component={RouterLink}
                      to={child.path}
                      selected={location.pathname === child.path || location.pathname.startsWith(child.path + '/')}
                      sx={{ borderRadius: 2, mb: 0.5, ml: 0.5 }}
                    >
                      <ListItemIcon sx={{ minWidth: 36 }}>{child.icon}</ListItemIcon>
                      <ListItemText primary={child.label} />
                    </ListItemButton>
                  ))}
                </Collapse>
              </Box>
            );
          }
          return (
            <ListItemButton
              key={item.path}
              component={RouterLink}
              to={item.path!}
              selected={location.pathname === item.path}
              sx={{ borderRadius: 2, mb: 0.5 }}
            >
              <ListItemIcon sx={{ minWidth: 36 }}>{item.icon}</ListItemIcon>
              <ListItemText primary={item.label} />
            </ListItemButton>
          );
        })}
      </List>
    </Box>
  );

  return (
    <Box sx={{ display: 'flex', minHeight: '100vh' }}>
      <AppBar position="fixed" color="transparent" elevation={0} sx={{
        width: { md: `calc(100% - ${open ? drawerWidth : 0}px)` },
        ml: { md: open ? `${drawerWidth}px` : 0 },
        borderBottom: '1px solid',
        borderColor: 'divider',
        bgcolor: mode === 'dark' ? 'rgba(11,18,32,0.7)' : 'rgba(255,255,255,0.7)',
      }}>
        <Toolbar>
          <IconButton edge="start" onClick={() => setOpen((v) => !v)} sx={{ mr: 1 }}>
            <MenuIcon />
          </IconButton>
          <Typography variant="h6" sx={{ flexGrow: 1 }}>Load Testing Platform</Typography>
          <IconButton onClick={onToggleTheme} color="inherit">
            {mode === 'dark' ? <Brightness7 /> : <Brightness4 />}
          </IconButton>
        </Toolbar>
      </AppBar>
      <Drawer
        variant={compact ? 'temporary' : 'persistent'}
        open={open}
        onClose={() => setOpen(false)}
        sx={{
          width: drawerWidth,
          flexShrink: 0,
          '& .MuiDrawer-paper': { width: drawerWidth, boxSizing: 'border-box' },
        }}
      >
        {drawer}
      </Drawer>
      <Box component="main" sx={{
        flexGrow: 1,
        p: { xs: 2, md: 3 },
        width: { md: `calc(100% - ${open ? drawerWidth : 0}px)` },
        mt: 8,
      }}>
        <motion.div
          key={location.pathname}
          initial={{ opacity: 0, y: 8 }}
          animate={{ opacity: 1, y: 0 }}
          transition={{ duration: 0.25 }}
        >
          <Outlet />
        </motion.div>
      </Box>
    </Box>
  );
}
