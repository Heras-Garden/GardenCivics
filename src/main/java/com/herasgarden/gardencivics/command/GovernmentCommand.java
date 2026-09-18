package com.herasgarden.gardencivics.command;

import com.herasgarden.gardencivics.CivicsService;
import com.herasgarden.gardencivics.CivicsService.GovernmentContext;
import com.herasgarden.gardencivics.payroll.PayrollService;
import com.herasgarden.gardencivics.payroll.PayrollService.PayrollPreview;
import com.herasgarden.gardencivics.payroll.PayrollService.PayrollRecipient;
import com.herasgarden.gardencivics.payroll.PayrollService.PayrollResult;
import com.herasgarden.gardencore.api.organization.OrganizationCapability;
import com.herasgarden.gardencore.api.organization.OrganizationRoleView;
import com.herasgarden.gardencore.api.organization.OrganizationView;
import com.herasgarden.gardencore.api.ui.GardenMessages;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

public final class GovernmentCommand implements CommandExecutor, TabCompleter {
    private final CivicsService civics;
    private final PayrollService payroll;

    public GovernmentCommand(CivicsService civics, PayrollService payroll) {
        this.civics = civics;
        this.payroll = payroll;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            GardenMessages.send(sender, "Government commands must be used in-game.");
            return true;
        }
        if (!player.hasPermission("gardencivics.government")) {
            GardenMessages.send(player, "You do not have permission to use government commands.");
            return true;
        }

        try {
            if (args.length == 0 || args[0].equalsIgnoreCase("info")) {
                showInfo(player, args);
                return true;
            }

            switch (args[0].toLowerCase(Locale.ROOT)) {
                case "create" -> create(player, args);
                case "roles" -> roles(player);
                case "role" -> role(player, args);
                case "appoint" -> appoint(player, args);
                case "remove" -> remove(player, args);
                case "treasury" -> treasury(player);
                case "deposit" -> deposit(player, args);
                case "withdraw" -> withdraw(player, args);
                case "payroll" -> payroll(player, args);
                default -> usage(player);
            }
        } catch (NumberFormatException exception) {
            GardenMessages.send(player, "Use a positive whole-number Obol amount.");
        } catch (IllegalArgumentException exception) {
            GardenMessages.send(player, exception.getMessage());
        } catch (SQLException exception) {
            GardenMessages.send(player, "The government system could not update right now.");
        }
        return true;
    }

    private void create(Player player, String[] args) throws SQLException {
        if (!player.hasPermission("gardencivics.government.create")) {
            GardenMessages.send(player, "You do not have permission to create a government.");
            return;
        }
        if (args.length < 2) {
            GardenMessages.send(player, "Use /government create <territory>.");
            return;
        }
        GovernmentContext context = civics.createGovernment(player, join(args, 1));
        GardenMessages.send(player, "Created " + context.organization().name()
                + " for " + context.territory().name() + ".");
        GardenMessages.send(player, "Default positions: Founder, Mayor, Council, Treasurer.");
    }

    private void showInfo(Player player, String[] args) throws SQLException {
        Optional<GovernmentContext> context;
        if (args.length > 1) {
            context = civics.governmentForTerritoryName(join(args, 1));
        } else {
            context = civics.governmentForPlayer(player.getUniqueId());
        }
        if (context.isEmpty()) {
            GardenMessages.send(player, args.length > 1
                    ? "That territory does not have a government."
                    : "You are not connected to a territory government.");
            return;
        }

        GovernmentContext government = context.get();
        OrganizationView organization = government.organization();
        String role = organization.memberRoles().get(player.getUniqueId());
        GardenMessages.send(player, organization.name() + " | Territory: " + government.territory().name() + ".");
        GardenMessages.send(player, "Treasury: ⟡ " + organization.treasury()
                + (role == null ? "" : " | Your role: " + role) + ".");
        GardenMessages.send(player, "Officials: " + organization.memberRoles().size()
                + " | Positions: " + organization.roles().size() + ".");
    }

    private void roles(Player player) throws SQLException {
        GovernmentContext context = civics.requireActorGovernment(player);
        GardenMessages.send(player, context.organization().name() + " positions:");
        for (OrganizationRoleView role : context.organization().roles().values()) {
            GardenMessages.send(player, role.key() + " — " + role.displayName()
                    + (role.salary() > 0 ? " | Salary ⟡ " + role.salary() : ""));
        }
    }

    private void role(Player player, String[] args) throws SQLException {
        if (!player.hasPermission("gardencivics.government.manage")) {
            GardenMessages.send(player, "You do not have permission to manage government roles.");
            return;
        }
        if (args.length < 2) {
            roleUsage(player);
            return;
        }

        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "create" -> roleCreate(player, args);
            case "info" -> roleInfo(player, args);
            case "salary" -> roleSalary(player, args);
            case "grant" -> roleCapability(player, args, true);
            case "revoke" -> roleCapability(player, args, false);
            default -> roleUsage(player);
        }
    }

    private void roleCreate(Player player, String[] args) throws SQLException {
        if (args.length < 4) {
            GardenMessages.send(player, "Use /government role create <key> <display name>.");
            return;
        }
        String displayName = join(args, 3);
        OrganizationView updated = civics.createRole(player, args[2], displayName);
        OrganizationRoleView role = updated.roles().get(args[2].toLowerCase(Locale.ROOT));
        GardenMessages.send(player, "Created government role "
                + (role == null ? args[2] : role.displayName()) + ".");
        GardenMessages.send(player,
                "Grant capabilities with /government role grant " + args[2] + " <capability>.");
    }

    private void roleInfo(Player player, String[] args) throws SQLException {
        if (args.length < 3) {
            GardenMessages.send(player, "Use /government role info <key>.");
            return;
        }
        OrganizationRoleView role = civics.role(player, args[2]);
        GardenMessages.send(player, role.displayName() + " (" + role.key() + ")"
                + " | Salary ⟡ " + role.salary() + ".");
        GardenMessages.send(player, role.capabilities().isEmpty()
                ? "Capabilities: none."
                : "Capabilities: " + role.capabilities().stream()
                .map(value -> value.name().toLowerCase(Locale.ROOT))
                .sorted()
                .collect(java.util.stream.Collectors.joining(", ")) + ".");
    }

    private void roleSalary(Player player, String[] args) throws SQLException {
        if (args.length < 4) {
            GardenMessages.send(player, "Use /government role salary <key> <amount>.");
            return;
        }
        long salary = Long.parseLong(args[3].replace(",", ""));
        if (salary < 0) {
            throw new IllegalArgumentException("Role salary cannot be negative.");
        }
        OrganizationView updated = civics.setRoleSalary(player, args[2], salary);
        OrganizationRoleView role = updated.roles().get(args[2].toLowerCase(Locale.ROOT));
        GardenMessages.send(player, "Salary for "
                + (role == null ? args[2] : role.displayName()) + " set to ⟡ " + salary + ".");
    }

    private void roleCapability(Player player, String[] args, boolean grant) throws SQLException {
        if (args.length < 4) {
            GardenMessages.send(player, "Use /government role "
                    + (grant ? "grant" : "revoke") + " <key> <capability>.");
            return;
        }
        OrganizationCapability capability = parseCapability(args[3]);
        OrganizationView updated = civics.setRoleCapability(player, args[2], capability, grant);
        OrganizationRoleView role = updated.roles().get(args[2].toLowerCase(Locale.ROOT));
        GardenMessages.send(player, (grant ? "Granted " : "Revoked ")
                + capability.name().toLowerCase(Locale.ROOT)
                + (grant ? " to " : " from ")
                + (role == null ? args[2] : role.displayName()) + ".");
    }

    private OrganizationCapability parseCapability(String raw) {
        String key = raw.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        try {
            return OrganizationCapability.valueOf(key);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Unknown capability. Use /government role info <key> "
                    + "or tab-complete a capability.");
        }
    }

    private void roleUsage(Player player) {
        GardenMessages.send(player,
                "/government role create <key> <display name>, role info <key>, "
                        + "role salary <key> <amount>, role grant <key> <capability>, "
                        + "role revoke <key> <capability>");
    }

    private void appoint(Player player, String[] args) throws SQLException {
        if (!player.hasPermission("gardencivics.government.manage")) {
            GardenMessages.send(player, "You do not have permission to appoint officials.");
            return;
        }
        if (args.length < 3) {
            GardenMessages.send(player, "Use /government appoint <player> <role>.");
            return;
        }

        OfflinePlayer target = knownPlayer(args[1])
                .orElseThrow(() -> new IllegalArgumentException("That player has not joined The Garden SMP before."));
        OrganizationView updated = civics.appoint(player, target.getUniqueId(), args[2]);
        String assignedRole = updated.memberRoles().get(target.getUniqueId());
        GardenMessages.send(player, "Assigned " + displayName(target) + " to " + assignedRole + ".");

        Player online = target.getPlayer();
        if (online != null && online.isOnline()) {
            GardenMessages.send(online, "You were appointed to " + assignedRole + " in " + updated.name() + ".");
        }
    }

    private void remove(Player player, String[] args) throws SQLException {
        if (!player.hasPermission("gardencivics.government.manage")) {
            GardenMessages.send(player, "You do not have permission to remove officials.");
            return;
        }
        if (args.length < 2) {
            GardenMessages.send(player, "Use /government remove <player>.");
            return;
        }

        OfflinePlayer target = knownPlayer(args[1])
                .orElseThrow(() -> new IllegalArgumentException("That player has not joined The Garden SMP before."));
        boolean removed = civics.removeOfficial(player, target.getUniqueId());
        GardenMessages.send(player, removed
                ? "Removed " + displayName(target) + " from the government."
                : "That player does not hold a position in your government.");
    }

    private void treasury(Player player) throws SQLException {
        if (!player.hasPermission("gardencivics.treasury")) {
            GardenMessages.send(player, "You do not have permission to view the treasury.");
            return;
        }
        GardenMessages.send(player, "Government treasury: ⟡ " + civics.treasury(player) + ".");
    }

    private void deposit(Player player, String[] args) throws SQLException {
        if (args.length < 2) {
            GardenMessages.send(player, "Use /government deposit <amount>.");
            return;
        }
        long amount = parseAmount(args[1]);
        long balance = civics.deposit(player, amount);
        GardenMessages.send(player, "Deposited ⟡ " + amount + ". Treasury balance: ⟡ " + balance + ".");
    }

    private void withdraw(Player player, String[] args) throws SQLException {
        if (args.length < 2) {
            GardenMessages.send(player, "Use /government withdraw <amount>.");
            return;
        }
        long amount = parseAmount(args[1]);
        long balance = civics.withdraw(player, amount);
        GardenMessages.send(player, "Withdrew ⟡ " + amount + ". Treasury balance: ⟡ " + balance + ".");
    }

    private void payroll(Player player, String[] args) throws SQLException {
        if (!player.hasPermission("gardencivics.payroll")) {
            GardenMessages.send(player, "You do not have permission to use government payroll.");
            return;
        }
        if (args.length < 2 || args[1].equalsIgnoreCase("preview")) {
            payrollPreview(player);
            return;
        }
        if (args[1].equalsIgnoreCase("run")) {
            payrollRun(player);
            return;
        }
        GardenMessages.send(player, "Use /government payroll <preview|run>.");
    }

    private void payrollPreview(Player player) throws SQLException {
        PayrollPreview preview = payroll.preview(player);
        GardenMessages.send(player, preview.governmentName() + " payroll for week " + preview.periodKey() + ".");
        GardenMessages.send(player, "Officials: " + preview.recipients().size()
                + " | Total: ⟡ " + preview.total()
                + " | Treasury: ⟡ " + preview.treasury() + ".");
        if (preview.existingStatus() != null) {
            GardenMessages.send(player, "This week's payroll status: " + preview.existingStatus() + ".");
        }
        if (preview.recipients().isEmpty()) {
            GardenMessages.send(player, "No positions currently have a salary above ⟡ 0.");
            return;
        }
        for (PayrollRecipient recipient : preview.recipients()) {
            GardenMessages.send(player, displayName(Bukkit.getOfflinePlayer(recipient.playerId()))
                    + " | " + recipient.roleName() + " | ⟡ " + recipient.amount());
        }
    }

    private void payrollRun(Player player) throws SQLException {
        PayrollResult result = payroll.run(player);
        GardenMessages.send(player, "Payroll complete for week " + result.periodKey() + ": "
                + result.recipientCount() + " official"
                + (result.recipientCount() == 1 ? "" : "s")
                + " paid ⟡ " + result.total() + ".");
        GardenMessages.send(player, "Treasury balance: ⟡ " + result.treasuryAfter()
                + " | Run " + result.runId().toString().substring(0, 8) + ".");
    }

    private long parseAmount(String value) {
        long amount = Long.parseLong(value.replace(",", ""));
        if (amount <= 0) {
            throw new IllegalArgumentException("Amount must be a positive whole number of Obols.");
        }
        return amount;
    }

    private Optional<OfflinePlayer> knownPlayer(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            return Optional.of(online);
        }
        return Arrays.stream(Bukkit.getOfflinePlayers())
                .filter(player -> player.getName() != null && player.getName().equalsIgnoreCase(name))
                .findFirst();
    }

    private String displayName(OfflinePlayer player) {
        return player.getName() == null ? player.getUniqueId().toString().substring(0, 8) : player.getName();
    }

    private String join(String[] args, int start) {
        return String.join(" ", Arrays.copyOfRange(args, start, args.length)).trim();
    }

    private void usage(Player player) {
        GardenMessages.send(player,
                "/government create <territory>, info [territory], roles, role <...>, "
                        + "appoint <player> <role>, remove <player>, treasury, deposit <amount>, "
                        + "withdraw <amount>, payroll <preview|run>");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return match(args[0], List.of(
                    "create", "info", "roles", "role", "appoint", "remove", "treasury", "deposit", "withdraw", "payroll"));
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("payroll")) {
            return match(args[1], List.of("preview", "run"));
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("role")) {
            return match(args[1], List.of("create", "info", "salary", "grant", "revoke"));
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("role")
                && !args[1].equalsIgnoreCase("create") && sender instanceof Player player) {
            try {
                return match(args[2], civics.requireActorGovernment(player)
                        .organization().roles().keySet().stream()
                        .filter(key -> !key.equals("owner"))
                        .toList());
            } catch (Exception ignored) {
                return List.of();
            }
        }
        if (args.length == 4 && args[0].equalsIgnoreCase("role")
                && (args[1].equalsIgnoreCase("grant") || args[1].equalsIgnoreCase("revoke"))) {
            return match(args[3], Arrays.stream(OrganizationCapability.values())
                    .map(value -> value.name().toLowerCase(Locale.ROOT))
                    .toList());
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("appoint") || args[0].equalsIgnoreCase("remove"))) {
            return match(args[1], Bukkit.getOnlinePlayers().stream().map(Player::getName).toList());
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("appoint") && sender instanceof Player player) {
            try {
                return match(args[2], civics.requireActorGovernment(player)
                        .organization().roles().keySet().stream()
                        .filter(key -> !key.equals("owner"))
                        .toList());
            } catch (Exception ignored) {
                return List.of();
            }
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("create") || args[0].equalsIgnoreCase("info"))) {
            return match(args[1], civics.territories().stream().map(value -> value.name()).toList());
        }
        return List.of();
    }

    private List<String> match(String prefix, List<String> values) {
        String lower = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT);
        return values.stream()
                .filter(value -> value.toLowerCase(Locale.ROOT).startsWith(lower))
                .toList();
    }
}
