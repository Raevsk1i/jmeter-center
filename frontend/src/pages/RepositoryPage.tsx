import {
  Alert, Box, Button, MenuItem, Stack, TextField, Typography, Chip,
} from '@mui/material';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { api } from '../api/client';

type System = { id: string; name: string; bitbucketBranch: string; description?: string };
type Group = { id: string; name: string; parentId?: string };
type Test = {
  id: string; groupId?: string; systemId: string; name: string;
  jmxPath: string; testType: string; defaultProperties: Record<string, string>;
};

export function RepositoryPage() {
  const qc = useQueryClient();
  const { data: systems = [] } = useQuery({ queryKey: ['systems'], queryFn: () => api.get<System[]>('/api/v1/systems') });
  const { data: groups = [] } = useQuery({ queryKey: ['groups'], queryFn: () => api.get<Group[]>('/api/v1/test-groups') });
  const { data: tests = [] } = useQuery({ queryKey: ['tests'], queryFn: () => api.get<Test[]>('/api/v1/tests') });

  const sync = useMutation({
    mutationFn: () => api.post('/api/v1/bitbucket/sync'),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['systems'] }),
  });

  const [groupName, setGroupName] = useState('');
  const [testForm, setTestForm] = useState({
    name: '', systemId: '', groupId: '', jmxPath: 'perf_test/test.jmx', testType: 'PERF',
  });
  const [msg, setMsg] = useState('');

  const createGroup = useMutation({
    mutationFn: () => api.post('/api/v1/test-groups', { name: groupName }),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ['groups'] }); setGroupName(''); },
  });

  const createTest = useMutation({
    mutationFn: () => api.post('/api/v1/tests', {
      ...testForm,
      groupId: testForm.groupId || null,
      defaultProperties: {},
    }),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['tests'] });
      setMsg('Test definition created');
    },
    onError: (e: Error) => setMsg(e.message),
  });

  return (
    <Stack spacing={3}>
      <Stack direction="row" justifyContent="space-between" alignItems="center">
        <Box>
          <Typography variant="h4">Test Repository</Typography>
          <Typography color="text.secondary">Groups → Systems → Tests (Bitbucket-backed)</Typography>
        </Box>
        <Button variant="outlined" onClick={() => sync.mutate()} disabled={sync.isPending}>
          Sync Bitbucket Branches
        </Button>
      </Stack>

      {msg && <Alert severity="info" onClose={() => setMsg('')}>{msg}</Alert>}
      {sync.isError && <Alert severity="error">{(sync.error as Error).message}</Alert>}

      <Box sx={{ display: 'grid', gridTemplateColumns: { xs: '1fr', md: '1fr 1.4fr' }, gap: 2 }}>
        <Box sx={{ p: 2.5, borderRadius: 2, border: '1px solid', borderColor: 'divider', bgcolor: 'background.paper' }}>
          <Typography variant="h6" gutterBottom>Tree</Typography>
          <Stack spacing={1.5}>
            {groups.map((g) => (
              <Box key={g.id}>
                <Typography fontWeight={650}>{g.name}</Typography>
                {systems.map((s) => (
                  <Box key={s.id} sx={{ ml: 2, mt: 1 }}>
                    <Stack direction="row" spacing={1} alignItems="center">
                      <Typography variant="body2">{s.name}</Typography>
                      <Chip size="small" label={s.bitbucketBranch} />
                    </Stack>
                    {tests.filter((t) => t.systemId === s.id && t.groupId === g.id).map((t) => (
                      <Typography key={t.id} variant="caption" display="block" sx={{ ml: 2 }}>
                        {t.name} · {t.jmxPath}
                      </Typography>
                    ))}
                  </Box>
                ))}
              </Box>
            ))}
            {groups.length === 0 && systems.map((s) => (
              <Box key={s.id}>
                <Stack direction="row" spacing={1} alignItems="center">
                  <Typography fontWeight={650}>{s.name}</Typography>
                  <Chip size="small" label={s.bitbucketBranch} />
                </Stack>
                {tests.filter((t) => t.systemId === s.id).map((t) => (
                  <Typography key={t.id} variant="caption" display="block" sx={{ ml: 2 }}>
                    {t.name} · {t.jmxPath}
                  </Typography>
                ))}
              </Box>
            ))}
            {systems.length === 0 && (
              <Typography color="text.secondary">No systems yet. Sync Bitbucket or create one in Settings.</Typography>
            )}
          </Stack>
        </Box>

        <Stack spacing={2}>
          <Box sx={{ p: 2.5, borderRadius: 2, border: '1px solid', borderColor: 'divider', bgcolor: 'background.paper' }}>
            <Typography variant="h6" gutterBottom>New Test Group</Typography>
            <Stack direction="row" spacing={1}>
              <TextField size="small" fullWidth label="Group name" value={groupName}
                onChange={(e) => setGroupName(e.target.value)} />
              <Button variant="contained" onClick={() => createGroup.mutate()} disabled={!groupName}>Create</Button>
            </Stack>
          </Box>
          <Box sx={{ p: 2.5, borderRadius: 2, border: '1px solid', borderColor: 'divider', bgcolor: 'background.paper' }}>
            <Typography variant="h6" gutterBottom>New Test Definition</Typography>
            <Stack spacing={1.5}>
              <TextField label="Name" value={testForm.name}
                onChange={(e) => setTestForm({ ...testForm, name: e.target.value })} />
              <TextField select label="System" value={testForm.systemId}
                onChange={(e) => setTestForm({ ...testForm, systemId: e.target.value })}>
                {systems.map((s) => <MenuItem key={s.id} value={s.id}>{s.name}</MenuItem>)}
              </TextField>
              <TextField select label="Group (optional)" value={testForm.groupId}
                onChange={(e) => setTestForm({ ...testForm, groupId: e.target.value })}>
                <MenuItem value="">None</MenuItem>
                {groups.map((g) => <MenuItem key={g.id} value={g.id}>{g.name}</MenuItem>)}
              </TextField>
              <TextField label="JMX path" value={testForm.jmxPath}
                onChange={(e) => setTestForm({ ...testForm, jmxPath: e.target.value })}
                helperText="e.g. perf_test/test.jmx" />
              <TextField select label="Type" value={testForm.testType}
                onChange={(e) => setTestForm({ ...testForm, testType: e.target.value })}>
                <MenuItem value="PERF">PERF</MenuItem>
                <MenuItem value="STABILITY">STABILITY</MenuItem>
              </TextField>
              <Button variant="contained" onClick={() => createTest.mutate()}
                disabled={!testForm.name || !testForm.systemId || !testForm.jmxPath}>
                Create Test
              </Button>
            </Stack>
          </Box>
        </Stack>
      </Box>
    </Stack>
  );
}
