package dev.chicken.systems;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.chicken.ChickenClient;
import dev.chicken.events.EventBus;
import dev.chicken.events.TickEvent;
import dev.chicken.modules.BaseFinder;
import dev.chicken.modules.ChickenStorageESP;
import dev.chicken.modules.ClearRender;
import dev.chicken.modules.ClusterBypass;
import dev.chicken.modules.Karambit;
import dev.chicken.modules.PlayerFinder;
import dev.chicken.modules.SpawnerFlag;
import dev.chicken.modules.SusChunkFinder;
import dev.chicken.settings.Setting;
import dev.chicken.settings.SettingGroup;
import net.fabricmc.loader.api.FabricLoader;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Owns every module, dispatches events to the active ones and persists the config. */
public final class Modules {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static Modules instance;

    private final List<Category> categories = new ArrayList<>();
    private final List<Module> modules = new ArrayList<>();
    private final EventBus eventBus = new EventBus();
    private final Path configFile;

    private Modules() {
        configFile = FabricLoader.getInstance().getConfigDir().resolve(ChickenClient.MOD_ID).resolve("config.json");

        Category world = Categories.WORLD;
        Category render = Categories.RENDER;
        Category visuals = Categories.VISUALS;

        categories.add(world);
        categories.add(render);
        categories.add(visuals);

        add(new ClusterBypass());
        add(new BaseFinder());
        add(new SusChunkFinder());
        add(new SpawnerFlag());
        add(new ChickenStorageESP());
        add(new ClearRender());
        add(new PlayerFinder());
        add(new Karambit());
    }

    public static Modules get() {
        if (instance == null) instance = new Modules();
        return instance;
    }

    public EventBus getEventBus() {
        return eventBus;
    }

    public List<Category> getCategories() {
        return categories;
    }

    public List<Module> getAll() {
        return modules;
    }

    public List<Module> get(Category category) {
        List<Module> list = new ArrayList<>();
        for (Module module : modules) {
            if (module.category == category) list.add(module);
        }
        return list;
    }

    public Module get(String name) {
        for (Module module : modules) {
            if (module.name.equalsIgnoreCase(name)) return module;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    public <T extends Module> T get(Class<T> type) {
        for (Module module : modules) {
            if (module.getClass() == type) return (T) module;
        }
        return null;
    }

    private void add(Module module) {
        modules.add(module);
    }

    public void setActive(Module module, boolean active) {
        boolean wasActive = module.isActive();
        module.setActive(active);

        if (active) {
            eventBus.subscribe(module);
        } else {
            eventBus.unsubscribe(module);
        }

        if (wasActive != active) save();
    }

    public void toggle(Module module) {
        setActive(module, !module.isActive());
    }

    public void post(Object event) {
        eventBus.post(event);
    }

    public void tick(boolean pre) {
        eventBus.post(pre ? new TickEvent.Pre() : new TickEvent.Post());
    }

    // ------------------------------------------------------------------ config

    public void save() {
        JsonObject root = new JsonObject();
        JsonObject moduleObject = new JsonObject();

        for (Module module : modules) {
            JsonObject entry = new JsonObject();
            entry.addProperty("active", module.isActive());

            JsonObject settings = new JsonObject();
            for (Setting<?> setting : module.settings.all()) {
                settings.add(setting.name, setting.toJson());
            }

            entry.add("settings", settings);
            moduleObject.add(module.name, entry);
        }

        root.add("modules", moduleObject);

        try {
            Files.createDirectories(configFile.getParent());
            try (Writer writer = Files.newBufferedWriter(configFile, StandardCharsets.UTF_8)) {
                GSON.toJson(root, writer);
            }
        } catch (Exception e) {
            ChickenClient.LOG.error("Could not save the config", e);
        }
    }

    public void load() {
        if (!Files.isRegularFile(configFile)) return;

        try (Reader reader = Files.newBufferedReader(configFile, StandardCharsets.UTF_8)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            if (!root.has("modules")) return;

            for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject("modules").entrySet()) {
                Module module = get(entry.getKey());
                if (module == null || !entry.getValue().isJsonObject()) continue;

                JsonObject data = entry.getValue().getAsJsonObject();

                if (data.has("settings") && data.get("settings").isJsonObject()) {
                    JsonObject settings = data.getAsJsonObject("settings");

                    for (Map.Entry<String, JsonElement> settingEntry : settings.entrySet()) {
                        Setting<?> setting = module.settings.get(settingEntry.getKey());
                        if (setting == null) continue;

                        try {
                            setting.fromJson(settingEntry.getValue());
                        } catch (Exception ignored) {
                            // A single corrupt value should never break the whole config.
                        }
                    }
                }

                if (data.has("active") && data.get("active").getAsBoolean()) {
                    setActive(module, true);
                }
            }
        } catch (Exception e) {
            ChickenClient.LOG.error("Could not load the config", e);
        }
    }

    /** Settings grouped for the GUI, skipping empty groups. */
    public List<SettingGroup> groups(Module module) {
        List<SettingGroup> groups = new ArrayList<>();
        for (SettingGroup group : module.settings.getGroups()) {
            if (!group.settings.isEmpty()) groups.add(group);
        }
        return groups;
    }

    public Map<String, Module> byName() {
        Map<String, Module> map = new LinkedHashMap<>();
        for (Module module : modules) map.put(module.name, module);
        return map;
    }
}
