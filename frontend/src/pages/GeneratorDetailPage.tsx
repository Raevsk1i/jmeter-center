import {
  Alert, Box, Button, Chip, Dialog, DialogActions, DialogContent, DialogTitle,
  FormControlLabel, LinearProgress, MenuItem, Stack, Switch, TextField, Typography,
} from '@mui/material';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useMemo, useRef, useState } from 'react';
import { Link as RouterLink, useNavigate, useParams } from 'react-router-dom';
import { api } from '../api/client';
import type { Generator } from '../api/client';

export type GeneratorLog = {
  id: number;
  generatorId: string;
  source: string;
  level: string;
  eventType: string;
  message: string;
  detail?: Record<string, unknown> | null;
  at: string;
};

const SOURCES = ['ALL', 'PROVISION', 'AGENT_CMD', 'AGENT_CONN'] as const;

function levelColor(level: string): 'default' | 'info' | 'warning' | 'error' | 'success' {
  switch (level) {
    case 'ERROR': return 'error';
    case 'WARN': return 'warning';
    case 'INFO': return 'info';
    default: return 'default';
  }
}

function formatTime(iso: string) {
  try {
    return new Date(iso).toLocaleTimeString(undefined, { hour12: false });
  } catch {
    return iso;
  }
}

function formatDetail(detail?: Record<string, unknown> | null) {
  if (!detail || Object.keys(detail).length === 0) return '';
  try {
    return JSON.stringify(detail, null, 2);
  } catch {
    return String(detail);
  }
}

export function GeneratorDetailPage() {
  const { id = '' } = useParams();
  const navigate = useNavigate();
  const qc = useQueryClient();
  const logEndRef = useRef<HTMLDivElement | null>(null);
  const [source, setSource] = useState<(typeof SOURCES)[number]>('ALL');
  const [autoScroll, setAutoScroll] = useState(true);
  const [showDetail, setShowDetail] = useState(true);
  const [logs, setLogs] = useState<GeneratorLog[]>([]);
  const [pollError, setPollError] = useState('');
  const [confirmDelete, setConfirmDelete] = useState(false);
  const [deleteError, setDeleteError] = useState('');

  const { data: generator, isLoading, error } = useQuery({
    queryKey: ['generator', id],
    queryFn: () => api.get<Generator>(`/api/v1/generators/${id}`),
    enabled: !!id,
    refetchInterval: 3000,
  });

  const { data: steps = [] } = useQuery({
    queryKey: ['provision-steps', id],
    queryFn: () => api.get<{ stepName: string; status: string; message: string }[]>(
      `/api/v1/generators/${id}/provision/steps`,
    ),
    enabled: !!id,
    refetchInterval: 2000,
  });

  const { data: sourceCounts } = useQuery({
    queryKey: ['generator-log-sources', id],
    queryFn: () => api.get<Record<string, number>>(`/api/v1/generators/${id}/logs/sources`),
    enabled: !!id,
    refetchInterval: 5000,
  });

  const reprovision = useMutation({
    mutationFn: () => api.post(`/api/v1/generators/${id}/provision`),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['generator', id] });
      qc.invalidateQueries({ queryKey: ['provision-steps', id] });
    },
  });

  const check = useMutation({
    mutationFn: () => api.post<{ ok?: boolean; message?: string; output?: string }>(
      `/api/v1/generators/${id}/check`,
    ),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['generator-log-sources', id] });
    },
  });

  const remove = useMutation({
    mutationFn: () => api.del(`/api/v1/generators/${id}`),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['generators'] });
      navigate('/generators');
    },
    onError: (e: Error) => setDeleteError(e.message),
  });

  // Initial load + incremental poll (works with Basic auth; SSE cannot set Authorization)
  useEffect(() => {
    if (!id) return;
    let cancelled = false;
    let afterId = 0;
    setLogs([]);
    setPollError('');

    const load = async (incremental: boolean) => {
      try {
        const params = new URLSearchParams();
        if (source !== 'ALL') params.set('source', source);
        if (incremental && afterId > 0) params.set('afterId', String(afterId));
        params.set('limit', incremental ? '100' : '400');
        const path = `/api/v1/generators/${id}/logs?${params.toString()}`;
        const batch = await api.get<GeneratorLog[]>(path);
        if (cancelled) return;
        if (!incremental) {
          setLogs(batch);
          afterId = batch.length ? batch[batch.length - 1].id : 0;
        } else if (batch.length) {
          setLogs((prev) => {
            const seen = new Set(prev.map((l) => l.id));
            const merged = [...prev];
            for (const row of batch) {
              if (!seen.has(row.id)) merged.push(row);
            }
            return merged.slice(-2000);
          });
          afterId = batch[batch.length - 1].id;
        }
        setPollError('');
      } catch (e) {
        if (!cancelled) setPollError(e instanceof Error ? e.message : 'Failed to load logs');
      }
    };

    void load(false);
    const timer = setInterval(() => void load(true), 1500);
    return () => {
      cancelled = true;
      clearInterval(timer);
    };
  }, [id, source]);

  useEffect(() => {
    if (!autoScroll) return;
    logEndRef.current?.scrollIntoView({ behavior: 'smooth', block: 'end' });
  }, [logs, autoScroll]);

  const filteredHint = useMemo(() => {
    if (!sourceCounts) return '';
    return SOURCES.filter((s) => s !== 'ALL')
      .map((s) => `${s}: ${sourceCounts[s] ?? 0}`)
      .join(' · ');
  }, [sourceCounts]);

  const statusColor = (s?: string) => {
    switch (s) {
      case 'AVAILABLE': return 'success';
      case 'RUNNING': return 'warning';
      case 'RESERVED': return 'info';
      case 'ERROR': return 'error';
      case 'OFFLINE': return 'default';
      case 'PROVISIONING': return 'secondary';
      default: return 'default';
    }
  };

  if (isLoading) return <LinearProgress />;
  if (error || !generator) {
    return (
      <Stack spacing={2}>
        <Alert severity="error">{(error as Error)?.message || 'Generator not found'}</Alert>
        <Button component={RouterLink} to="/generators">Back to generators</Button>
      </Stack>
    );
  }

  return (
    <Stack spacing={2.5}>
      <Stack direction={{ xs: 'column', sm: 'row' }} justifyContent="space-between" alignItems={{ sm: 'center' }} gap={1}>
        <Box>
          <Button component={RouterLink} to="/generators" size="small" sx={{ mb: 0.5 }}>← Generators</Button>
          <Typography variant="h4">{generator.name}</Typography>
          <Typography color="text.secondary">
            {generator.hostname}:{generator.sshPort} · {generator.sshUser}
          </Typography>
        </Box>
        <Stack direction="row" spacing={1} flexWrap="wrap">
          <Chip label={generator.status} color={statusColor(generator.status) as 'success'} />
          <Button size="small" variant="outlined" onClick={() => check.mutate()} disabled={check.isPending}>
            Check SSH
          </Button>
          <Button size="small" variant="contained" onClick={() => reprovision.mutate()} disabled={reprovision.isPending}>
            Reprovision
          </Button>
          <Button size="small" color="error" onClick={() => { setConfirmDelete(true); setDeleteError(''); }}>
            Delete
          </Button>
        </Stack>
      </Stack>

      {generator.provisionError && <Alert severity="error">{generator.provisionError}</Alert>}
      {pollError && <Alert severity="warning">Log stream: {pollError}</Alert>}

      <Dialog open={confirmDelete} onClose={() => setConfirmDelete(false)} fullWidth maxWidth="sm">
        <DialogTitle>Delete generator?</DialogTitle>
        <DialogContent>
          <Typography sx={{ mb: 1 }}>
            Delete <strong>{generator.name}</strong> ({generator.hostname})?
            Running or reserved generators cannot be deleted.
          </Typography>
          {deleteError && <Alert severity="error">{deleteError}</Alert>}
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setConfirmDelete(false)}>Cancel</Button>
          <Button color="error" variant="contained" disabled={remove.isPending} onClick={() => remove.mutate()}>
            Delete
          </Button>
        </DialogActions>
      </Dialog>

      <Box sx={{
        display: 'grid',
        gridTemplateColumns: { xs: '1fr', md: '280px 1fr' },
        gap: 2,
      }}>
        <Stack spacing={2}>
          <Box sx={{ p: 2, borderRadius: 2, border: '1px solid', borderColor: 'divider', bgcolor: 'background.paper' }}>
            <Typography variant="subtitle2" gutterBottom>Agent</Typography>
            <Typography variant="body2">Version: {generator.agentVersion || '—'}</Typography>
            <Typography variant="body2">Java: {generator.javaVersion || '—'}</Typography>
            <Typography variant="body2">JMeter: {generator.jmeterVersion || '—'}</Typography>
            <Typography variant="body2" sx={{ mt: 1 }}>
              CPU {generator.cpuUsagePercent?.toFixed?.(0) ?? '—'}% · Free disk {generator.diskFreeMb ?? '—'} MB
            </Typography>
            <Typography variant="caption" color="text.secondary" display="block" sx={{ mt: 1 }}>
              Heartbeat: {generator.lastHeartbeatAt ? new Date(generator.lastHeartbeatAt).toLocaleString() : 'never'}
            </Typography>
            {generator.provisionStep && (
              <Typography variant="caption" display="block" sx={{ mt: 0.5 }}>
                Provision step: {generator.provisionStep}
              </Typography>
            )}
          </Box>

          <Box sx={{ p: 2, borderRadius: 2, border: '1px solid', borderColor: 'divider', bgcolor: 'background.paper' }}>
            <Typography variant="subtitle2" gutterBottom>Provision steps</Typography>
            {steps.length === 0 && (
              <Typography variant="body2" color="text.secondary">No steps recorded yet</Typography>
            )}
            <Stack spacing={0.75}>
              {steps.map((s, i) => (
                <Box key={`${s.stepName}-${i}`}>
                  <Chip
                    size="small"
                    label={`${s.stepName}: ${s.status}`}
                    color={s.status === 'OK' || s.status === 'DONE' ? 'success'
                      : s.status === 'FAILED' || s.status === 'ERROR' ? 'error'
                        : 'default'}
                    sx={{ mb: 0.25 }}
                  />
                  {s.message && (
                    <Typography variant="caption" color="text.secondary" display="block" sx={{ pl: 0.5 }}>
                      {s.message}
                    </Typography>
                  )}
                </Box>
              ))}
            </Stack>
          </Box>
        </Stack>

        <Box sx={{
          p: 2, borderRadius: 2, border: '1px solid', borderColor: 'divider', bgcolor: 'background.paper',
          display: 'flex', flexDirection: 'column', minHeight: { xs: 420, md: 560 },
        }}>
          <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1.5} alignItems={{ sm: 'center' }} mb={1.5}>
            <Typography variant="h6" sx={{ flex: 1 }}>Diagnostic logs</Typography>
            <TextField
              select size="small" label="Source" value={source}
              onChange={(e) => setSource(e.target.value as (typeof SOURCES)[number])}
              sx={{ minWidth: 160 }}
            >
              {SOURCES.map((s) => (
                <MenuItem key={s} value={s}>
                  {s === 'ALL' ? 'All sources' : s}
                  {s !== 'ALL' && sourceCounts?.[s] != null ? ` (${sourceCounts[s]})` : ''}
                </MenuItem>
              ))}
            </TextField>
            <FormControlLabel
              control={<Switch size="small" checked={showDetail} onChange={(_, v) => setShowDetail(v)} />}
              label="Details"
            />
            <FormControlLabel
              control={<Switch size="small" checked={autoScroll} onChange={(_, v) => setAutoScroll(v)} />}
              label="Auto-scroll"
            />
          </Stack>
          {filteredHint && (
            <Typography variant="caption" color="text.secondary" sx={{ mb: 1 }}>
              {filteredHint}
            </Typography>
          )}
          <Typography variant="caption" color="text.secondary" sx={{ mb: 1 }}>
            PROVISION — SSH bootstrap · AGENT_CMD — commands & agent replies · AGENT_CONN — session register/disconnect
          </Typography>

          <Box sx={{
            flex: 1, p: 1.5, borderRadius: 1.5,
            bgcolor: 'rgba(0,0,0,0.35)', color: '#D7E2F2',
            fontFamily: '"IBM Plex Mono", ui-monospace, monospace',
            fontSize: 12, lineHeight: 1.45,
            overflow: 'auto', whiteSpace: 'pre-wrap', wordBreak: 'break-word',
          }}>
            {logs.length === 0 && !pollError && 'Waiting for logs…'}
            {logs.map((row) => (
              <Box key={row.id} sx={{ mb: 1, borderBottom: '1px solid rgba(255,255,255,0.06)', pb: 0.75 }}>
                <Stack direction="row" spacing={1} alignItems="center" flexWrap="wrap">
                  <Typography component="span" sx={{ opacity: 0.65, fontFamily: 'inherit', fontSize: 'inherit' }}>
                    {formatTime(row.at)}
                  </Typography>
                  <Chip size="small" label={row.source} sx={{ height: 20, fontSize: 10 }} />
                  <Chip size="small" label={row.level} color={levelColor(row.level)} sx={{ height: 20, fontSize: 10 }} />
                  <Typography component="span" sx={{ color: '#7CDEDC', fontFamily: 'inherit', fontSize: 'inherit' }}>
                    {row.eventType}
                  </Typography>
                </Stack>
                <Box component="div">{row.message}</Box>
                {showDetail && row.detail && (
                  <Box component="pre" sx={{
                    m: 0, mt: 0.5, p: 1, borderRadius: 1,
                    bgcolor: 'rgba(0,0,0,0.25)', color: '#A8B8CC',
                    fontSize: 11, overflow: 'auto', maxHeight: 180,
                  }}>
                    {formatDetail(row.detail)}
                  </Box>
                )}
              </Box>
            ))}
            <div ref={logEndRef} />
          </Box>
        </Box>
      </Box>
    </Stack>
  );
}
