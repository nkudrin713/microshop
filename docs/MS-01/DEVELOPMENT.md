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

## Текущая граница запуска

В MS-01-01 подключены JPA, Flyway и PostgreSQL driver, но параметры БД и
миграции еще не реализованы. Поэтому `bootRun` и запуск JAR сейчас не являются
рабочим сценарием: они требуют настройки PostgreSQL из MS-01-03.

MS-01-02 проверяет окружение PostgreSQL. Проверки доменных ограничений,
миграций, HTTP API и healthcheck добавляются в соответствующих задачах.

## Локальные файлы

Результаты сборки, Gradle/Kotlin cache, IDE-файлы, `.env`, локальные
`application-local.*`, ключи и локальный `AGENTS.md` исключены из Git.
В примерах конфигурации допускаются только названия параметров и placeholders,
без реальных secrets.

Сообщения коммитов следуют [соглашению Git](../GIT.md).
