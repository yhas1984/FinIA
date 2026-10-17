#!/usr/bin/env bash
# recovery-regressions: suite instrumentada, en dos fases y con reintentos de las clases fallidas.
#
# HISTORIA (para no volver a perder el tiempo):
#  - Los flakes del emulador de GitHub («No compose hierarchies found», carreras de arranque) no deben
#    teñir el CI de rojo.
#  - 16-oct-2026: si el emulador se degrada (log con «bad color buffer handle» / «Failed to bind to
#    texture»), los tests de UI fallan en cascada y repetir SOBRE EL MISMO emulador repite el fallo.
#  - 17-oct-2026 (run 39010139266): con las opciones de GPU ya aplicadas, el emulador aguantó
#    1 h 44 min y 77 tests en verde, y se degradó justo cuando le tocó el test de UI (el framebuffer
#    salió «bad color buffer handle» en ese momento; tras reiniciar, ya venía roto de arranque).
#    Conclusión: NO es que el test sea lento — es que el emulador se rompe por el camino.
#
# ESTRATEGIA ACTUAL:
#  1) Los tests de INTERFAZ se ejecutan PRIMERO, con el emulador recién arrancado y sano.
#  2) Después el resto (datos/recovery).
#  3) Antes de cada fase se deja el dispositivo despierto, desbloqueado y sin salvapantallas.
#  4) Si algo falla: se REINICIA el emulador y se reintentan solo las clases fallidas (hasta 2 veces).
set -u

UI_CLASSES="com.gastos.di.BankImportUiTest,com.gastos.di.DocumentViewerUiTest,com.gastos.di.EssentialUiTest,com.gastos.di.ManualCompactUiTest"
DATOS_CLASSES="com.gastos.di.BankImportStorageTest,com.gastos.di.WalletCaptureRecoveryTest,com.gastos.di.MovementListProjectionTest,com.gastos.di.OrganizationStorageTest,com.gastos.di.CommandOperationDatabaseTest,com.gastos.di.RemoteSyncOutboxMigrationTest,com.gastos.di.TaxMigrationTest,com.gastos.di.BankPerformanceTest,com.gastos.di.DocumentCaptureDatabaseTest,com.gastos.di.AutomationIntegrationTest,com.gastos.di.FinancialReportTest,com.gastos.di.DeviceLargeBackupTest"

run_suite() {
  ./gradlew :app:connectedDebugAndroidTest --max-workers=2 -Dorg.gradle.parallel=false \
    -Pandroid.testInstrumentationRunnerArguments.largeBackup=true \
    -Pandroid.testInstrumentationRunnerArguments.class="$1"
}

# Deja el dispositivo listo: despierto, sin bloqueo de pantalla y sin que se apague.
preparar_dispositivo() {
  echo "== Preparando el dispositivo (despierto, desbloqueado, pantalla siempre encendida)"
  adb shell svc power stayon true >/dev/null 2>&1 || true
  adb shell settings put global stay_on_while_plugged_in 3 >/dev/null 2>&1 || true
  adb shell locksettings set-disabled true >/dev/null 2>&1 || true
  adb shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1 || true
  adb shell wm dismiss-keyguard >/dev/null 2>&1 || true
  adb shell input keyevent 82 >/dev/null 2>&1 || true
  sleep 3
  echo "== dispositivo: boot_completed=$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')"
}

reiniciar_emulador() {
  echo "== Reiniciando el emulador (un emulador degradado envenena los tests de UI)"
  adb reboot 2>/dev/null || true
  adb wait-for-device 2>/dev/null || true
  for _ in $(seq 1 72); do
    if [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; then
      break
    fi
    sleep 5
  done
  sleep 20
  preparar_dispositivo
}

clases_fallidas() {
  python3 - <<'PY'
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
}

preparar_dispositivo

FALLO=0
if ! run_suite "$UI_CLASSES"; then FALLO=1; fi   # interfaz primero, con el emulador sano
if ! run_suite "$DATOS_CLASSES"; then FALLO=1; fi

if [ "$FALLO" = "0" ]; then
  echo "== Suite completa en verde"
  exit 0
fi

for intento in 1 2; do
  retry=$(clases_fallidas)
  echo "== Intento $intento · clases fallidas: $retry"
  if [ -z "$retry" ]; then
    # Hubo fallo de Gradle pero sin XML con fallos (p. ej. caída del entorno): reintento la fase de UI.
    retry="$UI_CLASSES"
  fi
  reiniciar_emulador
  if run_suite "$retry"; then
    echo "== Intento $intento en verde"
    exit 0
  fi
done

echo "== La suite sigue en rojo tras 2 reintentos con emulador reiniciado"
exit 1
