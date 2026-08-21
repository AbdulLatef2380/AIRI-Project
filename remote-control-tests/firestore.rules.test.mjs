import fs from 'node:fs';
import { initializeTestEnvironment, assertFails, assertSucceeds } from '@firebase/rules-unit-testing';
import { doc, setDoc, updateDoc, deleteDoc, Timestamp, serverTimestamp } from 'firebase/firestore';

const rules = fs.readFileSync(new URL('../firestore.rules', import.meta.url), 'utf8');
const projectId = 'demo-airi-remote-control';
const now = Date.now();
const activeSession = {
  ownerId: 'alice',
  desktopDeviceId: 'desktop-1',
  revoked: false,
  expiresAt: Timestamp.fromMillis(now + 5 * 60_000)
};

const validDevice = (deviceId, platform, status = 'PENDING') => ({
  deviceId,
  ownerId: 'alice',
  displayName: platform === 'DESKTOP' ? 'AIRI Desktop' : 'AIRI Android',
  platform,
  createdAt: serverTimestamp(),
  lastSeenAt: serverTimestamp(),
  status,
  capabilities: ['REQUEST_STATUS', 'SYNC_STATE']
});

const validCommand = (overrides = {}) => ({
  commandId: 'command-1',
  ownerId: 'alice',
  desktopDeviceId: 'desktop-1',
  controllerDeviceId: 'android-1',
  sessionId: 'session-1',
  commandType: 'REQUEST_STATUS',
  payload: {},
  sequence: 1,
  createdAt: serverTimestamp(),
  expiresAt: Timestamp.fromMillis(now + 5 * 60_000),
  correlationId: 'correlation-1',
  ...overrides
});

const environment = await initializeTestEnvironment({ projectId, firestore: { rules } });

async function verify(name, assertion) {
  await assertion;
  console.log(`verified: ${name}`);
}

async function seed() {
  await environment.clearFirestore();
  await environment.withSecurityRulesDisabled(async (context) => {
    const db = context.firestore();
    await setDoc(doc(db, 'users/alice/devices/desktop-1'), {
      ...validDevice('desktop-1', 'DESKTOP', 'PAIRED'),
      createdAt: Timestamp.fromMillis(now),
      lastSeenAt: Timestamp.fromMillis(now)
    });
    await setDoc(doc(db, 'users/alice/devices/android-1'), {
      ...validDevice('android-1', 'ANDROID', 'PAIRED'),
      createdAt: Timestamp.fromMillis(now),
      lastSeenAt: Timestamp.fromMillis(now)
    });
    await setDoc(doc(db, 'users/alice/devices/desktop-1/sessions/session-1'), activeSession);
  });
}

try {
  await seed();
  const alice = environment.authenticatedContext('alice').firestore();
  const bob = environment.authenticatedContext('bob').firestore();
  const anonymous = environment.unauthenticatedContext().firestore();

  await verify('unauthenticated device creation is denied', assertFails(setDoc(doc(anonymous, 'users/alice/devices/desktop-2'), validDevice('desktop-2', 'DESKTOP'))));
  await verify('owner Android pending-device creation is allowed', assertSucceeds(setDoc(doc(alice, 'users/alice/devices/android-2'), validDevice('android-2', 'ANDROID'))));
  await verify('owner desktop-device creation is denied', assertFails(setDoc(doc(alice, 'users/alice/devices/desktop-2'), validDevice('desktop-2', 'DESKTOP', 'PENDING'))));
  await verify('owner paired-device creation is denied', assertFails(setDoc(doc(alice, 'users/alice/devices/android-3'), validDevice('android-3', 'ANDROID', 'PAIRED'))));
  await verify('other user device creation is denied', assertFails(setDoc(doc(bob, 'users/alice/devices/desktop-3'), validDevice('desktop-3', 'DESKTOP'))));

  await verify('active-session allowlisted command is allowed', assertSucceeds(setDoc(doc(alice, 'users/alice/devices/desktop-1/commands/command-1'), validCommand())));
  await verify('unknown command is denied', assertFails(setDoc(doc(alice, 'users/alice/devices/desktop-1/commands/command-2'), validCommand({ commandId: 'command-2', commandType: 'UNKNOWN' }))));
  await verify('oversized text payload is denied', assertFails(setDoc(doc(alice, 'users/alice/devices/desktop-1/commands/command-3'), validCommand({ commandId: 'command-3', commandType: 'SUBMIT_TEXT_REQUEST', payload: { text: 'x'.repeat(8_001) } }))));
  await verify('command update is denied', assertFails(updateDoc(doc(alice, 'users/alice/devices/desktop-1/commands/command-1'), { sequence: 2 })));
  await verify('command deletion is denied', assertFails(deleteDoc(doc(alice, 'users/alice/devices/desktop-1/commands/command-1'))));

  await environment.withSecurityRulesDisabled(async (context) => {
    await setDoc(doc(context.firestore(), 'users/alice/devices/desktop-1/sessions/expired-session'), {
      ...activeSession,
      expiresAt: Timestamp.fromMillis(now - 1)
    });
  });
  await verify('expired session command is denied', assertFails(setDoc(doc(alice, 'users/alice/devices/desktop-1/commands/command-4'), validCommand({ commandId: 'command-4', sessionId: 'expired-session' }))));
  await verify('pending controller command is denied', assertFails(setDoc(doc(alice, 'users/alice/devices/desktop-1/commands/command-5'), validCommand({ commandId: 'command-5', controllerDeviceId: 'android-2' }))));

  console.log('Firestore remote-control rules tests passed.');
} finally {
  await environment.cleanup();
}
