import { setBasicAuth } from '../api/client';

/** Applies default admin basic auth for local MVP convenience (sync so first queries are authenticated). */
export function useDefaultAuth() {
  if (typeof sessionStorage !== 'undefined' && !sessionStorage.getItem('basicAuth')) {
    setBasicAuth('admin', 'admin');
  }
}
