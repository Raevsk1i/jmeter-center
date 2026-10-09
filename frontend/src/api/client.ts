const API_BASE = import.meta.env.VITE_API_BASE ?? 'http://localhost:8080';

function authHeader(): HeadersInit {
  const token = sessionStorage.getItem('basicAuth');
  return token ? { Authorization: `Basic ${token}` } : {};
}

export function setBasicAuth(username: string, password: string) {
  sessionStorage.setItem('basicAuth', btoa(`${username}:${password}`));
}

export function clearAuth() {
  sessionStorage.removeItem('basicAuth');
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const res = await fetch(`${API_BASE}${path}`, {
    ...init,
    headers: {
      'Content-Type': 'application/json',
      ...authHeader(),
      ...(init?.headers ?? {}),
    },
    credentials: 'include',
  });
  if (!res.ok) {
    const body = await res.json().catch(() => ({}));
    throw new Error(body.error || res.statusText);
  }
  if (res.status === 204) return undefined as T;
  return res.json();
}

export const api = {
  get: <T>(path: string) => request<T>(path),
  post: <T>(path: string, body?: unknown) =>
    request<T>(path, { method: 'POST', body: body ? JSON.stringify(body) : undefined }),
  put: <T>(path: string, body?: unknown) =>
    request<T>(path, { method: 'PUT', body: body ? JSON.stringify(body) : undefined }),
  del: <T>(path: string) => request<T>(path, { method: 'DELETE' }),
};

export type Generator = {
  id: string;
  name: string;
  hostname: string;
  sshPort: number;
  sshUser: string;
  status: string;
  sshCredentialId?: string;
  agentVersion?: string;
  javaVersion?: string;
  jmeterVersion?: string;
  cpuCores?: number;
  ramMb?: number;
  diskFreeMb?: number;
  cpuUsagePercent?: number;
  lastHeartbeatAt?: string;
  provisionStep?: string;
  provisionError?: string;
};

export type SshCredential = {
  id: string;
  name: string;
  createdAt: string;
  inUseCount: number;
};

export type TestRun = {
  id: string;
  testDefinitionId: string;
  status: string;
  commitHash?: string;
  configuration: Record<string, unknown>;
  masterGeneratorId?: string;
  startedAt?: string;
  finishedAt?: string;
  errorMessage?: string;
  createdBy?: string;
  createdAt: string;
};
