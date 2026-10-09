import {
  Alert, Box, Button, Dialog, DialogActions, DialogContent, DialogTitle,
  LinearProgress, Stack, TextField, Typography,
} from '@mui/material';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { Link as RouterLink } from 'react-router-dom';
import { api } from '../api/client';
import type { SshCredential } from '../api/client';

const emptyForm = { name: '', privateKeyPem: '', passphrase: '' };

export function SecretsPage() {
  const qc = useQueryClient();
  const { data = [], isLoading } = useQuery({
    queryKey: ['ssh-credentials'],
    queryFn: () => api.get<SshCredential[]>('/api/v1/ssh-credentials'),
  });

  const [dialogOpen, setDialogOpen] = useState(false);
  const [editing, setEditing] = useState<SshCredential | null>(null);
  const [form, setForm] = useState(emptyForm);
  const [error, setError] = useState('');
  const [deleteTarget, setDeleteTarget] = useState<SshCredential | null>(null);
  const [deleteError, setDeleteError] = useState('');

  const openCreate = () => {
    setEditing(null);
    setForm(emptyForm);
    setError('');
    setDialogOpen(true);
  };

  const openEdit = (c: SshCredential) => {
    setEditing(c);
    setForm({ name: c.name, privateKeyPem: '', passphrase: '' });
    setError('');
    setDialogOpen(true);
  };

  const save = useMutation({
    mutationFn: () => {
      if (editing) {
        const body: Record<string, string> = {};
        if (form.name.trim()) body.name = form.name.trim();
        if (form.privateKeyPem.trim()) body.privateKeyPem = form.privateKeyPem;
        if (form.passphrase !== '') body.passphrase = form.passphrase;
        return api.put<SshCredential>(`/api/v1/ssh-credentials/${editing.id}`, body);
      }
      return api.post<SshCredential>('/api/v1/ssh-credentials', form);
    },
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['ssh-credentials'] });
      setDialogOpen(false);
    },
    onError: (e: Error) => setError(e.message),
  });

  const remove = useMutation({
    mutationFn: (id: string) => api.del(`/api/v1/ssh-credentials/${id}`),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['ssh-credentials'] });
      setDeleteTarget(null);
      setDeleteError('');
    },
    onError: (e: Error) => setDeleteError(e.message),
  });

  return (
    <Stack spacing={2.5}>
      <Stack direction="row" justifyContent="space-between" alignItems="center">
        <Box>
          <Typography variant="h4">Secrets</Typography>
          <Typography color="text.secondary">SSH private keys used to provision generators</Typography>
        </Box>
        <Button variant="contained" onClick={openCreate}>Add SSH key</Button>
      </Stack>

      {isLoading && <LinearProgress />}

      <Stack spacing={1.25}>
        {data.map((c) => (
          <Box key={c.id} sx={{
            p: 2, borderRadius: 2, border: '1px solid', borderColor: 'divider',
            bgcolor: 'background.paper',
            display: 'grid',
            gridTemplateColumns: { xs: '1fr', md: '1.5fr 1fr auto' },
            gap: 2, alignItems: 'center',
          }}>
            <Box>
              <Typography fontWeight={650}>{c.name}</Typography>
              <Typography variant="caption" color="text.secondary" fontFamily="monospace">
                {c.id}
              </Typography>
            </Box>
            <Typography variant="body2" color="text.secondary">
              Used by {c.inUseCount} generator{c.inUseCount === 1 ? '' : 's'}
              {c.createdAt ? ` · created ${new Date(c.createdAt).toLocaleString()}` : ''}
            </Typography>
            <Stack direction="row" spacing={1}>
              <Button size="small" onClick={() => openEdit(c)}>Edit</Button>
              <Button size="small" color="error" onClick={() => { setDeleteTarget(c); setDeleteError(''); }}>
                Delete
              </Button>
            </Stack>
          </Box>
        ))}
        {!isLoading && data.length === 0 && (
          <Alert severity="info">No SSH credentials yet. Add a key here or from the Generator wizard.</Alert>
        )}
      </Stack>

      <Dialog open={dialogOpen} onClose={() => setDialogOpen(false)} fullWidth maxWidth="sm">
        <DialogTitle>{editing ? 'Edit SSH credential' : 'Add SSH credential'}</DialogTitle>
        <DialogContent>
          <Stack spacing={2} sx={{ mt: 1 }}>
            {error && <Alert severity="error">{error}</Alert>}
            <TextField label="Name" value={form.name}
              onChange={(e) => setForm({ ...form, name: e.target.value })} />
            <TextField
              label={editing ? 'Private key PEM (leave blank to keep)' : 'Private key (PEM)'}
              multiline minRows={6} value={form.privateKeyPem}
              onChange={(e) => setForm({ ...form, privateKeyPem: e.target.value })}
            />
            <TextField
              label={editing ? 'Passphrase (leave empty to keep current)' : 'Passphrase (optional)'}
              type="password" value={form.passphrase}
              onChange={(e) => setForm({ ...form, passphrase: e.target.value })}
              helperText={editing ? 'Send a new key or passphrase to rotate; host-key TOFU is reset when key material changes.' : undefined}
            />
          </Stack>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setDialogOpen(false)}>Cancel</Button>
          <Button
            variant="contained"
            onClick={() => save.mutate()}
            disabled={
              save.isPending
              || !form.name.trim()
              || (!editing && !form.privateKeyPem.trim())
            }
          >
            Save
          </Button>
        </DialogActions>
      </Dialog>

      <Dialog open={!!deleteTarget} onClose={() => setDeleteTarget(null)} fullWidth maxWidth="sm">
        <DialogTitle>Delete SSH credential?</DialogTitle>
        <DialogContent>
          <Typography sx={{ mb: 1 }}>
            Delete <strong>{deleteTarget?.name}</strong>? This cannot be undone.
          </Typography>
          {(deleteTarget?.inUseCount ?? 0) > 0 && (
            <Alert severity="warning" sx={{ mb: 1 }}>
              This key is used by {deleteTarget?.inUseCount} generator(s).
              Delete or reassign those generators first —{' '}
              <Button component={RouterLink} to="/generators" size="small">Open Generators</Button>
            </Alert>
          )}
          {deleteError && <Alert severity="error">{deleteError}</Alert>}
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setDeleteTarget(null)}>Cancel</Button>
          <Button
            color="error" variant="contained"
            disabled={remove.isPending || (deleteTarget?.inUseCount ?? 0) > 0}
            onClick={() => deleteTarget && remove.mutate(deleteTarget.id)}
          >
            Delete
          </Button>
        </DialogActions>
      </Dialog>
    </Stack>
  );
}
