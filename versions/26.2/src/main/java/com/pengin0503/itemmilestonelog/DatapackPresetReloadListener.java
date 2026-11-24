package com.pengin0503.itemmilestonelog;

import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.Map;
import java.util.TreeMap;

/**
 * F21: enumerates {@code data/<namespace>/itemmilestonelog/presets/<id>.json} on every server
 * (data pack) reload and hands the file contents to {@link Presets#resolveDatapackPresets},
 * which applies the same JSON parsing as config-folder presets. Only file enumeration lives in
 * this listener because the reload-listener base classes differ between Minecraft versions;
 * the preset rules (ID validation, subfolders, namespace collisions) are shared. After the
 * reload completes, players with the GUI open are re-synced so their preset list reflects the
 * new data pack presets.
 */
public final class DatapackPresetReloadListener
        extends SimplePreparableReloadListener<Map<String, Presets.PresetDefinition>> {
    private static final FileToIdConverter FILES = FileToIdConverter.json(Presets.DATAPACK_PRESETS_DIR);

    @Override
    protected Map<String, Presets.PresetDefinition> prepare(ResourceManager manager, ProfilerFiller profiler) {
        return Presets.resolveDatapackPresets(collectFilesByNamespace(manager));
    }

    private static Map<String, Map<String, String>> collectFilesByNamespace(ResourceManager manager) {
        Map<String, Map<String, String>> filesByNamespace = new TreeMap<>();
        for (Map.Entry<Identifier, Resource> entry : FILES.listMatchingResources(manager).entrySet()) {
            String namespace = entry.getKey().getNamespace();
            // File name relative to the presets folder, without the ".json" extension
            // (may contain "/" when the file sits in a subfolder).
            String fileKey = FILES.fileToId(entry.getKey()).getPath();
            String sourceLabel = entry.getKey().toString();
            String content = readResource(entry.getValue(), sourceLabel);
            if (content != null) {
                filesByNamespace.computeIfAbsent(namespace, key -> new TreeMap<>()).put(fileKey, content);
            }
        }
        return filesByNamespace;
    }

    private static String readResource(Resource resource, String sourceLabel) {
        try (BufferedReader reader = resource.openAsReader()) {
            StringBuilder content = new StringBuilder();
            String line = reader.readLine();
            while (line != null) {
                content.append(line).append('\n');
                line = reader.readLine();
            }
            return content.toString();
        } catch (IOException e) {
            ItemMilestoneLog.LOGGER.warn(
                    "[ItemMilestoneLog] Could not read datapack preset {}: {}", sourceLabel, e.getMessage());
            return null;
        }
    }

    @Override
    protected void apply(Map<String, Presets.PresetDefinition> presets, ResourceManager manager, ProfilerFiller profiler) {
        Presets.setDatapackPresets(presets);
        // /reload can change datapack presets while players are online; refresh open GUIs. During
        // the initial load the server reference does not exist yet, so there is nobody to sync.
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server != null) {
            MilestoneService.syncAllOnlineGuiPlayers(server, null);
        }
    }
}
