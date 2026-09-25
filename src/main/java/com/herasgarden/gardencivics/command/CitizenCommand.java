package com.herasgarden.gardencivics.command;

import com.herasgarden.gardencivics.CitizenshipService;
import com.herasgarden.gardencore.api.land.GardenTerritoryDirectory;
import com.herasgarden.gardencore.api.land.TerritorySummary;
import com.herasgarden.gardencore.api.ui.GardenMessages;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

public final class CitizenCommand implements CommandExecutor, TabCompleter {
    private final CitizenshipService citizenship;
    private final GardenTerritoryDirectory territories;

    public CitizenCommand(CitizenshipService citizenship, GardenTerritoryDirectory territories) {
        this.citizenship = citizenship;
        this.territories = territories;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            GardenMessages.send(sender, "Players only.");
            return true;
        }
        if (!player.hasPermission("gardencivics.citizen")) {
            GardenMessages.send(player, "You do not have permission to use citizenship commands.");
            return true;
        }

        if (args.length == 0 || args[0].equalsIgnoreCase("status")) {
            showStatus(player);
            return true;
        }

        try {
            switch (args[0].toLowerCase(Locale.ROOT)) {
                case "join" -> join(player, args);
                case "leave" -> leave(player);
                case "list" -> list(player, args);
                default -> usage(player);
            }
        } catch (IllegalArgumentException exception) {
            GardenMessages.send(player, exception.getMessage());
        } catch (SQLException exception) {
            GardenMessages.send(player, "Citizenship could not be updated right now.");
        }
        return true;
    }

    private void join(Player player, String[] args) throws SQLException {
        if (args.length < 2) {
            GardenMessages.send(player, "Usage: /citizen join <territory>");
            return;
        }
        String name = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
        TerritorySummary joined = citizenship.join(player.getUniqueId(), name);
        GardenMessages.send(player, "You are now affiliated with " + joined.name() + ".");
    }

    private void leave(Player player) throws SQLException {
        if (citizenship.clearMembership(player.getUniqueId())) {
            GardenMessages.send(player, "Your territory affiliation was cleared.");
        } else {
            GardenMessages.send(player, "You do not currently have a territory affiliation.");
        }
    }

    private void list(Player player, String[] args) {
        if (!player.hasPermission("gardencivics.citizen.list")) {
            GardenMessages.send(player, "You do not have permission to list citizens.");
            return;
        }
        if (args.length < 2) {
            GardenMessages.send(player, "Usage: /citizen list <territory>");
            return;
        }
        String name = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
        TerritorySummary territory = territories.findByName(name)
                .orElseThrow(() -> new IllegalArgumentException("That territory does not exist."));
        List<UUID> members = citizenship.members(territory.claimId());
        if (members.isEmpty()) {
            GardenMessages.send(player, territory.name() + " does not have any declared citizens yet.");
            return;
        }

        List<String> names = new ArrayList<>();
        for (UUID uuid : members) {
            names.add(Optional.ofNullable(Bukkit.getOfflinePlayer(uuid).getName()).orElse(uuid.toString()));
        }
        GardenMessages.send(player, territory.name() + " citizens: " + String.join(", ", names));
    }

    private void showStatus(Player player) {
        Optional<TerritorySummary> current = citizenship.territoryOf(player.getUniqueId());
        if (current.isPresent()) {
            GardenMessages.send(player, "Territory affiliation: " + current.get().name() + ".");
        } else {
            GardenMessages.send(player, "You have not chosen a territory affiliation.");
        }
    }

    private void usage(Player player) {
        GardenMessages.send(player, "Usage: /citizen <status|join|leave|list>");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            return List.of("status", "join", "leave", "list").stream()
                    .filter(value -> value.startsWith(prefix))
                    .toList();
        }
        if (args.length >= 2 && (args[0].equalsIgnoreCase("join") || args[0].equalsIgnoreCase("list"))) {
            String prefix = String.join(" ", Arrays.copyOfRange(args, 1, args.length)).toLowerCase(Locale.ROOT);
            return territories.list().stream()
                    .map(TerritorySummary::name)
                    .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix))
                    .toList();
        }
        return List.of();
    }
}
