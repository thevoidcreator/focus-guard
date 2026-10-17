# Отчёт проверок — 17 октября 2026

**Итог: лабораторная реализация со сборками; не принята для основного телефона.**
Целевая модель владельца — Nothing Phone (3a). Версия Android/Nothing OS, фактические
VPN-пакеты и их подписи не предоставлены. Физического телефона, USB и Windows GUI
в среде не было. Production activation закрыта до поддерживаемого и испытанного
модельного пути снятия Device Owner без потери данных.

## Реализовано

Android DPC/HOME/Lock Task, инвентаризация сертификатов, OS restrictions/suspension,
дневной сеанс и консервативное время, atomic state и journal, Keystore, QR/SAF,
enrollment/removal/emergency/policy_change, возобновляемое lab deprovisioning.
Windows WPF с USB-мастером, PKCS#8, readback/probe, независимыми ролями, резервом,
камерой/файлами, проверкой телефона и подписью. Исходники, scripts/CI и русские
инструкции включены. Детальная карта — IMPLEMENTATION_MATRIX_RU.md.

## Проверено автоматически в Linux

| Проверка | Результат | Доказательство |
|---|---|---|
| Kotlin core | PASS, 17 тестов (16 Engine + 1 golden) | verification/TEST-ru.focusguard.core.*.xml |
| C# core | PASS, 8 тестов, 0 skipped | verification/windows-tests.trx |
| Подписи Kotlin/C#/Python | PASS, exact bytes + ES256 R||S | 3 публичных vectors, check-vectors.py |
| PKCS#8 interoperability | PASS, .NET контейнер расшифрован Python cryptography | verification/pkcs8-interop.txt |
| KDF/шифр | PASS, ASN.1 PBES2/PBKDF2/HMAC-SHA256/600000/AES-256-CBC | тот же отчёт, без приватного ключа |
| Android labDebug и instrumentation APK | BUILD SUCCESSFUL | verification/android-build.txt |
| Android productionRelease unsigned APK | BUILD SUCCESSFUL, activation disabled | verification/android-build.txt |
| Lint productionRelease | PASS: 0 errors; предупреждения о новой версии serialization/стиле Uri | verification/android-lint.txt |
| Release manifest APK | PASS: debuggable/testOnly отсутствуют, backup=false, экспортированы только HOME и permission-gated admin | verification/production-manifest.xml |
| Lab APK signature | PASS, APK Signature Scheme v2 | apksigner verify |
| Source guardrails | PASS: нет wipeData, overlay, AccessibilityService, приватных ключей | scripts/check-release.py |
| Windows self-contained win-x64 | BUILD SUCCESSFUL, .NET + WindowsDesktop 10.0.13 | verification/windows-build.txt |
| NuGet locked restore tests/publish | PASS, оба графа | verification/windows-locked.txt |
| CI на GitHub | NOT RUN, workflow предоставлен | .github/workflows/build.yml |

Тесты времени проверяют отмену/паузы, сгорание остатка, границу дня и 12 часов,
reboot и недостоверную дату, 24-часовое восстановление, обратный перевод часов,
DST, ожидание 48 часов без учёта power-off, fresh final, отмену flow и screenshots.
Криптотесты проверяют неправильную подпись/ключ/роль/устройство/фазу/digest/policy,
replay/новый challenge, expiry, alg=none/crit/version/duplicate keys/depth/size.
USB-тест использует реальную криптографию и временный файловый каталог с injectable
probe. Он проверяет неверный пароль/отсутствие/потерю носителя как сигнал, **не**
реальный Windows USB hot-unplug. Тестовая криптография не включена в APK/EXE.

## Эмулятор

Google APIs API31 r14, Android Emulator37.2.12, x86_64, программный TCG без KVM.
Первый запуск завершён OOM (код137); повторный с ограничением памяти загрузился.
Для локального теста использован adb-shell0.4.4 с ключом в рабочем каталоге и TCP
только к своему AVD: нативный adb пытался писать в недоступный HOME. Поставляемые
scripts/CI используют обычный adb на нормальном хосте.

| Подэтап | Результат |
|---|---|
| Установка настоящего lab APK/test APK | PASS |
| Назначение Device Owner | PASS |
| HOME и вход в Lock Task API | PASS: assertion LOCK_TASK_MODE_LOCKED пройдён |
| Settings отсутствует в allowlist | PASS: assertion пройдён |
| Начальные стадии снятия Lock Task/HOME | Выполнены до стадии4 |
| clearDeviceOwnerApp / полное снятие | **FAIL / BLOCKED**: ошибка telephony provider |
| Повторная регистрация после частичного сбоя | BLOCKED: admin остаётся в Removing Device Admins |
| Полный цикл без потери данных | **Не принят**; sentinel не заменяет полный выход |
| Reboot с жёсткими политиками/связь/VPN | NOT RUN |

Точная ошибка: `IllegalArgumentException: Unknown URI
content://telephony/carriers/enforce_managed` внутри DPM при clearDeviceOwnerApp.
Повторный setup получил `Trying to set an admin which is being removed`.
Первый прогон также выявил 45-секундный таймаут запуска HOME; инвентаризация и
Keystore перенесены с главного потока, повторный прогон дошёл до снятия Device Owner.
Логи отказов сохранены в verification/emulator-*.txt. Ошибка не замаскирована как
COMPLETE, wipeData/factory reset не использованы.

В финальном исходнике предусмотрены повторение по checkpoint, отдельная проверка
owner/admin/Lock Task и удаление обычного admin только **после** отсутствия Device
Owner. Эти изменения не превращают отказавший образ API31 в принятую платформу.
Финальные APK собраны и проверены статически; полный успешный emulator workflow
для них **не подтверждён**. Лабораторный ADB cleanup testOnly admin выполнен: sentinel сохранился, owner/admin
отсутствуют по readback. Это отдельная операция разработчика и не засчитывается
как снятие приложением; журнал — verification/emulator-cleanup.txt.

## Проверено на реальном оборудовании

**Ничего: NOT RUN.** Nothing Phone (3a), звонки/SMS/emergency UI, WhatsApp, каждый
VPN, Wi-Fi/Bluetooth, Doze, reboot с политиками, system dialogs, реальные камеры,
Windows GUI, USB hot-unplug, резервный USB и второй ноутбук ещё не испытаны.
Сборка и unit-тесты не подтверждают эти сценарии.

## Ограничения платформы и незакрытая приёмка

- clearDeviceOwnerApp deprecated/testing-only, очистка best effort. Lab-результат
  нельзя перенести на production Nothing OS. Поддерживаемого адаптера для этой
  модели нет, поэтому основной телефон не допускается и factory reset не является
  успешным deprovisioning.
- Офлайн нет универсального защищённого счётчика календарного времени через
  выключение. Ожидание/cooldown считают подтверждённое монотонное время; могут
  удлиниться. Доверие к дате после 24 часов стабильного OS auto-time — эвристика,
  не криптографическая аттестация времени.
- OEM зависимости связи и permission dialogs, переходы из чужих WebView,
  installers/assistant/notifications/SAF и OS/app updates требуют проверки модели.
  Ограничение вкладок WhatsApp/VPN не заявляется. Captive portal/Enterprise Wi-Fi
  и отдельный maintenance-мастер обновлений не реализованы.
- ОС хранит ограничения без процесса; переход по exact alarm зависит от доставки
  Android. Screen-off/Doze/убийство процесса должны быть приняты на реальном аппарате.
- Обычный USB копируется; короткий unplug/replug может оказаться между probes.
  Уже выпущенный ответ не отзывается извлечением USB. Администратор Windows,
  изменённый клиент, root/recovery/перепрошивка вне модели защиты.
- Production APK unsigned, Windows EXE без Authenticode. Инструкции подписи даны;
  сертификаты/секреты в поставку не входят. Побайтовая воспроизводимость двух
  подписанных сборок не проверялась.

## Ручной протокол приёмки

Для каждой строки записать дату, точный OS build fingerprint, версии приложений,
packageId/signing certificate, действие, ожидаемый/фактический результат, свидетельство.

- [ ] Резервные копии личных данных проверены восстановлением на тестовом аппарате.
- [ ] До жёстких политик пройден provisioning → Lock Task → выход без потерь.
- [ ] Нормальные входящие/исходящие звонки, SMS, контакты, IME и keyguard работают.
- [ ] Emergency dialer доступен; реальный экстренный номер при тестировании не вызывать.
- [ ] WhatsApp и фактические VPN работают по одному, в том числе после reboot.
- [ ] Ссылки/share/WebView/files/notification/recent/search/assistant/installer не дают обход.
- [ ] 60с/120мин/04:00/12ч, досрочное завершение, экран, Doze и process death проверены.
- [ ] В обоих режимах нельзя удалить DPC, снять управление, добавить пользователя/отладку.
- [ ] Первый USB-ответ не открывает приложения; 48 часов нельзя ускорить часами ПК/телефона.
- [ ] Финальный challenge появляется после ожидания; нужен тот же ключ и новый QR.
- [ ] Отмена/новый flow/неверная роль/подпись/replay/параллельный импорт дают отказ.
- [ ] Основной ключ не проходит emergency; независимый зарегистрированный проходит.
- [ ] Создание USB без форматирования, readback, неверный пароль и unplug проверены.
- [ ] Резервный носитель и другой ноутбук реально подписали новый запрос телефона.
- [ ] Камеры в обе стороны и файловый перенос без камер/USB debugging проверены.
- [ ] На каждой стадии deprovisioning смоделирован сбой; повтор безопасен.
- [ ] DO/admin отсутствуют, пакеты/HOME восстановлены, личные данные сохранены.
- [ ] После OTA/обновления DPC/WhatsApp/VPN приёмка повторена.

Пока пункты не выполнены, статус основного Nothing Phone (3a): **BLOCKED**.
