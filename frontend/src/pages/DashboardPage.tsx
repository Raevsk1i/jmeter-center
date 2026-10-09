import { Box, Chip, LinearProgress, Paper, Skeleton, Stack, Typography } from '@mui/material';
import { useQuery } from '@tanstack/react-query';
import { api } from '../api/client';
import type { TestRun } from '../api/client';
import { motion } from 'framer-motion';

type Dashboard = {
  generators: Record<string, number>;
  runs: Record<string, number>;
  activeRuns: number;
  onlineAgents: number;
  recentRuns: TestRun[];
};

function Stat({ label, value, accent }: { label: string; value: string | number; accent: string }) {
  return (
    <Paper sx={{ p: 2.5, position: 'relative', overflow: 'hidden', height: '100%' }}>
      <Box sx={{
        position: 'absolute', inset: 0,
        background: `linear-gradient(135deg, ${accent}22, transparent 60%)`,
      }} />
      <Typography variant="overline" color="text.secondary">{label}</Typography>
      <Typography variant="h4" sx={{ mt: 0.5 }}>{value}</Typography>
    </Paper>
  );
}

export function DashboardPage() {
  const { data, isLoading } = useQuery({
    queryKey: ['dashboard'],
    queryFn: () => api.get<Dashboard>('/api/v1/dashboard'),
    refetchInterval: 5000,
  });

  if (isLoading || !data) {
    return <Stack spacing={2}>{[1, 2, 3].map((i) => <Skeleton key={i} height={100} />)}</Stack>;
  }

  const available = data.generators.AVAILABLE ?? 0;
  const running = data.generators.RUNNING ?? 0;
  const offline = data.generators.OFFLINE ?? 0;

  const stats = [
    { label: 'Online Agents', value: data.onlineAgents, accent: '#0B5CAB' },
    { label: 'Available Generators', value: available, accent: '#2E9B6A' },
    { label: 'Running Generators', value: running, accent: '#D97706' },
    { label: 'Active Test Runs', value: data.activeRuns, accent: '#0F766E' },
  ];

  return (
    <Stack spacing={3}>
      <Box>
        <Typography variant="h4">Operations Dashboard</Typography>
        <Typography color="text.secondary">Generator pool health and recent executions</Typography>
      </Box>
      <Box sx={{ display: 'grid', gridTemplateColumns: { xs: '1fr', sm: '1fr 1fr', md: 'repeat(4, 1fr)' }, gap: 2 }}>
        {stats.map((s, i) => (
          <motion.div key={s.label} initial={{ opacity: 0, y: 10 }} animate={{ opacity: 1, y: 0 }} transition={{ delay: i * 0.05 }}>
            <Stat {...s} />
          </motion.div>
        ))}
      </Box>

      <Box sx={{ display: 'grid', gridTemplateColumns: { xs: '1fr', md: '5fr 7fr' }, gap: 2 }}>
        <Paper sx={{ p: 2.5 }}>
          <Typography variant="h6" gutterBottom>Resource States</Typography>
          {Object.entries(data.generators).map(([status, count]) => (
            <Box key={status} sx={{ mb: 1.5 }}>
              <Stack direction="row" justifyContent="space-between">
                <Typography variant="body2">{status}</Typography>
                <Typography variant="body2">{count}</Typography>
              </Stack>
              <LinearProgress
                variant="determinate"
                value={Math.min(100, Number(count) * 20)}
                sx={{ height: 8, borderRadius: 4, mt: 0.5 }}
              />
            </Box>
          ))}
          <Chip size="small" label={`Offline: ${offline}`} sx={{ mt: 1 }} />
        </Paper>
        <Paper sx={{ p: 2.5 }}>
          <Typography variant="h6" gutterBottom>Recent Runs</Typography>
          <Stack spacing={1.25}>
            {data.recentRuns.length === 0 && (
              <Typography color="text.secondary">No runs yet</Typography>
            )}
            {data.recentRuns.map((run) => (
              <Stack key={run.id} direction="row" justifyContent="space-between" alignItems="center"
                sx={{ py: 1, borderBottom: '1px solid', borderColor: 'divider' }}>
                <Box>
                  <Typography variant="body2" fontFamily="monospace">{run.id.slice(0, 8)}</Typography>
                  <Typography variant="caption" color="text.secondary">
                    {run.createdAt ? new Date(run.createdAt).toLocaleString() : '—'}
                  </Typography>
                </Box>
                <Chip size="small" label={run.status} color={
                  run.status === 'COMPLETED' ? 'success'
                    : run.status === 'FAILED' ? 'error'
                      : run.status === 'RUNNING' ? 'warning' : 'default'
                } />
              </Stack>
            ))}
          </Stack>
        </Paper>
      </Box>
    </Stack>
  );
}
