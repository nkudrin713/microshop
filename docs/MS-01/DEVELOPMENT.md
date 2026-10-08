# Microshop: сборка и локальная разработка

## Требования

- Установленный JDK 25. Gradle toolchain выбирает его для компиляции;
  JDK 17 вместо него недостаточен.
- Доступ к Gradle distribution, Gradle Plugin Portal и Maven Central для
  первой загрузки Wrapper и зависимостей.
- Системный Gradle не требуется: используется включенный Wrapper 9.7.1.
- Для интеграционных тестов нужен запущенный Docker Engine или Docker Desktop,
  доступный текущему пользователю. Testcontainers скачивает `postgres:18.6`
  и служебный образ Ryuk; нужен доступ к registry при первой загрузке.

Проверить активную Java:

```sh
java -version
```

На macOS можно выбрать установленный JDK 25 для текущего терминала:

```sh
export JAVA_HOME="$(/usr/libexec/java_home -v 25)"
export PATH="$JAVA_HOME/bin:$PATH"
```

На других системах `JAVA_HOME` задается путем к установленному JDK 25.
Автоматическое скачивание JDK в проекте не настроено.

## Команды

Все команды выполняются из корня репозитория:

```sh
./gradlew --version
./gradlew compileKotlin
./gradlew check
./gradlew build
```

На Windows вместо `./gradlew` используется `gradlew.bat`.

`compileKotlin` проверяет production-исходники. `check` выполняет подключенные
проверки, включая тесты по мере их появления. `build` также собирает
исполняемый JAR: `build/libs/microshop-0.0.1-SNAPSHOT.jar`.

Версия `0.0.1-SNAPSHOT` — версия артефакта разработки, а не выпущенный релиз.

## Тестовое окружение PostgreSQL

Узкая проверка окружения:

```sh
./gradlew test --tests 'dev.nkudrin.microshop.PostgresEnvironmentTest'
```

Повторный запуск с обязательным выполнением тестов, даже если Gradle считает
их актуальными:

```sh
./gradlew test --tests 'dev.nkudrin.microshop.PostgresEnvironmentTest' --rerun-tasks
```

Все тесты запускаются через `./gradlew test`; они также входят в `check` и
`build`. Отчет: `build/reports/tests/test/index.html`. Отсутствие Docker
прерывает запуск тестового контекста с ошибкой Testcontainers. Пропуск тестов
при недоступном Docker и подмена PostgreSQL на H2 не настроены.

`PostgresTestConfiguration` объявляет контейнер Spring-бином с
`@ServiceConnection`: Spring Boot запускает его до потребляющих соединение
бинов и останавливает при закрытии контекста. Подключение использует адрес,
случайный порт и учетные данные контейнера; `spring.datasource.*` не определяет
тестовое подключение. Это проверяется с заведомо нерабочими параметрами БД.
Контейнер не использует reuse или пользовательские volumes.

`PostgresEnvironmentTest` поднимает только JDBC-контекст с
`DataSourceAutoConfiguration` и `ServiceConnectionAutoConfiguration`:
последняя обрабатывает `@ServiceConnection` на контейнерном бине. Каждый метод
закрывает его через `@DirtiesContext(AFTER_EACH_TEST_METHOD)`; следующий
получает новый контейнер и пустую БД. Оба метода проверяют отсутствие таблицы
предыдущего теста, выполняют SQL и читают результат через отдельное соединение
после явного commit или rollback. Порядок методов значения не имеет.

Для следующих интеграционных тестов импортируй `PostgresTestConfiguration`.
Для полной изоляции используй тот же режим `@DirtiesContext`; контейнерный
контекст нельзя делить с тестами, оставляющими данные. Тесты commit и
конкурентных операций выполняй без `@Transactional` на тестовом методе:
транзакции задаются явно, а результат проверяется другим соединением.

## Подключение к БД и локальный запуск

Подключение задается стандартной внешней конфигурацией Spring Boot.
Обязательные параметры для локальной PostgreSQL:

| Переменная окружения | Значение |
|---|---|
| `SPRING_DATASOURCE_URL` | JDBC URL вида `jdbc:postgresql://<host>:<port>/<database>` |
| `SPRING_DATASOURCE_USERNAME` | Пользователь БД |
| `SPRING_DATASOURCE_PASSWORD` | Пароль пользователя БД |

URL и учетные данные не имеют значений по умолчанию в исходниках. Драйвер
определяется по JDBC URL. PostgreSQL и указанная БД должны уже существовать;
Flyway создает таблицы внутри БД. Пользователю БД нужны права на создание и
изменение объектов схемы для миграций.

Пример с placeholders, которые нужно заменить локальными значениями:

```sh
export SPRING_DATASOURCE_URL='jdbc:postgresql://<host>:<port>/<database>'
export SPRING_DATASOURCE_USERNAME='<db-user>'
export SPRING_DATASOURCE_PASSWORD='<db-password>'
./gradlew bootRun
```

С теми же переменными можно запустить собранный JAR:

```sh
java -jar build/libs/microshop-0.0.1-SNAPSHOT.jar
```

Spring Boot не загружает `.env` автоматически. Переменные передаются процессу
через окружение или локальную конфигурацию Spring; реальные значения не
добавляются в Git. Преобразование установочных `POSTGRES_*` в конфигурацию
backend относится к deployment-комплекту MS-13.

При старте Flyway применяет миграции и проверяет их checksums. Hibernate
использует `ddl-auto=validate`; Open EntityManager in View отключен.
Инициализация через `schema.sql`/`data.sql` отключена (`spring.sql.init.mode=never`),
схемой управляет Flyway. Ошибка миграции прерывает старт приложения.

Пока создана только `admin_user`; миграция не создает владельца. Вход, setup,
служебные таблицы Spring Session и административный API реализуются в MS-02.

## SQL-миграции

Production-миграции находятся в `src/main/resources/db/migration/`.
Имена: `V<N>__<description>.sql`, где `N` — следующий целый номер, начиная
с `1`, а описание — на английском в `snake_case`. Первая миграция:
`V1__create_admin_user.sql`. Каждый номер используется один раз; новые
миграции добавляются по порядку.

Уже примененную миграцию не редактируй и не переименовывай: изменение
существующей схемы оформляется следующей версией. Flyway хранит историю
и checksums в `flyway_schema_history`; повторный старт не выполняет
примененные миграции заново. Не включай `baseline-on-migrate` и не меняй
историю миграций ради обхода ошибки запуска.

Узкая приемка MS-01-03:

```sh
./gradlew test --tests 'dev.nkudrin.microshop.DatabaseMigrationTest'
```

Каждый метод получает отдельную PostgreSQL. Контейнер принадлежит тестовому
Spring-контексту, а приложение запускается на нем с внешними параметрами БД.
В сценарии повторного старта контейнер продолжает работать, пока два
последовательных контекста приложения открываются и закрываются.

Проверяются пустая БД без владельца, сохранение данных и истории миграций,
типы и ограничения `admin_user`, эффективный `ddl-auto=validate` и отсутствие
бинов Open EntityManager in View в servlet-контексте. Ошибочный SQL находится
только в `src/test/resources/db/invalid-migration/` и подключается явно в
отрицательном сценарии. Проверка несовпадения JPA-маппинга со схемой относится
к MS-01-07 после появления сущности audit.

## Локальные файлы

Результаты сборки, Gradle/Kotlin cache, IDE-файлы, `.env`, локальные
`application-local.*`, ключи и локальный `AGENTS.md` исключены из Git.
В примерах конфигурации допускаются только названия параметров и placeholders,
без реальных secrets.

Сообщения коммитов следуют [соглашению Git](../GIT.md).
