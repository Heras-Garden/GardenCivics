package com.herasgarden.gardencivics.command;

import com.herasgarden.gardencivics.CivicsService;
import com.herasgarden.gardencivics.model.CitizenshipApplication;
import com.herasgarden.gardencore.api.land.TerritorySummary;
import com.herasgarden.gardencore.api.ui.GardenMessages;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
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

public final class CivicsCommand implements CommandExecutor, TabCompleter {
    private final CivicsService civics;

    public CivicsCommand(CivicsService civics) {
        this.civics = civics;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            GardenMessages.send(sender, "Civics commands must be used in-game.");
            return true;
        }

        try {
            if (args.length == 0) {
                usage(player);
                return true;
            }
            switch (args[0].toLowerCase(Locale.ROOT)) {
                case "apply" -> apply(player, args);
                case "applications" -> applications(player);
                case "approve" -> review(player, args, true);
                case "reject" -> review(player, args, false);
                default -> usage(player);
            }
        } catch (IllegalArgumentException exception) {
            GardenMessages.send(player, exception.getMessage());
        } catch (SQLException exception) {
            GardenMessages.send(player, "The civics system could not update right now.");
        }
        return true;
    }

    private void apply(Player player, String[] args) throws SQLException {
        if (!player.hasPermission("gardencivics.apply")) {
            GardenMessages.send(player, "You do not have permission to apply for citizenship.");
            return;
        }
        if (args.length < 2) {
            GardenMessages.send(player, "Use /civics apply <territory> [message].");
            return;
        }

        ApplicationTarget target = resolveTarget(args);
        CitizenshipApplication application = civics.apply(player, target.territory().name(), target.message());
        GardenMessages.send(player, "Citizenship application "
                + application.id().toString().substring(0, 8)
                + " submitted to " + target.territory().name() + ".");
    }

    private void applications(Player player) throws SQLException {
        if (!player.hasPermission("gardencivics.review")) {
            GardenMessages.send(player, "You do not have permission to review citizenship applications.");
            return;
        }

        List<CitizenshipApplication> applications = civics.pendingApplications(player);
        if (applications.isEmpty()) {
            GardenMessages.send(player, "There are no pending citizenship applications.");
            return;
        }

        player.sendMessage(GardenMessages.prefix()
                .append(Component.text("Citizenship applications", GardenMessages.PETAL_FROST)));
        player.sendMessage(Component.text(
                "Review each application, then choose an action at the end of its row.",
                GardenMessages.NEUTRAL_GRAY));
        for (CitizenshipApplication application : applications) {
            String applicant = playerName(application.playerId());
            String shortId = application.id().toString().substring(0, 8);
            Component line = Component.text(shortId + " — " + applicant, GardenMessages.MESSAGE_COLOR);
            if (!application.message().isBlank()) {
                line = line.append(Component.text(" | " + application.message(), GardenMessages.NEUTRAL_GRAY));
            }
            line = line.append(Component.space())
                    .append(GardenMessages.action(
                            "[Approve]",
                            "/civics approve " + application.id(),
                            "Approve this citizenship application.",
                            GardenMessages.MUTED_OLIVE))
                    .append(Component.space())
                    .append(GardenMessages.action(
                            "[Reject]",
                            "/civics reject " + application.id(),
                            "Reject this citizenship application.",
                            GardenMessages.BUBBLEGUM_PINK));
            player.sendMessage(line);
        }
    }

    private void review(Player player, String[] args, boolean approve) throws SQLException {
        if (!player.hasPermission("gardencivics.review")) {
            GardenMessages.send(player, "You do not have permission to review citizenship applications.");
            return;
        }
        if (args.length < 2) {
            GardenMessages.send(player, "Use /civics " + (approve ? "approve" : "reject") + " <application>.");
            return;
        }

        CitizenshipApplication reviewed = civics.review(player, args[1], approve);
        String action = approve ? "approved" : "rejected";
        GardenMessages.send(player, "Citizenship application "
                + reviewed.id().toString().substring(0, 8) + " " + action + ".");

        Player applicant = Bukkit.getPlayer(reviewed.playerId());
        if (applicant != null && applicant.isOnline()) {
            GardenMessages.send(applicant, "Your citizenship application was " + action + ".");
        }
    }

    private ApplicationTarget resolveTarget(String[] args) {
        List<TerritorySummary> territories = civics.territories();
        for (int end = args.length; end >= 2; end--) {
            String candidate = String.join(" ", Arrays.copyOfRange(args, 1, end)).trim();
            TerritorySummary territory = territories.stream()
                    .filter(value -> value.name().equalsIgnoreCase(candidate))
                    .findFirst()
                    .orElse(null);
            if (territory != null) {
                String message = end >= args.length
                        ? ""
                        : String.join(" ", Arrays.copyOfRange(args, end, args.length)).trim();
                return new ApplicationTarget(territory, message);
            }
        }
        throw new IllegalArgumentException("That territory does not exist.");
    }

    private String playerName(java.util.UUID playerId) {
        OfflinePlayer player = Bukkit.getOfflinePlayer(playerId);
        String name = player.getName();
        return name == null ? playerId.toString().substring(0, 8) : name;
    }

    private void usage(Player player) {
        GardenMessages.send(player,
                "/civics apply <territory> [message], applications, approve <id>, reject <id>");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return match(args[0], List.of("apply", "applications", "approve", "reject"));
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("apply")) {
            return match(args[1], civics.territories().stream().map(TerritorySummary::name).toList());
        }
        if (args.length == 2
                && (args[0].equalsIgnoreCase("approve") || args[0].equalsIgnoreCase("reject"))
                && sender instanceof Player player) {
            try {
                List<String> ids = new ArrayList<>();
                for (CitizenshipApplication application : civics.pendingApplications(player)) {
                    ids.add(application.id().toString().substring(0, 8));
                }
                return match(args[1], ids);
            } catch (Exception ignored) {
                return List.of();
            }
        }
        return List.of();
    }

    private List<String> match(String prefix, List<String> values) {
        String lower = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT);
        return values.stream()
                .filter(value -> value.toLowerCase(Locale.ROOT).startsWith(lower))
                .toList();
    }

    private record ApplicationTarget(TerritorySummary territory, String message) {
    }
}
