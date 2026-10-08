# Microshop: сборка и локальная разработка

## Требования

- Установленный JDK 25. Gradle toolchain выбирает его для компиляции;
  JDK 17 вместо него недостаточен.
- Доступ к Gradle distribution, Gradle Plugin Portal и Maven Central для
  первой загрузки Wrapper и зависимостей.
- Системный Gradle не требуется: используется включенный Wrapper 9.7.1.

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
В MS-01-01 тестов еще нет: `test` завершается как `NO-SOURCE`.

## Текущая граница запуска

В MS-01-01 подключены JPA, Flyway и PostgreSQL driver, но параметры БД и
миграции еще не реализованы. Поэтому `bootRun` и запуск JAR сейчас не являются
рабочим сценарием: они требуют настройки PostgreSQL из MS-01-03.

Docker и Testcontainers потребуются для MS-01-02. Проверки SQL, ограничений,
миграций, HTTP API и healthcheck добавляются в соответствующих задачах.

## Локальные файлы

Результаты сборки, Gradle/Kotlin cache, IDE-файлы, `.env`, локальные
`application-local.*`, ключи и локальный `AGENTS.md` исключены из Git.
В примерах конфигурации допускаются только названия параметров и placeholders,
без реальных secrets.
