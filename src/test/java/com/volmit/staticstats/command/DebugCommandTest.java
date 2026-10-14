package com.volmit.staticstats.command;

import art.arcane.volmlib.util.diagnostics.BukkitDebugDump;
import art.arcane.volmlib.util.director.DirectorEngineOptions;
import art.arcane.volmlib.util.director.compat.DirectorEngineFactory;
import art.arcane.volmlib.util.director.context.DirectorContextRegistry;
import art.arcane.volmlib.util.director.runtime.DirectorInvocation;
import art.arcane.volmlib.util.director.runtime.DirectorRuntimeEngine;
import art.arcane.volmlib.util.director.runtime.DirectorSender;
import com.volmit.staticstats.StaticPlugin;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DebugCommandTest {
    @Test
    void directorDiscoversAndExecutesNestedDumpCommand() {
        StaticPlugin plugin = mock(StaticPlugin.class);
        BukkitDebugDump dump = mock(BukkitDebugDump.class);
        CommandSender sender = mock(CommandSender.class);
        DirectorSender invocationSender = mock(DirectorSender.class);
        when(plugin.getDebugDump()).thenReturn(dump);
        when(invocationSender.getName()).thenReturn("Console");
        DirectorContextRegistry contexts = new DirectorContextRegistry();
        contexts.register(CommandSender.class, (invocation, arguments) -> sender);
        DirectorRuntimeEngine engine = DirectorEngineFactory.create(new StaticCommands(plugin),
                DirectorEngineOptions.builder().contexts(contexts).build());

        engine.execute(new DirectorInvocation(invocationSender, "static", List.of("debug", "dump", "upload=false")));

        verify(dump).request(sender, false);
    }
}
