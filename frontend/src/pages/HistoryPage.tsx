import {
  Alert, Box, Chip, MenuItem, Stack, TextField, Typography, Button,
} from '@mui/material';
import { useQuery } from '@tanstack/react-query';
import { useMemo, useState } from 'react';
import { Link as RouterLink, useParams } from 'react-router-dom';
import { api } from '../api/client';
import type { TestRun } from '../api/client';

export function HistoryPage() {
  const { runId } = useParams();
  const { data: runs = [], isLoading } = useQuery({
    queryKey: ['runs'],
    queryFn: () => api.get<TestRun[]>('/api/v1/runs'),
    refetchInterval: 5000,
  });
  const [status, setStatus] = useState('');
  const [q, setQ] = useState('');

  const filtered = useMemo(() => runs.filter((r) => {
    if (status && r.status !== status) return false;
    if (q && !r.id.includes(q) && !(r.commitHash || '').includes(q)) return false;
    return true;
  }), [runs, status, q]);

  const selected = runId ? runs.find((r) => r.id === runId) : null;
  const { data: events = [] } = useQuery({
    queryKey: ['events', runId],
    queryFn: () => api.get<Record<string, unknown>[]>(`/api/v1/runs/${runId}/events`),
    enabled: !!runId,
  });

  if (runId && selected) {
    return (
      <Stack spacing={2}>
        <Button component={RouterLink} to="/history" sx={{ alignSelf: 'flex-start' }}>← Back</Button>
        <Typography variant="h4">Run {selected.id.slice(0, 8)}</Typography>
        <Stack direction="row" spacing={1}>
          <Chip label={selected.status} />
          {selected.commitHash && <Chip label={`commit ${selected.commitHash.slice(0, 12)}`} />}
        </Stack>
        {selected.errorMessage && <Alert severity="error">{selected.errorMessage}</Alert>}
        <Box sx={{ p: 2, borderRadius: 2, border: '1px solid', borderColor: 'divider', bgcolor: 'background.paper' }}>
          <Typography variant="h6" gutterBottom>Events</Typography>
          <Stack spacing={1.25}>
            {events.map((e, i) => {
              const detail = (e.detail && typeof e.detail === 'object')
                ? e.detail as Record<string, unknown>
                : null;
              const tail = typeof detail?.jmeterLogTail === 'string' ? detail.jmeterLogTail as string
                : typeof detail?.consoleLogTail === 'string' ? detail.consoleLogTail as string
                  : '';
              return (
                <Box key={i} sx={{ pb: 1, borderBottom: '1px solid', borderColor: 'divider' }}>
                  <Typography variant="caption" color="text.secondary">
                    {e.at ? new Date(String(e.at)).toLocaleString() : ''}
                    {e.generatorId ? ` · ${String(e.generatorId).slice(0, 8)}` : ''}
                  </Typography>
                  <Typography variant="body2">
                    <strong>{String(e.eventType || e.event_type)}</strong> — {String(e.message || '')}
                  </Typography>
                  {detail && (detail.exitCode != null || detail.jtlSamples != null || detail.pid != null) && (
                    <Typography variant="caption" sx={{ fontFamily: 'IBM Plex Mono, monospace' }} display="block">
                      {[
                        detail.pid != null ? `pid=${detail.pid}` : '',
                        detail.state != null ? `state=${detail.state}` : '',
                        detail.exitCode != null ? `exit=${detail.exitCode}` : '',
                        detail.jtlSamples != null ? `jtlSamples=${detail.jtlSamples}` : '',
                        detail.jtlBytes != null ? `jtlBytes=${detail.jtlBytes}` : '',
                      ].filter(Boolean).join(' · ')}
                    </Typography>
                  )}
                  {tail && (
                    <Box sx={{
                      mt: 0.75, p: 1, borderRadius: 1, bgcolor: 'rgba(0,0,0,0.28)',
                      fontFamily: 'IBM Plex Mono, monospace', fontSize: 11,
                      maxHeight: 140, overflow: 'auto', whiteSpace: 'pre-wrap',
                    }}>
                      {tail.slice(-2000)}
                    </Box>
                  )}
                </Box>
              );
            })}
            {events.length === 0 && <Typography color="text.secondary">No events</Typography>}
          </Stack>
        </Box>
      </Stack>
    );
  }

  return (
    <Stack spacing={2.5}>
      <Box>
        <Typography variant="h4">Run History</Typography>
        <Typography color="text.secondary">Filter and inspect past executions</Typography>
      </Box>
      <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1.5}>
        <TextField size="small" label="Search id/commit" value={q} onChange={(e) => setQ(e.target.value)} />
        <TextField size="small" select label="Status" value={status} onChange={(e) => setStatus(e.target.value)} sx={{ minWidth: 160 }}>
          <MenuItem value="">All</MenuItem>
          {['SCHEDULED', 'PREPARING', 'RUNNING', 'COMPLETED', 'FAILED', 'CANCELLED'].map((s) => (
            <MenuItem key={s} value={s}>{s}</MenuItem>
          ))}
        </TextField>
      </Stack>
      {isLoading && <Typography>Loading…</Typography>}
      <Stack spacing={1}>
        {filtered.map((r) => (
          <Box key={r.id} component={RouterLink} to={`/history/${r.id}`} sx={{
            textDecoration: 'none', color: 'inherit',
            p: 2, borderRadius: 2, border: '1px solid', borderColor: 'divider', bgcolor: 'background.paper',
            display: 'flex', justifyContent: 'space-between', alignItems: 'center',
            '&:hover': { borderColor: 'primary.main' },
          }}>
            <Box>
              <Typography fontFamily="monospace">{r.id}</Typography>
              <Typography variant="caption" color="text.secondary">
                {new Date(r.createdAt).toLocaleString()} · master {r.masterGeneratorId?.slice(0, 8) || '—'}
              </Typography>
            </Box>
            <Chip label={r.status} size="small" />
          </Box>
        ))}
      </Stack>
    </Stack>
  );
}
