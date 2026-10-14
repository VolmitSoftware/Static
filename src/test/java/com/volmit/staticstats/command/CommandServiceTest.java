package com.volmit.staticstats.command;

import art.arcane.volmlib.util.director.annotations.Director;
import art.arcane.volmlib.util.director.annotations.Param;
import art.arcane.volmlib.util.localization.TextValue;
import com.volmit.staticstats.localization.StaticMessages;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CommandServiceTest {
    @Test
    void positionalArgumentsFillOnlyUnassignedParameters() {
        assertThat(CommandService.normalizeArguments(List.of("top", "page=2", "blocks_broken")))
                .containsExactly("top", "page=2", "stat=blocks_broken");
        assertThat(CommandService.normalizeArguments(List.of("top", "blocks_broken", "page=2")))
                .containsExactly("top", "stat=blocks_broken", "page=2");
        assertThat(CommandService.normalizeArguments(List.of("top", "blocks_broken", "2")))
                .containsExactly("top", "stat=blocks_broken", "page=2");
    }

    @Test
    void languageSubcommandsRemainIntact() {
        List<String> arguments = List.of("language", "server", "edit", "de_DE");
        assertThat(CommandService.normalizeArguments(arguments)).containsExactlyElementsOf(arguments);
    }

    @Test
    void periodsCanFollowAPageOrReplaceTheOptionalPagePosition() {
        assertThat(CommandService.normalizeArguments(List.of("top", "blocks_broken", "2", "week")))
                .containsExactly("top", "stat=blocks_broken", "page=2", "period=week");
        assertThat(CommandService.normalizeArguments(List.of("top", "blocks_broken", "day")))
                .containsExactly("top", "stat=blocks_broken", "period=day");
        assertThat(CommandService.normalizeArguments(List.of("top", "period=month", "blocks_broken", "2")))
                .containsExactly("top", "period=month", "stat=blocks_broken", "page=2");
        assertThat(CommandService.normalizeArguments(List.of("stats", "self", "month")))
                .containsExactly("stats", "player=self", "period=month");
        assertThat(CommandService.normalizeArguments(List.of("sessions", "River", "2")))
                .containsExactly("sessions", "player=River", "page=2");
        assertThat(CommandService.normalizeArguments(List.of("milestones", "River", "2")))
                .containsExactly("milestones", "player=River", "page=2");
        assertThat(CommandService.normalizeArguments(List.of("milestones", "page=2", "River")))
                .containsExactly("milestones", "page=2", "player=River");
        assertThat(CommandService.normalizeArguments(List.of("seasons", "2"))).containsExactly("seasons", "page=2");
        assertThat(CommandService.normalizeArguments(List.of("season", "autumn", "River")))
                .containsExactly("season", "id=autumn", "player=River");
        assertThat(CommandService.normalizeArguments(List.of("seasontop", "page=2", "autumn")))
                .containsExactly("seasontop", "page=2", "id=autumn");
        assertThat(CommandService.normalizeArguments(List.of("details", "River", "blocks_mined", "2")))
                .containsExactly("details", "player=River", "category=blocks_mined", "page=2");
        assertThat(CommandService.normalizeArguments(List.of("details", "category=items_traded", "River", "page=2")))
                .containsExactly("details", "category=items_traded", "player=River", "page=2");
    }

    @Test
    void helpDescriptionsMatchTheRegisteredLanguageDefinitions() {
        for (Class<?> type : List.of(StaticCommands.class, StaticCommands.DebugCommands.class)) {
            assertDescription(type.getAnnotation(Director.class));
            for (Method method : type.getDeclaredMethods()) {
                Director command = method.getAnnotation(Director.class);
                if (command != null) {
                    assertDescription(command);
                }
                for (Parameter parameter : method.getParameters()) {
                    Param description = parameter.getAnnotation(Param.class);
                    if (description != null && !description.descriptionKey().isBlank()) {
                        assertThat(((TextValue) StaticMessages.catalog().require(description.descriptionKey()).englishValue()).template())
                                .isEqualTo(description.description());
                    }
                }
            }
        }
    }

    @Test
    void personalBestArgumentsRespectNamedScopeAndPageValues() {
        assertThat(CommandService.normalizeArguments(List.of("bests", "River", "session", "2")))
                .containsExactly("bests", "player=River", "period=session", "page=2");
        assertThat(CommandService.normalizeArguments(List.of("bests", "period=month", "River", "page=2")))
                .containsExactly("bests", "period=month", "player=River", "page=2");
    }

    @Test
    void detailedRankingArgumentsRespectNamedCounterAndPageValues() {
        assertThat(CommandService.normalizeArguments(List.of("detailtop", "mined_diamond_ore", "2")))
                .containsExactly("detailtop", "counter=mined_diamond_ore", "page=2");
        assertThat(CommandService.normalizeArguments(List.of("detailtop", "page=2", "mined_diamond_ore")))
                .containsExactly("detailtop", "page=2", "counter=mined_diamond_ore");
    }

    @Test
    void participationArgumentsRespectNamedPeriodAndOffsetValues() {
        assertThat(CommandService.normalizeArguments(List.of("participation", "week", "2")))
                .containsExactly("participation", "period=week", "offset=2");
        assertThat(CommandService.normalizeArguments(List.of("participation", "offset=2", "month")))
                .containsExactly("participation", "offset=2", "period=month");
    }

    private void assertDescription(Director command) {
        assertThat(((TextValue) StaticMessages.catalog().require(command.descriptionKey()).englishValue()).template())
                .isEqualTo(command.description());
    }
}
