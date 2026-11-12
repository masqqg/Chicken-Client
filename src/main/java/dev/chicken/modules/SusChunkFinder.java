package dev.chicken.modules;

import dev.chicken.systems.Categories;
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
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Marks "sus" chunks: chunks that show signs of player activity.
 *
 * Four signals, each worth 1 point per chunk (so the maximum is 4):
 *   1. tall kelp         - a kelp column taller than {@code kelp-min-height}, scanned from sea level down
 *   2. chicken egg       - a dropped egg item
 *   3. trader llama      - a trader llama (or the wandering trader itself)
 *   4. villager
 *
 * A chunk is marked when at least {@code sensitivity} different signals are present.
 * The mark is drawn as a filled red 3D block over the chunk - no outline.
 */
public class SusChunkFinder extends Module {
    private static final int SIG_KELP = 0;
    private static final int SIG_EGG = 1;
    private static final int SIG_TRADER = 2;
    private static final int SIG_VILLAGER = 3;
    private static final int SCORED = 4;                // signals that add up to the sensitivity
    private static final int SIG_HOSTILE = 4;           // marks the chunk directly, not scored
    private static final int SIGNALS = 5;
    private static final String[] SIGNAL_NAMES =
        {"tall kelp", "chicken egg", "trader llama", "villager", "hostile mobs"};

    private static final long NEVER = -1L;
    private static final int SWEEP_TICKS = 40;          // kelp rescan interval
    private static final long SCAN_BUDGET_NS = 6_000_000L; // kelp scan time budget per tick

    private static final Predicate<BlockState> KELP =
        st -> st.is(Blocks.KELP) || st.is(Blocks.KELP_PLANT);

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgRender = settings.createGroup("Render");

    private final Setting<Integer> sensitivity = sgGeneral.add(new IntSetting.Builder()
        .name("sensitivity")
        .description("How many different signals (tall kelp, chicken egg, trader llama, villager) " +
            "a chunk needs before it is marked. Each signal counts as 1, max 4.")
        .defaultValue(2).range(1, 4).sliderRange(1, 4).build());

    private final Setting<Integer> chunkRange = sgGeneral.add(new IntSetting.Builder()
        .name("chunk-range").description("Look at loaded chunks within this many chunks of you.")
        .defaultValue(16).range(1, 32).sliderRange(1, 32).build());

    private final Setting<Integer> seaLevel = sgGeneral.add(new IntSetting.Builder()
        .name("sea-level").description("Y of the highest water block. Kelp is scanned from here downwards.")
        .defaultValue(62).range(-64, 320).sliderRange(40, 80).build());

    private final Setting<Integer> kelpMinHeight = sgGeneral.add(new IntSetting.Builder()
        .name("kelp-min-height").description("A kelp column must be taller than this many blocks to count.")
        .defaultValue(9).range(1, 40).sliderRange(1, 30).build());

    private final Setting<Integer> minY = sgGeneral.add(new IntSetting.Builder()
        .name("min-y").description("Chicken eggs and trader llamas only count at or above this Y.")
        .defaultValue(4).range(-64, 320).sliderRange(-64, 128).build());

    private final Setting<Integer> maxY = sgGeneral.add(new IntSetting.Builder()
        .name("max-y").description("Chicken eggs and trader llamas only count at or below this Y.")
        .defaultValue(64).range(-64, 320).sliderRange(-64, 128).build());

    private final Setting<Integer> hostileMinY = sgGeneral.add(new IntSetting.Builder()
        .name("hostile-min-y").description("Hostile mobs only count at or above this Y.")
        .defaultValue(0).range(-64, 320).sliderRange(-64, 128).build());

    private final Setting<Integer> hostileMaxY = sgGeneral.add(new IntSetting.Builder()
        .name("hostile-max-y").description("Hostile mobs only count at or below this Y.")
        .defaultValue(17).range(-64, 320).sliderRange(-64, 128).build());

    private final Setting<Integer> hostileCount = sgGeneral.add(new IntSetting.Builder()
        .name("hostile-count")
        .description("A chunk is marked directly when MORE than this many hostile mobs are inside the Y range.")
        .defaultValue(5).range(1, 100).sliderRange(1, 30).build());

    private final Setting<Integer> memorySeconds =sgGeneral.add(new IntSetting.Builder()
        .name("memory-seconds")
        .description("How long an egg / trader llama / villager keeps counting after it is gone " +
            "(stops the mark from flickering).")
        .defaultValue(30).range(0, 300).sliderRange(0, 120).build());

    private final Setting<Boolean> debug = sgGeneral.add(new BoolSetting.Builder()
        .name("debug").description("Print the signals in chat when a chunk becomes sus.")
        .defaultValue(false).build());

    private final Setting<SettingColor> color = sgRender.add(new ColorSetting.Builder()
        .name("color").description("Fill color of the sus chunk block. The alpha is the body opacity.")
        .defaultValue(new SettingColor(255, 0, 0, 80)).build());

    private final Setting<Integer> renderY = sgRender.add(new IntSetting.Builder()
        .name("render-y").description("Y level of the bottom of the marker.")
        .defaultValue(64).range(-64, 320).sliderRange(-64, 128).build());

    private final Setting<Integer> renderHeight = sgRender.add(new IntSetting.Builder()
        .name("render-height").description("Height of the marker in blocks.")
        .defaultValue(8).range(1, 64).sliderRange(1, 32).build());

    private final Setting<Boolean> pulse = sgRender.add(new BoolSetting.Builder()
        .name("pulse").description("Slowly pulse the marker so it is easy to spot.")
        .defaultValue(true).build());

    /** chunk key -> tick each signal was last seen at (NEVER = not present) */
    private final Map<Long, long[]> seen = new HashMap<>();
    private final List<int[]> queue = new ArrayList<>();
    private Set<Long> sus = new HashSet<>();
    private final Color bodyColor = new Color(255, 0, 0, 0);
    private final Color plateColor = new Color(255, 0, 0, 0);
    private ClientLevel lastLevel;
    private long tick;
    private int cursor;
    private int sinceSweep;

    public SusChunkFinder() {
        super(Categories.WORLD, "sus-chunk-finder",
            "Marks chunks with tall kelp, chicken eggs, trader llamas and villagers.");
    }

    private static long key(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    private void resetState() {
        seen.clear();
        queue.clear();
        sus = new HashSet<>();
        cursor = 0;
        sinceSweep = SWEEP_TICKS;
    }

    @Override
    public void onActivate() {
        resetState();
        lastLevel = mc.level;
        tick = 0;
    }

    @Override
    public void onDeactivate() {
        resetState();
        lastLevel = null;
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.level == null || mc.player == null) return;

        if (mc.level != lastLevel) { // joined another world / changed dimension
            lastLevel = mc.level;
            resetState();
        }

        tick++;
        sinceSweep++;

        int pcx = mc.player.blockPosition().getX() >> 4;
        int pcz = mc.player.blockPosition().getZ() >> 4;
        int r = chunkRange.get();

        scanEntities(pcx, pcz, r);
        scanKelp(pcx, pcz, r);
        refreshSus();
    }

    // ------------------------------------------------------------------ signals

    private long[] entry(long k) {
        return seen.computeIfAbsent(k, x -> {
            long[] arr = new long[SIGNALS];
            java.util.Arrays.fill(arr, NEVER);
            return arr;
        });
    }

    /** Eggs, trader llamas and villagers - read straight from the loaded entities. */
    private void scanEntities(int pcx, int pcz, int r) {
        Map<Long, Integer> hostiles = new HashMap<>();

        for (Entity entity : mc.level.entitiesForRendering()) {
            EntityType<?> type = entity.getType();
            BlockPos bp = entity.blockPosition();
            int y = bp.getY();
            int signal;

            if (type == EntityType.VILLAGER) {
                signal = SIG_VILLAGER;
            } else if (type == EntityType.TRADER_LLAMA || type == EntityType.WANDERING_TRADER) {
                if (y < minY.get() || y > maxY.get()) continue;
                signal = SIG_TRADER;
            } else if (entity instanceof ItemEntity item && item.getItem().is(Items.EGG)) {
                if (y < minY.get() || y > maxY.get()) continue;
                signal = SIG_EGG;
            } else if (entity instanceof Enemy) {
                signal = SIG_HOSTILE;
            } else {
                continue;
            }

            int cx = bp.getX() >> 4;
            int cz = bp.getZ() >> 4;
            if (Math.abs(cx - pcx) > r || Math.abs(cz - pcz) > r) continue;

            if (signal == SIG_HOSTILE) {
                if (y >= hostileMinY.get() && y <= hostileMaxY.get()) hostiles.merge(key(cx, cz), 1, Integer::sum);
            } else {
                entry(key(cx, cz))[signal] = tick;
            }
        }

        for (Map.Entry<Long, Integer> e : hostiles.entrySet()) {
            if (e.getValue() > hostileCount.get()) entry(e.getKey())[SIG_HOSTILE] = tick;
        }
    }

    /** Time-budgeted sweep over the loaded chunks looking for tall kelp. */
    private void scanKelp(int pcx, int pcz, int r) {
        if (cursor >= queue.size()) {
            if (sinceSweep < SWEEP_TICKS) return;
            sinceSweep = 0;
            rebuildQueue(pcx, pcz, r);
            if (queue.isEmpty()) return;
        }

        long deadline = System.nanoTime() + SCAN_BUDGET_NS;
        while (cursor < queue.size() && System.nanoTime() < deadline) {
            int[] c = queue.get(cursor++);
            if (!mc.level.hasChunk(c[0], c[1])) continue;

            long k = key(c[0], c[1]);
            if (hasTallKelp(c[0], c[1])) {
                entry(k)[SIG_KELP] = tick;
            } else {
                long[] arr = seen.get(k);
                if (arr != null) arr[SIG_KELP] = NEVER; // kelp is static: no lingering
            }
        }
    }

    private void rebuildQueue(int pcx, int pcz, int r) {
        queue.clear();
        cursor = 0;

        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                int cx = pcx + dx;
                int cz = pcz + dz;
                if (mc.level.hasChunk(cx, cz)) queue.add(new int[]{cx, cz, dx * dx + dz * dz});
            }
        }
        queue.sort((a, b) -> Integer.compare(a[2], b[2]));

        // forget chunks that are gone / out of range / have nothing left
        seen.entrySet().removeIf(e -> {
            long k = e.getKey();
            int cx = (int) (k >> 32);
            int cz = (int) k;
            return Math.abs(cx - pcx) > r || Math.abs(cz - pcz) > r
                || !mc.level.hasChunk(cx, cz)
                || (active(e.getValue()) == 0 && !hostileActive(e.getValue()));
        });
    }

    /**
     * True when the chunk holds a kelp column taller than kelp-min-height.
     * Water can't exist above sea level, so every column is scanned from sea level
     * down to the lowest chunk section that contains kelp at all.
     */
    private boolean hasTallKelp(int cx, int cz) {
        LevelChunk chunk = mc.level.getChunk(cx, cz);
        int minSection = mc.level.getMinY() >> 4;
        int top = Math.min(seaLevel.get(), mc.level.getMaxY() - 1);
        int bottom = Integer.MAX_VALUE;

        // cheap reject: most chunks have no kelp section at all
        for (int i = 0; i < chunk.getSectionsCount(); i++) {
            int secMin = (minSection + i) << 4;
            if (secMin > top) continue;

            LevelChunkSection section = chunk.getSection(i);
            if (section.hasOnlyAir() || !section.maybeHas(KELP)) continue;
            bottom = Math.min(bottom, secMin);
        }
        if (bottom == Integer.MAX_VALUE) return false;

        int need = kelpMinHeight.get();
        int bx = cx << 4;
        int bz = cz << 4;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                int run = 0;
                for (int y = top; y >= bottom; y--) {
                    pos.set(bx + x, y, bz + z);
                    if (KELP.test(chunk.getBlockState(pos))) {
                        if (++run > need) return true; // taller than the minimum
                    } else {
                        run = 0;
                    }
                }
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ scoring

    private boolean hostileActive(long[] last) {
        return last[SIG_HOSTILE] != NEVER && tick - last[SIG_HOSTILE] <= memorySeconds.get() * 20L;
    }

    /** How many of the 4 scored signals are currently active in this chunk. */
    private int active(long[] last) {
        long window = memorySeconds.get() * 20L;
        int n = 0;
        for (int i = 0; i < SCORED; i++) {
            if (last[i] == NEVER) continue;
            if (i == SIG_KELP || tick - last[i] <= window) n++;
        }
        return n;
    }

    private String describe(long[] last) {
        long window = memorySeconds.get() * 20L;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < SIGNALS; i++) {
            if (last[i] == NEVER) continue;
            if (i != SIG_KELP && tick - last[i] > window) continue; // (kelp never lingers)
            if (sb.length() > 0) sb.append(", ");
            sb.append(SIGNAL_NAMES[i]);
        }
        return sb.toString();
    }

    private void refreshSus() {
        int need = sensitivity.get();
        Set<Long> now = new HashSet<>();

        for (Map.Entry<Long, long[]> e : seen.entrySet()) {
            long[] last = e.getValue();
            if (active(last) >= need || hostileActive(last)) now.add(e.getKey());
        }

        if (debug.get()) {
            for (long k : now) {
                if (sus.contains(k)) continue;
                info("Sus chunk %d,%d: %s", (int) (k >> 32), (int) k, describe(seen.get(k)));
            }
        }
        sus = now;
    }

    // ------------------------------------------------------------------ render

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (mc.level == null || mc.player == null || sus.isEmpty()) return;

        SettingColor base = color.get();
        double p = pulse.get() ? 0.75 + 0.25 * Math.sin(System.currentTimeMillis() / 350.0) : 1.0;

        // translucent body + brighter floor and lid plates = a solid glowing red block
        bodyColor.r = base.r; bodyColor.g = base.g; bodyColor.b = base.b;
        bodyColor.a = Math.min(255, (int) (base.a * p));
        plateColor.r = base.r; plateColor.g = base.g; plateColor.b = base.b;
        plateColor.a = Math.min(255, (int) (base.a * 2.0 * p));

        double y = renderY.get();
        double h = renderHeight.get();
        double plate = Math.min(0.2, h / 4.0);

        for (long k : sus) {
            double x0 = (int) (k >> 32) * 16.0;
            double z0 = (int) k * 16.0;
            double x1 = x0 + 16.0;
            double z1 = z0 + 16.0;

            // Sides only - no outline lines
            event.renderer.box(new AABB(x0, y, z0, x1, y + plate, z1),
                plateColor, plateColor, ShapeMode.Sides, 0);
            event.renderer.box(new AABB(x0, y + plate, z0, x1, y + h - plate, z1),
                bodyColor, bodyColor, ShapeMode.Sides, 0);
            event.renderer.box(new AABB(x0, y + h - plate, z0, x1, y + h, z1),
                plateColor, plateColor, ShapeMode.Sides, 0);
        }
    }
}
