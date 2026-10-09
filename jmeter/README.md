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

Docker Compose монтирует этот каталог:

- в **agent-*** → `/opt/lt-jmeter` (`LT_JMETER_HOME`)
- в **controller** → `/data/jmeter` (`LT_JMETER_BUNDLE`, для SSH-provisioning на RHEL)
