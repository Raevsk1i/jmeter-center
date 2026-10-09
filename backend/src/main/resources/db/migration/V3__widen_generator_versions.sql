-- java -version / jmeter -v first lines often exceed 64 chars
ALTER TABLE generators
    ALTER COLUMN agent_version TYPE VARCHAR(255),
    ALTER COLUMN java_version TYPE VARCHAR(512),
    ALTER COLUMN jmeter_version TYPE VARCHAR(512);
