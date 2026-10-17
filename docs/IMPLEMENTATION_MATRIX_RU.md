# Сопоставление с приёмкой

Статусы код/автотест/оборудование намеренно различаются. Указанный владельцем
Nothing Phone (3a) не был физически подключён к Linux-среде.

| Требование | Реализация | Граница приёмки |
|---|---|---|
| DPC/HOME/Lock Task | PolicyController, AdminReceiver, MainActivity | API31 provisioning/Lock Task прошли; deprovisioning FAIL; production заблокирован |
| Связь/WhatsApp/VPN | Системные handlers + явные package/certificate pins | Реальные звонки/SMS/WhatsApp/VPN не проверены |
| Системные зависимости | Инвентаризация IME, Telecom, SMS, permission controller, SystemUI | OEM Nothing OS может требовать дополнительные компоненты |
| 60с/120мин/04:00/12ч | Pure Engine + Reading/clock + accountDay | Unit-тесты включая DST, reboot, rollback |
| Экран/процесс/Doze | elapsedRealtime + durable state + exact deadline alarm + OS policy | Требуется тест screen-off/Doze на Nothing |
| 48 часов | Подтверждённые монотонные интервалы, checkpoint, fresh challenge | Unit tests с injectable Reading; не 48ч на реальном устройстве |
| Защищённые изменения | Policy snapshot в challenge/digest, тот же flow | UI принимает полный JSON, отдельный графический редактор правил не реализован |
| Аварийный ключ | Независимый SPKI/role, отсутствие ключа закрывает путь | Нужна отдельная первоначальная регистрация на доверенном устройстве |
| USB | Removable volume, PKCS#8 PBES2, readback, password, probe, backup | Unit fixture на временном каталоге; это не проверка USB hardware |
| QR/файлы | ZXing Android/Windows, OpenCV camera0, SAF bounded parser | Камеры/SAF OEM/передача файлов требуют ручного теста |
| Криптопротокол | Nimbus/IdentityModel, ES256 R||S, strict JSON, digest exact bytes | Kotlin/C#/Python public golden vectors |
| Replay/параллельность | Один persisted challenge, Storage.lock, atomic write | Unit transitions + код синхронизации; стресс-тест power-loss/fsync не выполнен |
| Выход без данных | Lab journal/checkpoints, clearDeviceOwnerApp только lab, verify owner/admin/packages | Не поддерживаемый универсальный production path; OEM-адаптера нет |
| Wi-Fi/Bluetooth | WPA2-PSK DO addNetwork, DO Bluetooth toggle/discovery/pairing | Enterprise Wi-Fi, captive portal, OEM dialogs не приняты |
| Updates | Signing lineage, package broadcast, system apps не блокируются массово | OS OTA/Play updates/installer UI требуют теста; отдельного maintenance-мастера нет |
| Защита Settings/intents | Lock Task block-in-task + Settings исключён + suspension user apps | Ссылки/share/WebView/уведомления/assistant на OEM не приняты |
| Сборки | APK labDebug/productionRelease unsigned, Windows self-contained, scripts/CI | CI предоставлен, удалённо не запускался; см. фактический TEST_REPORT |

Модельный адаптер деактивации не имитируется флагом «проверено». Для снятия
production gate понадобится поддерживаемый путь Nothing/Android, его реализация,
версионированная матрица и успешные испытания сохранности данных и восстановления.
