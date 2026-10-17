# Focus Guard — файлы лабораторной поставки

Код и русские инструкции: [codex/focus-guard-source](https://github.com/thevoidcreator/focus-guard/tree/codex/focus-guard-source),
коммит [`aa4e313`](https://github.com/thevoidcreator/focus-guard/commit/aa4e3132376e9af0186d758054e9d7fb1f6710ae).

Сначала прочитайте [START_HERE_RU.md](START_HERE_RU.md) и [TEST_REPORT.md](TEST_REPORT.md).

| Файл | Скачать |
| --- | --- |
| Исходники и инструкции | [FocusGuard-source.zip](FocusGuard-source.zip?raw=true) |
| Windows x64 | [FocusGuard-Windows-win-x64.zip](FocusGuard-Windows-win-x64.zip?raw=true) |
| Android lab debug | [FocusGuard-lab-debug.apk](FocusGuard-lab-debug.apk?raw=true) |
| Android production, активация заблокирована | [FocusGuard-production-disabled-unsigned.apk](FocusGuard-production-disabled-unsigned.apk?raw=true) |
| Состав и точный коммит | [MANIFEST.json](MANIFEST.json?raw=true) |
| Контрольные суммы SHA-256 | [SHA256SUMS.txt](SHA256SUMS.txt?raw=true) |

Репозиторий приватный. Войдите в GitHub с доступом к нему; на странице файла
нажмите **Download raw file**. Прежние локальные ссылки чата для этой выдачи не нужны.

Архив исходников заново создан из отслеживаемого дерева указанного коммита.
APK и Windows ZIP побайтово совпадают с предыдущей проверенной сборкой.
Обновление выдачи не означает прохождения оставшихся аппаратных испытаний.
Секреты и приватные ключи в поставку не включены. Production activation закрыта:
безопасное снятие Device Owner на основном Nothing Phone (3a) не подтверждено.

Проверить хеш в Windows: `Get-FileHash .\FocusGuard-source.zip -Algorithm SHA256`.
Linux: `sha256sum -c SHA256SUMS.txt`.
