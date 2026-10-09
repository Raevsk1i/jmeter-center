# Apache JMeter distribution

Положите сюда **распакованный** Apache JMeter (содержимое архива, не сам `.tgz`).

Ожидаемая структура:

```text
jmeter/
├── bin/
│   ├── jmeter
│   ├── jmeter-server
│   └── ...
├── lib/
└── ...
```

Пример:

```bash
# из корня репозитория
curl -L -o /tmp/jmeter.tgz https://dlcdn.apache.org/jmeter/binaries/apache-jmeter-5.6.3.tgz
tar -xzf /tmp/jmeter.tgz -C jmeter --strip-components=1
```

Использование:

- **Docker Compose agents** (`--profile agents`): каталог монтируется в `/opt/lt-jmeter` (`LT_JMETER_HOME`).
- **Controller / SSH-provisioning**: каталог монтируется в `/data/jmeter` (`LT_JMETER_BUNDLE`). При установке генератора Controller упаковывает дерево в `.tgz`, загружает по SFTP и распаковывает в `/opt/lt-jmeter` на хосте.
