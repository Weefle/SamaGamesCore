package net.samagames.core.api.resourcepacks;

import io.netty.channel.Channel;
import net.samagames.api.SamaGamesAPI;
import net.samagames.api.resourcepacks.IResourceCallback;
import net.samagames.api.resourcepacks.IResourcePacksManager;
import net.samagames.core.APIPlugin;
import net.samagames.tools.Reflection;
import net.samagames.tools.TinyProtocol;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;
import redis.clients.jedis.Jedis;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * This file is a part of the SamaGames project
 * This code is absolutely confidential.
 * Created by Silvanosky
 * (C) Copyright Elydra Network 2015
 * All rights reserved.
 */
public class ResourcePacksManagerImpl implements IResourcePacksManager, Listener
{
    private static Class<?> packetPlayInResourcePackStatus = Reflection.getNMSClass("PacketPlayInResourcePackStatus");
    private static Class<?> enumResourcePackStatusClass = Reflection.getNMSClass("PacketPlayInResourcePackStatus$EnumResourcePackStatus");

    private static Object declinedEnumStatus;
    private static Object failedEnumStatus;
    private static Object successEnumStatus;

    private final List<UUID> currentlyDownloading = new ArrayList<>();
    private final SamaGamesAPI api;
    private final String resetUrl;
    private String forceUrl;
    private IResourceCallback callback;

    private final static String rejectMessage = ChatColor.RED + "Il est nécessaire d'accepter le ressource pack pour jouer.";

    private TinyProtocol protocol;

    public ResourcePacksManagerImpl(SamaGamesAPI api)
    {
        Bukkit.getPluginManager().registerEvents(this, APIPlugin.getInstance());

        this.api = api;

        Jedis jedis = api.getBungeeResource();
        this.resetUrl = jedis.get("resourcepacks:reseturl");
        APIPlugin.getInstance().getLogger().info("Resource packs reset URL defined to " + resetUrl);
        jedis.close();
        protocol = new TinyProtocol(api.getPlugin()) {
            @Override
            public Object onPacketInAsync(Player sender, Channel channel, Object packet) {
                if (sender == null)
                    return super.onPacketInAsync(null, channel, packet);

                if (packetPlayInResourcePackStatus.isAssignableFrom(packet.getClass()))
                {
                    try
                    {
                        Field hashField = packet.getClass().getDeclaredField("a");
                        hashField.setAccessible(true);
                        Field stateField = packet.getClass().getDeclaredField("b");
                        stateField.setAccessible(true);

                        String hash = (String) hashField.get(packet);
                        Object state = stateField.get(packet);

                        handle(sender, hash, state);
                    }
                    catch (Exception e)
                    {
                        e.printStackTrace();
                    }
                }

                return super.onPacketInAsync(sender, channel, packet);
            }
        };
    }

    private void handle(Player sender, String hash, Object state)
    {
        Player player = sender;
        if (forceUrl == null)
            return;

        if (state.equals(declinedEnumStatus) || state.equals(failedEnumStatus))
        {
            if (callback == null || callback.automaticKick(player))
            {
                Bukkit.getScheduler().runTask(SamaGamesAPI.get().getPlugin(), () -> player.kickPlayer(rejectMessage));
            }
            APIPlugin.getInstance().getLogger().info("Player " + player.getName() + " rejected resource pack");
            currentlyDownloading.remove(player.getUniqueId());

        }
        else if(state.equals(successEnumStatus))
        {
            currentlyDownloading.remove(player.getUniqueId());
            APIPlugin.getInstance().getLogger().info("Player " + player.getName() + " successfully downloaded resource pack");
            Bukkit.getScheduler().runTaskAsynchronously(APIPlugin.getInstance(), () -> {
                Jedis jedis = api.getBungeeResource();
                jedis.sadd("playersWithPack", player.getUniqueId().toString());
                jedis.close();
            });
            //Call when it's done
            if (callback != null)
                callback.callback(player, PlayerResourcePackStatusEvent.Status.valueOf(state.toString()));
        }
    }


    @Override
    public void forcePack(String name)
    {
        forcePack(name, null);
    }

    @Override
    public void forcePack(String name, IResourceCallback callback)
    {
        Jedis jedis = api.getBungeeResource();
        forceUrlPack(jedis.hget("resourcepack:" + name, "url"), jedis.hget("resourcepack:" + name, "hash"), callback);
        jedis.close();
    }

    @Override
    public void forceUrlPack(String url, String hash, IResourceCallback callback)
    {
        Bukkit.getScheduler().runTaskAsynchronously(APIPlugin.getInstance(), () -> {
            forceUrl = url;

            try
            {
                Class<?> craftServerClass = Reflection.getOBCClass("CraftServer");
                Method setResourcePackMethod = craftServerClass.getMethod("setResourcePack", String.class, String.class);
                setResourcePackMethod.invoke(craftServerClass.cast(Bukkit.getServer()), url, hash);
            }
            catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e)
            {
                e.printStackTrace();
            }


            APIPlugin.getInstance().getLogger().info("Defined automatic resource pack : " + url);
        });

        this.callback = callback;
    }

    private void sendPack(Player player, String url)
    {
        /*player.setResourcePack(url);*/
        APIPlugin.getInstance().getLogger().info("Sending pack to " + player.getName() + " : " + url);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event)
    {
        final Player player = event.getPlayer();

        if (forceUrl != null)
        {
            currentlyDownloading.add(player.getUniqueId());
            sendPack(player, forceUrl);

            //Kick if still downloading after 1 minute
            Bukkit.getScheduler().runTaskLater(SamaGamesAPI.get().getPlugin(),
                    () -> {
                        if(currentlyDownloading.contains(player.getUniqueId()) && player.isOnline())
                        {
                            if (callback == null || callback.automaticKick(player))
                            {
                                player.kickPlayer(rejectMessage);
                            }
                            currentlyDownloading.remove(player.getUniqueId());
                            APIPlugin.getInstance().getLogger().info("Player " + player.getName() + " timed out resource pack");
                        }
                    }, 1200L);//20*60
        } else
        {
            Jedis jedis = api.getBungeeResource();
            Long l = jedis.srem("playersWithPack", player.getUniqueId().toString());
            jedis.close();

            if (l > 0)
            {
                //Better to check than force resourcepack
                player.setResourcePack(resetUrl);
                APIPlugin.getInstance().getLogger().info("Sending pack to " + player.getName() + " : " + resetUrl);
            }
        }
    }

    /*@EventHandler
    public void onResourcePack(PlayerResourcePackStatusEvent event)
    {
        Player player = event.getPlayer();
        if (forceUrl == null)
            return;

        if (event.getStatus().equals(PlayerResourcePackStatusEvent.Status.DECLINED)
                || event.getStatus().equals(PlayerResourcePackStatusEvent.Status.FAILED_DOWNLOAD))
        {
            if (callback == null || callback.automaticKick(player))
            {
                Bukkit.getScheduler().runTask(SamaGamesAPI.get().getPlugin(), () -> player.kickPlayer(rejectMessage));
            }
            APIPlugin.getInstance().getLogger().info("Player " + player.getName() + " rejected resource pack");
            currentlyDownloading.remove(player.getUniqueId());

        }else if(event.getStatus().equals(PlayerResourcePackStatusEvent.Status.SUCCESSFULLY_LOADED))
        {
            currentlyDownloading.remove(player.getUniqueId());
            APIPlugin.getInstance().getLogger().info("Player " + player.getName() + " successfully downloaded resource pack");
            Bukkit.getScheduler().runTaskAsynchronously(APIPlugin.getInstance(), () -> {
                Jedis jedis = api.getBungeeResource();
                jedis.sadd("playersWithPack", player.getUniqueId().toString());
                jedis.close();
            });
            //Call when it's done
            if (callback != null)
                callback.callback(player, event.getStatus());
        }
    }*/


    @Override
    public void kickAllUndownloaded()
    {
        for (UUID id : currentlyDownloading)
        {
            Player player = Bukkit.getPlayer(id);
            if (player != null)
                player.kickPlayer(rejectMessage);
        }

        currentlyDownloading.clear();
    }

    static
    {
        try
        {
            declinedEnumStatus = enumResourcePackStatusClass.getField("DECLINED").get(null);
            failedEnumStatus = enumResourcePackStatusClass.getField("FAILED_DOWNLOAD").get(null);
            successEnumStatus = enumResourcePackStatusClass.getField("SUCCESSFULLY_LOADED").get(null);
        }
        catch (IllegalAccessException | NoSuchFieldException e)
        {
            e.printStackTrace();
        }
    }
}
