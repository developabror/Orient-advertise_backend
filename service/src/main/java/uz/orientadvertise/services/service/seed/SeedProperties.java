package uz.orientadvertise.services.service.seed;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Local-development seed configuration. Bound from {@code app.seed.*}.
 *
 * <p>Defaults to {@link #enabled} = {@code false} so prod / test stay clean unless
 * explicitly opted in via {@code application-dev.yml} or the {@code APP_SEED_ENABLED}
 * env var. The seeder itself is always wired as a bean — it just no-ops when disabled.
 */
@ConfigurationProperties(prefix = "app.seed")
public class SeedProperties {

    private boolean enabled = false;
    private int deviceCount = 5;
    private String serialPrefix = "FAKE-TV-";
    private String namePrefix = "Fake Android TV ";

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public int getDeviceCount() { return deviceCount; }
    public void setDeviceCount(int deviceCount) { this.deviceCount = deviceCount; }

    public String getSerialPrefix() { return serialPrefix; }
    public void setSerialPrefix(String serialPrefix) { this.serialPrefix = serialPrefix; }

    public String getNamePrefix() { return namePrefix; }
    public void setNamePrefix(String namePrefix) { this.namePrefix = namePrefix; }
}
