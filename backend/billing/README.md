# FinAI Billing Backend

Cloud Run service for server-side Google Play purchase verification.

## Contract

- `GET /` (external Cloud Run health check)
- `GET /healthz` (application health check)
- `POST /v1/entitlements:verify`
- `POST /v1/entitlements:reconcile` with Cloud Scheduler OIDC bearer token and `X-Internal-Reconcile-Secret`

`/v1/entitlements:verify` checks the package, product and purchase token against
the Google Play Android Publisher API, acknowledges a valid purchase, stores an
idempotent entitlement in Firestore, and returns an RSA-signed JWT. The Android
client must verify that JWT with the matching public key before enabling Premium.

## Required environment

- `GCP_PROJECT_ID=finai-501616`
- `FIRESTORE_DATABASE_ID=finai`
- `PLAY_PACKAGE_NAME=com.gastos.ingresos`
- `PLAY_PRODUCT_ID=finai_premium`
- `ENTITLEMENT_ISSUER=finai-billing`
- `ENTITLEMENT_KEY_ID=<key-id>`
- `ENTITLEMENT_PRIVATE_KEY_PEM=<Secret Manager value>`
- `INTERNAL_RECONCILE_SECRET=<Secret Manager value>`
- `RECONCILE_AUDIENCE=<Cloud Run service URL>`
- `REQUIRE_PLAY_INTEGRITY=false`

Cloud Run must run as `finai-billing-backend@finai-501616.iam.gserviceaccount.com`.
That account needs access to the Firestore database and the Google Play Android
Developer API. Do not put a service-account JSON key in this repository.

## Local tests

```bash
./gradlew -p backend/billing test
```

## Deployment outline

Build and deploy from `backend/billing` with Secret Manager references for the
private signing key and reconcile secret. Keep minimum instances at zero and
add a Cloud Scheduler job calling `/v1/entitlements:reconcile` with an OIDC
identity whose audience is the Cloud Run service URL, plus the internal secret.

The Android client integration is intentionally not enabled until the Cloud Run
URL, public signing key, issuer and key ID are configured. Play Integrity is
implemented but must remain disabled until the Play Console/API linkage is
verified end to end.

## Deployment runbook

Run these commands only after `gcloud auth login` and use Secret Manager for
the private key and reconcile secret. Never commit the values below.

```bash
export PROJECT_ID=finai-501616
export REGION=europe-west1
export SERVICE=finai-billing
export RUNTIME_SA=finai-billing-backend@${PROJECT_ID}.iam.gserviceaccount.com
export SCHEDULER_SA=finai-billing-scheduler@${PROJECT_ID}.iam.gserviceaccount.com
export ENTITLEMENT_ISSUER=finai-billing
export ENTITLEMENT_KEY_ID=2026-01

KEY_DIR="$(mktemp -d)"
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 \
  -out "${KEY_DIR}/entitlement-private.pem"
openssl pkey -in "${KEY_DIR}/entitlement-private.pem" -pubout \
  -out "${KEY_DIR}/entitlement-public.pem"
export ENTITLEMENT_PRIVATE_KEY_FILE="${KEY_DIR}/entitlement-private.pem"
export INTERNAL_RECONCILE_SECRET="$(openssl rand -hex 32)"

gcloud config set project "${PROJECT_ID}"
gcloud services enable run.googleapis.com cloudbuild.googleapis.com \
  artifactregistry.googleapis.com secretmanager.googleapis.com \
  firestore.googleapis.com cloudscheduler.googleapis.com \
  androidpublisher.googleapis.com

gcloud iam service-accounts create finai-billing-backend \
  --project="${PROJECT_ID}" \
  --display-name="FinAI Billing Cloud Run"
gcloud iam service-accounts create finai-billing-scheduler \
  --project="${PROJECT_ID}" \
  --display-name="FinAI Billing Scheduler"
gcloud projects add-iam-policy-binding "${PROJECT_ID}" \
  --member="serviceAccount:${RUNTIME_SA}" \
  --role=roles/datastore.user
gcloud firestore databases describe finai --project="${PROJECT_ID}" >/dev/null 2>&1 || \
  gcloud firestore databases create --database=finai --location="${REGION}" \
    --type=firestore-native --project="${PROJECT_ID}"

# Create these two Secret Manager secrets once, then add their versions.
gcloud secrets create finai-entitlement-private-key --replication-policy=automatic
gcloud secrets create finai-reconcile-secret --replication-policy=automatic
gcloud secrets versions add finai-entitlement-private-key --data-file="${ENTITLEMENT_PRIVATE_KEY_FILE}"
printf '%s' "${INTERNAL_RECONCILE_SECRET}" | \
  gcloud secrets versions add finai-reconcile-secret --data-file=-
gcloud secrets add-iam-policy-binding finai-entitlement-private-key \
  --member="serviceAccount:${RUNTIME_SA}" --role=roles/secretmanager.secretAccessor
gcloud secrets add-iam-policy-binding finai-reconcile-secret \
  --member="serviceAccount:${RUNTIME_SA}" --role=roles/secretmanager.secretAccessor

gcloud run deploy "${SERVICE}" --source . --region="${REGION}" \
  --project="${PROJECT_ID}" --service-account="${RUNTIME_SA}" \
  --allow-unauthenticated --min=0 --max=3 \
  --set-env-vars="GCP_PROJECT_ID=${PROJECT_ID},FIRESTORE_DATABASE_ID=finai,PLAY_PACKAGE_NAME=com.gastos.ingresos,PLAY_PRODUCT_ID=finai_premium,ENTITLEMENT_ISSUER=${ENTITLEMENT_ISSUER},ENTITLEMENT_KEY_ID=${ENTITLEMENT_KEY_ID},REQUIRE_PLAY_INTEGRITY=false,RECONCILE_AUDIENCE=https://placeholder.invalid" \
  --set-secrets="ENTITLEMENT_PRIVATE_KEY_PEM=finai-entitlement-private-key:latest,INTERNAL_RECONCILE_SECRET=finai-reconcile-secret:latest"

export SERVICE_URL="$(gcloud run services describe "${SERVICE}" --region="${REGION}" --format='value(status.url)')"
gcloud run services update "${SERVICE}" --region="${REGION}" \
  --update-env-vars="RECONCILE_AUDIENCE=${SERVICE_URL}"
gcloud run services add-iam-policy-binding "${SERVICE}" --region="${REGION}" \
  --member="serviceAccount:${SCHEDULER_SA}" --role=roles/run.invoker
gcloud scheduler jobs create http finai-billing-reconcile \
  --location="${REGION}" --schedule="0 * * * *" \
  --uri="${SERVICE_URL}/v1/entitlements:reconcile" --http-method=POST \
  --oidc-service-account-email="${SCHEDULER_SA}" \
  --oidc-token-audience="${SERVICE_URL}" \
  --headers="X-Internal-Reconcile-Secret=${INTERNAL_RECONCILE_SECRET}"
```

For the Android release build, configure these environment variables before
running Gradle:

- `FINAI_BILLING_BACKEND_URL=<Cloud Run URL>`
- `FINAI_BILLING_ENTITLEMENT_PUBLIC_KEY_PEM=<public signing key>`
- `FINAI_BILLING_ENTITLEMENT_ISSUER=finai-billing`
- `FINAI_BILLING_ENTITLEMENT_KEY_ID=<key-id>`
- `FINAI_BILLING_BACKEND_REQUIRED=true`
- `FINAI_BILLING_PLAY_INTEGRITY_ENABLED=false` until verified

Release builds fail closed when the backend configuration is absent; debug
builds retain the local entitlement behavior for development.
