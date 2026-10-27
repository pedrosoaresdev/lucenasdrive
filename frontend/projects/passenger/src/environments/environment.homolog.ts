import { LucenaConfig } from '@lucena/shared';
import { HOMOLOG_FCM_VAPID_KEY, HOMOLOG_FIREBASE, HOMOLOG_MAPS_BROWSER_KEY } from '../../../homolog.firebase';

/** Homologação: portais locais + backend local ligados ao Firebase real "lucenasdrive" (ver docs/HOMOLOGACAO.md). */
export const environment: LucenaConfig = {
  portal: 'passenger',
  apiBaseUrl: 'http://localhost:8080',
  firebase: HOMOLOG_FIREBASE,
  useEmulators: false,
  mapsBrowserKey: HOMOLOG_MAPS_BROWSER_KEY,
  fcmVapidKey: HOMOLOG_FCM_VAPID_KEY,
};
