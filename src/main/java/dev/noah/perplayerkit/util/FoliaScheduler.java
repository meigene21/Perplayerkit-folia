/*
 * Copyright 2022-2026 Noah Ross
 *
 * This file is part of PerPlayerKit.
 *
 * PerPlayerKit is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License as published by the
 * Free Software Foundation, either version 3 of the License, or (at your
 * option) any later version.
 *
 * PerPlayerKit is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for
 * more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with PerPlayerKit. If not, see <https://www.gnu.org/licenses/>.
 */
package dev.noah.perplayerkit.util;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Unified scheduler adapter that natively uses Folia's regionized schedulers
 * ({@code GlobalRegionScheduler}, {@code AsyncScheduler}, {@code EntityScheduler},
 * and {@code RegionScheduler}) when running on Folia, and falls back to the
 * standard Bukkit scheduler on Paper/Spigot.
 *
 * <p>Reflection handles are resolved once and cached so runtime overhead is
 * negligible while keeping compilation compatible with Spigot 1.19+ API.
 */
public final class FoliaScheduler {

    private static final boolean FOLIA_PRESENT = detectFolia();

    private static final Method GET_GLOBAL_REGION_SCHEDULER = findMethod(Server.class, "getGlobalRegionScheduler");
    private static final Method GET_ASYNC_SCHEDULER = findMethod(Server.class, "getAsyncScheduler");
    private static final Method GET_REGION_SCHEDULER = findMethod(Server.class, "getRegionScheduler");
    private static final Method GET_ENTITY_SCHEDULER = findMethod(Entity.class, "getScheduler");
    private static final Method IS_OWNED_BY_CURRENT_REGION_ENTITY = findMethod(Server.class, "isOwnedByCurrentRegion", Entity.class);

    // Cached scheduler interface methods (resolved lazily on first Folia use)
    private static volatile Method globalRun;
    private static volatile Method globalRunDelayed;
    private static volatile Method globalRunAtFixedRate;
    private static volatile Method globalCancelTasks;

    private static volatile Method asyncRunNow;
    private static volatile Method asyncRunDelayed;
    private static volatile Method asyncRunAtFixedRate;
    private static volatile Method asyncCancelTasks;

    private static volatile Method entityRun;
    private static volatile Method entityRunDelayed;

    private static volatile Method regionExecute;

    private static volatile Method scheduledTaskCancel;
    private static volatile Method scheduledTaskIsCancelled;

    private FoliaScheduler() {
    }

    /**
     * Cancellable handle abstracting over Folia's {@code ScheduledTask} and
     * Bukkit's {@code BukkitTask}.
     */
    public interface TaskHandle {
        TaskHandle NOOP = new TaskHandle() {
            @Override
            public void cancel() {
            }

            @Override
            public boolean isCancelled() {
                return true;
            }
        };

        void cancel();

        boolean isCancelled();
    }

    public static boolean isFolia() {
        return FOLIA_PRESENT
                && GET_GLOBAL_REGION_SCHEDULER != null
                && GET_ASYNC_SCHEDULER != null
                && GET_ENTITY_SCHEDULER != null;
    }

    public static boolean isOwnedByCurrentRegion(Entity entity) {
        if (entity == null) {
            return false;
        }
        Server server = Bukkit.getServer();
        if (server == null) {
            return true;
        }
        if (isFolia() && IS_OWNED_BY_CURRENT_REGION_ENTITY != null) {
            try {
                return Boolean.TRUE.equals(IS_OWNED_BY_CURRENT_REGION_ENTITY.invoke(server, entity));
            } catch (ReflectiveOperationException ignored) {
                return false;
            }
        }
        return Bukkit.isPrimaryThread();
    }

    // -------------------------------------------------------------------------
    // Async scheduler
    // -------------------------------------------------------------------------

    public static TaskHandle runAsync(Plugin plugin, Runnable task) {
        if (plugin == null || task == null) {
            return TaskHandle.NOOP;
        }
        if (isFolia()) {
            if (!plugin.isEnabled()) {
                return TaskHandle.NOOP;
            }
            try {
                Object scheduler = GET_ASYNC_SCHEDULER.invoke(plugin.getServer());
                Method m = asyncRunNow;
                if (m == null) {
                    m = findMethod(scheduler.getClass(), "runNow", Plugin.class, Consumer.class);
                    asyncRunNow = m;
                }
                if (m != null) {
                    Consumer<Object> consumer = ignored -> task.run();
                    return wrapFoliaTask(m.invoke(scheduler, plugin, consumer));
                }
            } catch (InvocationTargetException e) {
                if (e.getCause() instanceof IllegalPluginAccessException) {
                    return TaskHandle.NOOP;
                }
                throw new IllegalStateException("Failed to schedule Folia async task", e.getCause());
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Failed to schedule Folia async task", e);
            }
        }
        try {
            return wrapBukkitTask(plugin.getServer().getScheduler().runTaskAsynchronously(plugin, task));
        } catch (IllegalPluginAccessException ignored) {
            return TaskHandle.NOOP;
        }
    }

    public static TaskHandle runAsyncLater(Plugin plugin, Runnable task, long delayTicks) {
        if (plugin == null || task == null) {
            return TaskHandle.NOOP;
        }
        long safeTicks = Math.max(1L, delayTicks);
        if (isFolia()) {
            if (!plugin.isEnabled()) {
                return TaskHandle.NOOP;
            }
            try {
                Object scheduler = GET_ASYNC_SCHEDULER.invoke(plugin.getServer());
                Method m = asyncRunDelayed;
                if (m == null) {
                    m = findMethod(scheduler.getClass(), "runDelayed", Plugin.class, Consumer.class, long.class, TimeUnit.class);
                    asyncRunDelayed = m;
                }
                if (m != null) {
                    Consumer<Object> consumer = ignored -> task.run();
                    return wrapFoliaTask(m.invoke(scheduler, plugin, consumer, safeTicks * 50L, TimeUnit.MILLISECONDS));
                }
            } catch (InvocationTargetException e) {
                if (e.getCause() instanceof IllegalPluginAccessException) {
                    return TaskHandle.NOOP;
                }
                throw new IllegalStateException("Failed to schedule delayed Folia async task", e.getCause());
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Failed to schedule delayed Folia async task", e);
            }
        }
        try {
            return wrapBukkitTask(plugin.getServer().getScheduler().runTaskLaterAsynchronously(plugin, task, safeTicks));
        } catch (IllegalPluginAccessException ignored) {
            return TaskHandle.NOOP;
        }
    }

    public static TaskHandle runAsyncTimer(Plugin plugin, Runnable task, long delayTicks, long periodTicks) {
        if (plugin == null || task == null) {
            return TaskHandle.NOOP;
        }
        long safeDelay = Math.max(0L, delayTicks);
        long safePeriod = Math.max(1L, periodTicks);
        if (isFolia()) {
            if (!plugin.isEnabled()) {
                return TaskHandle.NOOP;
            }
            try {
                Object scheduler = GET_ASYNC_SCHEDULER.invoke(plugin.getServer());
                Method m = asyncRunAtFixedRate;
                if (m == null) {
                    m = findMethod(scheduler.getClass(), "runAtFixedRate", Plugin.class, Consumer.class, long.class, long.class, TimeUnit.class);
                    asyncRunAtFixedRate = m;
                }
                if (m != null) {
                    Consumer<Object> consumer = ignored -> task.run();
                    return wrapFoliaTask(m.invoke(scheduler, plugin, consumer, safeDelay * 50L, safePeriod * 50L, TimeUnit.MILLISECONDS));
                }
            } catch (InvocationTargetException e) {
                if (e.getCause() instanceof IllegalPluginAccessException) {
                    return TaskHandle.NOOP;
                }
                throw new IllegalStateException("Failed to schedule repeating Folia async task", e.getCause());
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Failed to schedule repeating Folia async task", e);
            }
        }
        try {
            return wrapBukkitTask(plugin.getServer().getScheduler().runTaskTimerAsynchronously(plugin, task, safeDelay, safePeriod));
        } catch (IllegalPluginAccessException ignored) {
            return TaskHandle.NOOP;
        }
    }

    // -------------------------------------------------------------------------
    // Global region scheduler
    // -------------------------------------------------------------------------

    public static TaskHandle runGlobal(Plugin plugin, Runnable task) {
        if (plugin == null || task == null) {
            return TaskHandle.NOOP;
        }
        if (isFolia()) {
            if (!plugin.isEnabled()) {
                return TaskHandle.NOOP;
            }
            try {
                Object scheduler = GET_GLOBAL_REGION_SCHEDULER.invoke(plugin.getServer());
                Method m = globalRun;
                if (m == null) {
                    m = findMethod(scheduler.getClass(), "run", Plugin.class, Consumer.class);
                    globalRun = m;
                }
                if (m != null) {
                    Consumer<Object> consumer = ignored -> task.run();
                    return wrapFoliaTask(m.invoke(scheduler, plugin, consumer));
                }
            } catch (InvocationTargetException e) {
                if (e.getCause() instanceof IllegalPluginAccessException) {
                    return TaskHandle.NOOP;
                }
                throw new IllegalStateException("Failed to schedule Folia global task", e.getCause());
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Failed to schedule Folia global task", e);
            }
        }
        try {
            return wrapBukkitTask(plugin.getServer().getScheduler().runTask(plugin, task));
        } catch (IllegalPluginAccessException ignored) {
            return TaskHandle.NOOP;
        }
    }

    public static TaskHandle runGlobalLater(Plugin plugin, Runnable task, long delayTicks) {
        if (plugin == null || task == null) {
            return TaskHandle.NOOP;
        }
        long safeTicks = Math.max(1L, delayTicks);
        if (isFolia()) {
            if (!plugin.isEnabled()) {
                return TaskHandle.NOOP;
            }
            try {
                Object scheduler = GET_GLOBAL_REGION_SCHEDULER.invoke(plugin.getServer());
                Method m = globalRunDelayed;
                if (m == null) {
                    m = findMethod(scheduler.getClass(), "runDelayed", Plugin.class, Consumer.class, long.class);
                    globalRunDelayed = m;
                }
                if (m != null) {
                    Consumer<Object> consumer = ignored -> task.run();
                    return wrapFoliaTask(m.invoke(scheduler, plugin, consumer, safeTicks));
                }
            } catch (InvocationTargetException e) {
                if (e.getCause() instanceof IllegalPluginAccessException) {
                    return TaskHandle.NOOP;
                }
                throw new IllegalStateException("Failed to schedule delayed Folia global task", e.getCause());
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Failed to schedule delayed Folia global task", e);
            }
        }
        try {
            return wrapBukkitTask(plugin.getServer().getScheduler().runTaskLater(plugin, task, safeTicks));
        } catch (IllegalPluginAccessException ignored) {
            return TaskHandle.NOOP;
        }
    }

    public static TaskHandle runGlobalTimer(Plugin plugin, Runnable task, long delayTicks, long periodTicks) {
        if (plugin == null || task == null) {
            return TaskHandle.NOOP;
        }
        // Folia requires initialDelayTicks >= 1 and periodTicks >= 1 on GlobalRegionScheduler.
        long safeDelay = Math.max(1L, delayTicks);
        long safePeriod = Math.max(1L, periodTicks);
        if (isFolia()) {
            if (!plugin.isEnabled()) {
                return TaskHandle.NOOP;
            }
            try {
                Object scheduler = GET_GLOBAL_REGION_SCHEDULER.invoke(plugin.getServer());
                Method m = globalRunAtFixedRate;
                if (m == null) {
                    m = findMethod(scheduler.getClass(), "runAtFixedRate", Plugin.class, Consumer.class, long.class, long.class);
                    globalRunAtFixedRate = m;
                }
                if (m != null) {
                    Consumer<Object> consumer = ignored -> task.run();
                    return wrapFoliaTask(m.invoke(scheduler, plugin, consumer, safeDelay, safePeriod));
                }
            } catch (InvocationTargetException e) {
                if (e.getCause() instanceof IllegalPluginAccessException) {
                    return TaskHandle.NOOP;
                }
                throw new IllegalStateException("Failed to schedule repeating Folia global task", e.getCause());
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Failed to schedule repeating Folia global task", e);
            }
        }
        try {
            return wrapBukkitTask(plugin.getServer().getScheduler().runTaskTimer(plugin, task, safeDelay, safePeriod));
        } catch (IllegalPluginAccessException ignored) {
            return TaskHandle.NOOP;
        }
    }

    // -------------------------------------------------------------------------
    // Entity scheduler
    // -------------------------------------------------------------------------

    public static TaskHandle runEntity(Plugin plugin, Entity entity, Runnable task) {
        return runEntity(plugin, entity, task, null);
    }

    public static TaskHandle runEntity(Plugin plugin, Entity entity, Runnable task, @Nullable Runnable retired) {
        if (plugin == null || entity == null || task == null) {
            return TaskHandle.NOOP;
        }
        if (isFolia()) {
            if (!plugin.isEnabled()) {
                return TaskHandle.NOOP;
            }
            try {
                Object scheduler = GET_ENTITY_SCHEDULER.invoke(entity);
                Method m = entityRun;
                if (m == null) {
                    m = findMethod(scheduler.getClass(), "run", Plugin.class, Consumer.class, Runnable.class);
                    entityRun = m;
                }
                if (m != null) {
                    Consumer<Object> consumer = ignored -> task.run();
                    Object result = m.invoke(scheduler, plugin, consumer, retired);
                    return wrapFoliaTask(result);
                }
            } catch (InvocationTargetException e) {
                if (e.getCause() instanceof IllegalPluginAccessException) {
                    return TaskHandle.NOOP;
                }
                throw new IllegalStateException("Failed to schedule Folia entity task", e.getCause());
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Failed to schedule Folia entity task", e);
            }
        }
        try {
            return wrapBukkitTask(plugin.getServer().getScheduler().runTask(plugin, task));
        } catch (IllegalPluginAccessException ignored) {
            return TaskHandle.NOOP;
        }
    }

    public static TaskHandle runEntityLater(Plugin plugin, Entity entity, Runnable task, long delayTicks) {
        return runEntityLater(plugin, entity, task, null, delayTicks);
    }

    public static TaskHandle runEntityLater(Plugin plugin, Entity entity, Runnable task, @Nullable Runnable retired, long delayTicks) {
        if (plugin == null || entity == null || task == null) {
            return TaskHandle.NOOP;
        }
        // Folia's EntityScheduler#runDelayed requires delayTicks >= 1.
        long safeTicks = Math.max(1L, delayTicks);
        if (isFolia()) {
            if (!plugin.isEnabled()) {
                return TaskHandle.NOOP;
            }
            try {
                Object scheduler = GET_ENTITY_SCHEDULER.invoke(entity);
                Method m = entityRunDelayed;
                if (m == null) {
                    m = findMethod(scheduler.getClass(), "runDelayed", Plugin.class, Consumer.class, Runnable.class, long.class);
                    entityRunDelayed = m;
                }
                if (m != null) {
                    Consumer<Object> consumer = ignored -> task.run();
                    Object result = m.invoke(scheduler, plugin, consumer, retired, safeTicks);
                    return wrapFoliaTask(result);
                }
            } catch (InvocationTargetException e) {
                if (e.getCause() instanceof IllegalPluginAccessException) {
                    return TaskHandle.NOOP;
                }
                throw new IllegalStateException("Failed to schedule delayed Folia entity task", e.getCause());
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Failed to schedule delayed Folia entity task", e);
            }
        }
        try {
            return wrapBukkitTask(plugin.getServer().getScheduler().runTaskLater(plugin, task, safeTicks));
        } catch (IllegalPluginAccessException ignored) {
            return TaskHandle.NOOP;
        }
    }

    // -------------------------------------------------------------------------
    // Region scheduler
    // -------------------------------------------------------------------------

    public static void executeAtLocation(Plugin plugin, Location location, Runnable task) {
        if (plugin == null || location == null || task == null) {
            return;
        }
        if (isFolia() && GET_REGION_SCHEDULER != null) {
            if (!plugin.isEnabled()) {
                return;
            }
            try {
                Object scheduler = GET_REGION_SCHEDULER.invoke(plugin.getServer());
                Method m = regionExecute;
                if (m == null) {
                    m = findMethod(scheduler.getClass(), "execute", Plugin.class, Location.class, Runnable.class);
                    regionExecute = m;
                }
                if (m != null) {
                    m.invoke(scheduler, plugin, location, task);
                    return;
                }
            } catch (InvocationTargetException e) {
                if (e.getCause() instanceof IllegalPluginAccessException) {
                    return;
                }
                throw new IllegalStateException("Failed to schedule Folia region task", e.getCause());
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Failed to schedule Folia region task", e);
            }
        }
        try {
            plugin.getServer().getScheduler().runTask(plugin, task);
        } catch (IllegalPluginAccessException ignored) {
        }
    }

    // -------------------------------------------------------------------------
    // High-level sender / player helpers
    // -------------------------------------------------------------------------

    /**
     * Runs {@code action} on the sender's region thread if it is a {@link Player}
     * (re-verifying that the player is still online), or on the global region
     * thread for console/other command senders.
     */
    public static void runForSender(Plugin plugin, CommandSender sender, Consumer<CommandSender> action) {
        if (plugin == null || sender == null || action == null) {
            return;
        }
        if (sender instanceof Player player) {
            UUID uuid = player.getUniqueId();
            Player current = plugin.getServer().getPlayer(uuid);
            if (current == null) {
                return;
            }
            runEntity(plugin, current, () -> {
                Player live = plugin.getServer().getPlayer(uuid);
                if (live != null && live.isOnline()) {
                    action.accept(live);
                }
            });
        } else {
            runGlobal(plugin, () -> action.accept(sender));
        }
    }

    /**
     * Runs {@code ifOnline} on the player's entity scheduler when online, or
     * falls back to running {@code ifOffline} on the global region scheduler if
     * the player is offline or retires before execution.
     */
    public static void runForPlayerOrGlobal(Plugin plugin, UUID uuid, Consumer<Player> ifOnline, @Nullable Runnable ifOffline) {
        if (plugin == null || uuid == null) {
            return;
        }
        Player player = plugin.getServer().getPlayer(uuid);
        if (player != null) {
            Runnable retiredFallback = ifOffline == null ? null : () -> runGlobal(plugin, ifOffline);
            TaskHandle handle = runEntity(plugin, player, () -> {
                Player live = plugin.getServer().getPlayer(uuid);
                if (live != null && live.isOnline()) {
                    ifOnline.accept(live);
                } else if (ifOffline != null) {
                    ifOffline.run();
                }
            }, retiredFallback);
            if (handle == TaskHandle.NOOP && ifOffline != null) {
                runGlobal(plugin, ifOffline);
            }
        } else if (ifOffline != null) {
            runGlobal(plugin, ifOffline);
        }
    }

    public static void cancelTasks(Plugin plugin) {
        if (plugin == null) {
            return;
        }
        if (isFolia()) {
            try {
                Object global = GET_GLOBAL_REGION_SCHEDULER.invoke(plugin.getServer());
                Method gm = globalCancelTasks;
                if (gm == null) {
                    gm = findMethod(global.getClass(), "cancelTasks", Plugin.class);
                    globalCancelTasks = gm;
                }
                if (gm != null) {
                    gm.invoke(global, plugin);
                }
                Object async = GET_ASYNC_SCHEDULER.invoke(plugin.getServer());
                Method am = asyncCancelTasks;
                if (am == null) {
                    am = findMethod(async.getClass(), "cancelTasks", Plugin.class);
                    asyncCancelTasks = am;
                }
                if (am != null) {
                    am.invoke(async, plugin);
                }
            } catch (ReflectiveOperationException ignored) {
            }
            return;
        }
        plugin.getServer().getScheduler().cancelTasks(plugin);
    }

    // -------------------------------------------------------------------------
    // Internals
    // -------------------------------------------------------------------------

    private static boolean detectFolia() {
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    private static @Nullable Method findMethod(Class<?> owner, String name, Class<?>... params) {
        try {
            Method method = owner.getMethod(name, params);
            method.setAccessible(true);
            return method;
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    private static TaskHandle wrapBukkitTask(@Nullable BukkitTask task) {
        if (task == null) {
            return TaskHandle.NOOP;
        }
        return new TaskHandle() {
            @Override
            public void cancel() {
                task.cancel();
            }

            @Override
            public boolean isCancelled() {
                return task.isCancelled();
            }
        };
    }

    private static TaskHandle wrapFoliaTask(@Nullable Object scheduledTask) {
        if (scheduledTask == null) {
            return TaskHandle.NOOP;
        }
        return new TaskHandle() {
            @Override
            public void cancel() {
                try {
                    Method m = scheduledTaskCancel;
                    if (m == null) {
                        m = findMethod(scheduledTask.getClass(), "cancel");
                        scheduledTaskCancel = m;
                    }
                    if (m != null) {
                        m.invoke(scheduledTask);
                    }
                } catch (ReflectiveOperationException ignored) {
                }
            }

            @Override
            public boolean isCancelled() {
                try {
                    Method m = scheduledTaskIsCancelled;
                    if (m == null) {
                        m = findMethod(scheduledTask.getClass(), "isCancelled");
                        scheduledTaskIsCancelled = m;
                    }
                    if (m != null) {
                        return Boolean.TRUE.equals(m.invoke(scheduledTask));
                    }
                } catch (ReflectiveOperationException ignored) {
                }
                return false;
            }
        };
    }
}
