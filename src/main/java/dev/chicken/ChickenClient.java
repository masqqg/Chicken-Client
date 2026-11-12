package dev.chicken;

import com.mojang.blaze3d.platform.InputConstants;
import dev.chicken.events.GameLeftEvent;
import dev.chicken.events.Render2DEvent;
import dev.chicken.events.Render3DEvent;
import dev.chicken.events.TickEvent;
import dev.chicken.gui.ClickGui;
import dev.chicken.hud.Hud;
import dev.chicken.render.Renderer2D;
import dev.chicken.render.Renderer3D;
import dev.chicken.systems.Modules;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

/**
 * Chicken Client — a standalone, client-side utility client.
 *
 * <p>Nothing in here depends on another client or addon API: the module system, settings,
 * event bus, HUD, GUI and the world renderer are all part of this mod.</p>
 */
public class ChickenClient implements ClientModInitializer {
    public static final String MOD_ID = "chicken-client";
    public static final String NAME = "Chicken Client";
    public static final String VERSION = FabricLoader.getInstance()
        .getModContainer(MOD_ID)
        .map(container -> container.getMetadata().getVersion().getFriendlyString())
        .orElse("dev");
    public static final Logger LOG = LoggerFactory.getLogger(NAME);

    public static final Path FOLDER = FabricLoader.getInstance().getConfigDir().resolve(MOD_ID);

    private static boolean clickGuiKeyDown;

    @Override
    public void onInitializeClient() {
        LOG.info("Starting {} {}", NAME, VERSION);

        Modules.get().load();

        ClientTickEvents.END_CLIENT_TICK.register(ChickenClient::onTick);
        HudRenderCallback.EVENT.register(ChickenClient::onHudRender);
        WorldRenderEvents.AFTER_ENTITIES.register(ChickenClient::onWorldRender);

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            Modules.get().post(new GameLeftEvent());
            Modules.get().save();
        });

        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> Modules.get().save());
    }

    private static void onTick(Minecraft client) {
        if (client.player == null || client.level == null) {
            clickGuiKeyDown = false;
            return;
        }

        Modules.get().tick(true);

        boolean down = InputConstants.isKeyDown(client.getWindow(), GLFW.GLFW_KEY_RIGHT_SHIFT);

        if (down && !clickGuiKeyDown && client.screen == null) {
            client.setScreen(new ClickGui());
        }

        clickGuiKeyDown = down;

        Modules.get().tick(false);
    }

    private static void onHudRender(GuiGraphics graphics, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.options.hideGui || mc.player == null) return;

        Renderer2D renderer = Renderer2D.get();
        int width = graphics.guiWidth();
        int height = graphics.guiHeight();

        renderer.begin(width, height);
        if (!renderer.isReady()) return;

        Render2DEvent event = new Render2DEvent(graphics, renderer, width, height,
            delta.getGameTimeDeltaPartialTick(false));

        Modules.get().post(event);
        Hud.render(event);

        renderer.end();
    }

    private static void onWorldRender(WorldRenderContext context) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;

        Vec3 camera = mc.gameRenderer.getMainCamera().position();
        Renderer3D renderer = new Renderer3D(context.matrices(), context.consumers(), camera);

        Modules.get().post(new Render3DEvent(renderer));
    }
}
