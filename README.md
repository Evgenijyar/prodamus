# Prodamus Backend

Центральный backend и web back-office для **Prodamus — ИИ-ассистента продаж**.

Проект рассчитан на дальнейшее подключение нативного Windows-клиента. Bitrix24 в эту сборку намеренно не входит: backend уже выделен так, чтобы Bitrix позже добавлялся отдельным интеграционным слоем, не меняя базовую авторизацию, роли и AI-сессии.

## Стек

- Java 21
- Spring Boot 4.1.0
- Spring MVC + REST
- Spring Data JPA / Hibernate
- PostgreSQL
- Flyway
- Maven
- Docker
- чистые HTML/CSS/JavaScript для back-office

В проекте **нет Lombok, Thymeleaf, Spring Security и YAML-конфигурации**.

## Что уже реализовано

### Back-office

Адрес локально: `http://localhost:8080/backoffice`

Первоначальный вход:

- login: `admin`
- password: `secret123`

Параметры можно переопределить переменными окружения `PRODAMUS_ADMIN_LOGIN` и `PRODAMUS_ADMIN_PASSWORD`.

Back-office умеет:

- добавлять/редактировать/отключать менеджеров;
- задавать логин и пароль Windows-клиента;
- назначать каждому менеджеру доступные роли;
- создавать роли / prompt profiles;
- хранить system prompt и текстовую базу знаний роли;
- версионировать prompt profile при каждом изменении;
- добавлять несколько Gemini API keys;
- шифровать Gemini API keys AES-256-GCM перед записью в PostgreSQL;
- задавать capacity каждого ключа;
- проверять ключ через создание Gemini ephemeral token;
- видеть технические Live-сессии и принудительно завершать их;
- управлять global prompt, минимальной/актуальной версией Windows-клиента и feature flags;
- смотреть технический audit log.

Транскрипты и аудио разговоров backend не сохраняет.

### API Windows-клиента

#### Авторизация

- `POST /api/client/auth/login`
- `POST /api/client/auth/refresh`
- `POST /api/client/auth/logout`

Пароль нигде не сохраняется в открытом виде. Backend выдаёт случайные opaque access/refresh tokens, а в PostgreSQL хранит только SHA-256 hash токенов.

Сроки по умолчанию:

- access token: 30 минут;
- обычный refresh token: 24 часа;
- `rememberMe=true`: 7 дней.

На клиентской стороне будущий refresh token нужно сохранять через Windows DPAPI.

#### Bootstrap

- `GET /api/client/bootstrap`
- заголовок `X-Prodamus-Client-Version`

Возвращает только разрешённые пользователю роли, feature flags и информацию об обновлении клиента. Содержимое промптов клиенту не отдаётся.

#### Live sessions

- `POST /api/client/live-sessions`
- `POST /api/client/live-sessions/{id}/heartbeat`
- `DELETE /api/client/live-sessions/{id}`

На START сервер:

1. проверяет пользователя и выбранную роль;
2. блокировкой PostgreSQL выбирает AI credential со свободной capacity;
3. создаёт lease сессии;
4. собирает effective system instruction из global prompt + роли + knowledge base + персональных инструкций менеджера;
5. постоянным Gemini API key на сервере создаёт constrained ephemeral token;
6. отдаёт Windows-клиенту только ephemeral token + WebSocket endpoint + model + sessionId.

Постоянный Gemini API key в Windows-клиент не передаётся.

Lease обновляется heartbeat-ом. Если приложение аварийно исчезло, просроченная сессия автоматически освобождает capacity.
Для одного менеджера одновременно разрешена одна активная Live-сессия — это защищает пул ключей от случайного двойного START.

## Настройка PostgreSQL

Все параметры находятся только в `src/main/resources/application.properties`.

Defaults этой сборки:

```properties
PRODAMUS_DB_HOST=45.11.92.142
PRODAMUS_DB_PORT=5433
PRODAMUS_DB_NAME=prodamus
PRODAMUS_DB_USER=prodamus_app
PRODAMUS_DB_PASSWORD=Pdm_7Jq4wL9x2Nf8cR5v
```

При необходимости они переопределяются обычными environment variables без изменения исходников.

Flyway сам создаёт таблицы при первом старте. Вручную требуется создать только PostgreSQL database + role.

## Локальный запуск в IntelliJ IDEA

1. Открыть папку проекта как Maven project.
2. Убедиться, что Project SDK = Java 21.
3. Создать БД/роль PostgreSQL.
4. Запустить `ru.prodamus.backend.ProdamusBackendApplication`.
5. Открыть `http://localhost:8080/backoffice`.

Либо из терминала Windows:

```powershell
.\mvnw.cmd spring-boot:run
```

## Сборка

```powershell
.\mvnw.cmd clean package
```

JAR появится в `target/`.

## Docker

```bash
docker build -t prodamus-backend:latest .
docker run -d --name prodamus-backend --restart unless-stopped -p 8080:8080 prodamus-backend:latest
```

## Деплой из GitHub

`deploy.py` запускается **в клонированном GitHub-репозитории на сервере**:

```bash
python3 deploy.py
```

Скрипт:

- `git fetch` + `reset --hard origin/main`;
- собирает новый Docker image;
- сохраняет предыдущий контейнер для rollback;
- запускает новый контейнер;
- ждёт `/actuator/health`;
- если новая версия не поднялась, автоматически возвращает предыдущий контейнер;
- если всё хорошо, удаляет предыдущий контейнер и выводит последние логи.

## Production environment

Перед внешней публикацией рекомендуется задать на сервере как минимум:

```bash
export PRODAMUS_MASTER_KEY='<32 random bytes as Base64>'
export PRODAMUS_ADMIN_PASSWORD='<strong admin password>'
export PRODAMUS_SESSION_SECURE=true
```

`PRODAMUS_MASTER_KEY` нельзя менять после добавления AI credentials, иначе уже сохранённые ключи невозможно будет расшифровать. Для production ключ необходимо хранить вне Git.

## Health

- `GET /actuator/health`

По нему `deploy.py` определяет, что приложение и PostgreSQL поднялись корректно.
