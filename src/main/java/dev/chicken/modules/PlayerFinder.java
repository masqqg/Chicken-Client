package dev.chicken.modules;

import dev.chicken.systems.Categories;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import dev.chicken.events.Render3DEvent;
import dev.chicken.events.TickEvent;
import dev.chicken.render.ShapeMode;
import dev.chicken.settings.BoolSetting;
import dev.chicken.settings.ColorSetting;
import dev.chicken.settings.IntSetting;
import dev.chicken.settings.Setting;
import dev.chicken.settings.SettingGroup;
import dev.chicken.systems.Module;
import dev.chicken.render.Color;
import dev.chicken.render.SettingColor;
import dev.chicken.events.EventHandler;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Player Finder - gorunurluk/duvar fark etmeksizin oyuncu kutulari ve tracer'lari.
 *
 * Sunucu oyunculari sadece gorunurluk menzilinde entity olarak gonderir; bu modul
 * gelen oyunculari mesafeye gore filtreleyip ESP ve tracer cizer, yeni girenleri
 * bir kez sohbete yazar. Isim/koordinat haricinde sunucuya packet gondermez.
 */
public class PlayerFinder extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgRender = settings.createGroup("Render");

    private final Setting<Integer> range = sgGeneral.add(new IntSetting.Builder()
        .name("range")
        .description("Only show players within this many blocks.")
        .defaultValue(256)
        .range(16, 2048)
        .sliderRange(32, 512)
        .build());

    private final Setting<Boolean> notify = sgGeneral.add(new BoolSetting.Builder()
        .name("chat-notify")
        .description("Announce a player when they enter range.")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> chatCoords = sgGeneral.add(new BoolSetting.Builder()
        .name("chat-coords")
        .description("Include exact coordinates in the notification.")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> boxes = sgRender.add(new BoolSetting.Builder()
        .name("boxes")
        .description("Draw a box around each found player.")
        .defaultValue(true)
        .build());

    private final Setting<Integer> fillOpacity = sgRender.add(new IntSetting.Builder()
        .name("fill-opacity")
        .description("Opacity of the player box fill.")
        .defaultValue(30)
        .range(0, 255)
        .sliderRange(0, 255)
        .build());

    private final Setting<Boolean> tracers = sgRender.add(new BoolSetting.Builder()
        .name("tracers")
        .description("Draw a line from the camera to each found player.")
        .defaultValue(true)
        .build());

    private final Setting<Integer> tracerLimit = sgRender.add(new IntSetting.Builder()
        .name("tracer-limit")
        .description("Maximum number of player tracers drawn at once.")
        .defaultValue(32)
        .range(1, 128)
        .sliderRange(1, 64)
        .build());

    private final Setting<SettingColor> color = sgRender.add(new ColorSetting.Builder()
        .name("color")
        .description("Player box color.")
        .defaultValue(new SettingColor(255, 60, 60, 220))
        .build());

    private final Setting<SettingColor> tracerColor = sgRender.add(new ColorSetting.Builder()
        .name("tracer-color")
        .description("Player tracer color.")
        .defaultValue(new SettingColor(255, 60, 60, 255))
        .build());

    private record FoundPlayer(AbstractClientPlayer player, String name, double distance) {
    }

    private final List<FoundPlayer> found = new ArrayList<>();
    private final Set<UUID> announced = new HashSet<>();

    public PlayerFinder() {
        super(Categories.RENDER, "player-finder",
            "Player ESP and tracers with a chat notification when a player enters range.");
    }

    @Override
    public void onActivate() {
        found.clear();
        announced.clear();
    }

    @Override
    public void onDeactivate() {
        found.clear();
        announced.clear();
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.level == null || mc.player == null) return;

        found.clear();
        Set<UUID> visible = new HashSet<>();
        int max = range.get();

        for (AbstractClientPlayer player : mc.level.players()) {
            if (player == mc.player) continue;

            double distance = player.distanceTo(mc.player);
            if (distance > max) continue;

            UUID id = player.getUUID();
            String name = player.getName().getString();
            visible.add(id);
            found.add(new FoundPlayer(player, name, distance));

            if (announced.add(id) && notify.get()) {
                if (chatCoords.get()) {
                    info("%s at %d, %d, %d (%dm)",
                        name, player.getBlockX(), player.getBlockY(), player.getBlockZ(),
                        (int) Math.round(distance));
                } else {
                    info("%s is nearby (%dm)", name, (int) Math.round(distance));
                }
            }
        }

        // Menzilden cikan oyuncu tekrar girince yeniden haber verilsin.
        announced.retainAll(visible);
        found.sort((a, b) -> Double.compare(a.distance(), b.distance()));
    }

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (mc.level == null || mc.player == null || found.isEmpty()) return;

        SettingColor c = color.get();
        Color line = new Color(c.r, c.g, c.b, 255);
        Color side = new Color(c.r, c.g, c.b, fillOpacity.get());

        if (boxes.get()) {
            for (FoundPlayer p : found) {
                event.renderer.box(p.player().getBoundingBox(), side, line, ShapeMode.Both, 0);
            }
        }

        if (!tracers.get()) return;

        Vec3 cam = mc.gameRenderer.getMainCamera().position();
        int limit = tracerLimit.get();

        for (int i = 0; i < found.size() && i < limit; i++) {
            AABB box = found.get(i).player().getBoundingBox();
            event.renderer.line(
                cam.x, cam.y, cam.z,
                (box.minX + box.maxX) * 0.5,
                (box.minY + box.maxY) * 0.5,
                (box.minZ + box.maxZ) * 0.5,
                tracerColor.get());
        }
    }
}
