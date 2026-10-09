import {
  Alert, Box, Button, Chip, Dialog, DialogActions, DialogContent, DialogTitle,
  LinearProgress, MenuItem, Stack, Step, StepLabel, Stepper, TextField, Typography,
} from '@mui/material';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { Link as RouterLink } from 'react-router-dom';
import { api } from '../api/client';
import type { Generator } from '../api/client';

export function GeneratorsPage() {
  const qc = useQueryClient();
  const { data = [], isLoading } = useQuery({
    queryKey: ['generators'],
    queryFn: () => api.get<Generator[]>('/api/v1/generators'),
    refetchInterval: 5000,
  });
  const { data: creds = [] } = useQuery({
    queryKey: ['ssh-credentials'],
    queryFn: () => api.get<{ id: string; name: string }[]>('/api/v1/ssh-credentials'),
  });

  const [wizardOpen, setWizardOpen] = useState(false);
  const [step, setStep] = useState(0);
  const [credForm, setCredForm] = useState({ name: '', privateKeyPem: '', passphrase: '' });
  const [genForm, setGenForm] = useState({
    name: '', hostname: '', sshPort: 22, sshUser: 'root', sshCredentialId: '', provisionNow: true,
  });
  const [error, setError] = useState('');
  const [createdId, setCreatedId] = useState<string | null>(null);

  const { data: steps = [] } = useQuery({
    queryKey: ['provision-steps', createdId],
    queryFn: () => api.get<{ stepName: string; status: string; message: string }[]>(
      `/api/v1/generators/${createdId}/provision/steps`,
    ),
    enabled: !!createdId,
    refetchInterval: createdId ? 2000 : false,
  });

  const createCred = useMutation({
    mutationFn: () => api.post<{ id: string }>('/api/v1/ssh-credentials', credForm),
    onSuccess: (c) => {
      setGenForm((f) => ({ ...f, sshCredentialId: c.id }));
      qc.invalidateQueries({ queryKey: ['ssh-credentials'] });
      setStep(1);
    },
    onError: (e: Error) => setError(e.message),
  });

  const createGen = useMutation({
    mutationFn: () => api.post<Generator>('/api/v1/generators', {
      ...genForm,
      sshPort: Number(genForm.sshPort),
    }),
    onSuccess: (g) => {
      setCreatedId(g.id);
      qc.invalidateQueries({ queryKey: ['generators'] });
      setStep(2);
    },
    onError: (e: Error) => setError(e.message),
  });

  const reprovision = useMutation({
    mutationFn: (id: string) => api.post(`/api/v1/generators/${id}/provision`),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['generators'] }),
  });

  const statusColor = (s: string) => {
    switch (s) {
      case 'AVAILABLE': return 'success';
      case 'RUNNING': return 'warning';
      case 'RESERVED': return 'info';
      case 'ERROR': return 'error';
      case 'OFFLINE': return 'default';
      default: return 'secondary';
    }
  };

  return (
    <Stack spacing={2.5}>
      <Stack direction="row" justifyContent="space-between" alignItems="center">
        <Box>
          <Typography variant="h4">Generators</Typography>
          <Typography color="text.secondary">RHEL load generators and agent health</Typography>
        </Box>
        <Button variant="contained" onClick={() => { setWizardOpen(true); setStep(0); setError(''); setCreatedId(null); }}>
          Add Generator
        </Button>
      </Stack>

      {isLoading && <LinearProgress />}

      <Stack spacing={1.25}>
        {data.map((g) => (
          <Box key={g.id} sx={{
            p: 2, borderRadius: 2, border: '1px solid', borderColor: 'divider',
            display: 'grid',
            gridTemplateColumns: { xs: '1fr', md: '1.4fr 1fr 1fr auto' },
            gap: 2, alignItems: 'center',
            bgcolor: 'background.paper',
          }}>
            <Box>
              <Typography fontWeight={650}>{g.name}</Typography>
              <Typography variant="body2" color="text.secondary">{g.hostname}:{g.sshPort} · {g.sshUser}</Typography>
              {g.provisionStep && <Typography variant="caption">Step: {g.provisionStep}</Typography>}
            </Box>
            <Stack direction="row" spacing={1} alignItems="center" flexWrap="wrap">
              <Chip size="small" label={g.status} color={statusColor(g.status) as 'success'} />
              <Typography variant="caption" color="text.secondary">
                CPU {g.cpuUsagePercent?.toFixed?.(0) ?? '—'}% · Free {g.diskFreeMb ?? '—'} MB
              </Typography>
            </Stack>
            <Typography variant="caption" color="text.secondary">
              Agent {g.agentVersion || '—'} · Java {g.javaVersion || '—'} · JMeter {g.jmeterVersion || '—'}
            </Typography>
            <Stack direction="row" spacing={1}>
              <Button size="small" component={RouterLink} to={`/generators/${g.id}`}>Details</Button>
              <Button size="small" onClick={() => reprovision.mutate(g.id)}>Reprovision</Button>
            </Stack>
          </Box>
        ))}
        {!isLoading && data.length === 0 && (
          <Alert severity="info">No generators yet. Use the wizard to bootstrap the first RHEL host.</Alert>
        )}
      </Stack>

      <Dialog open={wizardOpen} onClose={() => setWizardOpen(false)} fullWidth maxWidth="md">
        <DialogTitle>Generator Wizard</DialogTitle>
        <DialogContent>
          <Stepper activeStep={step} sx={{ my: 2 }}>
            {['SSH Credential', 'Host Details', 'Provisioning'].map((l) => (
              <Step key={l}><StepLabel>{l}</StepLabel></Step>
            ))}
          </Stepper>
          {error && <Alert severity="error" sx={{ mb: 2 }}>{error}</Alert>}
          {step === 0 && (
            <Stack spacing={2}>
              <TextField label="Credential name" value={credForm.name}
                onChange={(e) => setCredForm({ ...credForm, name: e.target.value })} />
              <TextField label="Private key (PEM)" multiline minRows={6} value={credForm.privateKeyPem}
                onChange={(e) => setCredForm({ ...credForm, privateKeyPem: e.target.value })} />
              <TextField label="Passphrase (optional)" type="password" value={credForm.passphrase}
                onChange={(e) => setCredForm({ ...credForm, passphrase: e.target.value })} />
              {creds.length > 0 && (
                <TextField select label="Or select existing" value={genForm.sshCredentialId}
                  onChange={(e) => { setGenForm({ ...genForm, sshCredentialId: e.target.value }); setStep(1); }}>
                  {creds.map((c) => <MenuItem key={c.id} value={c.id}>{c.name}</MenuItem>)}
                </TextField>
              )}
            </Stack>
          )}
          {step === 1 && (
            <Stack spacing={2}>
              <TextField label="Name" value={genForm.name} onChange={(e) => setGenForm({ ...genForm, name: e.target.value })} />
              <TextField label="Hostname / IP" value={genForm.hostname} onChange={(e) => setGenForm({ ...genForm, hostname: e.target.value })} />
              <TextField label="SSH port" type="number" value={genForm.sshPort}
                onChange={(e) => setGenForm({ ...genForm, sshPort: Number(e.target.value) })} />
              <TextField label="SSH user" value={genForm.sshUser}
                onChange={(e) => setGenForm({ ...genForm, sshUser: e.target.value })} />
            </Stack>
          )}
          {step === 2 && (
            <Stack spacing={1}>
              <Typography>Provisioning generator {createdId?.slice(0, 8)}…</Typography>
              {steps.map((s) => (
                <Chip key={s.stepName + s.message} label={`${s.stepName}: ${s.status}`} sx={{ justifyContent: 'flex-start' }} />
              ))}
              {steps.length === 0 && <LinearProgress />}
              {createdId && (
                <Button
                  component={RouterLink}
                  to={`/generators/${createdId}`}
                  variant="outlined"
                  sx={{ alignSelf: 'flex-start', mt: 1 }}
                  onClick={() => setWizardOpen(false)}
                >
                  Open live diagnostic logs
                </Button>
              )}
            </Stack>
          )}
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setWizardOpen(false)}>Close</Button>
          {step === 0 && !genForm.sshCredentialId && (
            <Button variant="contained" onClick={() => createCred.mutate()} disabled={!credForm.name || !credForm.privateKeyPem}>
              Save & Continue
            </Button>
          )}
          {step === 1 && (
            <Button variant="contained" onClick={() => createGen.mutate()}
              disabled={!genForm.name || !genForm.hostname || !genForm.sshCredentialId}>
              Provision
            </Button>
          )}
        </DialogActions>
      </Dialog>
    </Stack>
  );
}
