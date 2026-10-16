#!/usr/bin/env bash
# recovery-regressions: suite instrumentada con UN reintento solo de las clases fallidas.
# Los flakes conocidos del emulador («No compose hierarchies found», carreras de arranque) no deben
# teñir el CI de rojo; si el reintento vuelve a fallar, el job termina en rojo igualmente.
#
# 16-oct-2026: cuando el emulador se degrada (log con «bad color buffer handle» / «Failed to bind to
# texture»), los tests que necesitan foco de ventana o reloj de composición fallan en cascada y un
# reintento SOBRE EL MISMO emulador repite el fallo exactamente igual. Antes de reintentar se
# REINICIA el emulador y se espera a que esté listo: así el reintento mide la app, no el emulador roto.
set -u

CLASSES="com.gastos.di.BankImportStorageTest,com.gastos.di.WalletCaptureRecoveryTest,com.gastos.di.MovementListProjectionTest,com.gastos.di.OrganizationStorageTest,com.gastos.di.CommandOperationDatabaseTest,com.gastos.di.RemoteSyncOutboxMigrationTest,com.gastos.di.TaxMigrationTest,com.gastos.di.BankPerformanceTest,com.gastos.di.DocumentCaptureDatabaseTest,com.gastos.di.AutomationIntegrationTest,com.gastos.di.DocumentViewerUiTest,com.gastos.di.BankImportUiTest,com.gastos.di.FinancialReportTest,com.gastos.di.DeviceLargeBackupTest,com.gastos.di.EssentialUiTest,com.gastos.di.ManualCompactUiTest"

run_suite() {
  ./gradlew :app:connectedDebugAndroidTest --max-workers=2 -Dorg.gradle.parallel=false \
    -Pandroid.testInstrumentationRunnerArguments.largeBackup=true \
    -Pandroid.testInstrumentationRunnerArguments.class="$1"
}

reboot_emulator() {
  echo "== Reinicio del emulador antes del reintento (un emulador degradado envenena los tests de UI)"
  adb reboot 2>/dev/null || true
  adb wait-for-device 2>/dev/null || true
  for _ in $(seq 1 60); do
    if [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; then
      break
    fi
    sleep 5
  done
  sleep 20
  adb shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1 || true
  adb shell wm dismiss-keyguard >/dev/null 2>&1 || true
  adb shell input keyevent 82 >/dev/null 2>&1 || true
  sleep 5
  echo "== Emulador reiniciado: boot_completed=$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')"
}

if run_suite "$CLASSES"; then
  exit 0
fi

retry=$(python3 - <<'PY'
import glob
import xml.etree.ElementTree as ET

failed = set()
for path in glob.glob('app/build/outputs/androidTest-results/**/*.xml', recursive=True):
    try:
        root = ET.parse(path).getroot()
    except Exception:
        continue
    for case in root.iter('testcase'):
        if case.find('failure') is not None or case.find('error') is not None:
            failed.add(case.get('classname'))

print(','.join(sorted(name for name in failed if name)))
PY
)

echo "Reintento de clases fallidas: $retry"
if [ -z "$retry" ]; then
  exit 1
fi

reboot_emulator
run_suite "$retry"
