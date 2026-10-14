package com.volmit.staticstats.command;

import art.arcane.volmlib.util.director.DirectorEngineOptions;
import art.arcane.volmlib.util.director.compat.DirectorEngineFactory;
import art.arcane.volmlib.util.director.context.DirectorContextRegistry;
import art.arcane.volmlib.util.director.help.DirectorMiniMenu;
import art.arcane.volmlib.util.director.runtime.DirectorInvocation;
import art.arcane.volmlib.util.director.runtime.DirectorRuntimeEngine;
import art.arcane.volmlib.util.director.runtime.DirectorSender;
import art.arcane.volmlib.util.localization.LanguageAudience;
import art.arcane.volmlib.util.plugin.ComponentMessenger;
import art.arcane.volmlib.util.plugin.ComponentText;
import com.volmit.staticstats.StaticPlugin;
import com.volmit.staticstats.detailed.DetailCategory;
import com.volmit.staticstats.detailed.DetailKey;
import com.volmit.staticstats.records.PersonalBestPeriod;
import com.volmit.staticstats.localization.StaticMessages;
import com.volmit.staticstats.presentation.ChatMenuStyle;
import com.volmit.staticstats.presentation.FeedbackTone;
import com.volmit.staticstats.stats.StatKey;
import com.volmit.staticstats.stats.StatsPeriod;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Level;

public final class CommandService implements CommandExecutor, TabCompleter {
    private final StaticPlugin plugin;
    private final DirectorRuntimeEngine director;

    public CommandService(StaticPlugin plugin) {
        this.plugin = plugin;
        DirectorContextRegistry contexts = new DirectorContextRegistry();
        contexts.register(CommandSender.class, (invocation, map) ->
                invocation.getSender() instanceof Sender sender ? sender.sender() : null);
        director = DirectorEngineFactory.create(new StaticCommands(plugin), DirectorEngineOptions.builder()
                .contexts(contexts).textResolver(plugin.getLanguageService().directorResolver()).build());
    }

    public void register() {
        for (String name : List.of("static", "stats", "leaderboard")) {
            PluginCommand command = Objects.requireNonNull(plugin.getCommand(name), "Missing command " + name);
            command.setExecutor(this);
            command.setTabCompleter(this);
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        List<String> arguments = expandAlias(command.getName(), args);
        return LanguageAudience.call(sender instanceof Player player ? player.getUniqueId() : null,
                () -> execute(sender, arguments));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> arguments = expandAlias(command.getName(), args);
        String root = arguments.isEmpty() ? "" : arguments.get(0).toLowerCase(Locale.ROOT);
        if (arguments.size() <= 1 && command.getName().equals("static")) {
            return List.of("stats", "top", "details", "detailtop", "bests", "sessions", "participation", "milestones", "seasons", "season", "seasontop", "config", "language", "status", "privacy", "debug", "help").stream()
                    .filter(value -> value.startsWith(root))
                    .filter(value -> permitted(sender, value)).toList();
        }
        if (!permitted(sender, root)) { return List.of(); }
        if (root.equals("privacy") && arguments.size() > 1
                && (arguments.size() == 2 || arguments.getLast().toLowerCase(Locale.ROOT).startsWith("mode="))) {
            String partial = arguments.getLast().toLowerCase(Locale.ROOT);
            boolean named = partial.startsWith("mode=");
            String value = named ? partial.substring(5) : partial;
            return List.of("private", "server", "public").stream().filter(mode -> mode.startsWith(value))
                    .map(mode -> named ? "mode=" + mode : mode).toList();
        }
        if (root.equals("participation") && arguments.size() > 1
                && (arguments.size() == 2 || arguments.getLast().toLowerCase(Locale.ROOT).startsWith("period="))) {
            String partial = arguments.getLast().toLowerCase(Locale.ROOT);
            boolean named = partial.startsWith("period=");
            String value = named ? partial.substring("period=".length()) : partial;
            return List.of("day", "week", "month").stream().filter(period -> period.startsWith(value))
                    .map(period -> named ? "period=" + period : period).toList();
        }
        if (root.equals("detailtop") && arguments.size() > 1
                && (arguments.size() == 2 || arguments.getLast().toLowerCase(Locale.ROOT).startsWith("counter="))) {
            String partial = arguments.getLast().toLowerCase(Locale.ROOT);
            boolean named = partial.startsWith("counter=");
            String value = named ? partial.substring("counter=".length()) : partial;
            return Arrays.stream(DetailKey.values()).map(DetailKey::id).filter(id -> id.startsWith(value))
                    .map(id -> named ? "counter=" + id : id).toList();
        }
        if (root.equals("bests") && arguments.size() > 1
                && (arguments.size() == 3 || arguments.getLast().toLowerCase(Locale.ROOT).startsWith("period="))) {
            String partial = arguments.getLast().toLowerCase(Locale.ROOT);
            boolean named = partial.startsWith("period=");
            String value = named ? partial.substring("period=".length()) : partial;
            return Arrays.stream(PersonalBestPeriod.values()).map(PersonalBestPeriod::id)
                    .filter(period -> period.startsWith(value)).map(period -> named ? "period=" + period : period).toList();
        }
        if (root.equals("details") && arguments.size() > 1
                && (arguments.size() == 3 || arguments.getLast().toLowerCase(Locale.ROOT).startsWith("category="))) {
            String partial = arguments.getLast().toLowerCase(Locale.ROOT);
            boolean named = partial.startsWith("category=");
            String value = named ? partial.substring("category=".length()) : partial;
            List<String> categories = new ArrayList<>(List.of("all"));
            for (DetailCategory category : DetailCategory.values()) {
                categories.add(category.name().toLowerCase(Locale.ROOT));
            }
            return categories.stream().filter(category -> category.startsWith(value))
                    .map(category -> named ? "category=" + category : category).toList();
        }
        if ((root.equals("season") || root.equals("seasontop")) && arguments.size() == 2) {
            String partial = arguments.get(1).toLowerCase(Locale.ROOT);
            return plugin.getStatsService().seasons().stream().map(season -> season.definition().id())
                    .filter(id -> id.startsWith(partial)).toList();
        }
        if (root.equals("language")) {
            return plugin.getLanguageSwitcher().complete(sender, arguments.subList(1, arguments.size()).toArray(String[]::new));
        }
        if ((root.equals("stats") || root.equals("top")) && arguments.size() > 1
                && arguments.getLast().toLowerCase(Locale.ROOT).startsWith("period=")) {
            String partial = arguments.getLast().substring("period=".length()).toLowerCase(Locale.ROOT);
            return Arrays.stream(StatsPeriod.values()).map(period -> period.name().toLowerCase(Locale.ROOT))
                    .filter(period -> period.startsWith(partial)).map(period -> "period=" + period).toList();
        }
        if (root.equals("top") && arguments.size() == 2) {
            String partial = arguments.get(1).toLowerCase(Locale.ROOT);
            return Arrays.stream(StatKey.values()).map(StatKey::id).filter(id -> id.startsWith(partial)).toList();
        }
        if (((root.equals("stats") || root.equals("sessions") || root.equals("milestones") || root.equals("details") || root.equals("bests")) && arguments.size() == 2)
                || root.equals("season") && arguments.size() == 3) {
            String partial = arguments.getLast().toLowerCase(Locale.ROOT);
            ArrayList<String> names = new ArrayList<>();
            if ("self".startsWith(partial)) { names.add("self"); }
            if (sender.hasPermission("static." + (root.equals("season") ? "seasons" : root) + ".others")) {
                for (Player player : plugin.getServer().getOnlinePlayers()) {
                    if (player.getName().toLowerCase(Locale.ROOT).startsWith(partial)
                            && plugin.getProfileVisibility().canView(sender, player.getUniqueId())) { names.add(player.getName()); }
                }
            }
            return names;
        }
        if ((root.equals("stats") && arguments.size() == 3)
                || (root.equals("top") && (arguments.size() == 3 || arguments.size() == 4))) {
            String partial = arguments.getLast().toLowerCase(Locale.ROOT);
            boolean named = partial.startsWith("period=");
            String value = named ? partial.substring("period=".length()) : partial;
            List<String> periods = Arrays.stream(StatsPeriod.values()).map(period -> period.name().toLowerCase(Locale.ROOT))
                    .filter(period -> period.startsWith(value)).map(period -> named ? "period=" + period : period).toList();
            if (root.equals("top") && arguments.size() == 3 && !named) {
                List<String> choices = new ArrayList<>(periods);
                if ("1".startsWith(partial)) {
                    choices.addFirst("1");
                }
                return choices;
            }
            return periods;
        }
        return LanguageAudience.call(sender instanceof Player player ? player.getUniqueId() : null,
                () -> director.tabComplete(new DirectorInvocation(new Sender(sender), "static", arguments)));
    }

    private boolean execute(CommandSender sender, List<String> args) {
        try {
            if (!args.isEmpty() && !permitted(sender, args.get(0))) {
                plugin.getPresentationService().command(sender, StaticMessages.NO_PERMISSION, FeedbackTone.FAILURE);
                return true;
            }
            if (!args.isEmpty() && args.get(0).equalsIgnoreCase("language")) {
                return plugin.getLanguageSwitcher().command(sender, args.subList(1, args.size()).toArray(String[]::new));
            }
            Optional<DirectorMiniMenu.DirectorHelpPage> help = DirectorMiniMenu.resolveHelp(director, args);
            if (help.isPresent()) {
                DirectorMiniMenu.deliver(sender, help.get(), ChatMenuStyle.theme(), plugin.getLanguageService().directorResolver());
                return true;
            }
            if (!director.execute(new DirectorInvocation(new Sender(sender), "static", normalizeArguments(args))).isHandled()) {
                plugin.getLanguageService().send(sender, StaticMessages.UNKNOWN_COMMAND);
            }
        } catch (RuntimeException exception) {
            plugin.getLogger().log(Level.SEVERE, "Static command execution failed", exception);
            plugin.getPresentationService().command(sender, StaticMessages.COMMAND_FAILED, FeedbackTone.FAILURE);
        }
        return true;
    }

    static List<String> normalizeArguments(List<String> arguments) {
        if (arguments.isEmpty()) { return arguments; }
        List<String> parameters = switch (arguments.get(0).toLowerCase(Locale.ROOT)) {
            case "privacy" -> List.of("mode", "player");
            case "stats" -> List.of("player", "period");
            case "top" -> List.of("stat", "page", "period");
            case "sessions", "milestones" -> List.of("player", "page");
            case "details" -> List.of("player", "category", "page");
            case "detailtop" -> List.of("counter", "page");
            case "participation" -> List.of("period", "offset");
            case "bests" -> List.of("player", "period", "page");
            case "seasons" -> List.of("page");
            case "season" -> List.of("id", "player");
            case "seasontop" -> List.of("id", "page");
            default -> List.of();
        };
        ArrayList<String> normalized = new ArrayList<>(arguments);
        Set<String> assigned = new LinkedHashSet<>();
        for (int index = 1; index < normalized.size(); index++) {
            String argument = normalized.get(index);
            int equals = argument.indexOf('=');
            if (equals > 0) {
                assigned.add(argument.substring(0, equals).toLowerCase(Locale.ROOT));
            }
        }
        int position = 0;
        for (int index = 1; index < normalized.size(); index++) {
            String argument = normalized.get(index);
            if (argument.contains("=")) {
                continue;
            }
            while (position < parameters.size() && assigned.contains(parameters.get(position))) {
                position++;
            }
            if (position >= parameters.size()) {
                break;
            }
            if (arguments.getFirst().equalsIgnoreCase("top") && parameters.get(position).equals("page")
                    && !assigned.contains("period") && isPeriod(argument)) {
                normalized.set(index, "period=" + argument);
                assigned.add("period");
                continue;
            }
            normalized.set(index, parameters.get(position) + "=" + argument);
            position++;
        }
        return List.copyOf(normalized);
    }

    private static boolean isPeriod(String value) {
        for (StatsPeriod period : StatsPeriod.values()) {
            if (period.name().equalsIgnoreCase(value)) {
                return true;
            }
        }
        return false;
    }

    private static List<String> expandAlias(String command, String[] args) {
        ArrayList<String> arguments = new ArrayList<>();
        if (command.equalsIgnoreCase("stats")) { arguments.add("stats"); }
        if (command.equalsIgnoreCase("leaderboard")) { arguments.add("top"); }
        arguments.addAll(Arrays.asList(args));
        return arguments;
    }

    private static boolean permitted(CommandSender sender, String root) {
        String command = root.toLowerCase(Locale.ROOT);
        if (command.equals("version")) {
            return sender.hasPermission("static.debug");
        }
        if (command.equals("season") || command.equals("seasontop")) {
            return sender.hasPermission("static.seasons");
        }
        return command.equals("help") || command.isEmpty() || sender.hasPermission("static." + command);
    }

    private record Sender(CommandSender sender) implements DirectorSender {
        @Override
        public String getName() { return sender.getName(); }
        @Override
        public boolean isPlayer() { return sender instanceof Player; }
        @Override
        public void sendMessage(String message) { ComponentMessenger.send(sender, ComponentText.literal(message)); }
    }
}
