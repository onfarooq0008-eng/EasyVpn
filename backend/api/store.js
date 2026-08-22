// Tiny file-backed store -- no database server to install or manage. Fine at
// this scale (the API is only called once per device on first launch, or
// again if you build a "server ever changes" retry, not per-connection).
const fs = require('fs');
const path = require('path');

const DATA_DIR = path.join(__dirname, 'data');
const STORE_PATH = path.join(DATA_DIR, 'store.json');

function load() {
  if (!fs.existsSync(STORE_PATH)) {
    return { registrations: {}, ipCounters: {} };
  }
  return JSON.parse(fs.readFileSync(STORE_PATH, 'utf8'));
}

function save(store) {
  fs.mkdirSync(DATA_DIR, { recursive: true });
  const tmpPath = `${STORE_PATH}.tmp`;
  fs.writeFileSync(tmpPath, JSON.stringify(store, null, 2));
  fs.renameSync(tmpPath, STORE_PATH); // atomic on the same filesystem
}

/** Registration key is scoped to (devicePublicKey, serverId) -- the same
 *  device gets a fresh, independent registration on each server it uses. */
function registrationKey(devicePublicKey, serverId) {
  return `${devicePublicKey}::${serverId}`;
}

function getRegistration(devicePublicKey, serverId) {
  const store = load();
  return store.registrations[registrationKey(devicePublicKey, serverId)] || null;
}

/** Returns the next free host number (2-254) for a server's /24 subnet and
 *  reserves it. Simple incrementing allocator -- no reuse of freed addresses
 *  yet, which is fine up to ~250 registrations per server; worth upgrading to
 *  a free-list if you outgrow that on any single VPS. */
function allocateAddress(serverId) {
  const store = load();
  const next = store.ipCounters[serverId] || 2;
  if (next > 254) {
    throw new Error(`Server ${serverId} has run out of addresses in its /24 subnet`);
  }
  store.ipCounters[serverId] = next + 1;
  save(store);
  return next;
}

function saveRegistration(devicePublicKey, serverId, assignedAddress) {
  const store = load();
  store.registrations[registrationKey(devicePublicKey, serverId)] = {
    serverId,
    assignedAddress,
    createdAt: new Date().toISOString(),
  };
  save(store);
}

/** Counts how many devices are registered on each server, plus the total. */
function registrationCounts() {
  const store = load();
  const byServer = {};
  let total = 0;
  for (const reg of Object.values(store.registrations)) {
    byServer[reg.serverId] = (byServer[reg.serverId] || 0) + 1;
    total += 1;
  }
  return { byServer, total };
}

module.exports = { getRegistration, allocateAddress, saveRegistration, registrationCounts };
