# Focus Guard protocol v1

Транспорт — compact JWS ASCII, максимум 16384 байта, без пробелов. QR запроса ПК:
`{"envelopeVersion":1,"phoneSpki":"base64url DER SPKI","request":"compact JWS"}`.
Envelope не является доверенным: подпись проверяется закреплённым публичным ключом.
Для первого enrollment сравниваются fingerprints на обоих устройствах. Позднее
несовпадение идентичности не допускает автоматическую перепривязку.

JWS protected header содержит ровно `alg=ES256`, `typ=FG1`. Неизвестные поля,
crit, b64, alg=none/RS256, повторные JSON-ключи, несколько JSON roots, глубина >8,
неподдерживаемые version, неканонический base64url и подпись не 64 байта отклоняются.
Набор полей Message фиксирован схемой. UTF-8 JSON не канонизируется перед проверкой:
проверяются исходные `encodedHeader + '.' + encodedPayload`. Nimbus и IdentityModel
выдают/проверяют JWS с P-256, SHA-256, R||S по 32 байта, а не DER.

keyId = base64url(SHA256(DER SubjectPublicKeyInfo P-256)). Все base64url без `=`.
requestDigest = base64url(SHA256(точные ASCII/UTF-8 байты compact JWS запроса)),
включая две точки и подпись, без newline. Не hash envelope, JSON или signature отдельно.
Время ПК не влияет на принятие ответа. В запросе нет доверенных Windows timestamps.

Поля: protocolVersion=1; messageType; installationId; requestId (случайный UUID);
nonce (32 CSPRNG байта); keyId (целевой USB); phoneKeyId; policyVersion;
removalFlowId; phase; requestDigest; role; publicKey; proposal.
`publicKey` — только для enrollment. `proposal` — полный будущий snapshot политики.
Неиспользуемые строки пусты, proposal=null; поля всегда присутствуют.

Телефон подписывает запрос своим Keystore-ключом. ПК проверяет его и показывает
устройство, действие, запрос, flow, fingerprint и последствия. Подписанный ответ
должен совпадать со **всеми** полями исходного запроса, кроме phase=`response` и
requestDigest. В частности proposal не может быть изменён при финальном шаге.

Один текущий challenge на installation; новый аннулирует предыдущий. Challenge
содержит boot generation и elapsedRealtime создания; TTL 15 минут. Reboot аннулирует
challenge, но сохраняет flow. Потребление ответа + изменение flow + аннулирование
challenge — одна атомарная запись. Потоки входа сериализованы одним lock.

enrollment — только до activation. Основной и аварийный ключи различны.
removal_start/request_start — новый flow, успешный ответ запускает 48 часов.
removal_finish/request_finish — генерируется только после DELAY подтверждённого
монотонного времени, keyId тот же, flowId совпадает. policy_change использует тот же
двухфазный процесс и неизменный proposal. emergency_release принимает только отдельно
зарегистрированный аварийный ключ. Отмена flow удаляет pending challenge и flow.
Старая подпись, изменённый requestId, другой телефон/role/key/policy/digest отвергаются.

Граница гарантии: Windows может подписать переданный вредоносным клиентом запрос,
но телефон принимает только свой живой сохранённый challenge. Часы Windows и
наличие копии USB не доказывают ожидание. Удаление не считается завершённым, пока
адаптер ОС не проверил отсутствие Device Owner/admin и восстановление пакетов.

## Векторы

`vectors/python.json`, `kotlin.json`, `dotnet.json` содержат публичный SPKI, compact
JWS и ожидаемый exact-byte digest. Приватные тестовые ключи не сохраняются.
Оба приложения и Python проверяют взаимную совместимость. Подписи ECDSA случайны,
поэтому регенерация — отдельная операция обслуживания; обычная сборка использует
зафиксированные векторы. VectorTool не входит в Windows-поставку.
