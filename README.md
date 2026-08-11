# Kr-cart-yct

Bot que extrae **toda tu cartera de Kraken** (incluyendo Earn / Staking) y actualiza automáticamente un Google Sheet dentro de la carpeta **mi inversiones** en Google Drive.

## Características

- Extrae saldos spot + activos en Earn/Staking
- Organiza la información de forma clara
- Actualiza un Google Sheet en la carpeta `mi inversiones`

## Requisitos

- Python 3.10+
- Cuenta de Kraken con API Key (solo lectura)
- Acceso a Google Drive

## Instalación

```bash
pip install -r requirements.txt
```

## Configuración

1. Copia el archivo de ejemplo:
```bash
cp .env.example .env
```

2. Edita el archivo `.env` y agrega tus claves:

```env
KRAKEN_API_KEY=tu_api_key_aqui
KRAKEN_API_SECRET=tu_private_key_aqui
```

## Uso

```bash
python main.py
```

## Estructura del proyecto

- `main.py` → Punto de entrada del bot
- `kraken_client.py` → Conexión y extracción de datos de Kraken
- `requirements.txt` → Dependencias
- `.env` → Tus claves (nunca se sube a GitHub)
