package com.volmit.staticstats.presentation;

import art.arcane.volmlib.util.director.help.DirectorMiniMenu;
import art.arcane.volmlib.util.plugin.ComponentText;

public final class ChatMenuStyle {
    private static final DirectorMiniMenu.Theme THEME = new DirectorMiniMenu.Theme(
            "#FFB454",
            "#FFE39C",
            "#59412C",
            "#D39758",
            "#F2E7D5",
            "#FF7777",
            "#F4CC8D",
            "#BCAE97"
    );

    private ChatMenuStyle() {
    }

    public static DirectorMiniMenu.Theme theme() {
        return THEME;
    }

    public static ComponentText entry(ComponentText content) {
        return ComponentText.markup("<" + THEME.muted() + ">⇀</" + THEME.muted() + "> ")
                .append(content);
    }

    public static ComponentText commandHover(String title, String description, String usage) {
        return ComponentText.markup(
                "<" + THEME.primaryRight() + ">" + DirectorMiniMenu.escapeText(title)
                        + "</" + THEME.primaryRight() + "><reset>\n"
                        + "<" + THEME.description() + ">✎ <font:minecraft:uniform>"
                        + DirectorMiniMenu.escapeText(description) + "</font></" + THEME.description() + "><reset>\n"
                        + "<" + THEME.optional() + ">✒ <font:minecraft:uniform>"
                        + DirectorMiniMenu.escapeText(usage) + "</font></" + THEME.optional() + ">"
        );
    }
}
