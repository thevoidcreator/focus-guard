# Android Focus Guard + Windows Key Console

Русскоязычный монорепозиторий добровольного ограничения Android: DPC/HOME launcher,
дневной сеанс, USB-ключи Windows, QR/JWS, защищённые изменения и снятие защиты.

**Статус: лабораторная реализация. Для основного Nothing Phone (3a) устанавливать
Device Owner нельзя до проверки выхода без потери данных.** Production flavour
не позволяет включить ограничения: универсальный поддерживаемый способ снять
Device Owner без сброса не подтверждён. Lab использует testing-only API и не
является доказательством промышленного deprovisioning.

Владелец указал Nothing Phone (3a); точная версия Android/Nothing OS и установленные
VPN-пакеты пока неизвестны. Реальный телефон и Windows/USB не подключены к среде.
На образе API31 Google APIs полный выход не прошёл: ошибка системного telephony provider.
Итоги сборок и фактически проведённых проверок — [TEST_REPORT.md](docs/TEST_REPORT.md).

## Что реализовано

- Android 12+ (min31, target36), Compose, Device Owner, постоянный HOME, multi-app
  Lock Task, блокировка обычных пакетов, инвентаризация сертификатов и зависимостей.
- Один сеанс 120 минут: вопрос, пауза 60 секунд, отдельное подтверждение, сгорание
  остатка, день в 04:00 сохранённой zone, минимум 12 подтверждённых часов между стартами.
- При reboot активный сеанс сгорает; pending QR аннулируются. Ожидание 48 часов
  считает только сохранённое монотонное время. Выключение может удлинить ожидание.
- Windows 11 WPF/.NET 10 LTS, обычный USB removable volume, зашифрованный переносимый
  PKCS#8, проверка чтения, отдельные ключи/роли, резервный носитель, QR/файлы/камера.
- Keystore-идентичность телефона; первоначальная сверка fingerprints; JWS ES256
  на Nimbus/IdentityModel; одноразовые запросы, отмена, свежий финальный challenge,
  независимый аварийный ключ, изменение политики через такой же flow.
- Atomic storage, журнал собственных изменений, возобновляемые этапы lab-снятия,
  без wipeData и без автоматического включения ADB.

## Структура и запуск

`android/` — приложение и чистое JVM-ядро; `windows/` — WPF и переносимое ядро;
`protocol/` — формат, схема и публичные межъязыковые векторы; `docs/` — инструкции;
`scripts/` — сборка, проверки и smoke тест только на эмуляторе.

1. Прочитайте [решения](docs/DECISIONS.md), [модель угроз](docs/THREAT_MODEL.md) и
   [восстановление](docs/RECOVERY_RU.md).
2. На тестовом Windows/USB пройдите [USB_KEY_SETUP_RU](docs/USB_KEY_SETUP_RU.md) и
   [WINDOWS_SETUP_RU](docs/WINDOWS_SETUP_RU.md).
3. Используйте только эмулятор/отдельный тестовый аппарат по
   [ANDROID_SETUP_RU](docs/ANDROID_SETUP_RU.md). Не выполняйте сброс личного телефона.
4. JDK21 + SDK36 + Gradle wrapper: `bash scripts/build-android.sh`.
   .NET SDK10.0.402 на Windows: `pwsh scripts/build-windows.ps1`.
5. [Протокол](protocol/PROTOCOL_RU.md), [контейнер](docs/KEY_CONTAINER_RU.md),
   [релиз и подпись](docs/BUILD_RELEASE_RU.md).

Ни root, ни AccessibilityService, ни overlay, ни сервер, ни USB debugging в обычном
режиме не нужны. Защита не покрывает recovery reset, root/прошивку, администратора
Windows и модифицированный клиент. USB-файл копируется; WhatsApp/VPN доступны целиком.
Более строгий календарный учёт через выключение требует отдельного режима с
аутентифицированным серверным временем и в эту поставку не добавлен.

## Скачивание поставки

Исходники и инструкции сохранены в ветке
[`codex/focus-guard-source`](https://github.com/thevoidcreator/focus-guard/tree/codex/focus-guard-source).
Проверенные файлы поставки, новый архив исходников и SHA-256 размещены отдельно
в ветке [`codex/focus-guard-downloads`](https://github.com/thevoidcreator/focus-guard/tree/codex/focus-guard-downloads).
Подробности — [DOWNLOADS_RU.md](docs/DOWNLOADS_RU.md). Репозиторий приватный:
для скачивания войдите в GitHub с доступом к нему.
