/*
 * Copyright (c) 2015, Mazen Kotb <email@mazenmc.io>
 *
 * Permission to use, copy, modify, and/or distribute this software for any
 * purpose with or without fee is hereby granted, provided that the above
 * copyright notice and this permission notice appear in all copies.
 *
 * THE SOFTWARE IS PROVIDED "AS IS" AND THE AUTHOR DISCLAIMS ALL WARRANTIES
 * WITH REGARD TO THIS SOFTWARE INCLUDING ALL IMPLIED WARRANTIES OF
 * MERCHANTABILITY AND FITNESS. IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR
 * ANY SPECIAL, DIRECT, INDIRECT, OR CONSEQUENTIAL DAMAGES OR ANY DAMAGES
 * WHATSOEVER RESULTING FROM LOSS OF USE, DATA OR PROFITS, WHETHER IN AN
 * ACTION OF CONTRACT, NEGLIGENCE OR OTHER TORTIOUS ACTION, ARISING OUT OF
 * OR IN CONNECTION WITH THE USE OR PERFORMANCE OF THIS SOFTWARE.
 */
package io.minecloud.bukkit;

import com.google.common.io.Files;
import io.minecloud.Cached;
import io.minecloud.MineCloud;
import io.minecloud.db.mongo.MongoDatabase;
import io.minecloud.db.redis.RedisDatabase;
import io.minecloud.db.redis.msg.binary.MessageOutputStream;
import io.minecloud.db.redis.pubsub.SimpleRedisChannel;
import io.minecloud.models.player.PlayerData;
import io.minecloud.models.plugins.PluginType;
import io.minecloud.models.server.Server;
import io.minecloud.models.server.World;
import io.minecloud.models.server.type.ServerType;
import org.bukkit.Bukkit;
import org.bukkit.WorldCreator;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.FileUtil;
import redis.clients.jedis.Jedis;

import java.io.File;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;

public class MineCloudPlugin extends JavaPlugin {
    private Cached<Server> server;
    private MongoDatabase mongo;
    private RedisDatabase redis;
    private String serverId;

    @Override
    public void onEnable() {
        MineCloud.environmentSetup();

        serverId = System.getenv("server_id");
        mongo = MineCloud.instance().mongo();
        redis = MineCloud.instance().redis();

        try {
            Files.write(ManagementFactory.getRuntimeMXBean().getName().split("@")[0].getBytes(Charset.defaultCharset()),
                    new File("app.pid"));
        } catch (IOException ex) {
            ex.printStackTrace();
        }

        new BukkitRunnable() {
            @Override
            public void run() {
                Server server = mongo.repositoryBy(Server.class).findFirst(serverId);
                Runtime runtime = Runtime.getRuntime();

                if (server == null) {
                    getLogger().info("Server removed from db, shutting down");
                    Bukkit.shutdown();
                    return;
                }

                server.setRamUsage((int) ((runtime.totalMemory() - runtime.freeMemory()) / 1048576));
                server.setTps(fetchTps());
                updatePlayers(server);

                mongo.repositoryBy(Server.class).save(server);

                //Heartbeat
                try (Jedis jedis = redis.grabResource()) {
                    jedis.hset("server:" + server.entityId(), "heartbeat", String.valueOf(System.currentTimeMillis()));
                }
            }
        }.runTaskTimerAsynchronously(this, 40, 200);

        redis.addChannel(SimpleRedisChannel.create("server-start-notif", redis));
        redis.addChannel(SimpleRedisChannel.create("server-shutdown-notif", redis));

        getServer().getPluginManager().registerEvents(new PlayerTracker(), this);

        // start loading plugins and additional worlds

        ServerType type = server().type();

        type.worlds().forEach((world) -> {
            File worldFolder = new File("/mnt/minecloud/worlds/",
                    world.name() + "/" + world.version());

            if (validateFolder(worldFolder, world)) {
                return;
            }

            File wrld = new File(world.name());

            wrld.mkdirs();
            copyFolder(worldFolder, wrld);

            Bukkit.createWorld(new WorldCreator(world.name()));
        });

        new File("nplugins").mkdir();

        type.plugins().forEach((plugin) -> {
            String version = plugin.version();
            PluginType pluginType = plugin.type();
            File pluginsContainer = new File("/mnt/minecloud/plugins/",
                    pluginType.name() + "/" + version);

            getLogger().info("Loading " + pluginType.name() + "...");

            if (validateFolder(pluginsContainer, pluginType, version))
                return;

            File[] files;
            if ((files = pluginsContainer.listFiles()) != null) {
                for (File f : files) {
                    if (f.isDirectory())
                        continue; // ignore directories
                    File pl = new File("nplugins/" + f.getName());

                    FileUtil.copy(f, pl);
                }
            }

            File configs = new File("/mnt/minecloud/configs/",
                    pluginType.name() + "/" + (plugin.config() == null ? version : plugin.config()));
            File configContainer = new File("nplugins/" + pluginType.name());

            if (!validateFolder(configs, pluginType, version))
                copyFolder(configs, configContainer);
        });

        for (Plugin plugin : Bukkit.getPluginManager().loadPlugins(new File("nplugins"))) {
            plugin.onLoad();
            Bukkit.getPluginManager().enablePlugin(plugin);
        }

        try {
            MessageOutputStream os = new MessageOutputStream();

            os.writeString(server().entityId());

            redis.channelBy("server-start-notif").publish(os.toMessage());
        } catch (IOException e) {
            getLogger().log(Level.SEVERE, "Unable to publish server create message, shutting down", e);
            Bukkit.shutdown();
        }

        new File("/var/minecloud/", serverId).deleteOnExit();
    }

    private double fetchTps() {
        try {
            org.bukkit.Server server = Bukkit.getServer();
            Object minecraftServer = server.getClass().getDeclaredMethod("getServer").invoke(server);
            Field tps = minecraftServer.getClass().getField("recentTps");

            return ((double[]) tps.get(minecraftServer))[0];
        } catch (Exception ex) {
            getLogger().log(Level.SEVERE, "Could not fetch TPS", ex);
            return 21;
        }
    }

    private boolean validateFolder(File file, PluginType pluginType, String version) {
        if (!file.exists()) {
            getLogger().info(file.getPath() + " does not exist! Cannot load " + pluginType.name());
            return true;
        }
        File[] files = file.listFiles();
        if (!(file.isDirectory()) || files == null
                || files.length < 1) {
            getLogger().info(pluginType.name() + " " + version +
                    " has either no files or has an invalid setup");
            return true;
        }

        return false;
    }

    private boolean validateFolder(File file, World world) {
        if (!file.exists()) {
            getLogger().info(file.getPath() + " does not exist! Cannot load " + world.name());
            return true;
        }
        File[] files = file.listFiles();
        if (!(file.isDirectory()) || files == null
                || files.length < 1) {
            getLogger().info(world.name() + " " + world.version() + " has either no files or has an invalid setup");
            return true;
        }

        return false;
    }

    private void copyFolder(File folder, File folderContainer) {
        folderContainer.mkdirs();
        File[] files;
        if ((files = folder.listFiles()) != null) {
            for (File f : files) {
                if (f.isDirectory()) {
                    File newContainer = new File(folderContainer, f.getName());
                    copyFolder(f, newContainer);
                }

                FileUtil.copy(f, new File(folderContainer, f.getName()));
            }
        }
    }

    @Override
    public void onDisable() {
        try (Jedis jedis = this.redis.grabResource()) {
            jedis.hdel("server:" + server().entityId(), "heartbeat");
        }

        mongo.repositoryBy(Server.class).deleteById(serverId);

        try {
            MessageOutputStream os = new MessageOutputStream();

            os.writeString(serverId);

            redis.channelBy("server-shutdown-notif").publish(os.toMessage());
        } catch (IOException ex) {
            ex.printStackTrace(); // almost impossible to happen
        }
    }

    void updatePlayers(Server server) {
        List<PlayerData> onlinePlayers = new ArrayList<>();

        Bukkit.getOnlinePlayers().stream()
                .forEach((player) -> {
                    PlayerData data = new PlayerData();

                    data.setHealth(player.getHealth());
                    data.setMaxHealth(player.getMaxHealth());
                    data.setName(player.getName());
                    data.setId(player.getUniqueId().toString());

                    onlinePlayers.add(data);
                });

        server.setOnlinePlayers(onlinePlayers);
    }

    Server server() {
        if (server == null) {
            server = Cached.create(25_000, () -> mongo.repositoryBy(Server.class).findFirst(serverId));
        }

        return server.get();
    }

    MongoDatabase mongo() {
        return mongo;
    }
}
