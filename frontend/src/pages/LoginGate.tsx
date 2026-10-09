import { useEffect } from 'react';
import { setBasicAuth } from '../api/client';

/** Applies default admin basic auth for local MVP convenience. */
export function useDefaultAuth() {
  useEffect(() => {
    if (!sessionStorage.getItem('basicAuth')) {
      setBasicAuth('admin', 'admin');
    }
  }, []);
}
