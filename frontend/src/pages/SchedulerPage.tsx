import {
  Alert, Box, Button, Chip, MenuItem, Stack, TextField, Typography,
} from '@mui/material';
import { DateTimePicker } from '@mui/x-date-pickers/DateTimePicker';
import { LocalizationProvider } from '@mui/x-date-pickers/LocalizationProvider';
import { AdapterDayjs } from '@mui/x-date-pickers/AdapterDayjs';
import dayjs, { Dayjs } from 'dayjs';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { api } from '../api/client';
import type { Generator } from '../api/client';

type Schedule = {
  id: string; testDefinitionId: string; fireAt: string; timezone: string;
  status: string; runId?: string;
};
type Test = { id: string; name: string };

export function SchedulerPage() {
  const qc = useQueryClient();
  const { data: schedules = [] } = useQuery({
    queryKey: ['schedules'],
    queryFn: () => api.get<Schedule[]>('/api/v1/schedules'),
    refetchInterval: 5000,
  });
  const { data: tests = [] } = useQuery({ queryKey: ['tests'], queryFn: () => api.get<Test[]>('/api/v1/tests') });
  const { data: generators = [] } = useQuery({
    queryKey: ['generators'], queryFn: () => api.get<Generator[]>('/api/v1/generators'),
  });

  const [fireAt, setFireAt] = useState<Dayjs | null>(dayjs().add(1, 'hour'));
  const [form, setForm] = useState({
    testDefinitionId: '', masterGeneratorId: '', timezone: 'UTC',
  });
  const [error, setError] = useState('');

  const create = useMutation({
    mutationFn: () => api.post('/api/v1/schedules', {
      ...form,
      fireAt: fireAt?.toISOString(),
      slaveGeneratorIds: [],
      properties: {},
    }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['schedules'] }),
    onError: (e: Error) => setError(e.message),
  });

  const cancel = useMutation({
    mutationFn: (id: string) => api.del(`/api/v1/schedules/${id}`),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['schedules'] }),
  });

  return (
    <LocalizationProvider dateAdapter={AdapterDayjs}>
      <Stack spacing={3}>
        <Box>
          <Typography variant="h4">Scheduler</Typography>
          <Typography color="text.secondary">Calendar of planned executions (acquire-at-fire)</Typography>
        </Box>
        {error && <Alert severity="error">{error}</Alert>}

        <Box sx={{ display: 'grid', gridTemplateColumns: { xs: '1fr', md: '1fr 1.2fr' }, gap: 2 }}>
          <Box sx={{ p: 2.5, borderRadius: 2, border: '1px solid', borderColor: 'divider', bgcolor: 'background.paper' }}>
            <Typography variant="h6" gutterBottom>Schedule Run</Typography>
            <Stack spacing={1.5}>
              <TextField select label="Test" value={form.testDefinitionId}
                onChange={(e) => setForm({ ...form, testDefinitionId: e.target.value })}>
                {tests.map((t) => <MenuItem key={t.id} value={t.id}>{t.name}</MenuItem>)}
              </TextField>
              <TextField select label="Master" value={form.masterGeneratorId}
                onChange={(e) => setForm({ ...form, masterGeneratorId: e.target.value })}>
                {generators.map((g) => <MenuItem key={g.id} value={g.id}>{g.name}</MenuItem>)}
              </TextField>
              <DateTimePicker label="Fire at" value={fireAt} onChange={setFireAt} />
              <TextField label="Timezone" value={form.timezone}
                onChange={(e) => setForm({ ...form, timezone: e.target.value })} />
              <Button variant="contained" onClick={() => create.mutate()}
                disabled={!form.testDefinitionId || !form.masterGeneratorId || !fireAt}>
                Schedule
              </Button>
            </Stack>
          </Box>

          <Box sx={{ p: 2.5, borderRadius: 2, border: '1px solid', borderColor: 'divider', bgcolor: 'background.paper' }}>
            <Typography variant="h6" gutterBottom>Upcoming</Typography>
            <Stack spacing={1.25}>
              {schedules.map((s) => (
                <Stack key={s.id} direction="row" justifyContent="space-between" alignItems="center"
                  sx={{ py: 1, borderBottom: '1px solid', borderColor: 'divider' }}>
                  <Box>
                    <Typography variant="body2">{new Date(s.fireAt).toLocaleString()} ({s.timezone})</Typography>
                    <Typography variant="caption" color="text.secondary">{s.id.slice(0, 8)}</Typography>
                  </Box>
                  <Stack direction="row" spacing={1} alignItems="center">
                    <Chip size="small" label={s.status} />
                    {s.status === 'PENDING' && (
                      <Button size="small" color="error" onClick={() => cancel.mutate(s.id)}>Cancel</Button>
                    )}
                  </Stack>
                </Stack>
              ))}
              {schedules.length === 0 && <Typography color="text.secondary">No schedules</Typography>}
            </Stack>
          </Box>
        </Box>
      </Stack>
    </LocalizationProvider>
  );
}
