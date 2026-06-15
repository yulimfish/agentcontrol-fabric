package dev.agentcontrol.minecraft.client;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public class AgentControlConfig {
    private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve("agentcontrol.properties");

    public boolean captureMouseOnReleaseAction = false;

    public static AgentControlConfig load() {
        AgentControlConfig config = new AgentControlConfig();
        Properties properties = new Properties();
        if (Files.exists(CONFIG_PATH)) {
            try (var input = Files.newInputStream(CONFIG_PATH)) {
                properties.load(input);
            } catch (IOException ignored) {
            }
        }
        config.captureMouseOnReleaseAction = Boolean.parseBoolean(properties.getProperty("captureMouseOnReleaseAction", "false"));
        return config;
    }

    public void save() {
        Properties properties = new Properties();
        properties.setProperty("captureMouseOnReleaseAction", Boolean.toString(captureMouseOnReleaseAction));
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            try (var output = Files.newOutputStream(CONFIG_PATH)) {
                properties.store(output, "AgentControl settings");
            }
        } catch (IOException ignored) {
        }
    }
}
