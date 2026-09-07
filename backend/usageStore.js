const { Pool } = require('pg');

// Almacenamiento persistente en PostgreSQL (Neon, nivel gratis).
//
// Sustituye al antiguo archivo data/usage.json, que vivía en el disco de la
// instancia de Render y se perdía en cada despliegue, con lo que el límite
// gratis por dispositivo no era fiable.
//
// Se usa un unico Pool reutilizado durante toda la vida del proceso (no una
// conexion nueva por peticion) y con pocas conexiones, para no agotar el
// limite del nivel gratis. La cadena apunta al endpoint "-pooler" de Neon,
// que ademas agrupa conexiones del lado del servidor.
const connectionString = process.env.DATABASE_URL;

if (!connectionString) {
  console.error('Falta la variable de entorno DATABASE_URL (cadena de conexión de Neon).');
}

const pool = new Pool({
  connectionString,
  max: 3,
  idleTimeoutMillis: 30000,
  connectionTimeoutMillis: 10000,
  // Neon exige TLS. Sus certificados son válidos; se deja rejectUnauthorized
  // en false por robustez entre versiones de pg y porque esta base solo
  // guarda contadores de uso, nunca datos sensibles.
  ssl: { rejectUnauthorized: false }
});

pool.on('error', (err) => {
  console.error('Error inesperado en el pool de PostgreSQL:', err);
});

let initPromise = null;

/**
 * Crea las tablas si no existen y siembra los contadores globales.
 * Idempotente: se puede llamar en cada arranque.
 */
function initDb() {
  if (!initPromise) {
    initPromise = (async () => {
      await pool.query(`
        CREATE TABLE IF NOT EXISTS device_usage (
          device_id  TEXT PRIMARY KEY,
          year_month TEXT NOT NULL,
          count      INTEGER NOT NULL DEFAULT 0,
          updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
        );
      `);
      await pool.query(`
        CREATE TABLE IF NOT EXISTS gemini_requests (
          id            BIGSERIAL PRIMARY KEY,
          ts            TIMESTAMPTZ NOT NULL DEFAULT now(),
          device_id     TEXT,
          success       BOOLEAN NOT NULL,
          input_tokens  INTEGER,
          output_tokens INTEGER,
          total_tokens  INTEGER,
          model         TEXT,
          error         TEXT
        );
      `);
      await pool.query('CREATE INDEX IF NOT EXISTS gemini_requests_ts_idx ON gemini_requests (ts);');
      await pool.query(`
        CREATE TABLE IF NOT EXISTS counters (
          name  TEXT PRIMARY KEY,
          value BIGINT NOT NULL DEFAULT 0
        );
      `);
      await pool.query(`
        INSERT INTO counters (name, value) VALUES
          ('gemini_calls_total', 0),
          ('gemini_calls_ok', 0),
          ('gemini_calls_failed', 0)
        ON CONFLICT (name) DO NOTHING;
      `);
    })().catch((err) => {
      // Si falla, se reintenta en la próxima llamada.
      initPromise = null;
      throw err;
    });
  }
  return initPromise;
}

function currentYearMonth() {
  const now = new Date();
  return `${now.getUTCFullYear()}-${String(now.getUTCMonth() + 1).padStart(2, '0')}`;
}

/**
 * Uso del mes en curso para un dispositivo. Si la fila no existe o es de un
 * mes anterior, cuenta 0. Ante un fallo de base de datos devuelve 0 (no
 * bloquea el servicio; solo deja de aplicarse el límite mientras dure el
 * fallo) y lo registra en consola.
 */
async function getDeviceUsage(deviceId) {
  const yearMonth = currentYearMonth();
  try {
    await initDb();
    const { rows } = await pool.query(
      'SELECT year_month, count FROM device_usage WHERE device_id = $1',
      [deviceId]
    );
    const row = rows[0];
    if (!row || row.year_month !== yearMonth) {
      return { yearMonth, count: 0 };
    }
    return { yearMonth, count: row.count };
  } catch (err) {
    console.error('No se pudo leer device_usage:', err);
    return { yearMonth, count: 0 };
  }
}

/**
 * Suma 1 al contador del mes en curso para un dispositivo (creando o
 * reiniciando la fila si es de otro mes) y devuelve el nuevo valor.
 */
async function incrementDeviceUsage(deviceId) {
  const yearMonth = currentYearMonth();
  try {
    await initDb();
    const { rows } = await pool.query(
      `INSERT INTO device_usage (device_id, year_month, count, updated_at)
         VALUES ($1, $2, 1, now())
       ON CONFLICT (device_id) DO UPDATE SET
         count = CASE
                   WHEN device_usage.year_month = EXCLUDED.year_month
                   THEN device_usage.count + 1
                   ELSE 1
                 END,
         year_month = EXCLUDED.year_month,
         updated_at = now()
       RETURNING year_month, count`,
      [deviceId, yearMonth]
    );
    return { yearMonth: rows[0].year_month, count: rows[0].count };
  } catch (err) {
    console.error('No se pudo actualizar device_usage:', err);
    return { yearMonth, count: 0 };
  }
}

/**
 * Registra una llamada a Gemini: marca de tiempo, dispositivo, éxito o fallo,
 * tokens de entrada/salida (de usageMetadata) y modelo. Además incrementa los
 * contadores globales. Nunca lanza: un fallo de registro no debe tumbar la
 * respuesta al usuario.
 */
async function logGeminiRequest({
  deviceId = null,
  success,
  inputTokens = null,
  outputTokens = null,
  totalTokens = null,
  model = null,
  error = null
}) {
  try {
    await initDb();
    await pool.query(
      `INSERT INTO gemini_requests
         (device_id, success, input_tokens, output_tokens, total_tokens, model, error)
       VALUES ($1, $2, $3, $4, $5, $6, $7)`,
      [deviceId, success, inputTokens, outputTokens, totalTokens, model, error]
    );
    await pool.query(
      `UPDATE counters SET value = value + 1
         WHERE name IN ('gemini_calls_total', $1)`,
      [success ? 'gemini_calls_ok' : 'gemini_calls_failed']
    );
  } catch (err) {
    console.error('No se pudo registrar la llamada a Gemini:', err);
  }
}

module.exports = {
  initDb,
  getDeviceUsage,
  incrementDeviceUsage,
  logGeminiRequest
};
