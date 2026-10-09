import {
  Alert, Box, Button, Chip, MenuItem, Stack, TextField, Typography, LinearProgress,
} from '@mui/material';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useMemo, useState } from 'react';
import { Link as RouterLink, useParams } from 'react-router-dom';
import { api } from '../api/client';
import type { Generator, TestRun } from '../api/client';

type Test = { id: string; name: string; jmxPath: string };
type RunEvent = {
  id?: number;
  eventType?: string;
  message?: string;
  at?: string;
  generatorId?: string;
  detail?: Record<string, unknown> | null;
};

export function ExecutionPage() {
  const { runId } = useParams();
  const qc = useQueryClient();
  const { data: tests = [] } = useQuery({ queryKey: ['tests'], queryFn: () => api.get<Test[]>('/api/v1/tests') });
  const { data: generators = [] } = useQuery({
    queryKey: ['generators'],
    queryFn: () => api.get<Generator[]>('/api/v1/generators'),
    refetchInterval: 5000,
  });
  const { data: runs = [] } = useQuery({
    queryKey: ['runs'],
    queryFn: () => api.get<TestRun[]>('/api/v1/runs'),
    refetchInterval: 3000,
  });

  const [form, setForm] = useState({
    testDefinitionId: '',
    masterGeneratorId: '',
    slaveGeneratorIds: [] as string[],
    propertiesText: 'threads=10\nrampup=30',
  });
  const [liveLogs, setLiveLogs] = useState('');
  const [consoleLogs, setConsoleLogs] = useState('');
  const [logTab, setLogTab] = useState<'jmeter' | 'console'>('jmeter');
  const [error, setError] = useState('');

  const activeRunId = runId || runs.find((r) => r.status === 'RUNNING' || r.status === 'PREPARING')?.id
    || runs[0]?.id;
  const activeRun = runs.find((r) => r.id === activeRunId);

  const { data: events = [] } = useQuery({
    queryKey: ['run-events', activeRunId],
    queryFn: () => api.get<RunEvent[]>(`/api/v1/runs/${activeRunId}/events`),
    enabled: !!activeRunId,
    refetchInterval: activeRun?.status === 'RUNNING' || activeRun?.status === 'PREPARING' ? 3000 : 8000,
  });

  const start = useMutation({
    mutationFn: () => {
      const properties: Record<string, string> = {};
      form.propertiesText.split('\n').forEach((line) => {
        const [k, ...rest] = line.split('=');
        if (k && rest.length) properties[k.trim()] = rest.join('=').trim();
      });
      return api.post<TestRun>('/api/v1/runs', {
        testDefinitionId: form.testDefinitionId,
        masterGeneratorId: form.masterGeneratorId,
        slaveGeneratorIds: form.slaveGeneratorIds,
        properties,
        startNow: true,
      });
    },
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['runs'] });
      setError('');
    },
    onError: (e: Error) => setError(e.message),
  });

  const stop = useMutation({
    mutationFn: (id: string) => api.post(`/api/v1/runs/${id}/stop?force=true`),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['runs'] }),
  });

  useEffect(() => {
    if (!activeRunId || !activeRun?.masterGeneratorId) return;
    const poll = setInterval(async () => {
      try {
        const [jmeter, consoleOut] = await Promise.all([
          api.get<{ content: string }>(
            `/api/v1/runs/${activeRunId}/logs?generatorId=${activeRun.masterGeneratorId}&file=jmeter.log&fromOffset=0&maxBytes=48000`,
          ),
          api.get<{ content: string }>(
            `/api/v1/runs/${activeRunId}/logs?generatorId=${activeRun.masterGeneratorId}&file=jmeter-console.log&fromOffset=0&maxBytes=48000`,
          ),
        ]);
        if (jmeter.content) setLiveLogs(jmeter.content.slice(-24000));
        if (consoleOut.content) setConsoleLogs(consoleOut.content.slice(-24000));
      } catch { /* ignore */ }
    }, 3000);
    return () => clearInterval(poll);
  }, [activeRunId, activeRun?.masterGeneratorId]);

  const available = generators.filter((g) => g.status === 'AVAILABLE');
  const timeline = useMemo(() => [...events].reverse(), [events]);
  const logText = logTab === 'jmeter' ? liveLogs : consoleLogs;

  return (
    <Stack spacing={3}>
      <Box>
        <Typography variant="h4">Test Execution</Typography>
        <Typography color="text.secondary">Select Master/Slaves, launch distributed JMeter, watch launch diagnostics</Typography>
      </Box>

      {error && <Alert severity="error">{error}</Alert>}

      <Box sx={{ display: 'grid', gridTemplateColumns: { xs: '1fr', lg: '1fr 1fr' }, gap: 2 }}>
        <Box sx={{ p: 2.5, borderRadius: 2, border: '1px solid', borderColor: 'divider', bgcolor: 'background.paper' }}>
          <Typography variant="h6" gutterBottom>Launch</Typography>
          <Stack spacing={1.5}>
            <TextField select label="Test" value={form.testDefinitionId}
              onChange={(e) => setForm({ ...form, testDefinitionId: e.target.value })}>
              {tests.map((t) => <MenuItem key={t.id} value={t.id}>{t.name}</MenuItem>)}
            </TextField>
            <TextField select label="Master" value={form.masterGeneratorId}
              onChange={(e) => setForm({ ...form, masterGeneratorId: e.target.value })}>
              {available.map((g) => <MenuItem key={g.id} value={g.id}>{g.name} ({g.hostname})</MenuItem>)}
            </TextField>
            <TextField select SelectProps={{ multiple: true }} label="Slaves" value={form.slaveGeneratorIds}
              onChange={(e) => setForm({ ...form, slaveGeneratorIds: e.target.value as unknown as string[] })}>
              {available.filter((g) => g.id !== form.masterGeneratorId).map((g) => (
                <MenuItem key={g.id} value={g.id}>{g.name}</MenuItem>
              ))}
            </TextField>
            <TextField label="JMeter properties" multiline minRows={4} value={form.propertiesText}
              onChange={(e) => setForm({ ...form, propertiesText: e.target.value })} />
            <Button variant="contained" onClick={() => start.mutate()}
              disabled={!form.testDefinitionId || !form.masterGeneratorId || start.isPending}>
              Start Distributed Test
            </Button>
          </Stack>
        </Box>

        <Box sx={{ p: 2.5, borderRadius: 2, border: '1px solid', borderColor: 'divider', bgcolor: 'background.paper' }}>
          <Stack direction="row" justifyContent="space-between" alignItems="center" mb={1}>
            <Typography variant="h6">Live Run</Typography>
            {activeRun && (
              <Stack direction="row" spacing={1}>
                <Chip label={activeRun.status} color={
                  activeRun.status === 'RUNNING' ? 'warning'
                    : activeRun.status === 'FAILED' ? 'error'
                      : activeRun.status === 'COMPLETED' ? 'success' : 'default'
                } />
                {(activeRun.status === 'RUNNING' || activeRun.status === 'PREPARING') && (
                  <Button size="small" color="error" onClick={() => stop.mutate(activeRun.id)}>Stop</Button>
                )}
              </Stack>
            )}
          </Stack>
          {!activeRun && <Typography color="text.secondary">No active run</Typography>}
          {activeRun && (
            <Stack spacing={1}>
              <Typography variant="body2" fontFamily="monospace">{activeRun.id}</Typography>
              {activeRun.commitHash && <Typography variant="caption">Commit {activeRun.commitHash.slice(0, 12)}</Typography>}
              {(activeRun.status === 'PREPARING' || activeRun.status === 'RUNNING') && <LinearProgress />}
              {activeRun.errorMessage && <Alert severity="error">{activeRun.errorMessage}</Alert>}

              <Typography variant="subtitle2" sx={{ mt: 1 }}>Launch timeline</Typography>
              <Box sx={{
                maxHeight: 200, overflow: 'auto', p: 1.25,
                borderRadius: 1.5, border: '1px solid', borderColor: 'divider',
              }}>
                {timeline.length === 0 && (
                  <Typography variant="caption" color="text.secondary">Waiting for orchestration events…</Typography>
                )}
                <Stack spacing={0.75}>
                  {timeline.map((e, i) => (
                    <Box key={e.id ?? i}>
                      <Typography variant="caption" color="text.secondary" display="block">
                        {e.at ? new Date(e.at).toLocaleTimeString() : ''}
                        {e.generatorId ? ` · gen ${String(e.generatorId).slice(0, 8)}` : ''}
                      </Typography>
                      <Typography variant="body2">
                        <Box component="span" sx={{ fontWeight: 600, fontFamily: 'IBM Plex Mono, monospace', mr: 0.75 }}>
                          {e.eventType}
                        </Box>
                        {e.message}
                      </Typography>
                      {e.detail && (e.eventType === 'STATUS_POLL' || e.eventType === 'FINISH_CHECK'
                        || e.eventType === 'MASTER_STARTED' || e.eventType === 'NEVER_STARTED') && (
                        <Typography variant="caption" color="text.secondary" sx={{ fontFamily: 'IBM Plex Mono, monospace' }}>
                          {[
                            e.detail.pid != null ? `pid=${e.detail.pid}` : '',
                            e.detail.exitCode != null ? `exit=${e.detail.exitCode}` : '',
                            e.detail.jtlSamples != null ? `jtl=${e.detail.jtlSamples}` : '',
                            e.detail.jmxPath != null ? `jmx=${String(e.detail.jmxPath).slice(-40)}` : '',
                          ].filter(Boolean).join(' · ')}
                        </Typography>
                      )}
                    </Box>
                  ))}
                </Stack>
              </Box>

              <Stack direction="row" spacing={1} alignItems="center">
                <Chip
                  size="small"
                  label="jmeter.log"
                  color={logTab === 'jmeter' ? 'primary' : 'default'}
                  onClick={() => setLogTab('jmeter')}
                />
                <Chip
                  size="small"
                  label="jmeter-console.log"
                  color={logTab === 'console' ? 'primary' : 'default'}
                  onClick={() => setLogTab('console')}
                />
                {activeRun.masterGeneratorId && (
                  <Button
                    component={RouterLink}
                    to={`/generators/${activeRun.masterGeneratorId}`}
                    size="small"
                  >
                    Master agent logs
                  </Button>
                )}
              </Stack>
              <Box sx={{
                mt: 0.5, p: 1.5, borderRadius: 1.5, bgcolor: 'rgba(0,0,0,0.35)', color: '#D7E2F2',
                fontFamily: 'IBM Plex Mono, ui-monospace, monospace', fontSize: 12,
                maxHeight: 280, overflow: 'auto', whiteSpace: 'pre-wrap',
              }}>
                {logText || 'Waiting for agent log stream (starts after MASTER_STARTED)…'}
              </Box>
              <Button component={RouterLink} to={`/history/${activeRun.id}`} size="small">Open details</Button>
            </Stack>
          )}
        </Box>
      </Box>

      <Box>
        <Typography variant="h6" gutterBottom>Resource Availability</Typography>
        <Stack direction="row" flexWrap="wrap" gap={1}>
          {generators.map((g) => (
            <Chip key={g.id} label={`${g.name}: ${g.status}`}
              color={g.status === 'AVAILABLE' ? 'success' : g.status === 'RUNNING' ? 'warning' : 'default'} />
          ))}
        </Stack>
      </Box>
    </Stack>
  );
}
