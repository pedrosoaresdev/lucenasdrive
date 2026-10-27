// Testes das regras do Storage no Emulator.
import { readFileSync } from 'node:fs';
import { afterAll, beforeAll, describe, it } from 'vitest';
import { assertFails, assertSucceeds, initializeTestEnvironment } from '@firebase/rules-unit-testing';
import { getBytes, ref, uploadBytes } from 'firebase/storage';

let env;
const small = new Uint8Array(1024);
const big = new Uint8Array(11 * 1024 * 1024);

beforeAll(async () => {
  env = await initializeTestEnvironment({
    projectId: 'demo-lucena-drive',
    storage: { rules: readFileSync('storage.rules', 'utf8') },
  });
});

afterAll(async () => env?.cleanup());

const st = (uid) => env.authenticatedContext(uid).storage();

describe('documentos de motoristas', () => {
  it('motorista envia o próprio documento PDF', async () => {
    await assertSucceeds(uploadBytes(ref(st('drv1'), 'driverDocuments/drv1/cnh.pdf'), small, { contentType: 'application/pdf' }));
  });

  it('não envia na pasta de outro motorista, nem tipo/tamanho inválido', async () => {
    await assertFails(uploadBytes(ref(st('drv1'), 'driverDocuments/drv2/cnh.pdf'), small, { contentType: 'application/pdf' }));
    await assertFails(uploadBytes(ref(st('drv1'), 'driverDocuments/drv1/x.html'), small, { contentType: 'text/html' }));
    await assertFails(uploadBytes(ref(st('drv1'), 'driverDocuments/drv1/big.pdf'), big, { contentType: 'application/pdf' }));
  });

  it('documentos não são legíveis pelo cliente (nem pelo dono)', async () => {
    await assertFails(getBytes(ref(st('drv1'), 'driverDocuments/drv1/cnh.pdf')));
    await assertFails(getBytes(ref(st('pax1'), 'driverDocuments/drv1/cnh.pdf')));
  });
});

describe('fotos e caminhos não previstos', () => {
  it('foto de perfil: dono envia imagem; autenticados leem', async () => {
    await assertSucceeds(uploadBytes(ref(st('pax1'), 'profilePhotos/pax1/foto.jpg'), small, { contentType: 'image/jpeg' }));
    await assertSucceeds(getBytes(ref(st('drv1'), 'profilePhotos/pax1/foto.jpg')));
    await assertFails(getBytes(ref(env.unauthenticatedContext().storage(), 'profilePhotos/pax1/foto.jpg')));
    await assertFails(uploadBytes(ref(st('pax1'), 'profilePhotos/pax1/doc.pdf'), small, { contentType: 'application/pdf' }));
  });

  it('caminhos fora das regras são negados', async () => {
    await assertFails(uploadBytes(ref(st('pax1'), 'public/qualquer.jpg'), small, { contentType: 'image/jpeg' }));
  });
});
