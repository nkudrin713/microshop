# Microshop: архитектурная основа backend

## Статус и границы

Согласована основа для MS-01-01: один Gradle-проект, пакет
`dev.nkudrin.microshop`, Spring MVC, Spring Data JPA/Hibernate, Flyway и
отдельные тесты. Реализация каркаса разрешена до завершения полного
подготовительного проектирования из [ROADMAP.md](../ROADMAP.md).

Карта доменных модулей, их контракты и зависимости, успешные ответы API
и конкретные планы загрузки JPA пока не спроектированы. Контракт ошибок API
для MS-01-04 определен ниже. Этот документ не
означает завершения всего подготовительного проектирования. Перед следующими
задачами уточняются относящиеся к ним решения.

## Стек и сборка

| Компонент | Версия или механизм |
|---|---|
| JDK и целевая JVM | 25; фиксируются Gradle Java toolchain |
| Kotlin JVM, Spring и JPA plugins | 2.4.20 |
| Spring Boot | 4.1.1 |
| Gradle Wrapper | 9.7.1; дистрибутив проверяется по SHA-256 |
| Dependency management plugin | 1.1.7 |
| HTTP | Spring MVC, встроенный servlet-контейнер |
| JSON | Jackson с Kotlin-модулем |
| Валидация | Jakarta Bean Validation через Spring Boot |
| Persistence | Spring Data JPA, Hibernate, PostgreSQL JDBC driver |
| Миграции | Flyway с модулем PostgreSQL |
| Диагностика | Spring Boot Actuator |
| Тестирование | JUnit, Kotlin Test, Spring Test, MockMvc, Testcontainers PostgreSQL |

Зависимости библиотек управляются BOM Spring Boot; их отдельные версии
не задаются. Версии Kotlin-библиотек согласуются с Kotlin Gradle plugin
механизмом Spring Boot. Для тестов подключены `spring-boot-testcontainers`
и `testcontainers-postgresql`; версия Testcontainers 2.0.5 управляется BOM.

Совместимость инструментов сверена с [требованиями Spring Boot](https://docs.spring.io/spring-boot/system-requirements.html)
и [таблицей совместимости Kotlin Gradle plugin](https://kotlinlang.org/docs/gradle-configure-project.html).
JDK 25 поддерживается Spring Boot и выбранным Gradle; установленный JDK 17
не заменяет toolchain 25 при компиляции.

## Структура приложения

Backend — один Gradle-проект в корне репозитория. `group` — `dev.nkudrin`,
имя проекта — `microshop`. Точка входа — `MicroshopApplicationKt.main`,
конфигурация Spring Boot — `MicroshopApplication` в корневом пакете.

```text
build.gradle.kts
settings.gradle.kts
gradle/wrapper/
src/main/kotlin/dev/nkudrin/microshop/MicroshopApplication.kt
src/main/resources/application.yaml
src/test/kotlin/dev/nkudrin/microshop/  ← тесты по мере реализации
docs/MS-01/
```

Доменные пакеты добавляются вместе с согласованной реализацией, без пустых
контроллеров, сервисов и сущностей. Модульность не требует отдельных сервисов
или Gradle-проектов. Kotlin Spring plugin открывает классы Spring для proxy;
для JPA настроены no-arg и открытие `Entity`, `MappedSuperclass`, `Embeddable`.

## Конфигурация и проверки

Подключение к PostgreSQL задается внешними `spring.datasource.*`, в том числе
переменными `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME` и
`SPRING_DATASOURCE_PASSWORD`; credentials в исходниках отсутствуют.
Flyway при старте применяет SQL из `classpath:db/migration` и проверяет
checksums. Hibernate работает с `ddl-auto=validate`; Open EntityManager in View
и инициализация через `schema.sql`/`data.sql` отключены.

В MS-01-03 миграция `V1__create_admin_user.sql` создает `admin_user` по
[DATA_MODEL.md](../DATA_MODEL.md), без владельца, setup и входа. Имена миграций
имеют вид `V<N>__<description>.sql`; примененные версии неизменяемы.

Исходники тестов находятся отдельно от production-кода. MS-01-02 добавляет
узкий JDBC-тест на контейнерной PostgreSQL. MS-01-03 проверяет старт полного
приложения, повторный старт на той же БД, ограничения таблицы и ошибку миграции.
Одна компиляция или сборка JAR не доказывает успешный запуск с PostgreSQL.
БД-зависимые автоконфигурации не отключаются ради фиктивного успешного запуска.

Тестовый образ зафиксирован как `postgres:18.6`, без `latest`; PostgreSQL 18
выбрана базовой major-версией для будущей поставки. Это поддерживаемая версия
из [таблицы PostgreSQL](https://www.postgresql.org/support/versioning/).
Конкретный production-образ фиксируется при реализации поставки в MS-13;
тестовый образ должен сохранять совместимость с ним.

Контейнер — бин `PostgresTestConfiguration`, параметры подключения передаются
через `@ServiceConnection`. Время жизни управляется Spring Boot, как описано
в [документации Testcontainers](https://docs.spring.io/spring-boot/reference/testing/testcontainers.html).
SQL-тесты получают новый контекст и контейнер на каждый метод; reuse и внешние
volumes не используются. Изоляция не зависит от rollback тестового метода.

Spring Security и Spring Session JDBC остаются выбранными механизмами для
MS-02; их интеграция, служебные миграции сессий и CSRF в MS-01-01 не реализуются.
Команды сборки описаны в [DEVELOPMENT.md](DEVELOPMENT.md).

## Контракт ошибок API

Основа — [RFC 9457 и Spring ProblemDetail](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-ann-rest-exceptions.html).
Общий `@RestControllerAdvice` расширяет `ResponseEntityExceptionHandler`.
Ответ имеет `Content-Type: application/problem+json`, в том числе при ошибке
согласования формата ответа.

| Поле | Содержание |
|---|---|
| `type` | `about:blank`; категория определяется расширением `code` |
| `title` | Стандартная английская фраза HTTP-статуса |
| `status` | Числовой HTTP-статус, совпадающий со статусом ответа |
| `detail` | Фиксированное безопасное описание на английском |
| `instance` | Путь запроса без query string |
| `code` | Стабильный машинный код из таблицы ниже |
| `errors` | Необязательный массив `{field, code, message}` для ошибок валидации |

| Сценарий | `code` | HTTP |
|---|---|---|
| Отсутствующее или некорректное JSON-тело | `MALFORMED_JSON` | 400 |
| Неверный тип параметра | `TYPE_MISMATCH` | 400 |
| Bean Validation входных DTO или параметров | `VALIDATION_ERROR` | 400 |
| Прочие некорректные запросы, включая отсутствие обязательного параметра | `INVALID_REQUEST` | 400 |
| Ресурс не найден | `NOT_FOUND` | 404 |
| Конфликт состояния операции | `CONFLICT` | 409 |
| Неподдерживаемый HTTP-метод | `METHOD_NOT_ALLOWED` | 405 |
| Неподдерживаемый Content-Type | `UNSUPPORTED_MEDIA_TYPE` | 415 |
| Неподдерживаемый формат ответа | `NOT_ACCEPTABLE` | 406 |
| Неожиданная внутренняя ошибка | `INTERNAL_ERROR` | 500 |

Для прочих стандартных исключений Spring MVC сохраняются исходные HTTP-статусы
и служебные заголовки: ошибки 4xx используют `INVALID_REQUEST`, ошибки 5xx —
`INTERNAL_ERROR`. Нарушение ограничения возвращаемого значения — внутренняя
ошибка; детали валидации результата наружу не выдаются.

DTO проверяются через `@Valid @RequestBody` и Jakarta Bean Validation;
в Kotlin ограничения полей задаются с use-site target `@field:`. Для ограничений
параметров используется встроенная валидация Spring MVC без `@Validated` на
классе контроллера. `errors[].field` содержит имя поля DTO или параметра;
для ограничения всего объекта оно равно `null`. `errors[].code` — известное
имя ограничения (`NotBlank`, `NotNull`, `NotEmpty`, `Size`, `Min`, `Max`,
`Positive`, `PositiveOrZero`, `Email`, `Pattern`), иначе `Invalid`.
`message` — фиксированный английский текст; сообщения аннотаций и отвергнутые
значения не копируются в ответ.

Ошибки операций могут выбрасывать `ApiException` с выбранным `ApiError`, например
`INVALID_REQUEST`, `NOT_FOUND` или `CONFLICT`. Эти общие категории не реализуют
торговые операции; специальные коды добавляются при проектировании модулей.

Ответы не содержат имен исключений, SQL, stack trace, secrets или входных
значений. Обработчик не пишет тело запроса, cookie, заголовки авторизации и
сообщения исключений в журналы. Для неожиданных ошибок журналируется только
тип исключения. Контрольные значения проверяются в ответах и захваченных
журналах MockMvc-тестов. Тестовые контроллеры отсутствуют в production-исходниках.
Ответы 401/403 из фильтров Spring Security подключаются к этому контракту в MS-02.

## Транзакционный audit

MS-01-05 добавляет пакет `audit` и миграцию `V2__create_audit_log.sql` по
[DATA_MODEL.md](../DATA_MODEL.md). `AuditLog` хранит UUID, автора, действие,
тип и текстовый id объекта, необязательные JSON-снимки и время `Instant`.
JSON отображается средствами Hibernate в `jsonb`, время — в `timestamptz`.
FK автора запрещает удаление связанного `admin_user`; предусмотрены индексы
объекта, времени и FK. `ADMIN` требует существующего пользователя, для
`SYSTEM` ссылка необязательна.

Вызывающий сервис начинает транзакцию и обращается к Spring-бину
`AuditWriter.record`. [Propagation.MANDATORY](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/transaction/annotation/Propagation.html#MANDATORY)
запрещает запись вне существующей транзакции. `saveAndFlush` проверяет запись
до возврата из метода; исключение помечает общую транзакцию для rollback,
даже если вызывающий код его перехватит. Flush не означает commit.
Самостоятельной транзакции и обработки после commit нет.

`before` и `after` принимают только `AuditSnapshot`. Вызывающий модуль
определяет разрешенные поля конкретной операции и передает их явно в
`AuditSnapshot.select(source, ...allowedFields)`. Список должен исключать
пароли, token и secrets; общего автоматического снимка сущности или запроса
нет. Выбираются только указанные поля, их значения копируются в неизменяемую
карту. Поддерживаются скалярные JSON-значения: строки, boolean, конечные числа
и null. Объекты, вложенные карты и коллекции отклоняются, чтобы не сериализовать
скрытые поля. UUID, время и enum вызывающий модуль преобразует в строки явно.
Расширение формата для конкретной операции проектируется вместе с ее модулем.

Контракт проверяется на PostgreSQL тестовым изменением подготовленной записи
администратора: commit, rollback, ошибка FK и перехваченное исключение audit.
Проверка снимков использует вход с контрольными паролем, token и secrets и
проверяет сохраненный JSON. API просмотра журнала, создание владельца,
торговые операции и `order_event` в MS-01-05 не реализуются.

## Healthcheck backend

В MS-01-06 используется штатный [Actuator health endpoint](https://docs.spring.io/spring-boot/reference/actuator/endpoints.html#actuator.endpoints.health).
`management.endpoints.access.default=none` отключает остальные endpoint;
для `health` разрешен доступ `read-only`. По HTTP публикуется только `health`,
страница обнаружения `/actuator` отключена. `show-details=never` и
`show-components=never` скрывают конфигурацию, компоненты и сведения об ошибках
в публичном ответе, включая обращения к отдельным компонентам.
Отдельные группы `liveness` и `readiness` отключены через `probes.enabled=false`;
публичный контракт использует общий `/actuator/health` с проверкой БД.

`management.health.db.enabled=true` включает проверку PostgreSQL штатным
индикатором DataSource. После успешного запуска `/actuator/health` возвращает
HTTP 200 и `{"status":"UP"}`. При недоступной БД ответ — HTTP 503 и
`{"status":"DOWN"}`. Настройки стандартных индикаторов и таймаутов подключения
остаются средствами Spring Boot; собственный health controller не требуется.

Flyway и проверка JPA остаются обязательными при запуске. Ошибка миграции
прерывает запуск и закрывает HTTP-сервер. Готовность не зависит от владельца,
setup, публикации магазина или включения продаж. Проверки Caddy, витрины,
installer и Docker healthcheck реализуются в MS-13 по [DELIVERY.md](../DELIVERY.md).

## Приемка основы приложения

MS-01 завершен: backend запускается на пустой PostgreSQL без владельца,
применяет обе миграции, проверяет JPA-маппинг audit и предоставляет healthcheck.
Повторный запуск сохраняет администратора, audit и историю миграций.
Несовместимый тип `audit_log.created_at` прерывает запуск; Hibernate не
исправляет схему, история Flyway не меняется, healthcheck недоступен.

Итоговая сборка проверяет ошибки миграции, недоступную БД, общий контракт
ошибок API, безопасные снимки и атомарность audit. Команды и параметры
локального запуска приведены в [DEVELOPMENT.md](DEVELOPMENT.md).
Создание владельца, Spring Security и Spring Session остаются задачами MS-02;
полное проектирование доменных модулей не входит в завершенную основу.
