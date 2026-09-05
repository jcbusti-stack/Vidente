const fs = require('fs');
const path = require('path');

// Almacenamiento simple en un archivo JSON: suficiente para un solo
// dispositivo/servidor gratuito. Si más adelante se agrega pago, esto debe
// reemplazarse por una base de datos real (ver notas en README del backend
// o el mensaje que Claude le dio al usuario), porque el disco de los planes
// gratuitos de Render/Railway no está garantizado entre despliegues.
const DATA_FILE = path.join(__dirname, 'data', 'usage.json');

let usage = loadFromDisk();

function loadFromDisk() {
  try {
    return JSON.parse(fs.readFileSync(DATA_FILE, 'utf8'));
  } catch (error) {
    return {};
  }
}

function saveToDisk() {
  try {
    fs.mkdirSync(path.dirname(DATA_FILE), { recursive: true });
    fs.writeFileSync(DATA_FILE, JSON.stringify(usage), 'utf8');
  } catch (error) {
    console.error('No se pudo guardar el archivo de uso:', error);
  }
}

function currentYearMonth() {
  const now = new Date();
  return `${now.getUTCFullYear()}-${String(now.getUTCMonth() + 1).padStart(2, '0')}`;
}

function getUsage(deviceId) {
  const yearMonth = currentYearMonth();
  const entry = usage[deviceId];
  if (!entry || entry.yearMonth !== yearMonth) {
    return { yearMonth, count: 0 };
  }
  return entry;
}

function incrementUsage(deviceId) {
  const current = getUsage(deviceId);
  const updated = { yearMonth: current.yearMonth, count: current.count + 1 };
  usage[deviceId] = updated;
  saveToDisk();
  return updated;
}

module.exports = { getUsage, incrementUsage };
