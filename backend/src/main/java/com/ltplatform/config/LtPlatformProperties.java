package com.ltplatform.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ltplatform")
public class LtPlatformProperties {
    private String masterKey;
    private String artifactRoot;
    private String logRoot;
    private String jmeterBundlePath;
    private String agentBinaryPath;
    /**
     * Host/IP that remote agents use to reach this controller's gRPC port.
     * Must be reachable from generator hosts (not the Docker service name unless agents
     * share the same Compose network). Overridable via Settings → Controller advertise host.
     */
    private String controllerAdvertiseHost = "";
    private int agentRegisterWaitSeconds = 90;
    private Grpc grpc = new Grpc();
    private int heartbeatTimeoutSeconds = 45;
    private DefaultAdmin defaultAdmin = new DefaultAdmin();

    public String getMasterKey() { return masterKey; }
    public void setMasterKey(String masterKey) { this.masterKey = masterKey; }
    public String getArtifactRoot() { return artifactRoot; }
    public void setArtifactRoot(String artifactRoot) { this.artifactRoot = artifactRoot; }
    public String getLogRoot() { return logRoot; }
    public void setLogRoot(String logRoot) { this.logRoot = logRoot; }
    public String getJmeterBundlePath() { return jmeterBundlePath; }
    public void setJmeterBundlePath(String jmeterBundlePath) { this.jmeterBundlePath = jmeterBundlePath; }
    public String getAgentBinaryPath() { return agentBinaryPath; }
    public void setAgentBinaryPath(String agentBinaryPath) { this.agentBinaryPath = agentBinaryPath; }
    public String getControllerAdvertiseHost() { return controllerAdvertiseHost; }
    public void setControllerAdvertiseHost(String controllerAdvertiseHost) {
        this.controllerAdvertiseHost = controllerAdvertiseHost;
    }
    public int getAgentRegisterWaitSeconds() { return agentRegisterWaitSeconds; }
    public void setAgentRegisterWaitSeconds(int agentRegisterWaitSeconds) {
        this.agentRegisterWaitSeconds = agentRegisterWaitSeconds;
    }
    public Grpc getGrpc() { return grpc; }
    public void setGrpc(Grpc grpc) { this.grpc = grpc; }
    public int getHeartbeatTimeoutSeconds() { return heartbeatTimeoutSeconds; }
    public void setHeartbeatTimeoutSeconds(int heartbeatTimeoutSeconds) {
        this.heartbeatTimeoutSeconds = heartbeatTimeoutSeconds;
    }
    public DefaultAdmin getDefaultAdmin() { return defaultAdmin; }
    public void setDefaultAdmin(DefaultAdmin defaultAdmin) { this.defaultAdmin = defaultAdmin; }

    public static class Grpc {
        private int port = 9090;
        private String certsDir = "./data/certs";
        public int getPort() { return port; }
        public void setPort(int port) { this.port = port; }
        public String getCertsDir() { return certsDir; }
        public void setCertsDir(String certsDir) { this.certsDir = certsDir; }
    }

    public static class DefaultAdmin {
        private String username = "admin";
        private String password = "admin";
        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
    }
}
