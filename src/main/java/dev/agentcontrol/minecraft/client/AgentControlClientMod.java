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
import net.minecraft.state.property.Property;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public class AgentControlClientMod implements ClientModInitializer {
    private static final int PORT = Integer.getInteger("minecraftMcpStatePort", 17777);
    private static AgentControlConfig config;
    private volatile boolean running;
    private ServerSocket serverSocket;
    private AgentControlCache cache;
    private Identifier currentDimension;
    private final Set<Integer> programmaticKeys = new HashSet<>();

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

        // When MouseReleaseScreen is open, manually update keybinding states from GLFW
        // so movement works while a screen is active.
        // Skip keys that are being held programmatically via HTTP action.
        if (client.currentScreen instanceof MouseReleaseScreen) {
            long window = client.getWindow().getHandle();
            for (KeyBinding kb : client.options.allKeys) {
                if (kb != null) {
                    int code = kb.getDefaultKey().getCode();
                    if (!programmaticKeys.contains(code)) {
                        kb.setPressed(org.lwjgl.glfw.GLFW.glfwGetKey(window, code) == org.lwjgl.glfw.GLFW.GLFW_PRESS);
                    }
                }
            }
        }

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
                int keyCode = key.getDefaultKey().getCode();
                programmaticKeys.add(keyCode);
                key.setPressed(true);
                Thread release = new Thread(() -> {
                    try {
                        Thread.sleep(durationMs);
                    } catch (InterruptedException ignored) {
                    }
                    client.execute(() -> {
                        key.setPressed(false);
                        programmaticKeys.remove(keyCode);
                    });
                }, "minecraft-mcp-release-" + direction);
                release.setDaemon(true);
                release.start();
                return "{\"ok\":true,\"action\":\"move\",\"direction\":\"" + json(direction) + "\",\"durationMs\":" + durationMs + "}";
            }
            case "move_multi" -> {
                String dirs = params.getOrDefault("directions", "forward");
                int durationMs = clampInt(params.get("durationMs"), 50, 10_000, 1_000);
                String[] parts = dirs.split(",");
                List<KeyBinding> keys = new ArrayList<>();
                List<Integer> codes = new ArrayList<>();
                for (String part : parts) {
                    KeyBinding key = movementKey(client, part.trim());
                    if (key != null) {
                        keys.add(key);
                        codes.add(key.getDefaultKey().getCode());
                    }
                }
                if (keys.isEmpty()) return "{\"ok\":false,\"error\":\"bad_direction\"}";
                for (int code : codes) programmaticKeys.add(code);
                for (KeyBinding key : keys) key.setPressed(true);
                Thread release = new Thread(() -> {
                    try { Thread.sleep(durationMs); } catch (InterruptedException ignored) {}
                    client.execute(() -> {
                        for (KeyBinding key : keys) key.setPressed(false);
                        for (int code : codes) programmaticKeys.remove(code);
                    });
                }, "minecraft-mcp-release-multi");
                release.setDaemon(true);
                release.start();
                return "{\"ok\":true,\"action\":\"move_multi\",\"directions\":\"" + json(dirs) + "\",\"durationMs\":" + durationMs + "}";
            }
            case "look" -> {
                float yaw = clampFloat(params.get("yaw"), -180.0f, 180.0f, client.player.getYaw());
                float pitch = clampFloat(params.get("pitch"), -90.0f, 90.0f, client.player.getPitch());
                client.player.setYaw(yaw);
                client.player.setPitch(pitch);
                return "{\"ok\":true,\"action\":\"look\",\"yaw\":" + round(yaw) + ",\"pitch\":" + round(pitch) + "}";
            }
            case "look_at" -> {
                double x = Double.parseDouble(params.getOrDefault("x", String.valueOf(client.player.getX())));
                double y = Double.parseDouble(params.getOrDefault("y", String.valueOf(client.player.getY())));
                double z = Double.parseDouble(params.getOrDefault("z", String.valueOf(client.player.getZ())));
                // 对准方块中心：如果传入的是整数方块坐标，自动加 0.5
                if (x == Math.floor(x)) x += 0.5;
                if (y == Math.floor(y)) y += 0.5;
                if (z == Math.floor(z)) z += 0.5;
                double eyeY = client.player.getEyePos().getY();
                double dx = x - client.player.getX();
                double dy = y - eyeY;
                double dz = z - client.player.getZ();
                double distance = Math.sqrt(dx * dx + dz * dz);
                float yaw = (float) (-Math.atan2(dx, dz) * 180.0 / Math.PI);
                float pitch = (float) (-Math.atan2(dy, distance) * 180.0 / Math.PI);
                client.player.setYaw(yaw);
                client.player.setPitch(pitch);
                return "{\"ok\":true,\"action\":\"look_at\",\"yaw\":" + round(yaw) + ",\"pitch\":" + round(pitch) + "}";
            }
            case "look_facing" -> {
                String direction = params.getOrDefault("direction", "south");
                float yaw;
                float pitch = 0.0f;
                switch (direction) {
                    case "north" -> yaw = 180.0f;
                    case "south" -> yaw = 0.0f;
                    case "east" -> yaw = -90.0f;
                    case "west" -> yaw = 90.0f;
                    case "up" -> { yaw = client.player.getYaw(); pitch = -90.0f; }
                    case "down" -> { yaw = client.player.getYaw(); pitch = 90.0f; }
                    default -> yaw = client.player.getYaw();
                }
                client.player.setYaw(yaw);
                client.player.setPitch(pitch);
                return "{\"ok\":true,\"action\":\"look_facing\",\"direction\":\"" + json(direction) + "\",\"yaw\":" + round(yaw) + ",\"pitch\":" + round(pitch) + "}";
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
            case "break_block" -> {
                double bx = Double.parseDouble(params.getOrDefault("x", "0"));
                double by = Double.parseDouble(params.getOrDefault("y", "0"));
                double bz = Double.parseDouble(params.getOrDefault("z", "0"));
                // 对准方块中心
                double targetX = bx + 0.5;
                double targetY = by + 0.5;
                double targetZ = bz + 0.5;
                double eyeY = client.player.getEyePos().getY();
                double dx = targetX - client.player.getX();
                double dy = targetY - eyeY;
                double dz = targetZ - client.player.getZ();
                double horizontalDist = Math.sqrt(dx * dx + dz * dz);
                float yaw = (float) (-Math.atan2(dx, dz) * 180.0 / Math.PI);
                float pitch = (float) (-Math.atan2(dy, horizontalDist) * 180.0 / Math.PI);
                client.player.setYaw(yaw);
                client.player.setPitch(pitch);
                // 使用 attackBlock 直接攻击指定坐标的方块
                BlockPos blockPos = new BlockPos((int) bx, (int) by, (int) bz);
                if (client.interactionManager != null) {
                    // 获取方块面——从玩家方向看向方块
                    Direction side = getFacingDirection(dx, dy, dz);
                    boolean ok = client.interactionManager.attackBlock(blockPos, side);
                    client.player.swingHand(Hand.MAIN_HAND);
                    return "{\"ok\":" + ok + ",\"action\":\"break_block\",\"x\":" + bx + ",\"y\":" + by + ",\"z\":" + bz + "}";
                }
                return "{\"ok\":false,\"error\":\"no_interaction_manager\"}";
            }
            case "move_to" -> {
                double tx = Double.parseDouble(params.getOrDefault("x", String.valueOf(client.player.getX())));
                double tz = Double.parseDouble(params.getOrDefault("z", String.valueOf(client.player.getZ())));
                double curX = client.player.getX();
                double curZ = client.player.getZ();
                double distX = tx - curX;
                double distZ = tz - curZ;
                double horizontalDist = Math.sqrt(distX * distX + distZ * distZ);
                if (horizontalDist < 0.5) {
                    return "{\"ok\":true,\"action\":\"move_to\",\"message\":\"already_at_target\",\"distance\":0}";
                }
                // 面朝目标方向
                float yaw = (float) (-Math.atan2(distX, distZ) * 180.0 / Math.PI);
                client.player.setYaw(yaw);
                client.player.setPitch(0.0f);
                // 估算移动时间：约 4.3 格/秒（正常行走速度）
                int durationMs = (int) Math.max(100, Math.min(10_000, (horizontalDist / 4.3) * 1000));
                // 检查前方是否有需要跳跃的方块（脚部高度有方块阻挡）
                boolean needJump = false;
                BlockPos playerPos = client.player.getBlockPos();
                // 检查前方 1-2 格是否有 1 格高的障碍
                for (int dist = 1; dist <= 2; dist++) {
                    int checkX = (int) (curX + Math.round(distX / horizontalDist * dist));
                    int checkZ = (int) (curZ + Math.round(distZ / horizontalDist * dist));
                    BlockPos checkPos = new BlockPos(checkX, playerPos.getY(), checkZ);
                    BlockPos checkPosAbove = new BlockPos(checkX, playerPos.getY() + 1, checkZ);
                    BlockState footBlock = client.world.getBlockState(checkPos);
                    BlockState headBlock = client.world.getBlockState(checkPosAbove);
                    if (!footBlock.isAir() && headBlock.isAir()) {
                        needJump = true;
                        break;
                    }
                }
                if (needJump) {
                    // 跳跃 + 前进
                    KeyBinding jumpKey = client.options.jumpKey;
                    KeyBinding forwardKey = client.options.forwardKey;
                    int jumpCode = jumpKey.getDefaultKey().getCode();
                    int forwardCode = forwardKey.getDefaultKey().getCode();
                    programmaticKeys.add(jumpCode);
                    programmaticKeys.add(forwardCode);
                    jumpKey.setPressed(true);
                    forwardKey.setPressed(true);
                    Thread release = new Thread(() -> {
                        try { Thread.sleep(durationMs); } catch (InterruptedException ignored) {}
                        client.execute(() -> {
                            jumpKey.setPressed(false);
                            forwardKey.setPressed(false);
                            programmaticKeys.remove(jumpCode);
                            programmaticKeys.remove(forwardCode);
                        });
                    }, "minecraft-mcp-release-move-to");
                    release.setDaemon(true);
                    release.start();
                } else {
                    // 普通前进
                    KeyBinding forwardKey = client.options.forwardKey;
                    int forwardCode = forwardKey.getDefaultKey().getCode();
                    programmaticKeys.add(forwardCode);
                    forwardKey.setPressed(true);
                    Thread release = new Thread(() -> {
                        try { Thread.sleep(durationMs); } catch (InterruptedException ignored) {}
                        client.execute(() -> {
                            forwardKey.setPressed(false);
                            programmaticKeys.remove(forwardCode);
                        });
                    }, "minecraft-mcp-release-move-to");
                    release.setDaemon(true);
                    release.start();
                }
                String jumpStr = needJump ? "jump," : "";
                return "{\"ok\":true,\"action\":\"move_to\",\"targetX\":" + tx + ",\"targetZ\":" + tz + ",\"distance\":" + round(horizontalDist) + ",\"durationMs\":" + durationMs + ",\"jumped\":" + needJump + "}";
            }
            case "close_screen" -> {
                Screen current = client.currentScreen;
                if (current != null) {
                    current.close();
                }
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
        String filter = params.get("filter");

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
        builder.append("\"nearbyBlocks\":").append(nearbyBlocks(client, scanRadius, filter)).append(',');
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

    private Direction getFacingDirection(double dx, double dy, double dz) {
        double absDx = Math.abs(dx);
        double absDy = Math.abs(dy);
        double absDz = Math.abs(dz);
        if (absDy > absDx && absDy > absDz) {
            return dy > 0 ? Direction.UP : Direction.DOWN;
        }
        if (absDx > absDz) {
            return dx > 0 ? Direction.EAST : Direction.WEST;
        }
        return dz > 0 ? Direction.SOUTH : Direction.NORTH;
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
            StringBuilder sb = new StringBuilder();
            sb.append("{"
                    + "\"type\":\"block\","
                    + "\"block\":\"" + json(Registries.BLOCK.getId(state.getBlock()).toString()) + "\","
                    + "\"side\":\"" + json(blockHit.getSide().asString()) + "\","
                    + "\"x\":" + pos.getX() + ","
                    + "\"y\":" + pos.getY() + ","
                    + "\"z\":" + pos.getZ() + ","
                    + "\"distance\":" + round(distance) + ","
                    + "\"solid\":" + state.isSolidBlock(client.world, pos));
            // 添加方块属性（如颜色、朝向等）
            Collection<Property<?>> properties = state.getProperties();
            if (!properties.isEmpty()) {
                sb.append(",\"properties\":{");
                boolean first = true;
                for (Property<?> prop : properties) {
                    if (!first) sb.append(",");
                    first = false;
                    sb.append("\"" + json(prop.getName()) + "\":\"" + json(state.get(prop).toString()) + "\"");
                }
                sb.append("}");
            }
            sb.append("}");
            return sb.toString();
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

    private String nearbyBlocks(MinecraftClient client, int radius, String filter) {
        List<String> blocks = new ArrayList<>();
        BlockPos center = client.player.getBlockPos();
        boolean hasFilter = filter != null && !filter.isEmpty();
        for (int y = -radius; y <= radius; y++) {
            for (int x = -radius; x <= radius; x++) {
                for (int z = -radius; z <= radius; z++) {
                    BlockPos pos = center.add(x, y, z);
                    BlockState state = client.world.getBlockState(pos);
                    if (state.isAir()) continue;
                    String blockId = Registries.BLOCK.getId(state.getBlock()).toString();
                    if (hasFilter && !blockId.contains(filter)) continue;
                    blocks.add("{"
                            + "\"x\":" + pos.getX() + ","
                            + "\"y\":" + pos.getY() + ","
                            + "\"z\":" + pos.getZ() + ","
                            + "\"id\":\"" + json(blockId) + "\","
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
        public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
            if (keyCode == 256) { // GLFW_KEY_ESCAPE
                return super.keyPressed(keyCode, scanCode, modifiers);
            }
            // Manually update keybinding states so movement works while screen is open
            for (KeyBinding kb : client.options.allKeys) {
                if (kb != null && kb.getDefaultKey().getCode() == keyCode) {
                    kb.setPressed(true);
                }
            }
            return false;
        }

        @Override
        public boolean keyReleased(int keyCode, int scanCode, int modifiers) {
            for (KeyBinding kb : client.options.allKeys) {
                if (kb != null && kb.getDefaultKey().getCode() == keyCode) {
                    kb.setPressed(false);
                }
            }
            return false;
        }

        @Override
        public void render(DrawContext context, int mouseX, int mouseY, float delta) {
            // Intentionally transparent. It exists only to keep the OS mouse released.
        }
    }
}
