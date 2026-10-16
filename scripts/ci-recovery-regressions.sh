#!/usr/bin/env bash
# recovery-regressions: suite instrumentada con UN reintento solo de las clases fallidas.
# Los flakes conocidos del emulador («No compose hierarchies found», carreras de arranque) no deben
# teñir el CI de rojo; si el reintento vuelve a fallar, el job termina en rojo igualmente.
set -u

CLASSES="com.gastos.di.BankImportStorageTest,com.gastos.di.WalletCaptureRecoveryTest,com.gastos.di.MovementListProjectionTest,com.gastos.di.OrganizationStorageTest,com.gastos.di.CommandOperationDatabaseTest,com.gastos.di.RemoteSyncOutboxMigrationTest,com.gastos.di.TaxMigrationTest,com.gastos.di.BankPerformanceTest,com.gastos.di.DocumentCaptureDatabaseTest,com.gastos.di.AutomationIntegrationTest,com.gastos.di.DocumentViewerUiTest,com.gastos.di.BankImportUiTest,com.gastos.di.FinancialReportTest,com.gastos.di.DeviceLargeBackupTest,com.gastos.di.EssentialUiTest,com.gastos.di.ManualCompactUiTest"

run_suite() {
  ./gradlew :app:connectedDebugAndroidTest --max-workers=2 -Dorg.gradle.parallel=false \
    -Pandroid.testInstrumentationRunnerArguments.largeBackup=true \
    -Pandroid.testInstrumentationRunnerArguments.class="$1"
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

run_suite "$retry"
