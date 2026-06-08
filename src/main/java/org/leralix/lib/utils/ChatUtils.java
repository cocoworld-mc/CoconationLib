package org.leralix.lib.utils;

import net.kyori.adventure.text.Component;
import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.entity.Player;

/**
 * This class is used for chat related utilities.
 */
public class ChatUtils {

    private ChatUtils() {
        throw new IllegalStateException("Utility class");
    }

    /**
     * This method is used to send a clickable message to a player.
     * @param player    The player to send the message to.
     * @param message   The message to send.
     * @param command   The command to run when the message is clicked without the "/" (exemple : tp 0 0 0)
     */
    public static void sendClickableCommand(final Player player, final String message, final String command) {
        TextComponent component = new TextComponent(TextComponent.fromLegacyText(ChatColor.translateAlternateColorCodes('&', message)));
        component.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/" + command));
        player.spigot().sendMessage(component);
    }

    /**
     * Sends a clickable message built from an Adventure {@link Component}.
     *
     * <p>Unlike the legacy {@link #sendClickableCommand(Player, String, String)} overload (md_5
     * {@code TextComponent}, no hover), this keeps the full Component (colours, decorations,
     * {@code <click>}/{@code <hover>}) and adds a native click + hover. The caller renders the
     * message text upstream (single central renderer) and passes the resulting Component here;
     * no text parsing happens in the library.
     *
     * @param player    The player to send the message to.
     * @param message   The already-rendered message Component.
     * @param command   The command to run when clicked, without the leading "/" (e.g. "tp 0 0 0").
     */
    public static void sendClickableCommand(final Player player, final Component message, final String command) {
        player.sendMessage(message
                .clickEvent(net.kyori.adventure.text.event.ClickEvent.runCommand("/" + command))
                .hoverEvent(net.kyori.adventure.text.event.HoverEvent.showText(
                        Component.text("/" + command))));
    }

}
