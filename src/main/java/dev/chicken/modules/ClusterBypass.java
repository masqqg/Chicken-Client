package dev.chicken.modules;

import dev.chicken.systems.Categories;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.File;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import dev.chicken.events.GameLeftEvent;
import dev.chicken.events.Render2DEvent;
import dev.chicken.events.Render3DEvent;
import dev.chicken.events.TickEvent;
import dev.chicken.render.ShapeMode;
import dev.chicken.render.Renderer2D;
import dev.chicken.settings.BoolSetting;
import dev.chicken.settings.ColorSetting;
import dev.chicken.settings.IntSetting;
import dev.chicken.settings.Setting;
import dev.chicken.settings.SettingGroup;
import dev.chicken.systems.Module;
import dev.chicken.render.Color;
import dev.chicken.render.SettingColor;
import dev.chicken.events.EventHandler;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.lighting.LayerLightEventListener;
import net.minecraft.world.phys.AABB;

/**
 * Blok ışığı verisinden amethyst geode'larını bulur (blok state'i okumaz).
 * ClusterBypass v10
 *
 * v6 yenilikleri:
 *  - Işık, section DataLayer'larından okunur (getBrightness yerine) -> belirgin hız artışı
 *  - Otomatik marker Y: işaret chunk'ın en üstteki bloğuna çizilir
 *  - Sohbete "geode bulundu" bildirimi (chunk başına 1 kez, koordinat + mesafe + yön)
 *  - HUD: geode sayısı, sweep yüzdesi, en yakın geode mesafesi/yönü, tarama süresi
 *  - Tracer: en yakın geode'ye çizgi
 *  - Ziyaret edilen geodeları gizleme
 *  - Overworld filtresi ve ayar değişince kuyruğu otomatik yenileme
 *  - Sprite artık blok blok değil, birleştirilmiş dikdörtgenlerle çizilir (daha az draw call)
 *  - Bud ESP: işaretli chunk'ın içindeki amethyst bud'ları mor dolu kutu olarak çizilir
 *
 * v7 yenilikleri:
 *  - Artık sadece büyük amethyst bud'lar taranır (ışık seviyesi 4); küçük/orta bud ve cluster yok sayılır
 *  - Işık budun kendisinde değil çevresindeki Air bloklarında olduğunda, budun merkezi 6 yönlü
 *    ışık deseninden (açık Air yüzleri / kapalı duvar yüzleri) çözülür ve ESP merkeze çizilir
 *  - HUD ve sohbet bildirimi sadece büyük bud sayısını gösterir
 *
 * v8 yenilikleri:
 *  - Bulunan geodelar diske kaydedilir (meteor-client/cluster-bypass-geodes.json) ve
 *    modül açılınca geri yüklenir; menzilden çıkınca veya oyun kapanınca kaybolmazlar
 *
 * v9 yenilikleri:
 *  - Chunk işaretleri artık her zaman sabit Y=64'te çizilir
 *
 * v10 yenilikleri:
 *  - Sağ tarafta siyah-beyaz üstten harita: oyuncu oku + işaretli chunk'lar
 */
public class ClusterBypass extends Module {
    /** Büyük amethyst bud ışık seviyesi (küçük=1, orta=2, büyük=4, cluster=5). */
    private static final int LARGE_BUD_LIGHT = 4;
    /** Chunk işaretleri her zaman bu Y'de çizilir. */
    private static final int MARKER_Y = 64;

    private static final String GEODE_FILE = "cluster-bypass-geodes.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static final int QUIET_FULL_SCANS = 3;
    private static final int COLD_PERIOD = 5;

    private static final Direction[] DIRS = Direction.values();
    private static final String[] COMPASS = {"S", "SW", "W", "NW", "N", "NE", "E", "SE"};

    private final Random random = new Random();

    private static final int[] SPRITE_PALETTE = {7755952, 13409009, 9136332, 6309019, 16697829, 11503858, 16711121};
    private static final String[] SPRITE = {
        "................",
        ".......B........",
        "......BEF.......",
        ".....BBECB......",
        "....BBBECCB.....",
        "....BEGGFCB.....",
        "....AFFBACA.....",
        "....ACFBACD.....",
        "EEB..ACBAAD..BEE",
        "BEEB.AAFAAD.BEEB",
        "BCGBBDACFA.BBGCB",
        "BCCEADAAFDCCBCCB",
        ".BCABADACDABACB.",
        "..BAAAADCDCAAC..",
        "...CADADBCDAC...",
        "...ADDDCBDDDA..."
    };
    private static final Color[] SPRITE_COLORS = new Color[SPRITE_PALETTE.length];
    private static final List<int[]> SPRITE_RECTS = new ArrayList<>();

    static {
        for (int i = 0; i < SPRITE_PALETTE.length; i++) {
            int p = SPRITE_PALETTE[i];
            SPRITE_COLORS[i] = new Color(p >> 16 & 0xFF, p >> 8 & 0xFF, p & 0xFF, 255);
        }

        int rows = SPRITE.length;
        boolean[][] used = new boolean[rows][];
        for (int r = 0; r < rows; r++) used[r] = new boolean[SPRITE[r].length()];

        for (int r = 0; r < rows; r++) {
            String line = SPRITE[r];
            for (int c = 0; c < line.length(); c++) {
                char ch = line.charAt(c);
                if (ch == '.' || used[r][c]) continue;

                int w = 1;
                while (c + w < line.length() && line.charAt(c + w) == ch && !used[r][c + w]) w++;

                int h = 1;
                grow:
                while (r + h < rows) {
                    String next = SPRITE[r + h];
                    if (next.length() < c + w) break;
                    for (int k = 0; k < w; k++) {
                        if (next.charAt(c + k) != ch || used[r + h][c + k]) break grow;
                    }
                    h++;
                }

                for (int dr = 0; dr < h; dr++) {
                    for (int dc = 0; dc < w; dc++) used[r + dr][c + dc] = true;
                }
                SPRITE_RECTS.add(new int[]{c, r, w, h, ch - 'A'});
            }
        }
    }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgFilter = settings.createGroup("Filter");
    private final SettingGroup sgNotify = settings.createGroup("Notify");
    private final SettingGroup sgRender = settings.createGroup("Render");
    private final SettingGroup sgBud = settings.createGroup("Bud ESP");
    private final SettingGroup sgSave = settings.createGroup("Save");
    private final SettingGroup sgMap = settings.createGroup("Map");

    // ---- General ----
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
        .defaultValue(4)
        .range(-64, 320)
        .sliderRange(-64, 128)
        .build());

    private final Setting<Integer> maxY = sgGeneral.add(new IntSetting.Builder()
        .name("max-y")
        .description("Highest Y to scan.")
        .defaultValue(64)
        .range(-64, 320)
        .sliderRange(-64, 128)
        .build());

    private final Setting<Integer> sweepTicks = sgGeneral.add(new IntSetting.Builder()
        .name("sweep-ticks")
        .description("Start a new sweep over the loaded chunks every N ticks.")
        .defaultValue(20)
        .range(1, 200)
        .sliderRange(5, 100)
        .build());

    private final Setting<Integer> msPerTick = sgGeneral.add(new IntSetting.Builder()
        .name("ms-per-tick")
        .description("Scan time budget per tick in milliseconds.")
        .defaultValue(5)
        .range(1, 40)
        .sliderRange(1, 40)
        .build());

    private final Setting<Boolean> overworldOnly = sgGeneral.add(new BoolSetting.Builder()
        .name("overworld-only")
        .description("Only scan in the overworld (geodes do not spawn elsewhere).")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> debug = sgGeneral.add(new BoolSetting.Builder()
        .name("debug")
        .description("Print large-bud counts in chat when a chunk changes.")
        .defaultValue(false)
        .build());

    // ---- Filter ----
    private final Setting<Integer> minimum = sgFilter.add(new IntSetting.Builder()
        .name("min-buds")
        .description("Large-bud centers needed to mark a chunk.")
        .defaultValue(3)
        .range(1, 200)
        .sliderRange(1, 60)
        .build());

    private final Setting<Integer> maxBuds = sgFilter.add(new IntSetting.Builder()
        .name("max-buds")
        .description("A chunk reporting more sources than this is treated as noise and ignored.")
        .defaultValue(150)
        .range(10, 1000)
        .sliderRange(20, 400)
        .build());

    private final Setting<Integer> confirmSweeps = sgFilter.add(new IntSetting.Builder()
        .name("confirm-sweeps")
        .description("A chunk must qualify on this many consecutive sweeps before it is drawn.")
        .defaultValue(2)
        .range(1, 6)
        .sliderRange(1, 6)
        .build());

    private final Setting<Boolean> undergroundOnly = sgFilter.add(new BoolSetting.Builder()
        .name("underground-only")
        .description("Ignore positions that receive any sky light.")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> checkFalloff = sgFilter.add(new BoolSetting.Builder()
        .name("check-falloff")
        .description("Emitters with light >= 2 must have a neighbour at exactly light-1.")
        .defaultValue(true)
        .build());

    // ---- Notify ----
    private final Setting<Boolean> chatNotify = sgNotify.add(new BoolSetting.Builder()
        .name("chat-notify")
        .description("Announce a newly confirmed geode once per chunk (coords + distance + direction).")
        .defaultValue(true)
        .build());

    // ---- Render ----
    private final Setting<Boolean> spriteMarker = sgRender.add(new BoolSetting.Builder()
        .name("sprite-marker")
        .description("Draw the amethyst cluster pixel art on the chunk floor.")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> hud = sgRender.add(new BoolSetting.Builder()
        .name("hud-overlay")
        .description("Shows real-time status and found geode count on screen.")
        .defaultValue(true)
        .build());

    private final Setting<Integer> hudX = sgRender.add(new IntSetting.Builder()
        .name("hud-x")
        .description("HUD horizontal offset.")
        .defaultValue(10)
        .range(0, 3000)
        .sliderRange(0, 800)
        .build());

    private final Setting<Integer> hudY = sgRender.add(new IntSetting.Builder()
        .name("hud-y")
        .description("HUD vertical offset.")
        .defaultValue(10)
        .range(0, 2000)
        .sliderRange(0, 800)
        .build());

    private final Setting<SettingColor> hudColor = sgRender.add(new ColorSetting.Builder()
        .name("hud-color")
        .description("HUD text color.")
        .defaultValue(new SettingColor(255, 255, 255, 255))
        .build());

    private final Setting<Integer> hudGeodeList = sgRender.add(new IntSetting.Builder()
        .name("hud-geode-list")
        .description("How many found geodes to list with their bud counts.")
        .defaultValue(2)
        .range(0, 5)
        .sliderRange(0, 5)
        .build());

    private final Setting<SettingColor> sideColor = sgRender.add(new ColorSetting.Builder()
        .name("side-color")
        .description("Marker fill color.")
        .defaultValue(new SettingColor(170, 0, 255, 55))
        .build());

    private final Setting<SettingColor> lineColor = sgRender.add(new ColorSetting.Builder()
        .name("line-color")
        .description("Marker outline color.")
        .defaultValue(new SettingColor(255, 80, 255, 180))
        .build());

    private final Setting<Boolean> showGrowthArea = sgRender.add(new BoolSetting.Builder()
        .name("show-growth-area")
        .description("Also mark the full square of chunks around a found chunk.")
        .defaultValue(true)
        .build());

    private final Setting<Integer> growthRadius = sgRender.add(new IntSetting.Builder()
        .name("growth-radius")
        .description("Radius in chunks.")
        .defaultValue(1)
        .range(1, 16)
        .sliderRange(1, 10)
        .build());

    private final Setting<SettingColor> growthSideColor = sgRender.add(new ColorSetting.Builder()
        .name("growth-side-color")
        .defaultValue(new SettingColor(255, 150, 220, 25))
        .build());

    private final Setting<SettingColor> growthLineColor = sgRender.add(new ColorSetting.Builder()
        .name("growth-line-color")
        .defaultValue(new SettingColor(255, 180, 230, 90))
        .build());

    private final Setting<Boolean> tracer = sgRender.add(new BoolSetting.Builder()
        .name("tracer")
        .description("Draw a line from your eyes to the nearest confirmed geode.")
        .defaultValue(false)
        .build());

    private final Setting<SettingColor> tracerColor = sgRender.add(new ColorSetting.Builder()
        .name("tracer-color")
        .defaultValue(new SettingColor(255, 80, 255, 200))
        .build());

    private final Setting<Boolean> hideVisited = sgRender.add(new BoolSetting.Builder()
        .name("hide-visited")
        .description("Stop drawing geodes you have already walked up to.")
        .defaultValue(true)
        .build());

    private final Setting<Integer> visitedRadius = sgRender.add(new IntSetting.Builder()
        .name("visited-radius")
        .description("Distance in blocks from the chunk center that counts as visited.")
        .defaultValue(20)
        .range(4, 64)
        .sliderRange(4, 48)
        .build());

    // ---- Bud ESP ----
    private final Setting<Boolean> budEsp = sgBud.add(new BoolSetting.Builder()
        .name("bud-esp")
        .description("Draw every large amethyst bud the scan found, so you see the geode shape while digging.")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> budMarkedOnly = sgBud.add(new BoolSetting.Builder()
        .name("marked-only")
        .description("Only draw buds of chunks that reached min-buds and confirm-sweeps.")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> budFilled = sgBud.add(new BoolSetting.Builder()
        .name("bud-filled")
        .description("Fill the bud boxes instead of drawing only their outline.")
        .defaultValue(true)
        .build());

    private final Setting<Integer> budFillAlpha = sgBud.add(new IntSetting.Builder()
        .name("bud-fill-alpha")
        .description("Opacity of the filled part.")
        .defaultValue(120)
        .range(0, 255)
        .sliderRange(0, 255)
        .build());

    private final Setting<Integer> budRange = sgBud.add(new IntSetting.Builder()
        .name("bud-range")
        .description("Only draw buds within this many blocks of you.")
        .defaultValue(48)
        .range(8, 256)
        .sliderRange(8, 128)
        .build());

    private final Setting<SettingColor> budColor = sgBud.add(new ColorSetting.Builder()
        .name("bud-color")
        .description("Bud box color.")
        .defaultValue(new SettingColor(200, 70, 255, 220))
        .build());

    // ---- Save ----
    private final Setting<Boolean> persist = sgSave.add(new BoolSetting.Builder()
        .name("save-geodes")
        .description("Save found geode markers to disk and load them again on restart.")
        .defaultValue(true)
        .build());

    private final Setting<Integer> saveInterval = sgSave.add(new IntSetting.Builder()
        .name("save-interval")
        .description("Seconds between automatic saves while geode data changes.")
        .defaultValue(15)
        .range(5, 300)
        .sliderRange(5, 120)
        .build());

    // ---- Map ----
    private final Setting<Boolean> map = sgMap.add(new BoolSetting.Builder()
        .name("map")
        .description("Show a black-and-white top-down map while the module is active.")
        .defaultValue(true)
        .build());

    private final Setting<Integer> mapSize = sgMap.add(new IntSetting.Builder()
        .name("map-size")
        .description("Map width and height in pixels.")
        .defaultValue(128)
        .range(64, 320)
        .sliderRange(64, 256)
        .build());

    private final Setting<Integer> mapRange = sgMap.add(new IntSetting.Builder()
        .name("map-range")
        .description("How many chunks around the player the map shows.")
        .defaultValue(8)
        .range(2, 24)
        .sliderRange(2, 16)
        .build());

    private final Setting<Integer> mapX = sgMap.add(new IntSetting.Builder()
        .name("map-x")
        .description("Distance from the right screen edge.")
        .defaultValue(8)
        .range(0, 500)
        .sliderRange(0, 200)
        .build());

    private final Setting<Integer> mapY = sgMap.add(new IntSetting.Builder()
        .name("map-y")
        .description("Distance from the top screen edge.")
        .defaultValue(8)
        .range(0, 500)
        .sliderRange(0, 200)
        .build());

    private final Setting<Integer> mapOpacity = sgMap.add(new IntSetting.Builder()
        .name("map-opacity")
        .description("Opacity of the black map background.")
        .defaultValue(220)
        .range(0, 255)
        .sliderRange(0, 255)
        .build());

    private final Setting<Boolean> mapGrid = sgMap.add(new BoolSetting.Builder()
        .name("map-grid")
        .description("Draw the chunk grid.")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> mapMarkers = sgMap.add(new BoolSetting.Builder()
        .name("map-markers")
        .description("Draw confirmed geode chunks as white squares.")
        .defaultValue(true)
        .build());

    /** Bir chunk'ın tarama durumu. */
    private static final class ChunkState {
        int count;
        int confirms;
        int ySum;
        int emitters;
        boolean visited;
        /** Bir kez onaylanan geode menzilden çıkınca silinmez ve diske yazılır. */
        boolean persistent;
        /** Bulunan büyük bud merkezleri (BlockPos.asLong). */
        long[] buds = new long[0];

        int avgYOr(int fallback) {
            return emitters > 0 ? ySum / emitters : fallback;
        }
    }

    /** Diske yazılan tek bir geode. */
    private static final class SavedGeode {
        int x;
        int z;
        int y;
        int count;
        boolean visited;
        long[] buds;
    }

    private static final class SaveFile {
        int version = 1;
        List<SavedGeode> geodes = new ArrayList<>();
    }

    private final Map<Long, ChunkState> chunks = new HashMap<>();
    private final Map<Long, Integer> quiet = new HashMap<>();
    private final Set<Long> announced = new HashSet<>();
    private final List<int[]> queue = new ArrayList<>();

    // Aynı tick içinde aynı section verisi tekrar tekrar istenmesin diye küçük önbellek.
    private final Map<Long, DataLayer> blockLayers = new HashMap<>();
    private final Map<Long, DataLayer> skyLayers = new HashMap<>();
    private LayerLightEventListener blockLight;
    private LayerLightEventListener skyLight;

    private Level lastLevel;
    private int cursor;
    private int sinceSweep;
    private int sweepIndex;
    private int queueSignature;
    private int visitedTimer;

    private int scanSumY;
    private int scanEmitters;
    private final List<Long> scanBuds = new ArrayList<>();
    private double emaScanMs;

    private final List<int[]> markerCache = new ArrayList<>();
    private long[] growthCache = new long[0];
    private int dataVersion;
    private int cacheVersion = -1;
    private int cacheSignature;
    private boolean saveDirty;
    private long lastSaveMs;

    public ClusterBypass() {
        super(Categories.WORLD, "cluster-bypass", "Marks chunks when block-light bud sources reach the configured minimum.");
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

    @Override
    public void onActivate() {
        reset();
        lastLevel = null;
        loadGeodes();
    }

    @Override
    public void onDeactivate() {
        saveGeodes();
        reset();
        lastLevel = null;
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        saveGeodes();
    }

    private void reset() {
        chunks.clear();
        quiet.clear();
        announced.clear();
        queue.clear();
        markerCache.clear();
        blockLayers.clear();
        skyLayers.clear();
        scanBuds.clear();
        growthCache = new long[0];
        cursor = 0;
        sweepIndex = 0;
        queueSignature = 0;
        visitedTimer = 0;
        scanSumY = 0;
        scanEmitters = 0;
        emaScanMs = 0.0;
        sinceSweep = Integer.MAX_VALUE / 2;
        dataVersion++;
    }

    // ------------------------------------------------------------------ save

    private static File geodeFile() {
        return dev.chicken.ChickenClient.FOLDER.resolve(GEODE_FILE).toFile();
    }

    /** Onaylanmış geodeları diske yazar; hata olursa sohbete uyarı basar. */
    private void saveGeodes() {
        if (!persist.get() || !saveDirty) return;

        SaveFile data = new SaveFile();

        for (Map.Entry<Long, ChunkState> e : chunks.entrySet()) {
            ChunkState s = e.getValue();
            if (!s.persistent) continue;

            SavedGeode g = new SavedGeode();
            g.x = keyX(e.getKey());
            g.z = keyZ(e.getKey());
            g.y = s.avgYOr(MARKER_Y);
            g.count = s.count;
            g.visited = s.visited;
            g.buds = s.buds;
            data.geodes.add(g);
        }

        File file = geodeFile();
        File tmp = new File(file.getPath() + ".tmp");

        try {
            Files.createDirectories(dev.chicken.ChickenClient.FOLDER);

            try (Writer writer = Files.newBufferedWriter(tmp.toPath(), StandardCharsets.UTF_8)) {
                GSON.toJson(data, writer);
            }

            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            saveDirty = false;
            lastSaveMs = System.currentTimeMillis();
        } catch (Exception e) {
            warning("Could not save geodes: %s", e.getMessage());
        }
    }

    /** Diskten kaydedilmiş geodeları yükler (modül açılınca / dünyaya girince). */
    private void loadGeodes() {
        if (!persist.get()) return;

        File file = geodeFile();
        if (!file.isFile()) return;

        try (Reader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
            SaveFile data = GSON.fromJson(reader, SaveFile.class);
            if (data == null || data.geodes == null) return;

            int loaded = 0;

            for (SavedGeode g : data.geodes) {
                if (g == null) continue;

                ChunkState s = new ChunkState();
                s.count = Math.max(0, g.count);
                s.confirms = 100;
                s.emitters = Math.max(1, s.count);
                s.ySum = g.y * s.emitters;
                s.visited = g.visited;
                s.persistent = true;
                s.buds = g.buds != null ? g.buds : new long[0];

                long k = key(g.x, g.z);
                chunks.put(k, s);
                announced.add(k);
                loaded++;
            }

            if (loaded > 0) {
                dataVersion++;
                if (debug.get()) info("Loaded %d saved geode(s)", loaded);
            }
        } catch (Exception e) {
            warning("Could not load saved geodes: %s", e.getMessage());
        }
    }

    // ------------------------------------------------------------------ tick

    @EventHandler
    private void onTick(TickEvent.Post event) {
        Level level = mc.level;
        if (level == null || mc.player == null) return;

        if (saveDirty && System.currentTimeMillis() - lastSaveMs >= saveInterval.get() * 1000L) {
            saveGeodes();
        }

        if (level != lastLevel) {
            saveGeodes();
            reset();
            lastLevel = level;
            loadGeodes();
        }

        if (overworldOnly.get() && mc.level.dimension() != Level.OVERWORLD) {
            if (!queue.isEmpty()) queue.clear();
            return;
        }

        blockLight = level.getLightEngine().getLayerListener(LightLayer.BLOCK);
        skyLight = level.getLightEngine().getLayerListener(LightLayer.SKY);

        if (visitedTimer-- <= 0) {
            visitedTimer = 10; // saniyede 2 kez yeterli
            updateVisited();
        }

        // Ayar değiştiyse kuyruğu beklemeden baştan kur.
        int signature = queueSignatureOf();
        if (signature != queueSignature) {
            queueSignature = signature;
            sinceSweep = Integer.MAX_VALUE / 2;
            cursor = queue.size();
        }

        sinceSweep++;
        if (cursor >= queue.size()) {
            if (sinceSweep < sweepTicks.get()) return;
            sinceSweep = 0;
            rebuildQueue(level);
            if (queue.isEmpty()) return;
        }

        int bottom = level.getMinY();
        int top = level.getMaxY() + 1;
        int limit = maxBuds.get();
        int confirm = confirmSweeps.get();
        int needed = minimum.get();
        int fallbackY = MARKER_Y;

        blockLayers.clear();
        skyLayers.clear();

        long start = System.nanoTime();
        long deadline = start + msPerTick.get() * 1_000_000L;

        while (cursor < queue.size() && System.nanoTime() < deadline) {
            int[] c = queue.get(cursor++);
            if (!level.hasChunk(c[0], c[1])) continue;

            long k = key(c[0], c[1]);

            scanSumY = 0;
            scanEmitters = 0;
            scanBuds.clear();
            int found = scanChunk(level, c[0], c[1], bottom, top);

            if (found > limit) {
                if (debug.get()) info("chunk %d,%d: %d sources, ignored as noise", c[0], c[1], found);
                found = 0;
            }

            ChunkState state = chunks.get(k);

            if (found == 0) {
                quiet.merge(k, 1, (a, b) -> Math.min(a + b, 1000));
                if (state != null && !state.persistent) {
                    chunks.remove(k);
                    dataVersion++;
                }
                continue;
            }

            quiet.remove(k);

            if (state == null) {
                state = new ChunkState();
                chunks.put(k, state);
                dataVersion++;
            }

            int oldCount = state.count;
            state.count = found;
            if (state.confirms < 100) state.confirms++;
            state.ySum = scanSumY;
            state.emitters = scanEmitters;

            long[] budArray = new long[scanBuds.size()];
            for (int i = 0; i < budArray.length; i++) budArray[i] = scanBuds.get(i);
            state.buds = budArray;

            if (found != oldCount || state.confirms == confirm) {
                dataVersion++;
                saveDirty = true;
            }

            // Bir kez onaylanan geode kalıcıdır: menzilden çıkınca silinmez.
            if (!state.persistent && state.count >= needed && state.confirms >= confirm) {
                state.persistent = true;
                saveDirty = true;
                saveGeodes();
            }

            if (debug.get() && found != oldCount) {
                info("chunk %d,%d: large buds=%d (y~%d)", c[0], c[1], found, state.avgYOr(fallbackY));
            }

            // Yeni onaylanan geode -> tek seferlik bildirim.
            if (chatNotify.get() && found >= needed && state.confirms >= confirm && announced.add(k)) {
                notifyFound(c[0], c[1], state.avgYOr(fallbackY), state.count);
            }
        }

        double ms = (System.nanoTime() - start) / 1_000_000.0;
        emaScanMs = emaScanMs <= 0.0 ? ms : emaScanMs * 0.8 + ms * 0.2;
    }

    private int queueSignatureOf() {
        return ((chunkRange.get() * 31 + minY.get()) * 31 + maxY.get()) * 31 + sweepTicks.get();
    }

    private void notifyFound(int cx, int cz, int y, int buds) {
        int centerX = (cx << 4) + 8;
        int centerZ = (cz << 4) + 8;

        double dx = centerX + 0.5 - mc.player.getX();
        double dy = y + 0.5 - mc.player.getY();
        double dz = centerZ + 0.5 - mc.player.getZ();
        int dist = (int) Math.round(Math.sqrt(dx * dx + dy * dy + dz * dz));

        info("Geode: %d, %d, %d (%dm %s) | %d large buds",
            centerX, y, centerZ, dist, compass(dx, dz), buds);
    }

    private static String compass(double dx, double dz) {
        double yaw = -Math.toDegrees(Math.atan2(dx, dz));
        yaw = (yaw % 360.0 + 360.0) % 360.0;
        int i = (int) Math.round(yaw / 45.0) % 8;
        return COMPASS[i];
    }

    /**
     * Oyuncunun gerçekten yanına geldiği geodeları işaretle (bir daha çizilmesin).
     * Mesafe 3B hesaplanır: yüzeyde üstünden geçmek "ziyaret" saymaz.
     */
    private void updateVisited() {
        if (!hideVisited.get()) return;

        double px = mc.player.getX();
        double py = mc.player.getY();
        double pz = mc.player.getZ();
        double rSq = (double) visitedRadius.get() * visitedRadius.get();

        int min = minimum.get();
        int confirm = confirmSweeps.get();
        int fallbackY = MARKER_Y;
        boolean changed = false;

        for (Map.Entry<Long, ChunkState> e : chunks.entrySet()) {
            ChunkState s = e.getValue();
            if (s.visited) continue;
            if (!s.persistent && (s.count < min || s.confirms < confirm)) continue;

            double dx = (keyX(e.getKey()) * 16.0 + 8.0) - px;
            double dy = s.avgYOr(fallbackY) - py;
            double dz = (keyZ(e.getKey()) * 16.0 + 8.0) - pz;

            if (dx * dx + dy * dy + dz * dz <= rSq) {
                s.visited = true;
                changed = true;
            }
        }

        if (changed) {
            dataVersion++;
            saveDirty = true;
        }
    }

    // ------------------------------------------------------------------ scan

    private void rebuildQueue(Level level) {
        queue.clear();
        cursor = 0;
        sweepIndex++;

        int pcx = mc.player.blockPosition().getX() >> 4;
        int pcz = mc.player.blockPosition().getZ() >> 4;
        int r = chunkRange.get();
        boolean coldDue = sweepIndex % COLD_PERIOD == 0;

        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                int cx = pcx + dx;
                int cz = pcz + dz;
                if (!level.hasChunk(cx, cz)) continue;

                Integer q = quiet.get(key(cx, cz));
                if (q != null && q >= QUIET_FULL_SCANS && !coldDue) continue;

                int distSq = dx * dx + dz * dz;
                // Jitter önceden hesaplanır: comparator sözleşmesi bozulmaz.
                int jitteredDist = distSq + random.nextInt(4);
                queue.add(new int[]{cx, cz, jitteredDist});
            }
        }

        queue.sort(Comparator.comparingInt(a -> a[2]));

        boolean removed = chunks.keySet().removeIf(k -> {
            if (!isStale(level, k, pcx, pcz, r)) return false;
            ChunkState s = chunks.get(k);
            return s == null || !s.persistent;
        });
        quiet.keySet().removeIf(k -> isStale(level, k, pcx, pcz, r));
        if (removed) dataVersion++;
    }

    private static boolean isStale(Level level, long k, int pcx, int pcz, int r) {
        int cx = keyX(k);
        int cz = keyZ(k);
        return Math.abs(cx - pcx) > r || Math.abs(cz - pcz) > r || !level.hasChunk(cx, cz);
    }

    private int scanChunk(Level level, int cx, int cz, int bottom, int top) {
        int lo = Math.max(minY.get(), bottom);
        int hi = Math.min(maxY.get(), top - 1);
        if (lo > hi) return 0;

        boolean underground = undergroundOnly.get();
        boolean falloff = checkFalloff.get();

        int bx = cx << 4;
        int bz = cz << 4;
        // Aynı budun birden fazla ışık alan Air yüzü aynı merkeze çözülsün diye set.
        Set<Long> centers = new HashSet<>();

        for (int sy = lo >> 4; sy <= hi >> 4; sy++) {
            DataLayer arr = layer(blockLayers, blockLight, cx, sy, cz);
            if (arr == null) continue; // boş / yüklenmemiş section

            int yStart = Math.max(lo, sy << 4);
            int yEnd = Math.min(hi, (sy << 4) + 15);

            for (int y = yStart; y <= yEnd; y++) {
                int ly = y & 15;

                for (int x = 0; x < 16; x++) {
                    int wx = bx + x;

                    for (int z = 0; z < 16; z++) {
                        int light = arr.get(x, ly, z);
                        if (light != LARGE_BUD_LIGHT) continue;

                        int wz = bz + z;
                        if (underground && skyLightAt(wx, y, wz) > 0) continue;
                        if (!isEmitter(wx, y, wz, light, bottom, top, falloff)) continue;

                        // Işık çoğu zaman budun kendisinde değil, yanındaki Air bloğundadır.
                        // 6 yönlü desenden gerçek bud merkezini çöz.
                        long center = resolveBudCenter(wx, y, wz);
                        int centerX = BlockPos.getX(center);
                        int centerZ = BlockPos.getZ(center);

                        // Merkez başka chunk'taysa o chunk'ın taraması sayar (çift sayım olmasın).
                        if ((centerX >> 4) != cx || (centerZ >> 4) != cz) continue;
                        if (!centers.add(center)) continue;

                        scanSumY += BlockPos.getY(center);
                        scanEmitters++;
                        scanBuds.add(center);
                    }
                }
            }
        }

        return centers.size();
    }

    private boolean isEmitter(int x, int y, int z, int light, int bottom, int top, boolean falloff) {
        boolean hasFalloff = light < 2;

        for (Direction dir : DIRS) {
            int ny = y + dir.getStepY();
            if (ny < bottom || ny >= top) continue;

            int nl = blockLightAt(x + dir.getStepX(), ny, z + dir.getStepZ());
            if (nl < 0) continue;          // komşu section yok: eleme yapma
            if (nl > light) return false;  // daha parlak komşu: kaynak değil
            if (nl == light - 1) hasFalloff = true;
        }

        return !falloff || hasFalloff;
    }

    /**
     * Büyük bud ışığı çoğu sunucuda budun kendisinde değil, çevresindeki Air
     * bloklarında görünür; bu yüzden ışık noktası tek başına merkez değildir.
     *
     * Bir bloğun 6 yönüne bakılır: budun Air'e bakan (açık) yüzleri ışık alır,
     * geode duvarına bakan (kapalı) yüzleri almaz. Birden fazla ışık noktasının
     * ortak komşusu olan blok budun merkezidir. Böyle bir blok yoksa (ışık zaten
     * budun kendi bloğundaysa) ışık noktasının kendisi merkez kabul edilir.
     */
    private long resolveBudCenter(int x, int y, int z) {
        long fallback = BlockPos.asLong(x, y, z);
        long best = fallback;
        int bestOpen = 0;

        for (Direction dir : DIRS) {
            int nx = x + dir.getStepX();
            int ny = y + dir.getStepY();
            int nz = z + dir.getStepZ();

            // Merkez adayı Air olamaz: Air ışık alır. Bud ise karanlık (ışık
            // sunucuda budun üstünde tutulmuyorsa) veya kendi ışığını taşır.
            int own = blockLightAt(nx, ny, nz);
            if (own != 0 && own != LARGE_BUD_LIGHT) continue;

            int open = openFaces(nx, ny, nz);
            if (open > bestOpen) {
                bestOpen = open;
                best = BlockPos.asLong(nx, ny, nz);
            }
        }

        // Tek açık yüz belirsizdir (duvar bloğu da olabilir); ışık noktasını kullan.
        return bestOpen >= 2 ? best : fallback;
    }

    /** Bloğun 6 yönünden kaç tanesi büyük bud ışığı (açık Air yüzü) alıyor? */
    private int openFaces(int x, int y, int z) {
        int open = 0;

        for (Direction dir : DIRS) {
            if (blockLightAt(x + dir.getStepX(), y + dir.getStepY(), z + dir.getStepZ()) == LARGE_BUD_LIGHT) open++;
        }

        return open;
    }

    // ------------------------------------------------------------------ light

    /**
     * Section'ın ışık DataLayer'ını önbellekten döndürür.
     * null = section yok veya tamamen boş (o bölgede ışık yok sayılır).
     */
    private DataLayer layer(Map<Long, DataLayer> cache, LayerLightEventListener listener, int cx, int sy, int cz) {
        long k = SectionPos.asLong(cx, sy, cz);
        if (cache.containsKey(k)) return cache.get(k);

        DataLayer arr = listener == null ? null : listener.getDataLayerData(SectionPos.of(k));
        if (arr != null && arr.isEmpty()) arr = null;

        cache.put(k, arr);
        return arr;
    }

    private int blockLightAt(int x, int y, int z) {
        DataLayer arr = layer(blockLayers, blockLight, x >> 4, y >> 4, z >> 4);
        return arr == null ? -1 : arr.get(x & 15, y & 15, z & 15);
    }

    private int skyLightAt(int x, int y, int z) {
        DataLayer arr = layer(skyLayers, skyLight, x >> 4, y >> 4, z >> 4);
        return arr == null ? 0 : arr.get(x & 15, y & 15, z & 15);
    }

    // ------------------------------------------------------------------ cache

    private void refreshCache() {
        int min = minimum.get();
        int confirm = confirmSweeps.get();
        int gr = growthRadius.get();
        boolean growth = showGrowthArea.get();
        boolean skipVisited = hideVisited.get();

        int signature = min;
        signature = signature * 31 + confirm;
        signature = signature * 31 + gr;
        signature = signature * 2 + (growth ? 1 : 0);
        signature = signature * 2 + (skipVisited ? 1 : 0);

        if (cacheVersion == dataVersion && cacheSignature == signature) return;
        cacheVersion = dataVersion;
        cacheSignature = signature;

        markerCache.clear();
        Set<Long> area = new HashSet<>();

        for (Map.Entry<Long, ChunkState> e : chunks.entrySet()) {
            ChunkState s = e.getValue();
            if (!s.persistent && (s.count < min || s.confirms < confirm)) continue;
            if (skipVisited && s.visited) continue;

            int cx = keyX(e.getKey());
            int cz = keyZ(e.getKey());

            markerCache.add(new int[]{cx, cz, MARKER_Y, s.count});

            if (growth) {
                for (int dx = -gr; dx <= gr; dx++) {
                    for (int dz = -gr; dz <= gr; dz++) area.add(key(cx + dx, cz + dz));
                }
            }
        }

        growthCache = new long[area.size()];
        int i = 0;
        for (long k : area) growthCache[i++] = k;
    }

    private List<int[]> nearestMarkers(int n) {
        List<int[]> list = new ArrayList<>(markerCache);
        list.sort(Comparator.comparingDouble(this::distSqToPlayer));
        return list.size() > n ? list.subList(0, n) : list;
    }

    private double distSqToPlayer(int[] m) {
        double dx = m[0] * 16.0 + 8.0 - mc.player.getX();
        double dz = m[1] * 16.0 + 8.0 - mc.player.getZ();
        return dx * dx + dz * dz;
    }

    /** Büyük bud sayısını metne çevirir. */
    private static String budsText(int[] m) {
        return String.format("%d large buds", m[3]);
    }

    private int[] nearestMarker() {
        int[] best = null;
        double bestDist = Double.MAX_VALUE;

        for (int[] m : markerCache) {
            double dx = m[0] * 16.0 + 8.0 - mc.player.getX();
            double dz = m[1] * 16.0 + 8.0 - mc.player.getZ();
            double d = dx * dx + dz * dz;

            if (d < bestDist) {
                bestDist = d;
                best = m;
            }
        }

        return best;
    }

    // ----------------------------------------------------------------- render

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (mc.level == null || mc.player == null || mc.level != lastLevel || chunks.isEmpty()) return;

        refreshCache();

        int pcx = mc.player.blockPosition().getX() >> 4;
        int pcz = mc.player.blockPosition().getZ() >> 4;
        int r = chunkRange.get();
        double baseY = MARKER_Y;

        int min = minimum.get();
        int confirm = confirmSweeps.get();

        // --- bud ESP: işaretli chunk'ın içindeki amethyst bud'ları ---
        if (budEsp.get()) {
            renderBuds(event, pcx, pcz, r, min, confirm);
        }

        if (markerCache.isEmpty()) return;

        if (showGrowthArea.get()) {
            int reach = r + growthRadius.get();
            Color side = growthSideColor.get();
            Color line = growthLineColor.get();

            for (long k : growthCache) {
                int cx = keyX(k);
                int cz = keyZ(k);
                if (Math.abs(cx - pcx) > reach || Math.abs(cz - pcz) > reach) continue;

                double x = cx * 16.0;
                double z = cz * 16.0;
                event.renderer.box(new AABB(x, baseY - 0.05, z, x + 16.0, baseY + 0.25, z + 16.0),
                    side, line, ShapeMode.Both, 0);
            }
        }

        Color side = sideColor.get();
        Color line = lineColor.get();
        boolean sprite = spriteMarker.get();

        for (int[] m : markerCache) {
            if (Math.abs(m[0] - pcx) > r || Math.abs(m[1] - pcz) > r) continue;

            double x = m[0] * 16.0;
            double z = m[1] * 16.0;
            double y = MARKER_Y;

            if (sprite) {
                event.renderer.box(new AABB(x, y, z, x + 16.0, y + 0.5, z + 16.0), side, line, ShapeMode.Lines, 0);
                renderSprite(event, x, y, z);
            } else {
                event.renderer.box(new AABB(x, y, z, x + 16.0, y + 0.5, z + 16.0), side, line, ShapeMode.Both, 0);
            }
        }

        if (tracer.get()) {
            int[] nearest = nearestMarker();

            if (nearest != null) {
                event.renderer.line(
                    mc.player.getX(), mc.player.getEyeY(), mc.player.getZ(),
                    nearest[0] * 16.0 + 8.0, nearest[2] + 0.5, nearest[1] * 16.0 + 8.0,
                    tracerColor.get());
            }
        }
    }

    private void renderBuds(Render3DEvent event, int pcx, int pcz, int r, int min, int confirm) {
        Color line = budColor.get();
        Color side = new Color(line.r, line.g, line.b, budFillAlpha.get());
        ShapeMode mode = budFilled.get() ? ShapeMode.Both : ShapeMode.Lines;

        double rangeSq = (double) budRange.get() * budRange.get();
        double px = mc.player.getX();
        double py = mc.player.getY();
        double pz = mc.player.getZ();

        boolean markedOnly = budMarkedOnly.get();

        for (Map.Entry<Long, ChunkState> e : chunks.entrySet()) {
            ChunkState s = e.getValue();
            if (s.buds.length == 0) continue;
            if (markedOnly && !s.persistent && (s.count < min || s.confirms < confirm)) continue;

            long ck = e.getKey();
            if (Math.abs(keyX(ck) - pcx) > r || Math.abs(keyZ(ck) - pcz) > r) continue;

            for (long p : s.buds) {
                int x = BlockPos.getX(p);
                int y = BlockPos.getY(p);
                int z = BlockPos.getZ(p);

                double dx = x + 0.5 - px;
                double dy = y + 0.5 - py;
                double dz = z + 0.5 - pz;
                if (dx * dx + dy * dy + dz * dz > rangeSq) continue;

                event.renderer.box(new AABB(x + 0.15, y + 0.15, z + 0.15, x + 0.85, y + 0.85, z + 0.85),
                    side, line, mode, 0);
            }
        }
    }

    private void renderSprite(Render3DEvent event, double bx, double y, double bz) {
        for (int[] rect : SPRITE_RECTS) {
            Color c = SPRITE_COLORS[rect[4]];
            double x = bx + rect[0];
            double z = bz + rect[1];
            event.renderer.box(new AABB(x, y, z, x + rect[2], y + 0.5, z + rect[3]),
                c, c, ShapeMode.Sides, 0);
        }
    }

    /** Siyah-beyaz ustten harita: merkez oyuncu oku, beyaz kareler isaretli chunk'lar. */
    private void renderMap(Render2DEvent event) {
        int size = mapSize.get();
        int left = Math.max(0, event.screenWidth - size - mapX.get());
        int top = Math.max(0, Math.min(mapY.get(), event.screenHeight - size));

        int bg = (mapOpacity.get() & 255) << 24;
        event.drawContext.fill(left, top, left + size, top + size, bg);

        // Beyaz cerceve.
        event.drawContext.fill(left, top, left + size, top + 1, 0xFFFFFFFF);
        event.drawContext.fill(left, top + size - 1, left + size, top + size, 0xFFFFFFFF);
        event.drawContext.fill(left, top, left + 1, top + size, 0xFFFFFFFF);
        event.drawContext.fill(left + size - 1, top, left + size, top + size, 0xFFFFFFFF);

        int r = mapRange.get();
        int cells = r * 2 + 1;
        double cell = (double) size / cells;

        int pcx = mc.player.blockPosition().getX() >> 4;
        int pcz = mc.player.blockPosition().getZ() >> 4;

        if (mapGrid.get()) {
            for (int i = 1; i < cells; i++) {
                int gx = (int) Math.round(left + i * cell);
                int gy = (int) Math.round(top + i * cell);
                event.drawContext.vLine(gx, top + 1, top + size - 1, 0x33FFFFFF);
                event.drawContext.hLine(left + 1, left + size - 1, gy, 0x33FFFFFF);
            }
        }

        if (mapMarkers.get()) {
            for (int[] m : markerCache) {
                int dx = m[0] - pcx;
                int dz = m[1] - pcz;
                if (Math.abs(dx) > r || Math.abs(dz) > r) continue;

                int mx = (int) Math.round(left + (dx + r + 0.5) * cell);
                int mz = (int) Math.round(top + (dz + r + 0.5) * cell);
                int s = Math.max(2, (int) Math.round(cell * 0.55));

                event.drawContext.fill(mx - s / 2, mz - s / 2, mx - s / 2 + s, mz - s / 2 + s, 0xFFFFFFFF);
            }
        }

        // Oyuncu oku: yaw 0 = guney (+Z), haritada asagi bakar.
        double cx = left + size * 0.5;
        double cy = top + size * 0.5;
        double yaw = Math.toRadians(mc.player.getYRot());
        double fx = -Math.sin(yaw);
        double fy = Math.cos(yaw);
        double rx = fy;
        double ry = -fx;

        double tipX = cx + fx * 8.0;
        double tipY = cy + fy * 8.0;
        double baseX = cx - fx * 5.0;
        double baseY = cy - fy * 5.0;
        double leftX = baseX - rx * 5.0;
        double leftY = baseY - ry * 5.0;
        double rightX = baseX + rx * 5.0;
        double rightY = baseY + ry * 5.0;

        fillTriangle(event.drawContext, tipX, tipY, leftX, leftY, rightX, rightY, 0xFFFFFFFF);
    }

    /** GuiGraphics.fill ile 2B ucgen doldurur (ok icin). */
    private static void fillTriangle(GuiGraphics ctx, double x1, double y1, double x2, double y2,
                                     double x3, double y3, int color) {
        int minY = (int) Math.floor(Math.min(y1, Math.min(y2, y3)));
        int maxY = (int) Math.ceil(Math.max(y1, Math.max(y2, y3)));
        double[] span = new double[2];

        for (int y = minY; y <= maxY; y++) {
            span[0] = Double.MAX_VALUE;
            span[1] = -Double.MAX_VALUE;

            scanEdge(x1, y1, x2, y2, y, span);
            scanEdge(x2, y2, x3, y3, y, span);
            scanEdge(x3, y3, x1, y1, y, span);

            if (span[0] > span[1]) continue;
            ctx.fill((int) Math.floor(span[0]), y, (int) Math.ceil(span[1]), y + 1, color);
        }
    }

    private static void scanEdge(double x1, double y1, double x2, double y2, int y, double[] span) {
        if ((y1 <= y && y <= y2) || (y2 <= y && y <= y1)) {
            if (y1 == y2) {
                span[0] = Math.min(span[0], Math.min(x1, x2));
                span[1] = Math.max(span[1], Math.max(x1, x2));
            } else {
                double t = (y - y1) / (y2 - y1);
                double x = x1 + t * (x2 - x1);
                span[0] = Math.min(span[0], x);
                span[1] = Math.max(span[1], x);
            }
        }
    }

    @EventHandler
    private void onRender2D(Render2DEvent event) {
        if (mc.level == null || mc.player == null) return;

        refreshCache();

        if (map.get()) renderMap(event);

        if (!hud.get()) return;

        double x = hudX.get();
        double y = hudY.get();
        Color color = hudColor.get();
        double lineHeight = 10.0;

        int totalBuds = 0;
        for (int[] m : markerCache) totalBuds += m[3];

        int pct = queue.isEmpty() ? 100 : cursor * 100 / queue.size();
        int listSize = Math.min(hudGeodeList.get(), markerCache.size());

        Renderer2D renderer = event.renderer;
        if (renderer == null) return;

        renderer.text(String.format("Cluster Bypass  ·  %d geodes  ·  %d buds  ·  %d%% sweep  ·  %.1f ms",
            markerCache.size(), totalBuds, pct, emaScanMs), (float) x, (float) y, 9.5f, Renderer2D.Font.Medium, color);

        double cy = y + lineHeight;

        if (listSize > 0) {
            List<int[]> near = nearestMarkers(listSize);

            for (int i = 0; i < near.size(); i++) {
                int[] m = near.get(i);
                double dx = m[0] * 16.0 + 8.0 - mc.player.getX();
                double dz = m[1] * 16.0 + 8.0 - mc.player.getZ();
                int dist = (int) Math.round(Math.sqrt(dx * dx + dz * dz));

                renderer.text(String.format("#%d  %dm %s  ·  %s", i + 1, dist, compass(dx, dz), budsText(m)),
                    (float) x, (float) cy, 9.0f, Renderer2D.Font.Regular, color);
                cy += lineHeight;
            }
        } else {
            int[] nearest = nearestMarker();
            String near = "nearest  ·  none";

            if (nearest != null) {
                double dx = nearest[0] * 16.0 + 8.0 - mc.player.getX();
                double dz = nearest[1] * 16.0 + 8.0 - mc.player.getZ();
                near = String.format("nearest  ·  %dm %s  ·  %s",
                    (int) Math.round(Math.sqrt(dx * dx + dz * dz)), compass(dx, dz), budsText(nearest));
            }

            renderer.text(near, (float) x, (float) cy, 9.0f, Renderer2D.Font.Regular, color);
        }
    }
}
