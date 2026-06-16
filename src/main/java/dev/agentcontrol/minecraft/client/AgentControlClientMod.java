package dev.agentcontrol.minecraft.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.text.Text;
import net.minecraft.world.LightType;
import net.minecraft.world.biome.Biome;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public class AgentControlClientMod implements ClientModInitializer {
    private static final int PORT = Integer.getInteger("minecraftMcpStatePort", 17777);
    private static AgentControlConfig config;
    private volatile boolean running;
    private ServerSocket serverSocket;
    private AgentControlCache cache;
    private Identifier currentDimension;

    public static AgentControlConfig config() {
        if (config == null) config = AgentControlConfig.load();
        return config;
    }

    @Override
    public void onInitializeClient() {
        config();
        try {
            serverSocket = new ServerSocket();
            serverSocket.bind(new InetSocketAddress("127.0.0.1", PORT));
            running = true;
            Thread thread = new Thread(this::serve, "agentcontrol-client");
            thread.setDaemon(true);
            thread.start();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to start AgentControl client server on 127.0.0.1:" + PORT, e);
        }

        ClientTickEvents.END_CLIENT_TICK.register(client -> onTick(client));

        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            running = false;
            if (cache != null) cache.save();
            if (serverSocket != null) {
                try {
                    serverSocket.close();
                } catch (IOException ignored) {
                }
            }
        });
    }

    private void onTick(MinecraftClient client) {
        if (client.player == null || client.world == null) return;

        Identifier dimension = client.world.getRegistryKey().getValue();
        if (currentDimension == null || !currentDimension.equals(dimension)) {
            if (cache != null) cache.save();
            currentDimension = dimension;
            cache = new AgentControlCache(dimension);
        }

        updateCache(client);
        if (cache != null) cache.saveIfNeededAsync();
    }

    private void updateCache(MinecraftClient client) {
        if (cache == null) return;
        BlockPos center = client.player.getBlockPos();
        int radius = 4; // Small radius for incremental caching
        for (int y = -radius; y <= radius; y++) {
            for (int x = -radius; x <= radius; x++) {
                for (int z = -radius; z <= radius; z++) {
                    BlockPos pos = center.add(x, y, z);
                    BlockState state = client.world.getBlockState(pos);
                    if (state.isAir()) continue;
                    String blockId = Registries.BLOCK.getId(state.getBlock()).toString();
                    cache.recordBlock(pos.getX(), pos.getY(), pos.getZ(), blockId);
                }
            }
        }
    }

    private void serve() {
        while (running) {
            try (Socket socket = serverSocket.accept()) {
                handle(socket);
            } catch (IOException e) {
                if (running) e.printStackTrace();
            }
        }
    }

    private void handle(Socket socket) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        String requestLine = reader.readLine();
        if (requestLine == null || requestLine.isEmpty()) {
            send(socket, 400, "{\"error\":\"bad_request\"}");
            return;
        }

        String[] parts = requestLine.split(" ");
        String method = parts.length > 0 ? parts[0] : "";
        String path = parts.length > 1 ? parts[1] : "";
        if (!"GET".equals(method)) {
            send(socket, 405, "{\"error\":\"method_not_allowed\"}");
            return;
        }

        if ("/health".equals(path)) {
            send(socket, 200, "{\"ok\":true}");
            return;
        }

        String route = path;
        String query = "";
        int queryIndex = path.indexOf('?');
        if (queryIndex >= 0) {
            route = path.substring(0, queryIndex);
            query = path.substring(queryIndex + 1);
        }

        if ("/action".equals(route)) {
            handleAction(socket, query);
            return;
        }

        if (!"/state".equals(route)) {
            send(socket, 404, "{\"error\":\"not_found\"}");
            return;
        }

        MinecraftClient client = MinecraftClient.getInstance();
        Map<String, String> params = query(query);
        CompletableFuture<String> future = new CompletableFuture<>();
        client.execute(() -> future.complete(snapshot(client, params)));

        try {
            send(socket, 200, future.get(2, TimeUnit.SECONDS));
        } catch (Exception e) {
            send(socket, 500, "{\"error\":\"snapshot_failed\",\"message\":\"" + json(e.getMessage()) + "\"}");
        }
    }

    private void handleAction(Socket socket, String query) throws IOException {
        MinecraftClient client = MinecraftClient.getInstance();
        Map<String, String> params = query(query);
        CompletableFuture<String> future = new CompletableFuture<>();
        client.execute(() -> future.complete(action(client, params)));

        try {
            send(socket, 200, future.get(2, TimeUnit.SECONDS));
        } catch (Exception e) {
            send(socket, 500, "{\"ok\":false,\"error\":\"action_failed\",\"message\":\"" + json(e.getMessage()) + "\"}");
        }
    }

    private String action(MinecraftClient client, Map<String, String> params) {
        if (client.player == null || client.world == null) {
            return "{\"ok\":false,\"error\":\"not_in_game\"}";
        }

        String type = params.getOrDefault("type", "");
        switch (type) {
            case "move" -> {
                String direction = params.getOrDefault("direction", "forward");
                int durationMs = clampInt(params.get("durationMs"), 50, 10_000, 1_000);
                KeyBinding key = movementKey(client, direction);
                if (key == null) return "{\"ok\":false,\"error\":\"bad_direction\"}";
                key.setPressed(true);
                Thread release = new Thread(() -> {
                    try {
                        Thread.sleep(durationMs);
                    } catch (InterruptedException ignored) {
                    }
                    client.execute(() -> key.setPressed(false));
                }, "minecraft-mcp-release-" + direction);
                release.setDaemon(true);
                release.start();
                return "{\"ok\":true,\"action\":\"move\",\"direction\":\"" + json(direction) + "\",\"durationMs\":" + durationMs + "}";
            }
            case "look" -> {
                float yaw = clampFloat(params.get("yaw"), -180.0f, 180.0f, client.player.getYaw());
                float pitch = clampFloat(params.get("pitch"), -90.0f, 90.0f, client.player.getPitch());
                client.player.setYaw(yaw);
                client.player.setPitch(pitch);
                return "{\"ok\":true,\"action\":\"look\",\"yaw\":" + round(yaw) + ",\"pitch\":" + round(pitch) + "}";
            }
            case "attack" -> {
                boolean ok = false;
                if (client.interactionManager != null && client.crosshairTarget instanceof EntityHitResult entityHit) {
                    client.interactionManager.attackEntity(client.player, entityHit.getEntity());
                    client.player.swingHand(Hand.MAIN_HAND);
                    ok = true;
                } else if (client.interactionManager != null && client.crosshairTarget instanceof BlockHitResult blockHit) {
                    ok = client.interactionManager.attackBlock(blockHit.getBlockPos(), blockHit.getSide());
                    client.player.swingHand(Hand.MAIN_HAND);
                }
                return "{\"ok\":" + ok + ",\"action\":\"attack\"}";
            }
            case "use" -> {
                if (client.interactionManager == null) return "{\"ok\":false,\"error\":\"no_interaction_manager\"}";
                if (client.crosshairTarget instanceof BlockHitResult blockHit) {
                    client.interactionManager.interactBlock(client.player, Hand.MAIN_HAND, blockHit);
                } else if (client.crosshairTarget instanceof EntityHitResult entityHit) {
                    client.interactionManager.interactEntity(client.player, entityHit.getEntity(), Hand.MAIN_HAND);
                } else {
                    client.interactionManager.interactItem(client.player, Hand.MAIN_HAND);
                }
                client.player.swingHand(Hand.MAIN_HAND);
                return "{\"ok\":true,\"action\":\"use\"}";
            }
            case "break_crosshair" -> {
                if (!(client.crosshairTarget instanceof BlockHitResult blockHit) || client.interactionManager == null) {
                    return "{\"ok\":false,\"error\":\"no_block_target\"}";
                }
                BlockPos pos = blockHit.getBlockPos();
                Direction side = blockHit.getSide();
                boolean ok = client.interactionManager.attackBlock(pos, side);
                client.player.swingHand(Hand.MAIN_HAND);
                return "{\"ok\":" + ok + ",\"action\":\"break_crosshair\",\"x\":" + pos.getX() + ",\"y\":" + pos.getY() + ",\"z\":" + pos.getZ() + "}";
            }
            case "place_crosshair" -> {
                if (!(client.crosshairTarget instanceof BlockHitResult blockHit) || client.interactionManager == null) {
                    return "{\"ok\":false,\"error\":\"no_block_target\"}";
                }
                client.interactionManager.interactBlock(client.player, Hand.MAIN_HAND, blockHit);
                client.player.swingHand(Hand.MAIN_HAND);
                return "{\"ok\":true,\"action\":\"place_crosshair\"}";
            }
            case "select_slot" -> {
                int slot = clampInt(params.get("slot"), 0, 8, 0);
                client.player.getInventory().selectedSlot = slot;
                return "{\"ok\":true,\"action\":\"select_slot\",\"slot\":" + slot + "}";
            }
            case "drop" -> {
                boolean stack = "true".equalsIgnoreCase(params.getOrDefault("stack", "false"));
                if (client.player != null) {
                    client.player.dropSelectedItem(stack);
                }
                return "{\"ok\":true,\"action\":\"drop\",\"stack\":" + stack + "}";
            }
            case "swap_hands" -> {
                client.options.swapHandsKey.setPressed(true);
                Thread release = new Thread(() -> {
                    try { Thread.sleep(100); } catch (InterruptedException ignored) {}
                    client.execute(() -> client.options.swapHandsKey.setPressed(false));
                }, "minecraft-mcp-swap-hands");
                release.setDaemon(true);
                release.start();
                return "{\"ok\":true,\"action\":\"swap_hands\"}";
            }
            case "close_screen" -> {
                client.setScreen(null);
                return "{\"ok\":true,\"action\":\"close_screen\"}";
            }
            case "release_mouse" -> {
                boolean captureMouse = config().captureMouseOnReleaseAction;
                client.setScreen(captureMouse ? null : new MouseReleaseScreen());
                return "{\"ok\":true,\"action\":\"release_mouse\",\"captureMouse\":" + captureMouse + "}";
            }
            default -> {
                return "{\"ok\":false,\"error\":\"unknown_action\"}";
            }
        }
    }

    private String snapshot(MinecraftClient client, Map<String, String> params) {
        String screen = client.currentScreen == null ? null : client.currentScreen.getClass().getName();
        if (client.player == null || client.world == null) {
            return "{"
                    + "\"inGame\":false,"
                    + "\"screen\":" + nullable(screen)
                    + "}";
        }

        BlockPos pos = client.player.getBlockPos();
        int scanRadius = clampInt(params.get("scanRadius"), 1, 16, 4);

        StringBuilder builder = new StringBuilder();
        builder.append('{');
        builder.append("\"inGame\":true,");
        builder.append("\"screen\":").append(nullable(screen)).append(',');
        builder.append("\"dimension\":\"").append(json(client.world.getRegistryKey().getValue().toString())).append("\",");
        builder.append("\"position\":{");
        builder.append("\"x\":").append(round(client.player.getX())).append(',');
        builder.append("\"y\":").append(round(client.player.getY())).append(',');
        builder.append("\"z\":").append(round(client.player.getZ())).append(',');
        builder.append("\"blockX\":").append(pos.getX()).append(',');
        builder.append("\"blockY\":").append(pos.getY()).append(',');
        builder.append("\"blockZ\":").append(pos.getZ()).append("},");
        builder.append("\"rotation\":{");
        builder.append("\"yaw\":").append(round(client.player.getYaw())).append(',');
        builder.append("\"pitch\":").append(round(client.player.getPitch())).append("},");
        builder.append("\"facing\":\"").append(json(facingDirection(client.player.getYaw()))).append("\",");
        builder.append("\"onGround\":").append(client.player.isOnGround()).append(',');
        builder.append("\"health\":{");
        builder.append("\"health\":").append(round(client.player.getHealth())).append(',');
        builder.append("\"maxHealth\":").append(round(client.player.getMaxHealth())).append(',');
        builder.append("\"food\":").append(client.player.getHungerManager().getFoodLevel()).append(',');
        builder.append("\"oxygen\":").append(client.player.getAir()).append("},");
        builder.append("\"experience\":{");
        builder.append("\"level\":").append(client.player.experienceLevel).append(',');
        builder.append("\"progress\":").append(round(client.player.experienceProgress)).append("},");
        builder.append("\"inventory\":").append(inventory(client.player.getInventory())).append(',');
        builder.append("\"equipment\":").append(equipment(client.player)).append(',');
        builder.append("\"environment\":").append(environment(client)).append(',');
        builder.append("\"crosshairTarget\":").append(crosshairTarget(client)).append(',');
        builder.append("\"nearbyBlocks\":").append(nearbyBlocks(client, scanRadius)).append(',');
        builder.append("\"nearbyEntities\":").append(nearbyEntities(client, 16.0)).append(',');
        builder.append("\"cache\":").append(cacheInfo());
        builder.append('}');
        return builder.toString();
    }

    private String inventory(PlayerInventory inventory) {
        List<String> items = new ArrayList<>();
        for (int slot = 0; slot < inventory.main.size(); slot++) {
            ItemStack stack = inventory.main.get(slot);
            if (!stack.isEmpty()) items.add(item(slot, stack));
        }
        return "{\"selectedSlot\":" + inventory.selectedSlot + ",\"items\":[" + String.join(",", items) + "]}";
    }

    private String item(int slot, ItemStack stack) {
        return "{"
                + "\"slot\":" + slot + ","
                + "\"id\":\"" + json(Registries.ITEM.getId(stack.getItem()).toString()) + "\","
                + "\"name\":\"" + json(stack.getName().getString()) + "\","
                + "\"count\":" + stack.getCount()
                + (stack.isDamageable() ? ",\"damage\":" + stack.getDamage() + ",\"maxDamage\":" + stack.getMaxDamage() : "")
                + "}";
    }

    private String equipment(net.minecraft.client.network.ClientPlayerEntity player) {
        ItemStack head = player.getInventory().getArmorStack(0);
        ItemStack chest = player.getInventory().getArmorStack(1);
        ItemStack legs = player.getInventory().getArmorStack(2);
        ItemStack feet = player.getInventory().getArmorStack(3);
        ItemStack offHand = player.getInventory().offHand.get(0);
        return "{"
                + "\"head\":" + (head.isEmpty() ? "null" : item(-1, head)) + ","
                + "\"chest\":" + (chest.isEmpty() ? "null" : item(-1, chest)) + ","
                + "\"legs\":" + (legs.isEmpty() ? "null" : item(-1, legs)) + ","
                + "\"feet\":" + (feet.isEmpty() ? "null" : item(-1, feet)) + ","
                + "\"offHand\":" + (offHand.isEmpty() ? "null" : item(-1, offHand))
                + "}";
    }

    private String environment(MinecraftClient client) {
        BlockPos pos = client.player.getBlockPos();
        Biome biome = client.world.getBiome(pos).value();
        String biomeId = client.world.getBiome(pos).getKey().map(key -> key.getValue().toString()).orElse("unknown");
        int blockLight = client.world.getLightLevel(LightType.BLOCK, pos);
        int skyLight = client.world.getLightLevel(LightType.SKY, pos);
        int rawLight = client.world.getLightLevel(pos);
        long timeOfDay = client.world.getTimeOfDay() % 24000;

        return "{"
                + "\"biome\":\"" + json(biomeId) + "\","
                + "\"timeOfDay\":" + timeOfDay + ","
                + "\"weather\":{"
                + "\"raining\":" + client.world.isRaining() + ","
                + "\"thundering\":" + client.world.isThundering()
                + "},"
                + "\"lightLevel\":{"
                + "\"blockLight\":" + blockLight + ","
                + "\"skyLight\":" + skyLight + ","
                + "\"rawLight\":" + rawLight
                + "}"
                + "}";
    }

    private String facingDirection(float yaw) {
        float normalized = ((yaw % 360) + 360) % 360;
        if (normalized >= 315 || normalized < 45) return "South";
        if (normalized >= 45 && normalized < 135) return "West";
        if (normalized >= 135 && normalized < 225) return "North";
        return "East";
    }

    private String crosshairTarget(MinecraftClient client) {
        HitResult target = client.crosshairTarget;
        if (target == null || target.getType() == HitResult.Type.MISS) return "null";

        Vec3d playerPos = client.player.getPos();
        double distance = target.getPos().distanceTo(playerPos);

        if (target instanceof BlockHitResult blockHit) {
            BlockPos pos = blockHit.getBlockPos();
            BlockState state = client.world.getBlockState(pos);
            return "{"
                    + "\"type\":\"block\","
                    + "\"block\":\"" + json(Registries.BLOCK.getId(state.getBlock()).toString()) + "\","
                    + "\"side\":\"" + json(blockHit.getSide().asString()) + "\","
                    + "\"x\":" + pos.getX() + ","
                    + "\"y\":" + pos.getY() + ","
                    + "\"z\":" + pos.getZ() + ","
                    + "\"distance\":" + round(distance) + ","
                    + "\"solid\":" + state.isSolidBlock(client.world, pos)
                    + "}";
        }

        if (target instanceof EntityHitResult entityHit) {
            Entity entity = entityHit.getEntity();
            double health = entity instanceof LivingEntity living ? living.getHealth() : -1.0;
            double maxHealth = entity instanceof LivingEntity living ? living.getMaxHealth() : -1.0;
            List<String> effects = new ArrayList<>();
            if (entity instanceof LivingEntity living) {
                for (StatusEffectInstance effect : living.getStatusEffects()) {
                    effects.add("\"" + json(Registries.STATUS_EFFECT.getId(effect.getEffectType().value()).toString()) + "\"");
                }
            }
            return "{"
                    + "\"type\":\"entity\","
                    + "\"entity\":\"" + json(Registries.ENTITY_TYPE.getId(entity.getType()).toString()) + "\","
                    + "\"name\":\"" + json(entity.getName().getString()) + "\","
                    + "\"health\":" + round(health) + ","
                    + "\"maxHealth\":" + round(maxHealth) + ","
                    + "\"distance\":" + round(distance) + ","
                    + "\"effects\":[" + String.join(",", effects) + "]"
                    + "}";
        }

        return "null";
    }

    private String nearbyBlocks(MinecraftClient client, int radius) {
        List<String> blocks = new ArrayList<>();
        BlockPos center = client.player.getBlockPos();
        for (int y = -radius; y <= radius; y++) {
            for (int x = -radius; x <= radius; x++) {
                for (int z = -radius; z <= radius; z++) {
                    BlockPos pos = center.add(x, y, z);
                    BlockState state = client.world.getBlockState(pos);
                    if (state.isAir()) continue;
                    blocks.add("{"
                            + "\"x\":" + pos.getX() + ","
                            + "\"y\":" + pos.getY() + ","
                            + "\"z\":" + pos.getZ() + ","
                            + "\"id\":\"" + json(Registries.BLOCK.getId(state.getBlock()).toString()) + "\","
                            + "\"solid\":" + state.isSolidBlock(client.world, pos)
                            + "}");
                }
            }
        }
        return "[" + String.join(",", blocks) + "]";
    }

    private String nearbyEntities(MinecraftClient client, double radius) {
        Vec3d pos = client.player.getPos();
        Box box = new Box(pos.x - radius, pos.y - radius, pos.z - radius, pos.x + radius, pos.y + radius, pos.z + radius);
        List<String> entities = new ArrayList<>();
        for (Entity entity : client.world.getOtherEntities(client.player, box)) {
            Vec3d entityPos = entity.getPos();
            double health = entity instanceof LivingEntity living ? living.getHealth() : -1.0;
            double maxHealth = entity instanceof LivingEntity living ? living.getMaxHealth() : -1.0;
            List<String> effects = new ArrayList<>();
            if (entity instanceof LivingEntity living) {
                for (StatusEffectInstance effect : living.getStatusEffects()) {
                    effects.add("\"" + json(Registries.STATUS_EFFECT.getId(effect.getEffectType().value()).toString()) + "\"");
                }
            }
            entities.add("{"
                    + "\"id\":" + entity.getId() + ","
                    + "\"type\":\"" + json(Registries.ENTITY_TYPE.getId(entity.getType()).toString()) + "\","
                    + "\"name\":\"" + json(entity.getName().getString()) + "\","
                    + "\"x\":" + round(entityPos.x) + ","
                    + "\"y\":" + round(entityPos.y) + ","
                    + "\"z\":" + round(entityPos.z) + ","
                    + "\"distance\":" + round(entity.distanceTo(client.player)) + ","
                    + "\"health\":" + round(health) + ","
                    + "\"maxHealth\":" + round(maxHealth) + ","
                    + "\"effects\":[" + String.join(",", effects) + "]"
                    + "}");
        }
        return "[" + String.join(",", entities) + "]";
    }

    private String cacheInfo() {
        if (cache == null) {
            return "{\"enabled\":false,\"blockCount\":0}";
        }
        return "{"
                + "\"enabled\":true,"
                + "\"dimension\":\"" + json(currentDimension != null ? currentDimension.toString() : "unknown") + "\","
                + "\"blockCount\":" + cache.getBlockCount()
                + "}";
    }

    private void send(Socket socket, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        try (PrintWriter writer = new PrintWriter(socket.getOutputStream(), false, StandardCharsets.UTF_8)) {
            writer.print("HTTP/1.1 " + status + " " + statusText(status) + "\r\n");
            writer.print("Content-Type: application/json; charset=utf-8\r\n");
            writer.print("Content-Length: " + bytes.length + "\r\n");
            writer.print("Connection: close\r\n");
            writer.print("\r\n");
            writer.flush();
            socket.getOutputStream().write(bytes);
        }
    }

    private String statusText(int status) {
        return switch (status) {
            case 200 -> "OK";
            case 400 -> "Bad Request";
            case 404 -> "Not Found";
            case 405 -> "Method Not Allowed";
            default -> "Internal Server Error";
        };
    }

    private KeyBinding movementKey(MinecraftClient client, String direction) {
        return switch (direction) {
            case "forward" -> client.options.forwardKey;
            case "back" -> client.options.backKey;
            case "left" -> client.options.leftKey;
            case "right" -> client.options.rightKey;
            case "jump" -> client.options.jumpKey;
            case "sneak" -> client.options.sneakKey;
            case "sprint" -> client.options.sprintKey;
            default -> null;
        };
    }

    private Map<String, String> query(String query) {
        Map<String, String> params = new HashMap<>();
        if (query == null || query.isEmpty()) return params;
        for (String pair : query.split("&")) {
            int split = pair.indexOf('=');
            if (split < 0) params.put(urlDecode(pair), "");
            else params.put(urlDecode(pair.substring(0, split)), urlDecode(pair.substring(split + 1)));
        }
        return params;
    }

    private String urlDecode(String value) {
        return java.net.URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private int clampInt(String value, int min, int max, int fallback) {
        try {
            int parsed = Integer.parseInt(value);
            return Math.max(min, Math.min(max, parsed));
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private float clampFloat(String value, float min, float max, float fallback) {
        try {
            float parsed = Float.parseFloat(value);
            return Math.max(min, Math.min(max, parsed));
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private String nullable(String value) {
        return value == null ? "null" : "\"" + json(value) + "\"";
    }

    private String round(double value) {
        return String.format(java.util.Locale.ROOT, "%.3f", value);
    }

    private String json(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }

    private static class MouseReleaseScreen extends Screen {
        private MouseReleaseScreen() {
            super(Text.literal("AgentControl Mouse Release"));
        }

        @Override
        public boolean shouldPause() {
            return false;
        }

        @Override
        public void render(DrawContext context, int mouseX, int mouseY, float delta) {
            // Intentionally transparent. It exists only to keep the OS mouse released.
        }
    }
}
