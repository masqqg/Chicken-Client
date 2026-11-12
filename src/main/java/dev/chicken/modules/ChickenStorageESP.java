package dev.chicken.modules;

import dev.chicken.systems.Categories;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import dev.chicken.events.PacketEvent;
import dev.chicken.events.Render3DEvent;
import dev.chicken.events.TickEvent;
import dev.chicken.render.ShapeMode;
import dev.chicken.settings.BoolSetting;
import dev.chicken.settings.ColorSetting;
import dev.chicken.settings.IntSetting;
import dev.chicken.settings.Setting;
import dev.chicken.settings.SettingGroup;
import dev.chicken.systems.Module;
import dev.chicken.util.RenderDistance;
import dev.chicken.render.Color;
import dev.chicken.render.SettingColor;
import dev.chicken.events.EventHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.network.protocol.game.ClientboundBlockDestructionPacket;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundBlockEventPacket;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundLevelEventPacket;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.network.protocol.game.ClientboundOpenScreenPacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.equine.AbstractChestedHorse;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.vehicle.boat.AbstractChestBoat;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Storage ESP Bypass - anti-xray / anti-ESP sunuculara karsi cok kanalli tespit.
 *
 * Her kanal ayri ayri acilip kapanabilir. Amac: sunucu bir kanali kestiginde
 * digerlerinden hala iz yakalayabilmek.
 *
 *   1) packet-block-entities : chunk paketindeki block entity listesi (istemci islemeden once)
 *   2) scan-block-entities   : istemcinin kabul ettigi block entity listesi
 *   3) scan-block-states     : chunk section blok state'leri
 *   4) packet-memory         : blok guncelleme / block entity data paketleri
 *   5) block-events          : sandik kapagi animasyonu
 *   6) block-breaks          : blok kirilma efekti (2001) - gercek blok id'si
 *   7) sounds                : sandik/barrel/shulker ses paketleri
 *   8) block-particles       : blok parcaciklari
 *   9) entity-storage        : chest/hopper minecart, chest boat, sandikli esek/katir/llama
 *  10) item-drops            : yere dusen sandik/barrel/shulker item'i (kirilan container izi)
 *  11) mining-suspects       : birinin kirdigi bloklar (crack paketi) - isaret olarak
 *  12) container-open        : actigin container'i hatirlar
 *
 * Hepsi bellege yazilir, oyun kapaninca kaybolur. Diske kayit yok.
 */
public class ChickenStorageESP extends Module {
    private static final int G_CHEST = 0;
    private static final int G_HOPPER = 1;
    private static final int G_OTHER = 2;
    private static final int G_SUSPECT = 3;

    private static final String[] GROUP_NAMES = {"chest", "hopper", "other", "suspect"};

    private static final int MAX_MEMORY = 150_000;
    private static final int MAX_SUSPECTS = 2_000;

    private record Entry(BlockPos pos, int group, boolean hidden) {
    }

    private record EntityBox(AABB box, int group) {
    }

    private record TracerTarget(double x, double y, double z, Color color, double distance) {
    }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgChannels = settings.createGroup("Channels");
    private final SettingGroup sgEntities = settings.createGroup("Entities");
    private final SettingGroup sgColors = settings.createGroup("Colors");
    private final SettingGroup sgTracers = settings.createGroup("Tracers");
    private final SettingGroup sgDebug = settings.createGroup("Debug");

    // ---- General ----
    private final Setting<Integer> chunkRange = sgGeneral.add(new IntSetting.Builder()
        .name("chunk-range")
        .description("Minimum scan/render range in chunks; the client's render distance is used when it is larger.")
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
        .defaultValue(20)
        .range(1, 200)
        .sliderRange(5, 100)
        .build());

    private final Setting<Integer> msPerTick = sgGeneral.add(new IntSetting.Builder()
        .name("ms-per-tick")
        .description("Scan time budget per tick in milliseconds.")
        .defaultValue(8)
        .range(1, 40)
        .sliderRange(1, 40)
        .build());

    // ---- Channels ----
    private final Setting<Boolean> packetEntities = sgChannels.add(new BoolSetting.Builder()
        .name("packet-block-entities")
        .description("Read the block entity list straight out of the chunk packet, before the client drops entries whose block looks like stone.")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> scanEntities = sgChannels.add(new BoolSetting.Builder()
        .name("scan-block-entities")
        .description("Read the block entity list the client actually kept.")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> scanStates = sgChannels.add(new BoolSetting.Builder()
        .name("scan-block-states")
        .description("Read section block states.")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> packetMemory = sgChannels.add(new BoolSetting.Builder()
        .name("packet-memory")
        .description("Remember containers seen in block updates and block entity data packets.")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> blockEvents = sgChannels.add(new BoolSetting.Builder()
        .name("block-events")
        .description("Chest lid animation packets. Fires when anyone opens a nearby container.")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> levelEvents = sgChannels.add(new BoolSetting.Builder()
        .name("block-breaks")
        .description("Block break particle events carry the real block id.")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> sounds = sgChannels.add(new BoolSetting.Builder()
        .name("sounds")
        .description("Container open/close sounds carry the block position.")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> particles = sgChannels.add(new BoolSetting.Builder()
        .name("block-particles")
        .description("Block particles carry the block state that produced them.")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> miningSuspects = sgChannels.add(new BoolSetting.Builder()
        .name("mining-suspects")
        .description("Mark blocks somebody is breaking. A hidden container being mined shows up here.")
        .defaultValue(false)
        .build());

    private final Setting<Boolean> containerOpen = sgChannels.add(new BoolSetting.Builder()
        .name("container-open")
        .description("When you open a container, remember the block you clicked. Learns your own bases without any block entity packet.")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> showHidden = sgChannels.add(new BoolSetting.Builder()
        .name("show-hidden")
        .description("Draw containers that the server replaced with another block.")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> chatNotify = sgChannels.add(new BoolSetting.Builder()
        .name("notify-hidden")
        .description("Print a chat message the first time a hidden container is found.")
        .defaultValue(false)
        .build());

    // ---- Entities ----
    private final Setting<Boolean> entityStorage = sgEntities.add(new BoolSetting.Builder()
        .name("entity-storage")
        .description("Chest / hopper minecarts, chest boats and chested donkeys, mules and llamas.")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> itemDrops = sgEntities.add(new BoolSetting.Builder()
        .name("item-drops")
        .description("Dropped container items. Reveals containers that were just broken.")
        .defaultValue(true)
        .build());

    // ---- Colors ----
    private final Setting<SettingColor> chestColor = sgColors.add(new ColorSetting.Builder()
        .name("chest-color")
        .defaultValue(new SettingColor(255, 170, 60, 255))
        .build());

    private final Setting<SettingColor> hopperColor = sgColors.add(new ColorSetting.Builder()
        .name("hopper-color")
        .defaultValue(new SettingColor(205, 205, 215, 255))
        .build());

    private final Setting<SettingColor> otherColor = sgColors.add(new ColorSetting.Builder()
        .name("other-color")
        .defaultValue(new SettingColor(150, 205, 255, 255))
        .build());

    private final Setting<SettingColor> hiddenColor = sgColors.add(new ColorSetting.Builder()
        .name("hidden-color")
        .description("Containers the server tried to hide.")
        .defaultValue(new SettingColor(255, 60, 60, 255))
        .build());

    private final Setting<SettingColor> suspectColor = sgColors.add(new ColorSetting.Builder()
        .name("suspect-color")
        .defaultValue(new SettingColor(170, 150, 90, 255))
        .build());

    private final Setting<Integer> fillOpacity = sgColors.add(new IntSetting.Builder()
        .name("fill-opacity")
        .defaultValue(35)
        .range(0, 255)
        .sliderRange(0, 255)
        .build());

    // ---- Tracers ----
    private final Setting<Boolean> tracers = sgTracers.add(new BoolSetting.Builder()
        .name("tracers")
        .description("Draw a line from the camera to every container in range.")
        .defaultValue(true)
        .build());

    private final Setting<Integer> tracerRange = sgTracers.add(new IntSetting.Builder()
        .name("tracer-range")
        .description("Only draw tracers for containers within this many blocks. 512 covers the full render distance.")
        .defaultValue(512)
        .range(8, 512)
        .sliderRange(8, 512)
        .build());

    private final Setting<Integer> tracerLimit = sgTracers.add(new IntSetting.Builder()
        .name("tracer-limit")
        .description("Maximum number of tracer lines drawn at once.")
        .defaultValue(96)
        .range(4, 512)
        .sliderRange(8, 256)
        .build());

    private final Setting<Integer> tracerFade = sgTracers.add(new IntSetting.Builder()
        .name("tracer-fade")
        .description("How strongly far tracers fade out. 0 = solid lines.")
        .defaultValue(70)
        .range(0, 95)
        .sliderRange(0, 95)
        .build());

    private final Setting<Boolean> tracerFromFeet = sgTracers.add(new BoolSetting.Builder()
        .name("tracer-from-feet")
        .description("Start tracers at your feet instead of the camera.")
        .defaultValue(false)
        .build());

    // ---- Debug ----
    private final Setting<Boolean> debugPackets = sgDebug.add(new BoolSetting.Builder()
        .name("debug-packets")
        .description("Print the name of every packet type the server sends, once each.")
        .defaultValue(false)
        .build());

    private final Setting<Boolean> debugChannels = sgDebug.add(new BoolSetting.Builder()
        .name("debug-channels")
        .description("Print how many hits every detection channel has so far, every 5 seconds. Shows which channel your server leaves open.")
        .defaultValue(false)
        .build());

    private final Map<Long, List<Entry>> cache = new HashMap<>();

    /** chunk key -> packed block pos -> grup. Sadece bu oturum icin. */
    private final Map<Long, Map<Long, Integer>> memory = new HashMap<>();

    private final List<EntityBox> entityBoxes = new ArrayList<>();
    private final Set<Long> suspects = new HashSet<>();

    private final ConcurrentLinkedQueue<long[]> incoming = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<Long> suspectQueue = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<String> logQueue = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<String> hits = new ConcurrentLinkedQueue<>();
    private final Map<String, Integer> hitTotals = new HashMap<>();
    private final Set<Long> announcedHidden = new HashSet<>();
    private final Set<String> seenPacketTypes = new HashSet<>();
    private final List<int[]> queue = new ArrayList<>();

    private int cursor;
    private int hitTick;
    private int sinceSweep = Integer.MAX_VALUE / 2;
    private int memoryCount;
    private boolean capWarned;

    private Object lastLevel;

    public ChickenStorageESP() {
        super(Categories.RENDER, "storage-esp-bypass",
            "Storage ESP with packet level leak detection and clean tracers.");
    }

    // --------------------------------------------------------------- helpers

    private static long key(int x, int z) {
        return ((long) x << 32) | ((long) z & 0xFFFFFFFFL);
    }

    private static int keyX(long k) {
        return (int) (k >> 32);
    }

    private static int keyZ(long k) {
        return (int) k;
    }

    /** Block ESP gibi: en az chunk-range, en az istemcinin render mesafesi kadar tara ve göster. */
    private int viewRange() {
        return Math.max(chunkRange.get(), RenderDistance.get() + 1);
    }

    /** Blok state'ten grup. -1 = storage degil. */
    private static int group(BlockState st) {
        if (st == null) return -1;
        if (st.is(Blocks.CHEST) || st.is(Blocks.TRAPPED_CHEST)) return G_CHEST;
        if (st.is(Blocks.HOPPER)) return G_HOPPER;
        if (st.is(Blocks.BARREL) || st.is(Blocks.ENDER_CHEST) || st.is(BlockTags.SHULKER_BOXES)
            || st.is(Blocks.FURNACE) || st.is(Blocks.BLAST_FURNACE) || st.is(Blocks.SMOKER)
            || st.is(Blocks.DISPENSER) || st.is(Blocks.DROPPER) || st.is(Blocks.BREWING_STAND)
            || st.is(Blocks.CHISELED_BOOKSHELF) || st.is(Blocks.DECORATED_POT) || st.is(Blocks.CRAFTER)) {
            return G_OTHER;
        }
        return -1;
    }

    /** Block entity tipinden grup. */
    private static int group(BlockEntityType<?> type) {
        if (type == null) return -1;
        if (type == BlockEntityType.CHEST || type == BlockEntityType.TRAPPED_CHEST) return G_CHEST;
        if (type == BlockEntityType.HOPPER) return G_HOPPER;
        if (type == BlockEntityType.BARREL || type == BlockEntityType.ENDER_CHEST
            || type == BlockEntityType.SHULKER_BOX || type == BlockEntityType.FURNACE
            || type == BlockEntityType.BLAST_FURNACE || type == BlockEntityType.SMOKER
            || type == BlockEntityType.DISPENSER || type == BlockEntityType.DROPPER
            || type == BlockEntityType.BREWING_STAND || type == BlockEntityType.MOB_SPAWNER) {
            return G_OTHER;
        }
        return -1;
    }

    /** Dusen item'in grubu. */
    private static int group(ItemStack stack) {
        if (stack.isEmpty()) return -1;
        if (stack.getItem() instanceof BlockItem block) return group(block.getBlock().defaultBlockState());
        return -1;
    }

    /** Ses adindan grup. */
    private static int groupBySound(Identifier id) {
        String path = id.getPath();

        if (path.equals("block.chest.open") || path.equals("block.chest.close")
            || path.equals("block.chest.locked")) {
            return G_CHEST;
        }

        if (path.equals("block.ender_chest.open") || path.equals("block.ender_chest.close")
            || path.equals("block.barrel.open") || path.equals("block.barrel.close")
            || path.equals("block.shulker_box.open") || path.equals("block.shulker_box.close")) {
            return G_OTHER;
        }

        return -1;
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
        cache.clear();
        memory.clear();
        entityBoxes.clear();
        suspects.clear();
        incoming.clear();
        suspectQueue.clear();
        queue.clear();
        hits.clear();
        hitTotals.clear();
        announcedHidden.clear();
        seenPacketTypes.clear();
        cursor = 0;
        sinceSweep = Integer.MAX_VALUE / 2;
        memoryCount = 0;
        capWarned = false;
        lastLevel = null;
    }

    // ---------------------------------------------------------------- memory

    /** Bellekte bir konum tutar. Yeni kayit eklendiyse true doner. */
    private boolean store(long chunkKey, long packedPos, int group) {
        Map<Long, Integer> chunkMap = memory.computeIfAbsent(chunkKey, k -> new HashMap<>());
        Integer old = chunkMap.put(packedPos, group);

        if (old == null) {
            memoryCount++;
            return true;
        }
        return old != group;
    }

    // --------------------------------------------------------------- packets

    @EventHandler
    private void onPacket(PacketEvent.Receive event) {
        if (debugPackets.get()) {
            String name = event.packet.getClass().getSimpleName();
            if (seenPacketTypes.add(name)) logQueue.add(name);
        }

        // ---- chunk paketindeki ham block entity listesi ----
        if (event.packet instanceof ClientboundLevelChunkWithLightPacket p) {
            if (!packetEntities.get()) return;

            int cx = p.getX();
            int cz = p.getZ();

            try {
                p.getChunkData().getBlockEntitiesTagsConsumer(cx, cz).accept((pos, type, tag) -> {
                    int g = group(type);
                    if (g >= 0) {
                        hit("packet-be");
                        remember(pos, g);
                    }
                });
            } catch (Exception ignored) {
                // bilinmeyen block entity tipi: o chunk'i atla
            }
            return;
        }

        // ---- blok guncellemesi ----
        if (event.packet instanceof ClientboundBlockUpdatePacket p) {
            if (!packetMemory.get()) return;
            int g = group(p.getBlockState());
            if (g >= 0) {
                hit("block-update");
                remember(p.getPos(), g);
            }
            return;
        }

        // ---- section blok guncellemeleri ----
        if (event.packet instanceof ClientboundSectionBlocksUpdatePacket p) {
            if (!packetMemory.get()) return;
            p.runUpdates((pos, state) -> {
                int g = group(state);
                if (g >= 0) {
                    hit("multi-block");
                    remember(pos, g);
                }
            });
            return;
        }

        // ---- block entity data paketi ----
        if (event.packet instanceof ClientboundBlockEntityDataPacket p) {
            if (!packetMemory.get()) return;
            int g = group(p.getType());
            if (g >= 0) {
                hit("be-packet");
                remember(p.getPos(), g);
            }
            return;
        }

        // ---- sandik kapagi (blok olayi) ----
        if (event.packet instanceof ClientboundBlockEventPacket p) {
            if (!blockEvents.get()) return;
            int g = group(p.getBlock().defaultBlockState());
            if (g >= 0) {
                hit("block-event");
                remember(p.getPos(), g);
            }
            return;
        }

        // ---- blok kirilma parcacik olayi (2001) ----
        if (event.packet instanceof ClientboundLevelEventPacket p) {
            if (!levelEvents.get() || p.getType() != 2001) return;
            int g = group(Block.stateById(p.getData()));
            if (g >= 0) {
                hit("block-break");
                remember(p.getPos(), g);
            }
            return;
        }

        // ---- ses ----
        if (event.packet instanceof ClientboundSoundPacket p) {
            if (!sounds.get()) return;
            int g = groupBySound(p.getSound().value().location());
            if (g >= 0) {
                hit("sound");
                remember(BlockPos.containing(p.getX(), p.getY(), p.getZ()), g);
            }
            return;
        }

        // ---- blok parcaciklari ----
        if (event.packet instanceof ClientboundLevelParticlesPacket p) {
            if (!particles.get()) return;
            if (!(p.getParticle() instanceof BlockParticleOption opt)) return;
            int g = group(opt.getState());
            if (g >= 0) {
                hit("particle");
                remember(BlockPos.containing(p.getX(), p.getY(), p.getZ()), g);
            }
            return;
        }

        // ---- container ekrani acildi: tiklanan blogu ogren ----
        if (event.packet instanceof ClientboundOpenScreenPacket p) {
            if (!containerOpen.get() || mc.hitResult == null || mc.level == null) return;
            if (!(mc.hitResult instanceof BlockHitResult hit)) return;

            int g = group(mc.level.getBlockState(hit.getBlockPos()));
            if (g >= 0) {
                hit("open-screen");
                remember(hit.getBlockPos(), g);
            }
            return;
        }

        // ---- kirilan blok (crack) ----
        if (event.packet instanceof ClientboundBlockDestructionPacket p) {
            if (!miningSuspects.get()) return;
            if (p.getProgress() == 0) {
                hit("mining");
                suspectQueue.add(p.getPos().asLong());
            }
        }
    }

    private void hit(String channel) {
        hits.add(channel);
    }

    /** Ag is parcacigindan cagrilir: sadece kuyruga at. */
    private void remember(BlockPos pos, int group) {
        if (pos.getY() < minY.get() || pos.getY() > maxY.get()) return;
        incoming.add(new long[]{pos.getX(), pos.getY(), pos.getZ(), group});
    }

    private void drainIncoming() {
        Long sp;

        while ((sp = suspectQueue.poll()) != null) {
            if (suspects.size() < MAX_SUSPECTS) suspects.add(sp);
        }

        if (incoming.isEmpty()) return;

        Set<Long> touched = new HashSet<>();
        long[] a;

        while ((a = incoming.poll()) != null) {
            if (memoryCount >= MAX_MEMORY) {
                if (!capWarned) {
                    capWarned = true;
                    info("Storage memory is full (%d entries), new containers are ignored.", MAX_MEMORY);
                }
                continue;
            }

            long ck = key((int) (a[0] >> 4), (int) (a[2] >> 4));
            if (store(ck, BlockPos.asLong((int) a[0], (int) a[1], (int) a[2]), (int) a[3])) {
                touched.add(ck);
            }
        }

        for (long ck : touched) rescan(keyX(ck), keyZ(ck));
    }

    // ------------------------------------------------------------------- tick

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.level == null || mc.player == null) return;

        if (lastLevel != mc.level) {
            lastLevel = mc.level;
            memory.clear();
            cache.clear();
            suspects.clear();
            entityBoxes.clear();
            memoryCount = 0;
            capWarned = false;
        }

        while (!logQueue.isEmpty()) info("packet: %s", logQueue.poll());

        while (!hits.isEmpty()) hitTotals.merge(hits.poll(), 1, Integer::sum);

        if (debugChannels.get() && ++hitTick % 100 == 0) {
            info("channels: %s", hitTotals.isEmpty() ? "no hits yet" : hitTotals.toString());
        }

        drainIncoming();
        scanEntityStorage();

        sinceSweep++;

        if (cursor >= queue.size()) {
            if (sinceSweep < sweepTicks.get()) return;
            sinceSweep = 0;

            int pcx = mc.player.blockPosition().getX() >> 4;
            int pcz = mc.player.blockPosition().getZ() >> 4;
            int r = viewRange();

            queue.clear();
            cursor = 0;

            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    int cx = pcx + dx;
                    int cz = pcz + dz;
                    if (!mc.level.hasChunk(cx, cz)) continue;
                    queue.add(new int[]{cx, cz, dx * dx + dz * dz});
                }
            }
            queue.sort((a, b) -> Integer.compare(a[2], b[2]));

            cache.keySet().removeIf(k -> {
                int cx = keyX(k);
                int cz = keyZ(k);
                return Math.abs(cx - pcx) > r || Math.abs(cz - pcz) > r || !mc.level.hasChunk(cx, cz);
            });

            if (queue.isEmpty()) return;
        }

        long deadline = System.nanoTime() + msPerTick.get() * 1_000_000L;

        while (cursor < queue.size() && System.nanoTime() < deadline) {
            int[] c = queue.get(cursor++);
            if (!mc.level.hasChunk(c[0], c[1])) continue;
            rescan(c[0], c[1]);
        }
    }

    /** Entity olarak tasinan storage: minecart, chest boat, sandikli at/llama. */
    private void scanEntityStorage() {
        entityBoxes.clear();

        if (!entityStorage.get() && !itemDrops.get()) return;

        int range = viewRange() * 16;
        double px = mc.player.getX();
        double py = mc.player.getY();
        double pz = mc.player.getZ();
        double rangeSq = (double) range * range;

        for (Entity e : mc.level.entitiesForRendering()) {
            double dx = e.getX() - px;
            double dy = e.getY() - py;
            double dz = e.getZ() - pz;
            if (dx * dx + dy * dy + dz * dz > rangeSq) continue;
            if (e.getY() < minY.get() || e.getY() > maxY.get()) continue;

            int g = -1;

            if (entityStorage.get()) {
                if (e.getType() == EntityType.CHEST_MINECART) {
                    g = G_CHEST;
                } else if (e.getType() == EntityType.HOPPER_MINECART) {
                    g = G_HOPPER;
                } else if (e instanceof AbstractChestBoat) {
                    g = G_CHEST;
                } else if (e instanceof AbstractChestedHorse horse && horse.hasChest()) {
                    g = G_OTHER;
                }
            }

            if (g < 0 && itemDrops.get() && e instanceof ItemEntity item) {
                g = group(item.getItem());
            }

            if (g < 0) continue;

            hit(e instanceof ItemEntity ? "item-drop" : "entity");
            entityBoxes.add(new EntityBox(e.getBoundingBox(), g));
        }
    }

    private void rescan(int cx, int cz) {
        if (mc.level == null || !mc.level.hasChunk(cx, cz)) return;

        LevelChunk chunk = mc.level.getChunk(cx, cz);
        List<Entry> entries = scanChunk(chunk, cx, cz);
        long k = key(cx, cz);

        if (entries.isEmpty()) {
            cache.remove(k);
        } else {
            cache.put(k, entries);
        }
    }

    private List<Entry> scanChunk(LevelChunk chunk, int cx, int cz) {
        List<Entry> out = new ArrayList<>();
        Set<Long> seen = new HashSet<>();

        int lo = Math.max(minY.get(), mc.level.getMinY());
        int hi = Math.min(maxY.get(), mc.level.getMaxY() - 1);

        // ---- istemcinin tuttugu block entity'ler ----
        if (scanEntities.get()) {
            for (Map.Entry<BlockPos, BlockEntity> e : chunk.getBlockEntities().entrySet()) {
                BlockPos pos = e.getKey();
                if (pos.getY() < lo || pos.getY() > hi) continue;

                int g = group(e.getValue().getType());
                if (g < 0) continue;

                hit("be-map");

                BlockState state = chunk.getBlockState(pos);
                boolean hidden = group(state) < 0;

                if (hidden && !showHidden.get()) continue;

                out.add(new Entry(pos, g, hidden));
                seen.add(pos.asLong());

                if (hidden && chatNotify.get() && announcedHidden.add(pos.asLong())) {
                    info("Hidden container at %d, %d, %d (%s behind %s)", pos.getX(), pos.getY(), pos.getZ(),
                        GROUP_NAMES[g], state.getBlock().getName().getString());
                }
            }
        }

        // ---- blok state taramasi ----
        if (scanStates.get()) {
            int byteX = cx << 4;
            int byteZ = cz << 4;
            int minSection = mc.level.getMinY() >> 4;

            for (int i = 0; i < chunk.getSectionsCount(); i++) {
                int secMin = (minSection + i) << 4;
                int secMax = secMin + 15;
                if (secMax < lo || secMin > hi) continue;

                LevelChunkSection section = chunk.getSection(i);
                if (section == null || section.hasOnlyAir()) continue;

                int yStart = Math.max(secMin, lo);
                int yEnd = Math.min(secMax, hi);

                for (int y = yStart; y <= yEnd; y++) {
                    for (int x = 0; x < 16; x++) {
                        for (int z = 0; z < 16; z++) {
                            BlockState st = section.getBlockState(x, y - secMin, z);
                            int g = group(st);
                            if (g < 0) continue;

                            BlockPos pos = new BlockPos(byteX + x, y, byteZ + z);
                            long pk = pos.asLong();
                            if (seen.contains(pk)) continue;

                            seen.add(pk);
                            hit("states");
                            out.add(new Entry(pos, g, false));
                        }
                    }
                }
            }
        }

        // ---- paket hafizasi ----
        Map<Long, Integer> remembered = memory.get(key(cx, cz));

        if (remembered != null && !remembered.isEmpty()) {
            for (Map.Entry<Long, Integer> e : remembered.entrySet()) {
                long pk = e.getKey();
                if (seen.contains(pk)) continue;

                BlockPos pos = BlockPos.of(pk);
                if (pos.getY() < lo || pos.getY() > hi) continue;

                seen.add(pk);
                hit("memory");
                out.add(new Entry(pos, e.getValue(), false));
            }
        }

        return out;
    }

    // ------------------------------------------------------------------ render

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (mc.level == null || mc.player == null) return;

        int pcx = mc.player.blockPosition().getX() >> 4;
        int pcz = mc.player.blockPosition().getZ() >> 4;
        int r = viewRange();
        int op = fillOpacity.get();

        Color[] line = {chestColor.get(), hopperColor.get(), otherColor.get(), hiddenColor.get()};
        Color hidden = line[3];
        Color suspect = suspectColor.get();

        // --- kutular ---
        for (Map.Entry<Long, List<Entry>> e : cache.entrySet()) {
            if (Math.abs(keyX(e.getKey()) - pcx) > r || Math.abs(keyZ(e.getKey()) - pcz) > r) continue;

            for (Entry entry : e.getValue()) {
                Color c = entry.hidden() ? hidden : line[entry.group()];
                Color side = new Color(c.r, c.g, c.b, op);

                event.renderer.box(new AABB(entry.pos()), side, c, ShapeMode.Both, 0);
            }
        }

        // --- entity storage ---
        for (EntityBox box : entityBoxes) {
            Color c = line[box.group()];
            Color side = new Color(c.r, c.g, c.b, Math.min(op, 25));

            event.renderer.box(box.box(), side, c, ShapeMode.Both, 0);
        }

        // --- kirilan blok isaretleri ---
        if (miningSuspects.get() && !suspects.isEmpty()) {
            Color side = new Color(suspect.r, suspect.g, suspect.b, 20);

            for (long sp : suspects) {
                BlockPos pos = BlockPos.of(sp);
                if (Math.abs((pos.getX() >> 4) - pcx) > r || Math.abs((pos.getZ() >> 4) - pcz) > r) continue;

                event.renderer.box(new AABB(pos), side, suspect, ShapeMode.Lines, 0);
            }
        }

        // --- tracer'lar ---
        if (!tracers.get()) return;

        int range = tracerRange.get();
        int limit = tracerLimit.get();
        double fadeFactor = tracerFade.get() / 100.0;

        // Kamera pozisyonu zaten interpolasyonlu: yururken cizgi kaymaz.
        Vec3 cam = mc.gameRenderer.getMainCamera().position();
        double ox = cam.x;
        double oy = tracerFromFeet.get() ? cam.y - 1.62 : cam.y;
        double oz = cam.z;

        List<TracerTarget> targets = new ArrayList<>();

        for (Map.Entry<Long, List<Entry>> e : cache.entrySet()) {
            if (Math.abs(keyX(e.getKey()) - pcx) > r || Math.abs(keyZ(e.getKey()) - pcz) > r) continue;

            for (Entry entry : e.getValue()) {
                double tx = entry.pos().getX() + 0.5;
                double ty = entry.pos().getY() + 0.5;
                double tz = entry.pos().getZ() + 0.5;
                double dist = Math.sqrt((tx - ox) * (tx - ox) + (ty - oy) * (ty - oy) + (tz - oz) * (tz - oz));

                if (dist > range) continue;
                targets.add(new TracerTarget(tx, ty, tz,
                    entry.hidden() ? hidden : line[entry.group()], dist));
            }
        }

        for (EntityBox box : entityBoxes) {
            AABB b = box.box();
            double tx = (b.minX + b.maxX) * 0.5;
            double ty = (b.minY + b.maxY) * 0.5;
            double tz = (b.minZ + b.maxZ) * 0.5;
            double dist = Math.sqrt((tx - ox) * (tx - ox) + (ty - oy) * (ty - oy) + (tz - oz) * (tz - oz));

            if (dist > range) continue;
            targets.add(new TracerTarget(tx, ty, tz, line[box.group()], dist));
        }

        // En yakin hedefler once cizilsin; boylece limit uzaktaki kutulari yemez.
        targets.sort(Comparator.comparingDouble(TracerTarget::distance));

        for (int i = 0; i < targets.size() && i < limit; i++) {
            TracerTarget t = targets.get(i);
            drawTracer(event, ox, oy, oz, t.x(), t.y(), t.z(), range, fadeFactor, t.color());
        }
    }

    private boolean drawTracer(Render3DEvent event, double ox, double oy, double oz,
                               double tx, double ty, double tz, int range, double fadeFactor, Color base) {
        double dx = tx - ox;
        double dy = ty - oy;
        double dz = tz - oz;
        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);

        if (dist > range) return false;

        int alpha = (int) (255 * (1.0 - fadeFactor * (dist / range)));
        if (alpha < 30) alpha = 30;

        event.renderer.line(ox, oy, oz, tx, ty, tz, new Color(base.r, base.g, base.b, alpha));
        return true;
    }
}
