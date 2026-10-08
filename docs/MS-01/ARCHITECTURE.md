# Microshop: архитектурная основа backend

## Статус и границы

Согласована основа для MS-01-01: один Gradle-проект, пакет
`dev.nkudrin.microshop`, Spring MVC, Spring Data JPA/Hibernate, Flyway и
отдельные тесты. Реализация каркаса разрешена до завершения полного
подготовительного проектирования из [ROADMAP.md](../ROADMAP.md).

Карта доменных модулей, их контракты и зависимости, общие API-соглашения
и конкретные планы загрузки JPA пока не спроектированы. Этот документ не
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
