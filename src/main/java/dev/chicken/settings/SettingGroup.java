package dev.chicken.settings;

import java.util.ArrayList;
import java.util.List;

/** A collapsible group of settings. */
public class SettingGroup {
    public final String name;
    public final List<Setting<?>> settings = new ArrayList<>();

    public SettingGroup(String name) {
        this.name = name;
    }

    public <S extends Setting<?>> S add(S setting) {
        settings.add(setting);
        return setting;
    }
}
