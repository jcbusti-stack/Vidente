import os
from dotenv import load_dotenv
from kraken_client import KrakenClient
import json
from datetime import datetime

load_dotenv()


def main():
    api_key = os.getenv("KRAKEN_API_KEY")
    api_secret = os.getenv("KRAKEN_API_SECRET")

    if not api_key or not api_secret:
        print("Error: Faltan las claves de Kraken en el archivo .env")
        print("Copia .env.example a .env y agrega tus claves.")
        return

    print("Conectando a Kraken...")
    client = KrakenClient(api_key, api_secret)

    try:
        portfolio = client.get_full_portfolio()

        print("\n=== CARTERA COMPLETA DE KRAKEN ===")
        print(f"Fecha: {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}\n")

        print("--- Saldos ---")
        for asset, amount in sorted(portfolio["balances"].items()):
            print(f"{asset:25} : {amount:,.8f}")

        if portfolio.get("earn"):
            print("\n--- Detalle Earn/Staking ---")
            print(json.dumps(portfolio["earn"], indent=2))

        print("\n✅ Datos extraídos correctamente.")
        print("Próximo paso: conectar con Google Drive (carpeta 'mi inversiones')")

    except Exception as e:
        print(f"\n❌ Error: {e}")


if __name__ == "__main__":
    main()
