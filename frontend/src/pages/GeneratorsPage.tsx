import {
  Alert, Box, Button, Chip, Dialog, DialogActions, DialogContent, DialogTitle,
  FormControl, FormControlLabel, LinearProgress, MenuItem, Radio, RadioGroup,
  Stack, Step, StepLabel, Stepper, TextField, Typography,
} from '@mui/material';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { Link as RouterLink } from 'react-router-dom';
import { api } from '../api/client';
import type { Generator, SshCredential } from '../api/client';

const emptyGen = {
  name: '', hostname: '', sshPort: 22, sshUser: 'root', sshCredentialId: '', provisionNow: true,
};
const emptyCred = { name: '', privateKeyPem: '', passphrase: '' };

export function GeneratorsPage() {
  const qc = useQueryClient();
  const { data = [], isLoading } = useQuery({
    queryKey: ['generators'],
    queryFn: () => api.get<Generator[]>('/api/v1/generators'),
    refetchInterval: 5000,
  });
  const { data: creds = [] } = useQuery({
    queryKey: ['ssh-credentials'],
    queryFn: () => api.get<SshCredential[]>('/api/v1/ssh-credentials'),
  });

  const [wizardOpen, setWizardOpen] = useState(false);
  const [step, setStep] = useState(0);
  const [credMode, setCredMode] = useState<'existing' | 'new'>('existing');
  const [credForm, setCredForm] = useState(emptyCred);
  const [genForm, setGenForm] = useState(emptyGen);
  const [error, setError] = useState('');
  const [createdId, setCreatedId] = useState<string | null>(null);
  const [deleteTarget, setDeleteTarget] = useState<Generator | null>(null);
  const [deleteError, setDeleteError] = useState('');

  const { data: steps = [] } = useQuery({
    queryKey: ['provision-steps', createdId],
    queryFn: () => api.get<{ stepName: string; status: string; message: string }[]>(
      `/api/v1/generators/${createdId}/provision/steps`,
    ),
    enabled: !!createdId,
    refetchInterval: createdId ? 2000 : false,
  });

  const openWizard = () => {
    setWizardOpen(true);
    setStep(0);
    setError('');
    setCreatedId(null);
    setCredForm(emptyCred);
    setGenForm(emptyGen);
    setCredMode(creds.length > 0 ? 'existing' : 'new');
  };

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

  const remove = useMutation({
    mutationFn: (id: string) => api.del(`/api/v1/generators/${id}`),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['generators'] });
      setDeleteTarget(null);
      setDeleteError('');
    },
    onError: (e: Error) => setDeleteError(e.message),
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
        <Button variant="contained" onClick={openWizard}>Add Generator</Button>
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
              <Button size="small" color="error" onClick={() => { setDeleteTarget(g); setDeleteError(''); }}>
                Delete
              </Button>
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
              <FormControl>
                <RadioGroup
                  value={credMode}
                  onChange={(e) => {
                    setCredMode(e.target.value as 'existing' | 'new');
                    setError('');
                  }}
                >
                  <FormControlLabel
                    value="existing"
                    control={<Radio />}
                    disabled={creds.length === 0}
                    label={creds.length === 0 ? 'Use existing key (none saved yet)' : 'Use existing key'}
                  />
                  <FormControlLabel value="new" control={<Radio />} label="Create new key" />
                </RadioGroup>
              </FormControl>

              {credMode === 'existing' && (
                <TextField
                  select
                  label="SSH credential"
                  value={genForm.sshCredentialId}
                  onChange={(e) => setGenForm({ ...genForm, sshCredentialId: e.target.value })}
                  helperText="Manage keys in Resources → Secrets"
                >
                  {creds.map((c) => (
                    <MenuItem key={c.id} value={c.id}>
                      {c.name}{c.inUseCount ? ` · in use ×${c.inUseCount}` : ''}
                    </MenuItem>
                  ))}
                </TextField>
              )}

              {credMode === 'new' && (
                <>
                  <TextField label="Credential name" value={credForm.name}
                    onChange={(e) => setCredForm({ ...credForm, name: e.target.value })} />
                  <TextField label="Private key (PEM)" multiline minRows={6} value={credForm.privateKeyPem}
                    onChange={(e) => setCredForm({ ...credForm, privateKeyPem: e.target.value })} />
                  <TextField label="Passphrase (optional)" type="password" value={credForm.passphrase}
                    onChange={(e) => setCredForm({ ...credForm, passphrase: e.target.value })} />
                </>
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
          {step === 0 && credMode === 'existing' && (
            <Button
              variant="contained"
              onClick={() => { setError(''); setStep(1); }}
              disabled={!genForm.sshCredentialId}
            >
              Continue
            </Button>
          )}
          {step === 0 && credMode === 'new' && (
            <Button
              variant="contained"
              onClick={() => createCred.mutate()}
              disabled={!credForm.name || !credForm.privateKeyPem || createCred.isPending}
            >
              Save & Continue
            </Button>
          )}
          {step === 1 && (
            <Button variant="contained" onClick={() => createGen.mutate()}
              disabled={!genForm.name || !genForm.hostname || !genForm.sshCredentialId || createGen.isPending}>
              Provision
            </Button>
          )}
        </DialogActions>
      </Dialog>

      <Dialog open={!!deleteTarget} onClose={() => setDeleteTarget(null)} fullWidth maxWidth="sm">
        <DialogTitle>Delete generator?</DialogTitle>
        <DialogContent>
          <Typography sx={{ mb: 1 }}>
            Delete <strong>{deleteTarget?.name}</strong> ({deleteTarget?.hostname})?
            Running or reserved generators cannot be deleted.
          </Typography>
          {deleteError && <Alert severity="error">{deleteError}</Alert>}
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setDeleteTarget(null)}>Cancel</Button>
          <Button
            color="error" variant="contained"
            disabled={remove.isPending}
            onClick={() => deleteTarget && remove.mutate(deleteTarget.id)}
          >
            Delete
          </Button>
        </DialogActions>
      </Dialog>
    </Stack>
  );
}
