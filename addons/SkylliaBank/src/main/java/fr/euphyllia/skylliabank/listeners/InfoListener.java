package fr.euphyllia.skylliabank.listeners;

import fr.euphyllia.skyllia.api.event.IslandInfoEvent;
import fr.euphyllia.skyllia.api.event.SkyblockCreateEvent;
import fr.euphyllia.skyllia.api.skyblock.Island;
import fr.euphyllia.skyllia.configuration.ConfigLoader;
import fr.euphyllia.skylliabank.SkylliaBank;
import fr.euphyllia.skylliabank.api.BankAccount;
import fr.euphyllia.skylliabank.configuration.BankConfigLoader;
import net.kyori.adventure.text.Component;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

import java.util.Map;

public class InfoListener implements Listener {

    @EventHandler
    public void onIslandInfoEvent(final IslandInfoEvent event) {
        BankAccount account = SkylliaBank.getBankManager().getOrLoadBankAccount(event.getIsland().getId());

        if (account.balance() < 0) {
            return;
        }

        Component component = Component.text("")
                .append(ConfigLoader.language.translate(event.getViewer(), "addons.bank.display.title"))
                .append(Component.newline())
                .append(ConfigLoader.language.translate(event.getViewer(), "addons.bank.display.balance", Map.of("%amount%", String.valueOf(account.balance()))));

        event.addLine(component);
    }

    @EventHandler
    public void onSkyblockCreateEvent(final SkyblockCreateEvent event) {
        Island island = event.getIsland();
        BankAccount account = SkylliaBank.getBankManager().getOrLoadBankAccount(island.getId());
        if (account == null) {
            SkylliaBank.getBankManager().setBalance(island.getId(), BankConfigLoader.config.getVaultDefaultBalance());
        }
    }

    @EventHandler
    public void onSkyblockDeleteEvent(final SkyblockCreateEvent event) {
        Island island = event.getIsland();
        BankAccount account = SkylliaBank.getBankManager().getOrLoadBankAccount(island.getId());
        if (account != null) {
            SkylliaBank.getBankManager().setBalance(island.getId(), 0);
        }
    }

}
