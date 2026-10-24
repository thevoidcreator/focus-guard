> Документ относится к прежней поставке 0.1. Для версии 0.2 начните с
> [START_HERE_RU](../START_HERE_RU.md), [FG2](../protocol/FG2_RU.md) и
> [отчёта 0.2](TEST_REPORT_V02.md). Старые результаты не являются проверкой 0.2.

# Сборки, версии и подпись

Зафиксировано: JDK21, Gradle8.13, AGP8.13.2, Kotlin2.2.21, Compose BOM2025.10.01,
serialization1.9.0, Nimbus10.0.2, Jackson-core2.18.3, ZXing Android4.3.0;
.NET SDK10.0.402/LTS, runtime10.0.13; IdentityModel8.14.0, ZXing.Net0.16.11,
OpenCvSharp4.10.0.20240616. Доступность версий подтверждается restore/сборкой.
Это закреплённый совместимый набор, не обещание latest. Servicing/security updates
перед распространением проверяются отдельно и повторяют протокольные тесты.

## Android

Установите JDK21 и Android SDK. Через sdkmanager примите лицензии и установите
`platforms;android-36`, `build-tools;36.0.0`, `platform-tools`. Установите ANDROID_HOME,
JAVA_HOME. Для CI поместите Gradle/Android user caches в рабочий каталог. Gradle wrapper
зафиксирован в android/. В cloud Java может требовать параметры HTTPS proxy и
trustStore из доверенного CA окружения; не отключайте проверку TLS.

```sh
bash scripts/build-android.sh
# Отдельный smoke на disposable AVD:
# ./android/gradlew -p android :app:assembleLabDebugAndroidTest
# ANDROID_SERIAL=emulator-5554 bash scripts/emulator-smoke.sh
```

LabDebug APK подписан локальным отладочным сертификатом и testOnly=true.
ProductionRelease unsigned APK не подписан; production activation запрещена.
Для собственного релиза храните keystore и пароли вне repo, используйте apksigner
из SDK (`zipalign` перед подписью), затем `apksigner verify --verbose --print-certs`.
Пароли не помещайте в командную строку или исходники; передавайте защищённым
механизмом CI/вводом инструмента. Один и тот же release certificate нужен для updates.
Не распространяйте lab как безопасную для основного телефона сборку.

## Windows

Установите .NET SDK10.0.402. WPF собирается на Windows либо cross-target на Linux;
проверка реального GUI/USB требует Windows11. Запускайте:

```powershell
pwsh scripts/build-windows.ps1
```

Поставляется вся папка publish или её ZIP, не один EXE. Runtime self-contained,
native OpenCV DLL идут рядом. Для release Authenticode используйте собственный
сертификат и Windows SDK signtool, проверьте подпись и пересчитайте SHA256 после
подписи. Не включайте сертификат с приватным ключом/PFX в repo. Timestamping и
публикация требуют вашего выбранного поставщика и не выполняются автоматически.

NuGet packages.lock.json фиксируют транзитивные зависимости; CI использует locked
restore. SDK фиксируется global.json. Gradle wrapper включает checksum дистрибутива;
прикладные Maven-версии фиксированы build.gradle.kts. Повторяемость означает
воспроизводимый процесс с теми же toolchain/dependencies; побайтовая идентичность
подписанных APK/EXE не утверждается без отдельной проверки двух чистых сборок.

## Проверки и хеши

Kotlin unit-тесты: `cd android && ./gradlew :core:test`.
.NET: `dotnet test windows/FocusGuard.Tests -c Release` (global.json применять из windows).
Межъязыковые публичные векторы: `python scripts/check-vectors.py` (cryptography).
Статический guardrail: `python scripts/check-release.py`. Он не заменяет аудит и
проверку manifest. ZIP поставки исключает build/, caches, private keys и .git.

Перед упаковкой сверить merged manifest production: debuggable=false/отсутствует,
testOnly=false/отсутствует; экспортированы только HOME/launcher и admin receiver с
BIND_DEVICE_ADMIN; сканер и собственные broadcast receivers не экспортированы.
Таймеры release — 60с/120мин/12ч/48ч, без тестовых кнопок/ключей/таймеров.
Тестовый injectable Reading доступен unit-модулю; Android runtime всегда берёт
SystemClock/Settings. Instrumentation тесты не включаются в основной APK.
