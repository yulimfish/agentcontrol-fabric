package dev.agentcontrol.minecraft.client;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.GridWidget;
import net.minecraft.client.gui.widget.SimplePositioningWidget;
import net.minecraft.screen.ScreenTexts;
import net.minecraft.text.Text;

public class AgentControlModMenu implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return ConfigScreen::new;
    }

    private static class ConfigScreen extends Screen {
        private final Screen parent;
        private AgentControlConfig config;

        private ConfigScreen(Screen parent) {
            super(Text.translatable("text.agentcontrol.config.title"));
            this.parent = parent;
        }

        @Override
        protected void init() {
            config = AgentControlClientMod.config();
            GridWidget grid = new GridWidget();
            grid.getMainPositioner().margin(4, 4, 4, 4);
            GridWidget.Adder adder = grid.createAdder(1);

            adder.add(ButtonWidget.builder(label(), button -> {
                config.captureMouseOnReleaseAction = !config.captureMouseOnReleaseAction;
                config.save();
                button.setMessage(label());
            }).tooltip(Tooltip.of(Text.translatable("text.agentcontrol.config.capture_mouse.tooltip"))).width(280).build());

            adder.add(ButtonWidget.builder(ScreenTexts.DONE, button -> close()).width(120).build());
            grid.refreshPositions();
            SimplePositioningWidget.setPos(grid, 0, 0, width, height, 0.5f, 0.5f);
            grid.forEachChild(this::addDrawableChild);
        }

        @Override
        public void close() {
            client.setScreen(parent);
        }

        private Text label() {
            return Text.translatable(
                    "text.agentcontrol.config.capture_mouse",
                    Text.translatable(config.captureMouseOnReleaseAction ? "options.on" : "options.off")
            );
        }
    }
}
