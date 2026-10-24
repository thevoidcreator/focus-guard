> Документ относится к прежней поставке 0.1. Для версии 0.2 начните с
> [START_HERE_RU](../START_HERE_RU.md), [FG2](../protocol/FG2_RU.md) и
> [отчёта 0.2](TEST_REPORT_V02.md). Старые результаты не являются проверкой 0.2.

# Скачивание и проверка поставки

Предыдущие ссылки на локальные файлы в чате не обеспечили скачивание.
Файлы заново проверены по SHA-256 и CRC ZIP/APK и подготовлены для выдачи через
подключённый приватный репозиторий GitHub.

- Код и инструкции: [codex/focus-guard-source](https://github.com/thevoidcreator/focus-guard/tree/codex/focus-guard-source).
- Все файлы: [codex/focus-guard-downloads](https://github.com/thevoidcreator/focus-guard/tree/codex/focus-guard-downloads).
- [Новый FocusGuard-source.zip](https://github.com/thevoidcreator/focus-guard/blob/codex/focus-guard-downloads/FocusGuard-source.zip?raw=true).
- [Windows win-x64 ZIP](https://github.com/thevoidcreator/focus-guard/blob/codex/focus-guard-downloads/FocusGuard-Windows-win-x64.zip?raw=true).
- [Android lab debug APK](https://github.com/thevoidcreator/focus-guard/blob/codex/focus-guard-downloads/FocusGuard-lab-debug.apk?raw=true).
- [Android production disabled unsigned APK](https://github.com/thevoidcreator/focus-guard/blob/codex/focus-guard-downloads/FocusGuard-production-disabled-unsigned.apk?raw=true).

Войдите в GitHub с учётной записью, которой доступен этот репозиторий. Если браузер
открывает страницу файла, используйте **Download raw file**. Для ZIP/APK не
сохраняйте HTML-страницу через «Сохранить страницу как». Публичный доступ не включён.

В ветке файлов также находятся START_HERE_RU.md, TEST_REPORT.md, MANIFEST.json и
SHA256SUMS.txt. MANIFEST.json указывает точный коммит исходников; архив исходников
создан из его отслеживаемых файлов с помощью git archive. Бинарные файлы сохранены
в отдельной ветке выдачи; ветка исходников содержит только код и документацию.

Проверка скачанного файла в Windows:

```powershell
Get-FileHash .\FocusGuard-source.zip -Algorithm SHA256
```

Сравните значение с SHA256SUMS.txt. Для Linux: `sha256sum -c SHA256SUMS.txt`.
APK/Windows ZIP совпадают с ранее проверенной лабораторной сборкой; повторная
упаковка документации не означает новых аппаратных испытаний. Production activation
остаётся заблокированной, полный выход без потери данных не подтверждён.
