package com.herasgarden.gardencivics;

import com.herasgarden.gardencivics.command.CivicsCommand;
import com.herasgarden.gardencivics.command.GovernmentCommand;
import com.herasgarden.gardencivics.payroll.PayrollService;
import com.herasgarden.gardencivics.storage.CivicsSchema;
import com.herasgarden.gardencore.api.GardenPlatform;
import com.herasgarden.gardencore.api.land.GardenCitizenshipDirectory;
import com.herasgarden.gardencore.api.land.GardenTerritoryDirectory;
import com.herasgarden.gardencore.api.organization.OrganizationDirectory;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.SQLException;

public final class GardenCivics extends JavaPlugin {
    private CivicsService civics;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        GardenPlatform platform = service(GardenPlatform.class);
        OrganizationDirectory organizations = service(OrganizationDirectory.class);
        GardenTerritoryDirectory territories = service(GardenTerritoryDirectory.class);
        GardenCitizenshipDirectory citizenship = service(GardenCitizenshipDirectory.class);

        if (platform == null || organizations == null || territories == null || citizenship == null) {
            getLogger().severe("GardenCore/GardenLands civic platform services are unavailable.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        try {
            CivicsSchema.ensure(platform.storage());
        } catch (SQLException exception) {
            getLogger().severe("GardenCivics could not prepare storage: " + exception.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        civics = new CivicsService(
                this,
                platform,
                organizations,
                territories,
                citizenship,
                getConfig().getInt("citizenship.application-message-max", 280)
        );

        PayrollService payroll = new PayrollService(this, platform, organizations, civics);
        GovernmentCommand governmentCommand = new GovernmentCommand(civics, payroll);
        PluginCommand government = getCommand("government");
        if (government != null) {
            government.setExecutor(governmentCommand);
            government.setTabCompleter(governmentCommand);
        }

        CivicsCommand civicsCommand = new CivicsCommand(civics);
        PluginCommand civicsRoot = getCommand("civics");
        if (civicsRoot != null) {
            civicsRoot.setExecutor(civicsCommand);
            civicsRoot.setTabCompleter(civicsCommand);
        }

        getLogger().info("GardenCivics enabled. Governments, officials, treasury, payroll, and citizenship applications are active.");
    }

    public CivicsService civics() {
        return civics;
    }

    private <T> T service(Class<T> type) {
        RegisteredServiceProvider<T> registration = getServer().getServicesManager().getRegistration(type);
        return registration == null ? null : registration.getProvider();
    }
}
