package dev.chicken.modules;

import dev.chicken.systems.Categories;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Base Finder - oyuncu yapimi bloklardan base/chunk tespiti.
 *
 * Bir chunk'ta hem "container" (sandik, firin, crafting table, hopper, beacon...)
 * hem de yeterince insa blogu (plank, tas tugla, cam, merdiven, yatak...) varsa
 * o chunk base olarak isaretlenir. Dogal köyler de container ve insa blogu
 * icerdigi icin false positive olabilir; esikleri yukselterek azaltabilirsin.
 *
 * Tarama tick basina butcelenir; render sadece bu oturumdaki isaretleri cizer.
 */
public class BaseFinder extends Module {
    private static final Set<Block> CONTAINERS = Set.of(
        Blocks.CHEST, Blocks.TRAPPED_CHEST, Blocks.BARREL, Blocks.ENDER_CHEST,
        Blocks.FURNACE, Blocks.BLAST_FURNACE, Blocks.SMOKER, Blocks.CRAFTING_TABLE,
        Blocks.ANVIL, Blocks.CHIPPED_ANVIL, Blocks.DAMAGED_ANVIL,
        Blocks.ENCHANTING_TABLE, Blocks.BEACON, Blocks.RESPAWN_ANCHOR,
        Blocks.LODESTONE, Blocks.HOPPER, Blocks.DISPENSER, Blocks.DROPPER,
        Blocks.BREWING_STAND, Blocks.JUKEBOX, Blocks.NOTE_BLOCK,
        Blocks.OBSERVER, Blocks.PISTON, Blocks.STICKY_PISTON
    );

    private static final String[] COMPASS = {"S", "SW", "W", "NW", "N", "NE", "E", "SE"};

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgRender = settings.createGroup("Render");

    private final Setting<Integer> chunkRange = sgGeneral.add(new IntSetting.Builder()
        .name("chunk-range")
        .description("Scan loaded chunks within this many chunks of you.")
        .defaultValue(16)
        .range(1, 32)
        .sliderRange(1, 32)
        .build());

    private final Setting<Integer> minY = sgGeneral.add(new IntSetting.Builder()
        .name("min-y")
        .description("Lowest Y to scan.")
        .defaultValue(-64)
        .range(-64, 320)
        .sliderRange(-64, 128)
        .build());

    private final Setting<Integer> maxY = sgGeneral.add(new IntSetting.Builder()
        .name("max-y")
        .description("Highest Y to scan.")
        .defaultValue(320)
        .range(-64, 320)
        .sliderRange(-64, 320)
        .build());

    private final Setting<Integer> sweepTicks = sgGeneral.add(new IntSetting.Builder()
        .name("sweep-ticks")
        .description("Rescan loaded chunks every N ticks.")
        .defaultValue(40)
        .range(1, 200)
        .sliderRange(5, 100)
        .build());

    private final Setting<Integer> msPerTick = sgGeneral.add(new IntSetting.Builder()
        .name("ms-per-tick")
        .description("Scan time budget per tick in milliseconds.")
        .defaultValue(6)
        .range(1, 40)
        .sliderRange(1, 40)
        .build());

    private final Setting<Integer> minContainers = sgGeneral.add(new IntSetting.Builder()
        .name("min-containers")
        .description("Containers (chest, furnace, crafting table, hopper...) needed to mark a chunk.")
        .defaultValue(1)
        .range(1, 20)
        .sliderRange(1, 8)
        .build());

    private final Setting<Integer> minBuilding = sgGeneral.add(new IntSetting.Builder()
        .name("min-building-blocks")
        .description("Player building blocks (planks, stone bricks, glass, stairs...) needed to mark a chunk.")
        .defaultValue(12)
        .range(1, 500)
        .sliderRange(1, 100)
        .build());

    private final Setting<Boolean> chatNotify = sgGeneral.add(new BoolSetting.Builder()
        .name("chat-notify")
        .description("Announce a newly found base once per chunk.")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> chatCoords = sgGeneral.add(new BoolSetting.Builder()
        .name("chat-coords")
        .description("Include exact coordinates in the base notification.")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> debug = sgGeneral.add(new BoolSetting.Builder()
        .name("debug")
        .description("Print chunk scores in chat when they change.")
        .defaultValue(false)
        .build());

    private final Setting<SettingColor> color = sgRender.add(new ColorSetting.Builder()
        .name("color")
        .description("Base chunk marker color. Alpha is the fill opacity.")
        .defaultValue(new SettingColor(255, 80, 80, 80))
        .build());

    private final Setting<Integer> renderY = sgRender.add(new IntSetting.Builder()
        .name("render-y")
        .description("Y level of the base marker plate.")
        .defaultValue(64)
        .range(-64, 320)
        .sliderRange(-64, 128)
        .build());

    private final Setting<Integer> renderHeight = sgRender.add(new IntSetting.Builder()
        .name("render-height")
        .description("Height of the base marker plate.")
        .defaultValue(6)
        .range(1, 64)
        .sliderRange(1, 32)
        .build());

    private final Setting<Boolean> tracers = sgRender.add(new BoolSetting.Builder()
        .name("tracers")
        .description("Draw a line from the camera to the nearest base chunks.")
        .defaultValue(true)
        .build());

    private final Setting<Integer> tracerLimit = sgRender.add(new IntSetting.Builder()
        .name("tracer-limit")
        .description("Maximum number of base tracers drawn at once.")
        .defaultValue(64)
        .range(4, 256)
        .sliderRange(8, 128)
        .build());

    private final Setting<SettingColor> tracerColor = sgRender.add(new ColorSetting.Builder()
        .name("tracer-color")
        .description("Color of the base tracer lines.")
        .defaultValue(new SettingColor(255, 80, 80, 255))
        .build());

    private static final class BaseChunk {
        int containers;
        int building;
        int topY;
    }

    private record Marker(int cx, int cz, double distance) {
    }

    private final Map<Long, BaseChunk> bases = new HashMap<>();
    private final Set<Long> announced = new HashSet<>();
    private final List<int[]> queue = new ArrayList<>();

    private Object lastLevel;
    private int cursor;
    private int sinceSweep = Integer.MAX_VALUE / 2;

    public BaseFinder() {
        super(Categories.WORLD, "base-finder",
            "Marks chunks that contain containers and player building blocks.");
    }

    private static long key(int x, int z) {
        return ((long) x << 32) | ((long) z & 0xFFFFFFFFL);
    }

    private static int keyX(long k) {
        return (int) (k >> 32);
    }

    private static int keyZ(long k) {
        return (int) k;
    }

    private static boolean isContainer(BlockState st) {
        return CONTAINERS.contains(st.getBlock()) || st.is(BlockTags.SHULKER_BOXES);
    }

    private static boolean isBuilding(BlockState st) {
        return st.is(BlockTags.PLANKS)
            || st.is(BlockTags.WOOL)
            || st.is(BlockTags.WOOL_CARPETS)
            || st.is(BlockTags.STONE_BRICKS)
            || st.is(BlockTags.SLABS)
            || st.is(BlockTags.STAIRS)
            || st.is(BlockTags.FENCES)
            || st.is(BlockTags.WALLS)
            || st.is(BlockTags.BEDS)
            || st.is(Blocks.GLASS)
            || st.is(Blocks.GLASS_PANE)
            || st.is(Blocks.BRICKS)
            || st.is(Blocks.COBBLESTONE)
            || st.is(Blocks.MOSSY_COBBLESTONE)
            || st.is(Blocks.SMOOTH_STONE)
            || st.is(Blocks.BOOKSHELF)
            || st.is(Blocks.CHISELED_BOOKSHELF)
            || st.is(Blocks.IRON_BARS);
    }

    private static boolean isBaseBlock(BlockState st) {
        return isContainer(st) || isBuilding(st);
    }

    private static String compass(double dx, double dz) {
        double yaw = -Math.toDegrees(Math.atan2(dx, dz));
        yaw = (yaw % 360.0 + 360.0) % 360.0;
        int i = (int) Math.round(yaw / 45.0) % 8;
        return COMPASS[i];
    }

    @Override
    public void onActivate() {
        reset();
    }

    @Override
    public void onDeactivate() {
        reset();
    }

    private void reset() {
        bases.clear();
        announced.clear();
        queue.clear();
        cursor = 0;
        sinceSweep = Integer.MAX_VALUE / 2;
        lastLevel = null;
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.level == null || mc.player == null) return;

        if (lastLevel != mc.level) {
            lastLevel = mc.level;
            bases.clear();
            announced.clear();
            queue.clear();
            cursor = 0;
            sinceSweep = Integer.MAX_VALUE / 2;
        }

        sinceSweep++;

        if (cursor >= queue.size()) {
            if (sinceSweep < sweepTicks.get()) return;
            sinceSweep = 0;
            rebuildQueue();
            if (queue.isEmpty()) return;
        }

        long deadline = System.nanoTime() + msPerTick.get() * 1_000_000L;

        while (cursor < queue.size() && System.nanoTime() < deadline) {
            int[] c = queue.get(cursor++);
            if (mc.level.hasChunk(c[0], c[1])) scanChunk(c[0], c[1]);
        }
    }

    private void rebuildQueue() {
        int pcx = mc.player.blockPosition().getX() >> 4;
        int pcz = mc.player.blockPosition().getZ() >> 4;
        int r = chunkRange.get();

        queue.clear();

        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                int cx = pcx + dx;
                int cz = pcz + dz;
                if (!mc.level.hasChunk(cx, cz)) continue;
                queue.add(new int[]{cx, cz, dx * dx + dz * dz});
            }
        }

        queue.sort(Comparator.comparingInt(a -> a[2]));

        bases.keySet().removeIf(k -> {
            int cx = keyX(k);
            int cz = keyZ(k);
            return Math.abs(cx - pcx) > r || Math.abs(cz - pcz) > r || !mc.level.hasChunk(cx, cz);
        });
    }

    private void scanChunk(int cx, int cz) {
        LevelChunk chunk = mc.level.getChunk(cx, cz);
        int containers = 0;
        int building = 0;
        int topY = Integer.MIN_VALUE;

        int lo = Math.max(minY.get(), mc.level.getMinY());
        int hi = Math.min(maxY.get(), mc.level.getMaxY() - 1);
        int minSection = mc.level.getMinY() >> 4;

        for (int i = 0; i < chunk.getSectionsCount(); i++) {
            int secMin = (minSection + i) << 4;
            if (secMin + 15 < lo || secMin > hi) continue;

            LevelChunkSection section = chunk.getSection(i);
            if (section == null || section.hasOnlyAir()) continue;
            if (!section.maybeHas(BaseFinder::isBaseBlock)) continue;

            int yStart = Math.max(secMin, lo);
            int yEnd = Math.min(secMin + 15, hi);

            for (int y = yStart; y <= yEnd; y++) {
                for (int x = 0; x < 16; x++) {
                    for (int z = 0; z < 16; z++) {
                        BlockState st = section.getBlockState(x, y - secMin, z);

                        if (isContainer(st)) {
                            containers++;
                            if (y > topY) topY = y;
                        } else if (isBuilding(st)) {
                            building++;
                            if (y > topY) topY = y;
                        }
                    }
                }
            }
        }

        long k = key(cx, cz);
        BaseChunk old = bases.get(k);
        int oldContainers = old == null ? -1 : old.containers;
        int oldBuilding = old == null ? -1 : old.building;

        if (containers >= minContainers.get() && building >= minBuilding.get()) {
            BaseChunk base = old != null ? old : new BaseChunk();
            base.containers = containers;
            base.building = building;
            base.topY = topY == Integer.MIN_VALUE ? renderY.get() : topY;
            bases.put(k, base);

            if (announced.add(k)) {
                int centerX = (cx << 4) + 8;
                int centerZ = (cz << 4) + 8;
                double dx = centerX + 0.5 - mc.player.getX();
                double dz = centerZ + 0.5 - mc.player.getZ();
                int dist = (int) Math.round(Math.sqrt(dx * dx + dz * dz));

                if (chatNotify.get()) {
                    if (chatCoords.get()) {
                        info("Base: %d, %d, %d (%dm %s) | %d containers, %d blocks",
                            centerX, base.topY, centerZ, dist, compass(dx, dz), containers, building);
                    } else {
                        info("Base found (%dm %s) | %d containers, %d blocks",
                            dist, compass(dx, dz), containers, building);
                    }
                }
            }
        } else {
            bases.remove(k);
        }

        if (debug.get() && (oldContainers != containers || oldBuilding != building)) {
            info("chunk %d,%d: %d containers, %d building blocks", cx, cz, containers, building);
        }
    }

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (mc.level == null || mc.player == null) return;

        int pcx = mc.player.blockPosition().getX() >> 4;
        int pcz = mc.player.blockPosition().getZ() >> 4;
        int r = chunkRange.get();
        int minC = minContainers.get();
        int minB = minBuilding.get();

        SettingColor c = color.get();
        Color side = new Color(c.r, c.g, c.b, c.a);
        Color line = new Color(c.r, c.g, c.b, 255);

        List<Marker> markers = new ArrayList<>();

        for (Map.Entry<Long, BaseChunk> e : bases.entrySet()) {
            int cx = keyX(e.getKey());
            int cz = keyZ(e.getKey());
            if (Math.abs(cx - pcx) > r || Math.abs(cz - pcz) > r) continue;

            BaseChunk base = e.getValue();
            if (base.containers < minC || base.building < minB) continue;

            double x = cx * 16.0;
            double z = cz * 16.0;
            double y = renderY.get();
            double h = renderHeight.get();

            event.renderer.box(new AABB(x, y, z, x + 16.0, y + h, z + 16.0),
                side, line, ShapeMode.Both, 0);

            double dx = cx * 16.0 + 8.0 - mc.player.getX();
            double dz = cz * 16.0 + 8.0 - mc.player.getZ();
            markers.add(new Marker(cx, cz, dx * dx + dz * dz));
        }

        if (!tracers.get() || markers.isEmpty()) return;

        markers.sort(Comparator.comparingDouble(Marker::distance));

        Vec3 cam = mc.gameRenderer.getMainCamera().position();
        int limit = tracerLimit.get();

        for (int i = 0; i < markers.size() && i < limit; i++) {
            Marker m = markers.get(i);
            event.renderer.line(
                cam.x, cam.y, cam.z,
                m.cx() * 16.0 + 8.0,
                renderY.get() + renderHeight.get() * 0.5,
                m.cz() * 16.0 + 8.0,
                tracerColor.get());
        }
    }
}
