#!/usr/bin/env bash
# Compila el AAB de release de FinAI con el backend de verificación configurado.
#
# Uso:
#   ./scripts/build_release.sh          # lee scripts/.env (si existe)
#   ./scripts/build_release.sh --skip-check
#
# Requisitos:
#   - scripts/.env con las variables FINAI_BILLING_* (ver .env.example)
#   - Las credenciales de firma se leen de ~/.gradle/gradle.properties
#     (finai.keystore.*) o de las env vars FINAI_KEYSTORE_*.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_FILE="${ROOT}/scripts/.env"

if [[ -f "${ENV_FILE}" ]]; then
  echo "> Cargando ${ENV_FILE}"
  set -a
  # shellcheck disable=SC1090
  source "${ENV_FILE}"
  set +a
else
  echo "! No existe scripts/.env. Copia scripts/.env.example a scripts/.env y rellénalo."
  echo "  Continúo con las variables de entorno ya exportadas, si las hay."
fi

REQUIRED=(
  FINAI_BILLING_BACKEND_URL
  FINAI_BILLING_ENTITLEMENT_ISSUER
  FINAI_BILLING_ENTITLEMENT_KEY_ID
  FINAI_BILLING_ENTITLEMENT_PUBLIC_KEY_PEM
)

if [[ "${1:-}" != "--skip-check" ]]; then
  MISSING=()
  for var in "${REQUIRED[@]}"; do
    if [[ -z "${!var:-}" ]]; then
      MISSING+=("${var}")
    fi
  done
  if [[ ${#MISSING[@]} -gt 0 ]]; then
    echo "✗ Faltan variables obligatorias: ${MISSING[*]}"
    echo "  Rellena scripts/.env (plantilla en scripts/.env.example) o exporta las variables."
    exit 1
  fi
fi

echo "> Backend: ${FINAI_BILLING_BACKEND_URL}"
echo "> Compilando :app:bundleRelease ..."
cd "${ROOT}"
./gradlew :app:bundleRelease

AAB="${ROOT}/app/build/outputs/bundle/release/app-release.aab"
if [[ -f "${AAB}" ]]; then
  echo "✓ AAB listo: ${AAB}"
  sha256sum "${AAB}"
else
  echo "✗ No se encontró el AAB en ${AAB}"
  exit 1
fi
