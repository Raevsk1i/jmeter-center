import { Alert, Box, Button, Stack, TextField, Typography } from '@mui/material';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useState } from 'react';
import { api, setBasicAuth } from '../api/client';

export function SettingsPage() {
  const qc = useQueryClient();
  const { data } = useQuery({
    queryKey: ['settings'],
    queryFn: () => api.get<Record<string, unknown>>('/api/v1/settings'),
  });

  const [bb, setBb] = useState({ baseUrl: 'https://api.bitbucket.org/2.0', workspace: '', repo: '', token: '' });
  const [retention, setRetention] = useState({ logRetentionDays: 30 });
  const [jmeter, setJmeter] = useState({ rmiPort: 1099, localPort: 4000 });
  const [controller, setController] = useState({ advertiseHost: '', grpcPort: 9090 });
  const [login, setLogin] = useState({ username: 'admin', password: 'admin' });
  const [msg, setMsg] = useState('');

  useEffect(() => {
    if (!data) return;
    const bitbucket = (data.bitbucket || {}) as Record<string, string>;
    setBb((prev) => ({
      ...prev,
      baseUrl: bitbucket.baseUrl || prev.baseUrl,
      workspace: bitbucket.workspace || '',
      repo: bitbucket.repo || '',
      token: '',
    }));
    const ret = (data.retention || {}) as Record<string, number>;
    if (ret.logRetentionDays) setRetention({ logRetentionDays: Number(ret.logRetentionDays) });
    const jm = (data.jmeter || {}) as Record<string, number>;
    if (jm.rmiPort) setJmeter({ rmiPort: Number(jm.rmiPort), localPort: Number(jm.localPort || 4000) });
    const ctrl = (data.controller || {}) as Record<string, string | number>;
    setController({
      advertiseHost: String(ctrl.advertiseHost || ''),
      grpcPort: Number(ctrl.grpcPort || 9090),
    });
  }, [data]);

  const saveBb = useMutation({
    mutationFn: () => api.put('/api/v1/settings/bitbucket', bb),
    onSuccess: () => { setMsg('Bitbucket settings saved'); qc.invalidateQueries({ queryKey: ['settings'] }); },
    onError: (e: Error) => setMsg(e.message),
  });
  const saveRet = useMutation({
    mutationFn: () => api.put('/api/v1/settings/retention', retention),
    onSuccess: () => setMsg('Retention saved'),
  });
  const saveJm = useMutation({
    mutationFn: () => api.put('/api/v1/settings/jmeter', jmeter),
    onSuccess: () => setMsg('JMeter settings saved'),
  });
  const saveController = useMutation({
    mutationFn: () => api.put('/api/v1/settings/controller', { advertiseHost: controller.advertiseHost }),
    onSuccess: () => {
      setMsg('Controller advertise host saved — used by SSH-provisioned agents');
      qc.invalidateQueries({ queryKey: ['settings'] });
    },
    onError: (e: Error) => setMsg(e.message),
  });

  return (
    <Stack spacing={3}>
      <Box>
        <Typography variant="h4">Settings</Typography>
        <Typography color="text.secondary">Bitbucket, storage paths, agent/gRPC, retention</Typography>
      </Box>
      {msg && <Alert severity={msg.includes('saved') || msg.includes('stored') ? 'success' : 'error'} onClose={() => setMsg('')}>{msg}</Alert>}

      <Box sx={{ p: 2.5, borderRadius: 2, border: '1px solid', borderColor: 'divider', bgcolor: 'background.paper' }}>
        <Typography variant="h6" gutterBottom>Agent controller address</Typography>
        <Typography variant="body2" color="text.secondary" sx={{ mb: 1.5 }}>
          Host/IP that generators dial after SSH bootstrap (gRPC port {controller.grpcPort}).
          Must be reachable from generator hosts — not the Docker service name unless agents share the Compose network.
        </Typography>
        <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1.5} alignItems={{ sm: 'center' }}>
          <TextField
            label="Advertise host / IP"
            value={controller.advertiseHost}
            onChange={(e) => setController({ ...controller, advertiseHost: e.target.value })}
            fullWidth
            placeholder="e.g. 203.0.113.10"
            helperText="Or set env LT_CONTROLLER_HOST on the controller"
          />
          <Button variant="contained" onClick={() => saveController.mutate()}>Save</Button>
        </Stack>
      </Box>

      <Box sx={{ p: 2.5, borderRadius: 2, border: '1px solid', borderColor: 'divider', bgcolor: 'background.paper' }}>
        <Typography variant="h6" gutterBottom>Session Login</Typography>
        <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1.5}>
          <TextField label="Username" value={login.username}
            onChange={(e) => setLogin({ ...login, username: e.target.value })} />
          <TextField label="Password" type="password" value={login.password}
            onChange={(e) => setLogin({ ...login, password: e.target.value })} />
          <Button variant="contained" onClick={() => {
            setBasicAuth(login.username, login.password);
            setMsg('Credentials stored for API calls');
          }}>Apply</Button>
        </Stack>
      </Box>

      <Box sx={{ p: 2.5, borderRadius: 2, border: '1px solid', borderColor: 'divider', bgcolor: 'background.paper' }}>
        <Typography variant="h6" gutterBottom>Bitbucket</Typography>
        <Stack spacing={1.5}>
          <TextField label="Base URL" value={bb.baseUrl} onChange={(e) => setBb({ ...bb, baseUrl: e.target.value })} />
          <TextField label="Workspace" value={bb.workspace} onChange={(e) => setBb({ ...bb, workspace: e.target.value })} />
          <TextField label="Repository" value={bb.repo} onChange={(e) => setBb({ ...bb, repo: e.target.value })} />
          <TextField label="Token" type="password" value={bb.token}
            onChange={(e) => setBb({ ...bb, token: e.target.value })} helperText="Leave blank to keep existing" />
          <Button variant="contained" onClick={() => saveBb.mutate()}>Save Bitbucket</Button>
        </Stack>
      </Box>

      <Box sx={{ display: 'grid', gridTemplateColumns: { xs: '1fr', md: '1fr 1fr' }, gap: 2 }}>
        <Box sx={{ p: 2.5, borderRadius: 2, border: '1px solid', borderColor: 'divider', bgcolor: 'background.paper' }}>
          <Typography variant="h6" gutterBottom>Log Retention</Typography>
          <Stack spacing={1.5}>
            <TextField type="number" label="Days" value={retention.logRetentionDays}
              onChange={(e) => setRetention({ logRetentionDays: Number(e.target.value) })} />
            <Button variant="outlined" onClick={() => saveRet.mutate()}>Save</Button>
          </Stack>
        </Box>
        <Box sx={{ p: 2.5, borderRadius: 2, border: '1px solid', borderColor: 'divider', bgcolor: 'background.paper' }}>
          <Typography variant="h6" gutterBottom>JMeter RMI</Typography>
          <Stack spacing={1.5}>
            <TextField type="number" label="RMI port" value={jmeter.rmiPort}
              onChange={(e) => setJmeter({ ...jmeter, rmiPort: Number(e.target.value) })} />
            <TextField type="number" label="Local port" value={jmeter.localPort}
              onChange={(e) => setJmeter({ ...jmeter, localPort: Number(e.target.value) })} />
            <Button variant="outlined" onClick={() => saveJm.mutate()}>Save</Button>
          </Stack>
        </Box>
      </Box>

      {!!data?.storage && (
        <Box sx={{ p: 2.5, borderRadius: 2, border: '1px solid', borderColor: 'divider', bgcolor: 'background.paper' }}>
          <Typography variant="h6" gutterBottom>Controller Paths</Typography>
          <Typography variant="body2" component="pre" sx={{ m: 0, whiteSpace: 'pre-wrap' }}>
            {JSON.stringify(data.storage as object, null, 2)}
          </Typography>
          <Typography variant="body2" component="pre" sx={{ mt: 1, whiteSpace: 'pre-wrap' }}>
            {JSON.stringify((data.grpc as object) ?? {}, null, 2)}
          </Typography>
        </Box>
      )}
    </Stack>
  );
}
