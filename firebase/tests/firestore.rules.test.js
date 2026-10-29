// Testes das regras do Firestore no Emulator (dados fictícios).
import { readFileSync } from 'node:fs';
import { afterAll, beforeAll, beforeEach, describe, it } from 'vitest';
import {
  assertFails,
  assertSucceeds,
  initializeTestEnvironment,
} from '@firebase/rules-unit-testing';
import { collection, doc, getDoc, getDocs, query, setDoc, updateDoc, where } from 'firebase/firestore';

let env;

beforeAll(async () => {
  env = await initializeTestEnvironment({
    projectId: 'demo-lucena-drive',
    firestore: { rules: readFileSync('firestore.rules', 'utf8') },
  });
});

afterAll(async () => env?.cleanup());

beforeEach(async () => {
  await env.clearFirestore();
  await env.withSecurityRulesDisabled(async (ctx) => {
    const db = ctx.firestore();
    await setDoc(doc(db, 'users/pax1'), { id: 'pax1', roles: ['PASSENGER'], cpfMasked: '***.982.247-**' });
    await setDoc(doc(db, 'userPrivate/pax1'), { cpfEncrypted: 'v1:...' });
    await setDoc(doc(db, 'rides/r1'), { passengerId: 'pax1', driverId: 'drv1', participantIds: ['pax1', 'drv1'], status: 'ACCEPTED' });
    await setDoc(doc(db, 'rides/r2'), { passengerId: 'pax2', participantIds: ['pax2'], status: 'SEARCHING_DRIVER' });
    await setDoc(doc(db, 'rideChats/r1'), { participantIds: ['pax1', 'drv1'], status: 'OPEN' });
    await setDoc(doc(db, 'rideChats/r1/messages/m1'), { senderId: 'drv1', text: 'Chegando', createdAt: 1 });
    await setDoc(doc(db, 'rideChats/r9'), { participantIds: ['pax1', 'drv1'], status: 'CLOSED' });
    await setDoc(doc(db, 'rideChats/r9/messages/m1'), { senderId: 'drv1', text: 'antiga', createdAt: 1 });
    await setDoc(doc(db, 'rideOffers/r2_drv1'), { driverId: 'drv1', rideId: 'r2', status: 'PENDING' });
    await setDoc(doc(db, 'rideOffers/r2_drv2'), { driverId: 'drv2', rideId: 'r2', status: 'PENDING' });
    await setDoc(doc(db, 'notifications/n1'), { userId: 'pax1', title: 'Olá' });
    await setDoc(doc(db, 'driverLocations/drv1'), { lat: -7, lng: -35 });
    await setDoc(doc(db, 'ledgerEntries/e1'), { amountCents: 100 });
    await setDoc(doc(db, 'adminAuditLogs/a1'), { action: 'X' });
  });
});

const as = (uid) => env.authenticatedContext(uid).firestore();

describe('perfil e dados pessoais', () => {
  it('usuário lê o próprio perfil e não o de outros', async () => {
    await assertSucceeds(getDoc(doc(as('pax1'), 'users/pax1')));
    await assertFails(getDoc(doc(as('pax2'), 'users/pax1')));
    await assertFails(getDoc(doc(env.unauthenticatedContext().firestore(), 'users/pax1')));
  });

  it('dados privados (CPF) nunca são legíveis pelo cliente', async () => {
    await assertFails(getDoc(doc(as('pax1'), 'userPrivate/pax1')));
  });

  it('cliente não escreve nada, nem no próprio perfil (sem auto-promoção a ADMIN)', async () => {
    await assertFails(updateDoc(doc(as('pax1'), 'users/pax1'), { roles: ['OWNER'] }));
    await assertFails(setDoc(doc(as('pax1'), 'users/novo'), { roles: ['ADMIN'] }));
  });
});

describe('corridas e ofertas', () => {
  it('somente participantes leem a corrida', async () => {
    await assertSucceeds(getDoc(doc(as('pax1'), 'rides/r1')));
    await assertSucceeds(getDoc(doc(as('drv1'), 'rides/r1')));
    await assertFails(getDoc(doc(as('drv2'), 'rides/r1')));
    await assertFails(getDoc(doc(as('pax1'), 'rides/r2')));
  });

  it('cliente não altera status nem preço', async () => {
    await assertFails(updateDoc(doc(as('pax1'), 'rides/r1'), { status: 'COMPLETED' }));
    await assertFails(updateDoc(doc(as('drv1'), 'rides/r1'), { 'fare.finalCents': 1 }));
  });

  it('motorista lista somente as próprias ofertas', async () => {
    await assertSucceeds(getDocs(query(collection(as('drv1'), 'rideOffers'), where('driverId', '==', 'drv1'))));
    await assertFails(getDocs(collection(as('drv1'), 'rideOffers')));
    await assertFails(getDoc(doc(as('drv1'), 'rideOffers/r2_drv2')));
  });

  it('localização operacional não é exposta ao cliente', async () => {
    await assertFails(getDoc(doc(as('drv1'), 'driverLocations/drv1')));
    await assertFails(setDoc(doc(as('drv1'), 'driverLocations/drv1'), { lat: 0, lng: 0 }));
  });
});

describe('chat', () => {
  it('participantes leem mensagens; terceiros não', async () => {
    await assertSucceeds(getDocs(collection(as('pax1'), 'rideChats/r1/messages')));
    await assertSucceeds(getDocs(collection(as('drv1'), 'rideChats/r1/messages')));
    await assertFails(getDocs(collection(as('pax2'), 'rideChats/r1/messages')));
  });

  it('ninguém escreve mensagens diretamente (envio somente pela API)', async () => {
    await assertFails(setDoc(doc(as('pax1'), 'rideChats/r1/messages/fake'), { senderId: 'drv1', text: 'falsificada' }));
  });

  it('conversa encerrada não é mais legível pelo cliente', async () => {
    await assertFails(getDocs(collection(as('pax1'), 'rideChats/r9/messages')));
  });
});

describe('notificações e coleções internas', () => {
  it('somente o destinatário lê suas notificações', async () => {
    await assertSucceeds(getDocs(query(collection(as('pax1'), 'notifications'), where('userId', '==', 'pax1'))));
    await assertFails(getDocs(query(collection(as('pax2'), 'notifications'), where('userId', '==', 'pax1'))));
  });

  it('coleções financeiras e de auditoria são negadas', async () => {
    await assertFails(getDoc(doc(as('pax1'), 'ledgerEntries/e1')));
    await assertFails(getDoc(doc(as('pax1'), 'adminAuditLogs/a1')));
    await assertFails(setDoc(doc(as('pax1'), 'coupons/GRATIS'), { percentBp: 10000 }));
  });

  it('links de compartilhamento, conversas arquivadas e travas internas são negados', async () => {
    await assertFails(getDoc(doc(as('pax1'), 'rideShares/s1')));
    await assertFails(getDoc(doc(env.unauthenticatedContext().firestore(), 'rideShares/s1')));
    await assertFails(getDocs(collection(as('drv1'), 'rideChatArchives')));
    await assertFails(setDoc(doc(as('pax1'), 'schedulerLocks/dispatch'), { owner: 'x' }));
  });
});
