package fr.euphyllia.skylliabackup.manager;

import fr.euphyllia.skyllia.api.SkylliaAPI;
import fr.euphyllia.skyllia.api.configuration.WorldConfig;
import fr.euphyllia.skyllia.api.skyblock.Island;
import fr.euphyllia.skyllia.api.skyblock.Players;
import fr.euphyllia.skyllia.api.skyblock.model.Position;
import fr.euphyllia.skyllia.api.skyblock.model.RoleType;
import fr.euphyllia.skyllia.api.utils.helper.RegionHelper;
import fr.euphyllia.skylliabackup.SkylliaBackup;
import org.bukkit.Bukkit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class BackupManager {

    private static final Logger log = LoggerFactory.getLogger(BackupManager.class);
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
    private final SkylliaBackup plugin;
    private final File serverRoot;

    public BackupManager(SkylliaBackup plugin) {
        this.plugin = plugin;
        this.serverRoot = Bukkit.getWorldContainer();
    }


    public File backupIsland(Island island, String trigger) {
        UUID islandId = island.getId();
        Position pos = island.getPosition();

        List<WorldConfig> worlds = SkylliaAPI.getRegisteredWorlds();
        if (worlds == null || worlds.isEmpty()) {
            log.warn("No worlds registered in Skyllia — cannot backup island {}", islandId);
            return null;
        }

        List<File> regionFiles = new ArrayList<>();
        for (WorldConfig worldCfg : worlds) {
            String worldName = worldCfg.getWorldName();
            List<Position> regions = RegionHelper.getRegionsWithinBlockRange(pos, (int) island.getSize());
            log.info("Island pos: ({}, {}), size: {}, regions found: {}",
                    pos.x(), pos.z(), island.getSize(), regions.size());
            for (Position region : regions) {
                File mca = getRegionFile(worldName, region);
                log.info("Checking: {} | exists: {}", mca.getAbsolutePath(), mca.exists());
                if (mca != null && mca.exists()) {
                    regionFiles.add(mca);
                }
            }
        }

        if (regionFiles.isEmpty()) {
            log.error("No region found"); // Devrait jamais arrivé
            return null;
        }

        String timestamp = DATE_FMT.format(LocalDateTime.now());
        String ownerName = islandId.toString().substring(0, 8);
        for (Players p : island.getMembers()) {
            if (p.getRoleType() == RoleType.OWNER) {
                ownerName = p.getLastKnowName();
                break;
            }
        }

        File islandDir = new File(plugin.getDataFolder(), "/test/" + islandId);
        islandDir.mkdirs();

        String zipName = String.format("backup_%s_%s_%s.zip", ownerName, trigger, timestamp);
        File zipFile = new File(islandDir, zipName);

        try {
            createZip(zipFile, regionFiles, islandId.toString());
        } catch (IOException e) {
            log.error("Failed to create backup ZIP for island {}", islandId, e);
            return null;
        }

        log.info("Backup created: {}", zipFile.getAbsolutePath());
        return zipFile;
    }

    private File getRegionFile(String worldName, Position region) {
        org.bukkit.World world = Bukkit.getWorld(worldName);
        if (world == null) {
            log.warn("World '{}' is not loaded, skipping", worldName);
            return null;
        }
        File regionDir = new File(world.getWorldFolder(), "region");
        return new File(regionDir, "r." + region.x() + "." + region.z() + ".mca");
    }

    private void createZip(File zipFile, List<File> regionFiles, String islandId) throws IOException {
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zipFile))) {
            ZipEntry meta = new ZipEntry(islandId + "/backup-info.txt");
            zos.putNextEntry(meta);
            String info = "Island ID: " + islandId + "\n" +
                    "Created at: " + LocalDateTime.now() + "\n" +
                    "Region files: " + regionFiles.size() + "\n";
            zos.write(info.getBytes());
            zos.closeEntry();

            for (File regionFile : regionFiles) {
                String relativePath = islandId + "/" + relativize(serverRoot, regionFile);
                ZipEntry entry = new ZipEntry(relativePath.replace(File.separatorChar, '/'));
                zos.putNextEntry(entry);
                try (FileInputStream fis = new FileInputStream(regionFile)) {
                    byte[] buf = new byte[8192];
                    int len;
                    while ((len = fis.read(buf)) > 0) {
                        zos.write(buf, 0, len);
                    }
                }
                zos.closeEntry();
            }
        }
    }

    private String relativize(File base, File target) {
        return base.toURI().relativize(target.toURI()).getPath();
    }

    private void uploadBackup(File zipFile, UUID islandId) {
        // Todo
    }


}
