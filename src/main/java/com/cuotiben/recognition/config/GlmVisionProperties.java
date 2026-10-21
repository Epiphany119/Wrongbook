package com.cuotiben.recognition.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;

@ConfigurationProperties(prefix = "app.glm-vision")
public class GlmVisionProperties {
    private boolean enabled;
    private String apiKey = "";
    private URI apiUrl = URI.create("https://open.bigmodel.cn/api/paas/v4/chat/completions");
    private String model = "glm-4.6v-flash";
    private Duration timeout = Duration.ofSeconds(120);
    private long maxImageBytes = 10 * 1024 * 1024;
    private int minReadableCharacters = 5;
    private double minContentRatio = 0.30;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public URI getApiUrl() { return apiUrl; }
    public void setApiUrl(URI apiUrl) { this.apiUrl = apiUrl; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public Duration getTimeout() { return timeout; }
    public void setTimeout(Duration timeout) { this.timeout = timeout; }
    public long getMaxImageBytes() { return maxImageBytes; }
    public void setMaxImageBytes(long maxImageBytes) { this.maxImageBytes = maxImageBytes; }
    public int getMinReadableCharacters() { return minReadableCharacters; }
    public void setMinReadableCharacters(int minReadableCharacters) { this.minReadableCharacters = minReadableCharacters; }
    public double getMinContentRatio() { return minContentRatio; }
    public void setMinContentRatio(double minContentRatio) { this.minContentRatio = minContentRatio; }
}
