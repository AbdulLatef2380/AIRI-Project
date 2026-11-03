'use strict';

// Report 3, S1: Firestore rules forbid clients from writing
// users/{uid}/devices/{desktopId}/sessions/{sid}, so remote-control sessions need a
// trusted writer. These callables are that writer. They are NOT deployed by this
// repository; deploy with `firebase deploy --only functions` after review.

const { onCall, HttpsError } = require('firebase-functions/v2/https');
const { initializeApp } = require('firebase-admin/app');
const { getFirestore, Timestamp, FieldValue } = require('firebase-admin/firestore');
const crypto = require('node:crypto');

initializeApp();
const db = getFirestore();

const MAX_SESSION_SECONDS = 10 * 60; // matches the 10 minute command window in firestore.rules
const DEFAULT_SESSION_SECONDS = 10 * 60;

function requireString(value, name) {
  if (typeof value !== 'string' || value.length === 0 || value.length > 128 || value.includes('/')) {
    throw new HttpsError('invalid-argument', `${name} is invalid`);
  }
  return value;
}

async function pairedDevice(uid, deviceId, platform) {
  const snap = await db.doc(`users/${uid}/devices/${deviceId}`).get();
  const d = snap.exists ? snap.data() : null;
  return !!d && d.ownerId === uid && d.platform === platform && d.status === 'PAIRED';
}

// Creates a short-lived session binding one paired Android controller to one paired desktop.
exports.createRemoteSession = onCall({ enforceAppCheck: true }, async (request) => {
  const uid = request.auth && request.auth.uid;
  if (!uid) throw new HttpsError('unauthenticated', 'Sign in first');

  const desktopDeviceId = requireString(request.data && request.data.desktopDeviceId, 'desktopDeviceId');
  const controllerDeviceId = requireString(request.data && request.data.controllerDeviceId, 'controllerDeviceId');
  const asked = Number(request.data && request.data.ttlSeconds);
  const ttl = Number.isFinite(asked) && asked > 0
    ? Math.min(Math.floor(asked), MAX_SESSION_SECONDS)
    : DEFAULT_SESSION_SECONDS;

  const [desktopOk, controllerOk] = await Promise.all([
    pairedDevice(uid, desktopDeviceId, 'DESKTOP'),
    pairedDevice(uid, controllerDeviceId, 'ANDROID'),
  ]);
  if (!desktopOk || !controllerOk) {
    throw new HttpsError('failed-precondition', 'Both devices must be paired to this account');
  }

  const sessionId = crypto.randomUUID();
  const now = Timestamp.now();
  const expiresAt = Timestamp.fromMillis(now.toMillis() + ttl * 1000);
  // Fields read by activeSession() in firestore.rules: ownerId, desktopDeviceId, revoked, expiresAt.
  await db.doc(`users/${uid}/devices/${desktopDeviceId}/sessions/${sessionId}`).set({
    sessionId,
    ownerId: uid,
    desktopDeviceId,
    controllerDeviceId,
    revoked: false,
    createdAt: now,
    expiresAt,
  });
  return { sessionId, expiresAtMillis: expiresAt.toMillis() };
});

// Revokes a session. Only the owning account can do it.
exports.revokeRemoteSession = onCall({ enforceAppCheck: true }, async (request) => {
  const uid = request.auth && request.auth.uid;
  if (!uid) throw new HttpsError('unauthenticated', 'Sign in first');
  const desktopDeviceId = requireString(request.data && request.data.desktopDeviceId, 'desktopDeviceId');
  const sessionId = requireString(request.data && request.data.sessionId, 'sessionId');

  const ref = db.doc(`users/${uid}/devices/${desktopDeviceId}/sessions/${sessionId}`);
  const snap = await ref.get();
  if (!snap.exists || snap.data().ownerId !== uid) throw new HttpsError('not-found', 'Session not found');
  await ref.update({ revoked: true, revokedAt: FieldValue.serverTimestamp() });
  return { revoked: true };
});
