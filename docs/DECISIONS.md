# Архитектурные решения — 17.10.2026

1. Kotlin, Android 12/API 31 минимум, compile/target 36; AGP 8.13.2,
   Kotlin 2.2.21, Gradle 8.13, JDK 21. Compose используется только для UI.
   Ядро — отдельный JVM-модуль без Android. Версии зафиксированы, не обозначают
   автоматически последнюю доступную версию.
2. Device Owner + persistent HOME + multi-app Lock Task + suspension только
   обычных пользовательских пакетов. Системные пакеты не блокируются списком вслепую.
   LOCK_TASK_FEATURE_BLOCK_ACTIVITY_START_IN_TASK закрывает запуск чужих activities в той же task.
   Системные зависимости инвентаризируются на конкретной модели.
3. **Стоп для основного телефона:** официальная документация не даёт универсального
   поддерживаемого удаления Device Owner без сброса. clearDeviceOwnerApp deprecated
   с API 26, testing-only, очистка политик best effort. Production-сборка запрещает
   активацию, пока нет реализованного и испытанного модельного адаптера.
   Lab-сборка позволяет исследовать обратимый workflow, но не является production.
   wipeData не применяется. Наличие APK не является приёмкой устройства.
4. .NET 10 LTS/WPF, Windows 11 x64. USB — переносимый шифрованный PKCS#8,
   PBES2/PBKDF2-HMAC-SHA256 (600000 итераций)/AES-256-CBC, системная криптография.
   Это копируемый файл. Для QR — ZXing, для JWS Android — Nimbus JOSE JWT,
   Windows — Microsoft.IdentityModel.JsonWebTokens. Строгий входной JSON проверяется
   до передачи библиотекам. ES256, подпись R||S, SHA-256 exact UTF-8 compact JWS.
5. Учёт 04:00 в сохранённой IANA zone; elapsedRealtime в пределах boot.
   При reboot сеанс сгорает, challenges аннулируются; ожидание и cooldown
   накапливают только сохранённые монотонные интервалы. После reboot дата недостоверна.
   Восстановление: 24 часа непрерывного монотонного времени, automatic time/timezone
   включены, wall time согласовано с monotonic и день не ниже high-water mark.
   Это практическая эвристика ОС, не криптографическое доказательство даты.
   При несогласии новый сеанс закрыт. Сервер молча не добавляется.
6. Состояние сохраняется atomic file до ослабления политик ОС. Ошибка чтения —
   закрытое состояние, без автоматического сброса. ОС сохраняет ограничения без процесса;
   exact elapsed wakeup alarm завершает сеанс; boot receiver возобновляет согласование.
7. Изменения политики используют такой же flow 48 часов, proposal входит в digest.
   Enrollment возможен только до активации с ручной сверкой fingerprints.
   Аварийный ключ отдельно, роль проверяется в ядре; основной не заменяет аварийный.
8. Реальное оборудование, USB hot-unplug, камера, телефония и OEM-деактивация
   требуют отдельного отчёта. При отсутствии оборудования помечаются NOT RUN.

9. Матрица: API31 Google APIs r14 — provisioning/Lock Task подтверждены,
   clearDeviceOwnerApp падает на telephony/carriers/enforce_managed, выход не принят.
   API32–36 и Nothing Phone(3a) — нет успешной аппаратной приёмки. MinSDK31
   означает доступность API, не поддержку безопасного deprovisioning везде.
   На отказавшем образе не включать жёсткие политики до успешной проверки выхода.

## Проверенные официальные источники

- [Lock Task](https://developer.android.com/work/dpc/dedicated-devices/lock-task-mode):
  allowlist, HOME, keyguard, завершение task при удалении из allowlist.
- [Cookbook](https://developer.android.com/work/dpc/dedicated-devices/cookbook).
- [DPM](https://developer.android.com/reference/android/app/admin/DevicePolicyManager#clearDeviceOwnerApp(java.lang.String)):
  testing-only clearDeviceOwnerApp; нет гарантии очистки всех политик.
- [UserManager](https://developer.android.com/reference/android/os/UserManager):
  ограничения date/time, users, debug, safe boot и исключения DO.
- [.NET support](https://learn.microsoft.com/dotnet/core/releases-and-support):
  .NET 10 LTS до ноября 2028; перед распространением обновить servicing patch.
- [ECDsa](https://learn.microsoft.com/dotnet/api/system.security.cryptography.ecdsa)
  и [PKCS#8](https://learn.microsoft.com/dotnet/api/system.security.cryptography.asymmetricalgorithm.exportencryptedpkcs8privatekey).
- [RFC 7515](https://www.rfc-editor.org/rfc/rfc7515),
  [RFC 7518 §3.4](https://www.rfc-editor.org/rfc/rfc7518#section-3.4).

Данные решения не подменяют проверку API компилятором и тестирование устройства.
