package dev.plexusmodloader.game.layer.v1_21_1;

import dev.plexusmodloader.api.permission.PermissionNode;
import dev.plexusmodloader.api.permission.PermissionProvider;
import dev.plexusmodloader.api.permission.PermissionResult;
import java.util.List;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.world.entity.player.Player;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Decides named permissions: mod providers first, then the node's default operator level. */
public final class PermissionChecks {

    private static final Logger LOGGER = LoggerFactory.getLogger("Plexus Mod Loader");

    private PermissionChecks() {}

    /** 1.21.1 has integer operator levels, 0–4 (the permission sets came with 1.21.9). */
    private static boolean hasLevel(Player player, int level) {
        return level == 0 || player.hasPermissions(level);
    }

    private static boolean hasLevel(CommandSourceStack source, int level) {
        return level == 0 || source.hasPermission(level);
    }

    /** Whether {@code player} has {@code node}. */
    public static boolean check(Player player, PermissionNode node) {
        List<PermissionProvider> providers = LayerState.context()
                .map(context -> context.declarations().permissionProviders())
                .orElse(List.of());
        for (PermissionProvider provider : providers) {
            PermissionResult result;
            try {
                result = provider.check(Views.player(player), node);
            } catch (RuntimeException e) {
                LOGGER.error(
                        "[Plexus Mod Loader] PLEXUS-PERMISSION-003 a permission provider failed for {}", node.id(), e);
                continue;
            }
            if (result == PermissionResult.ALLOW) {
                return true;
            }
            if (result == PermissionResult.DENY) {
                return false;
            }
        }
        return hasLevel(player, node.defaultLevel());
    }

    /** Whether a command source has {@code node}: players through {@link #check}, others by level. */
    public static boolean check(CommandSourceStack source, PermissionNode node) {
        if (source.getEntity() instanceof Player player) {
            return check(player, node);
        }
        return hasLevel(source, node.defaultLevel());
    }
}
