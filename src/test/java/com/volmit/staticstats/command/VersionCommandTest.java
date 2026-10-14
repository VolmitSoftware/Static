package com.volmit.staticstats.command;

import art.arcane.volmlib.util.director.DirectorEngineOptions;
import art.arcane.volmlib.util.director.compat.DirectorEngineFactory;
import art.arcane.volmlib.util.director.context.DirectorContextRegistry;
import art.arcane.volmlib.util.director.help.DirectorMiniMenu;
import art.arcane.volmlib.util.director.runtime.DirectorInvocation;
import art.arcane.volmlib.util.director.runtime.DirectorRuntimeEngine;
import art.arcane.volmlib.util.director.runtime.DirectorSender;
import com.volmit.staticstats.StaticPlugin;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.PluginDescriptionFile;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VersionCommandTest {
    @Test
    void bothCommandPathsPrintOnlyTheInstalledVersionAndHelpListsTheNestedPath() {
        StaticPlugin plugin = mock(StaticPlugin.class);
        CommandSender sender = mock(CommandSender.class);
        DirectorSender invocationSender = mock(DirectorSender.class);
        when(plugin.getDescription()).thenReturn(new PluginDescriptionFile("Static", "3.2.1-test", "test.Plugin"));
        when(invocationSender.getName()).thenReturn("Console");
        DirectorContextRegistry contexts = new DirectorContextRegistry();
        contexts.register(CommandSender.class, (invocation, arguments) -> sender);
        DirectorRuntimeEngine engine = DirectorEngineFactory.create(new StaticCommands(plugin),
                DirectorEngineOptions.builder().contexts(contexts).build());

        assertThat(engine.execute(new DirectorInvocation(invocationSender, "static", List.of("debug", "version"))).isSuccess()).isTrue();
        assertThat(engine.execute(new DirectorInvocation(invocationSender, "static", List.of("version"))).isSuccess()).isTrue();

        verify(sender, times(2)).sendMessage("Static v3.2.1-test");
        assertThat(DirectorMiniMenu.resolveHelp(engine, List.of()).orElseThrow().entries())
                .extracting(entry -> entry.getDescriptor().getName()).doesNotContain("version");
        assertThat(DirectorMiniMenu.resolveHelp(engine, List.of("debug")).orElseThrow().entries())
                .extracting(entry -> entry.getDescriptor().getName()).contains("version");
    }
}
