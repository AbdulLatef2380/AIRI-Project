'use strict';

// Report 3, S1: Firestore rules forbid clients from writing
// users/{uid}/devices/{desktopId}/sessions/{sid}, so remote-control sessions need a
// trusted writer. These callables are that writer. They are NOT deployed by this
// repository; deploy with `firebase deploy --only functions` after review.

const { onCall, HttpsError } = require('firebase-functions/v2/https');
const { onDocumentCreated } = require('firebase-functions/v2/firestore');
const { defineString } = require('firebase-functions/params');
const { google } = require('googleapis');
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

// ─────────────────────────────────────────────────────────────────────────────
// B2: server-side verification of Google Play subscriptions.
//
// The app files users/{uid}/purchaseClaims/{id} (status 'pending'). This trigger asks the
// Google Play Developer API whether the purchaseToken is a real, active subscription for OUR
// package and product, binds the token to ONE account, and writes the verdict back. Clients
// cannot write `status` (see firestore.rules), so the verdict is trustworthy.
//
// One-time setup (not done by this repository):
//   1. Play Console → Users & permissions: invite this function's runtime service account
//      and grant "View financial data" (Android Publisher API access).
//   2. Enable the "Google Play Android Developer API" in the Cloud project.
//   3. firebase functions:config / params: set PLAY_PACKAGE_NAME (default com.airi.assistant).
//   4. npm install && firebase deploy --only functions,firestore:rules
// ─────────────────────────────────────────────────────────────────────────────

const PLAY_PACKAGE_NAME = defineString('PLAY_PACKAGE_NAME', { default: 'com.airi.assistant' });
const ALLOWED_PLAY_PRODUCTS = new Set(['premium_monthly', 'pro_annual']);
const ACTIVE_STATES = new Set(['SUBSCRIPTION_STATE_ACTIVE', 'SUBSCRIPTION_STATE_IN_GRACE_PERIOD']);

async function fetchPlaySubscription(packageName, token) {
  const auth = new google.auth.GoogleAuth({
    scopes: ['https://www.googleapis.com/auth/androidpublisher'],
  });
  const publisher = google.androidpublisher({ version: 'v3', auth });
  const res = await publisher.purchases.subscriptionsv2.get({ packageName, token });
  return res.data;
}

exports.verifyPlayPurchaseClaim = onDocumentCreated(
  'users/{uid}/purchaseClaims/{claimId}',
  async (event) => {
    const snap = event.data;
    if (!snap) return;
    const { uid } = event.params;
    const claim = snap.data() || {};
    const reject = (reason) => snap.ref.update({
      status: 'rejected', reason, decidedAt: FieldValue.serverTimestamp(),
    });

    const packageName = PLAY_PACKAGE_NAME.value();
    if (claim.status !== 'pending') return;
    if (claim.packageName !== packageName) return reject('Wrong application.');
    if (!ALLOWED_PLAY_PRODUCTS.has(claim.productId)) return reject('Unknown product.');
    if (typeof claim.purchaseToken !== 'string' || claim.purchaseToken.length === 0) {
      return reject('Missing purchase token.');
    }

    let sub;
    try {
      sub = await fetchPlaySubscription(packageName, claim.purchaseToken);
    } catch (err) {
      // Google says the token is unknown/invalid, or our API access is misconfigured.
      const code = err && (err.code || (err.response && err.response.status));
      console.warn('Play verification failed', { uid, code });
      return reject(code === 400 || code === 404 || code === 410
        ? 'Purchase not found.'
        : 'Verification service unavailable.');
    }

    const line = (sub.lineItems || []).find((li) => li.productId === claim.productId);
    if (!line) return reject('Product mismatch.');
    if (!ACTIVE_STATES.has(sub.subscriptionState)) return reject('Subscription is not active.');
    const expiresAtMillis = Date.parse(line.expiryTime || '');
    if (!Number.isFinite(expiresAtMillis) || expiresAtMillis <= Date.now()) return reject('Subscription expired.');

    // Bind the token to ONE account: a shared/leaked token cannot unlock other accounts.
    const tokenHash = crypto.createHash('sha256').update(claim.purchaseToken).digest('hex');
    const tokenRef = db.doc(`playPurchaseTokens/${tokenHash}`);
    const bound = await db.runTransaction(async (tx) => {
      const t = await tx.get(tokenRef);
      if (t.exists && t.data().uid !== uid) return false;
      tx.set(tokenRef, { uid, productId: claim.productId, boundAt: t.exists ? t.data().boundAt : Timestamp.now() });
      return true;
    });
    if (!bound) return reject('This purchase belongs to another account.');

    await snap.ref.update({
      status: 'verified',
      expiresAtMillis,
      verifiedAtMillis: Date.now(),
      decidedAt: FieldValue.serverTimestamp(),
      purchaseToken: FieldValue.delete(), // do not keep the token on the client-readable claim
    });
  }
);

// P1/P2: trusted deletion of cloud data. Clients cannot delete users/{uid}/memory (rules:
// `delete: if false`) nor the rest of the account's documents, so they file a request and this
// function does the work with the Admin SDK, then reports the verdict on the request document.
//   kind 'memory'  -> users/{uid}/memory/** and sync_meta/memory
//   kind 'account' -> users/{uid} and every subcollection (devices, sessions, commands, events,
//                     memory, profile, sync_meta, task_continuity, purchaseClaims, ...)
// Play purchase-token bindings are kept (fraud protection) but detached from the account.
// The request document itself keeps only the uid, kind and verdict as a minimal deletion record.
exports.processAccountDeletionRequest = onDocumentCreated(
  'accountDeletionRequests/{requestId}',
  async (event) => {
    const snap = event.data;
    if (!snap) return;
    const req = snap.data() || {};
    if (req.status !== 'pending') return;
    const finish = (status, reason) => snap.ref.update({
      status, reason: reason || FieldValue.delete(), decidedAt: FieldValue.serverTimestamp(),
    });

    const uid = req.uid;
    if (typeof uid !== 'string' || uid.length === 0 || uid.length > 128 || uid.includes('/')) {
      return finish('failed', 'Invalid request.');
    }
    if (req.kind !== 'memory' && req.kind !== 'account') return finish('failed', 'Unknown request kind.');

    try {
      if (req.kind === 'memory') {
        await db.recursiveDelete(db.collection(`users/${uid}/memory`));
        await db.doc(`users/${uid}/sync_meta/memory`).delete();
      } else {
        await db.recursiveDelete(db.doc(`users/${uid}`));
        const tokens = await db.collection('playPurchaseTokens').where('uid', '==', uid).get();
        await Promise.all(tokens.docs.map((d) => d.ref.update({ uid: 'deleted-account' })));
      }
    } catch (err) {
      console.error('Deletion failed', { kind: req.kind, code: err && err.code });
      return finish('failed', 'Deletion did not finish. Please try again.');
    }
    return finish('completed');
  }
);
