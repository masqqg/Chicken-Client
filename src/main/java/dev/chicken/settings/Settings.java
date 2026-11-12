package dev.chicken.settings;

import java.util.ArrayList;
import java.util.List;

/** Every setting of one module. */
public class Settings {
    private final SettingGroup defaultGroup = new SettingGroup("General");
    private final List<SettingGroup> groups = new ArrayList<>();

    public Settings() {
        groups.add(defaultGroup);
    }

    public SettingGroup getDefaultGroup() {
        return defaultGroup;
    }

    public SettingGroup createGroup(String name) {
        SettingGroup group = new SettingGroup(name);
        groups.add(group);
        return group;
    }

    public List<SettingGroup> getGroups() {
        return groups;
    }

    public Setting<?> get(String name) {
        for (SettingGroup group : groups) {
            for (Setting<?> setting : group.settings) {
                if (setting.name.equals(name)) return setting;
            }
        }

        return null;
    }

    public List<Setting<?>> all() {
        List<Setting<?>> all = new ArrayList<>();
        for (SettingGroup group : groups) all.addAll(group.settings);
        return all;
    }

    public void reset() {
        for (Setting<?> setting : all()) setting.reset();
    }
}
