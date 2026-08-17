# Prodamus Windows Client

Нативный Windows-клиент Prodamus для менеджера по продажам. Приложение авторизуется на центральном backend `https://prodamus.abs7.ru`, получает только доступные менеджеру роли и краткоживущий Gemini Live ephemeral token, а затем подключается к Gemini Live напрямую по WebSocket.

## Архитектура безопасности

В Windows-клиенте нет постоянных Gemini API keys, системных промптов, базы знаний или настроек модели. Они управляются администратором на сервере. Backend формирует скрытую AI-конфигурацию и выдаёт клиенту только краткоживущий токен для конкретной Live-сессии.

Пароль менеджера не сохраняется. При включённом «Запомнить меня» на компьютере сохраняется только refresh token, защищённый Windows DPAPI. Access token хранится только в памяти процесса.

## Что реализовано

- Java 21 + JavaFX + Spring Boot, без Lombok.
- Вход по логину/паролю через Prodamus backend.
- Восстановление запомненной серверной сессии через DPAPI.
- Выбор только тех ролей продаж, которые назначены пользователю в back-office.
- Серверная проверка минимальной/актуальной версии клиента и ссылка на установщик.
- Серверная аренда AI credential и контроль одной активной сессии на менеджера.
- Прямое соединение Windows-клиента с Gemini Live по ephemeral token; постоянный API key в приложение не передаётся.
- Автоматический heartbeat серверной Live-сессии.
- Session resumption и автоматическое переподключение Gemini Live с последним resumption handle.
- Автоматическое обновление ephemeral token для длинных разговоров.
- Раздельный захват микрофона менеджера и системного звука клиента через Windows WASAPI loopback.
- Локальный VAD и потоковая отправка 16 kHz PCM в Gemini Live прямо во время речи, без накопления целой реплики.
- Частичная транскрипция клиента и частичная подсказка обновляются в одной живой карточке чата по мере поступления фрагментов.
- Компактный overlay и расширенный режим полной истории разговора/подсказок.
- История сохраняется при переключении compact/expanded в рамках текущего запуска, но не отправляется в backend и не записывается на диск.
- В расширенной истории auto-follow отключается, если менеджер прокрутил вверх; появляется кнопка «К последнему».
- Живой переключатель `WDA_EXCLUDEFROMCAPTURE` — можно включать/выключать исключение окна из поддерживаемых механизмов захвата Windows без перезапуска.
- Опциональное поле ручного контекста клиента, управляемое серверным feature flag.
- AI-ключи, model ID, endpoint, промпт и база знаний отсутствуют в локальном окне настроек.
- Логотип Prodamus показывается на экране входа; в рабочем overlay используется только компактная текстовая надпись, без большого логотипа.

## Первый запуск

Перед проверкой клиента в back-office должны существовать:

1. пользователь менеджера с логином и паролем;
2. хотя бы одна включённая роль, назначенная этому пользователю;
3. хотя бы один включённый Gemini credential со свободной ёмкостью.

Без назначенной роли клиент успешно войдёт, но кнопка старта будет заблокирована. Без свободного AI credential backend не создаст Live-сессию.

## Запуск в IntelliJ IDEA

Требуется Windows и JDK 21.

1. Откройте корень проекта или `pom.xml` в IntelliJ IDEA.
2. Убедитесь, что Project SDK = JDK 21.
3. Выполните Maven Reload.
4. Запустите конфигурацию **Prodamus** или класс:

```text
ru.prodamus.client.ProdamusClientApplication
```

Из PowerShell:

```powershell
$env:JAVA_HOME = 'C:\Users\EDM\.jdks\temurin-21.0.11'
.\mvnw.cmd spring-boot:run
```

Backend по умолчанию:

```text
https://prodamus.abs7.ru
```

Версия клиента задаётся в `src/main/resources/application.properties`:

```properties
prodamus.client.version=1.1.0
```

## Локальные настройки менеджера

В шестерёнке доступны только параметры, которые действительно относятся к конкретному компьютеру:

- микрофон менеджера;
- устройство вывода / голос клиента;
- порог локального VAD;
- длительность паузы окончания реплики;
- прозрачность overlay.

Переключатель скрытия из screen capture находится прямо в рабочем окне и применяется немедленно.

## Сборка и тесты

```powershell
.\mvnw.cmd clean test
.\mvnw.cmd clean package
```

Fat JAR:

```text
target\prodamus-client.jar
```

## Сборка Windows EXE

В проект добавлен `package-windows.ps1`. Он сначала собирает fat JAR, затем вызывает `jpackage.exe` из JDK 21:

```powershell
$env:JAVA_HOME = 'C:\Users\EDM\.jdks\temurin-21.0.11'
.\package-windows.ps1
```

Результат появляется в:

```text
dist\
```

## Логи

```text
%LOCALAPPDATA%\Prodamus\logs\prodamus-client.log
```

В логах нет пароля пользователя, постоянного Gemini API key, системного промпта, базы знаний или base64-аудио.

## Поток данных

```text
Менеджер вводит login/password
        │
        ▼
https://prodamus.abs7.ru
        │
        ├─ access/refresh tokens
        ├─ назначенные роли + feature flags + version policy
        └─ start Live session
                │
                ├─ backend выбирает свободный Gemini credential
                ├─ backend формирует скрытый prompt/knowledge/config
                └─ backend выдаёт ephemeral token
                            │
                            ▼
Windows WASAPI → локальный VAD → Gemini Live WebSocket
                            │
                            ▼
                  транскрипция + подсказки
                            │
                            ▼
                      Prodamus overlay
```

Backend не проксирует realtime-аудио и не хранит историю разговора. Он отвечает за учётные записи, роли, AI credentials, policy/config, выдачу ephemeral token, capacity/leases и контроль версии приложения.
