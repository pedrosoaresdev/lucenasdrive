/**
 * Homologação no projeto Firebase "lucenasdrive" (dados de TESTE, nunca de clientes reais).
 *
 * Valores do app da Web "lucena-drive-web" (Console Firebase › Configurações do projeto › Seus apps). São identificadores PÚBLICOS: a proteção vem das regras do Firestore/Storage,
 * da validação no servidor e, depois, do App Check. NUNCA coloque aqui a conta de serviço (JSON).
 */
export const HOMOLOG_FIREBASE = {
  apiKey: 'AIzaSyCNS4vD5HgtDNOaH2VR9cuT_ElFC5hDrX4',
  authDomain: 'lucenasdrive.firebaseapp.com',
  projectId: 'lucenasdrive',
  storageBucket: 'lucenasdrive.firebasestorage.app',
  appId: '1:572738150537:web:604560ab8a3d880c27a658',
  messagingSenderId: '572738150537',
};

/** Console Firebase › Cloud Messaging › Certificados push da Web (chave pública). Vazio = push desativado. */
export const HOMOLOG_FCM_VAPID_KEY = '';

/** Chave de NAVEGADOR do Google Maps restrita a http://localhost:* e http://127.0.0.1:*. Vazio = sem mapa. */
export const HOMOLOG_MAPS_BROWSER_KEY = '';
