const express = require('express');
const cors = require('cors');
const rateLimit = require('express-rate-limit');
const { getUsage, incrementUsage } = require('./usageStore');

const app = express();
app.use(cors());
app.use(express.json({ limit: '200kb' }));

const PORT = process.env.PORT || 3000;
const GEMINI_API_KEY = process.env.GEMINI_API_KEY;
const GEMINI_MODEL = process.env.GEMINI_MODEL || 'gemini-2.5-flash-lite';
const BACKEND_ACCESS_KEY = process.env.VIDENTE_BACKEND_KEY;
const FREE_MONTHLY_LIMIT = parseInt(process.env.FREE_MONTHLY_LIMIT || '25', 10);

if (!GEMINI_API_KEY) {
  console.error('Falta la variable de entorno GEMINI_API_KEY.');
}

app.get('/health', (_req, res) => {
  res.json({ ok: true });
});

const askLimiter = rateLimit({ windowMs: 60 * 1000, max: 20 });

app.post('/ask', askLimiter, async (req, res) => {
  if (BACKEND_ACCESS_KEY) {
    const providedKey = req.get('x-vidente-key');
    if (providedKey !== BACKEND_ACCESS_KEY) {
      return res.status(401).json({ error: 'No autorizado.' });
    }
  }

  const { question, screenSummary, deviceId } = req.body || {};
  if (typeof question !== 'string' || !question.trim()) {
    return res.status(400).json({ error: 'Falta la pregunta (question).' });
  }
  if (typeof deviceId !== 'string' || !deviceId.trim()) {
    return res.status(400).json({ error: 'Falta el identificador del dispositivo (deviceId).' });
  }
  if (!GEMINI_API_KEY) {
    return res.status(500).json({ error: 'El servidor no tiene configurada la clave de Gemini.' });
  }

  const usageBefore = getUsage(deviceId);
  if (usageBefore.count >= FREE_MONTHLY_LIMIT) {
    return res.status(402).json({
      error: 'limit_reached',
      message: 'Se acabaron tus preguntas gratis de este mes. Vuelven a estar disponibles el próximo mes.',
      limit: FREE_MONTHLY_LIMIT,
      used: usageBefore.count
    });
  }

  try {
    const answer = await askGemini(question, typeof screenSummary === 'string' ? screenSummary : '');
    const usageAfter = incrementUsage(deviceId);
    res.json({
      answer,
      usage: {
        used: usageAfter.count,
        limit: FREE_MONTHLY_LIMIT,
        remaining: Math.max(FREE_MONTHLY_LIMIT - usageAfter.count, 0)
      }
    });
  } catch (error) {
    console.error('Error llamando a Gemini:', error);
    res.status(502).json({ error: 'No se pudo obtener respuesta de Gemini.' });
  }
});

async function askGemini(question, screenSummary) {
  const url = `https://generativelanguage.googleapis.com/v1beta/models/${GEMINI_MODEL}:generateContent?key=${GEMINI_API_KEY}`;

  const body = {
    systemInstruction: {
      parts: [
        {
          text:
            'Eres el asistente de voz de Vidente, una app de lectura de pantalla para personas ' +
            'ciegas o con baja visión. Te dan un resumen de los elementos visibles en la pantalla ' +
            'actual de un teléfono Android y una pregunta del usuario. Responde en español, de ' +
            'forma breve y directa (2 o 3 frases como máximo), resumiendo solo lo relevante para ' +
            'la pregunta. No leas la pantalla completa ni describas cosas que no se preguntaron.'
        }
      ]
    },
    contents: [
      {
        role: 'user',
        parts: [
          {
            text: `Resumen de la pantalla:\n${screenSummary}\n\nPregunta del usuario: ${question}`
          }
        ]
      }
    ]
  };

  const response = await fetch(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body)
  });

  if (!response.ok) {
    const errorText = await response.text();
    throw new Error(`Gemini respondió ${response.status}: ${errorText}`);
  }

  const data = await response.json();
  const text = data?.candidates?.[0]?.content?.parts?.[0]?.text;
  if (!text) {
    throw new Error('Respuesta de Gemini sin texto.');
  }
  return text.trim();
}

app.listen(PORT, () => {
  console.log(`Vidente backend escuchando en el puerto ${PORT}`);
});
