package com.volmit.staticstats.season;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.volmit.staticstats.stats.PlayerStatsSnapshot;
import com.volmit.staticstats.stats.StatKey;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

final class SeasonCodec {
    private SeasonCodec() { }

    static JsonObject encode(SeasonDefinition definition, long recordedSince, long archivedAt, boolean frozen, List<PlayerStatsSnapshot> profiles) {
        JsonObject data = new JsonObject();
        data.addProperty("id", definition.id());
        data.addProperty("name", definition.name());
        data.addProperty("starts-at", definition.startsAt().toString());
        data.addProperty("ends-at", definition.endsAt().toString());
        SeasonRules rules = definition.rules();
        data.addProperty("statistic", rules.statistic().id());
        data.addProperty("minimum-sample", rules.minimumSample());
        JsonArray excluded = new JsonArray();
        for (String world : rules.excludedWorlds()) {
            excluded.add(world);
        }
        data.add("excluded-worlds", excluded);
        data.addProperty("track-creative", rules.trackCreative());
        data.addProperty("track-spectator", rules.trackSpectator());
        data.addProperty("afk-millis", rules.afkMillis());
        data.addProperty("recorded-since", recordedSince);
        data.addProperty("archived-at", archivedAt);
        data.addProperty("frozen", frozen);
        JsonArray players = new JsonArray();
        for (PlayerStatsSnapshot profile : profiles) {
            JsonObject player = new JsonObject();
            player.addProperty("uuid", profile.uuid().toString());
            player.addProperty("name", profile.name());
            player.addProperty("recorded-since", profile.recordedSince());
            player.addProperty("updated-at", profile.updatedAt());
            JsonObject values = new JsonObject();
            for (Map.Entry<StatKey, Double> entry : profile.values().entrySet()) {
                values.addProperty(entry.getKey().id(), entry.getValue());
            }
            player.add("values", values);
            players.add(player);
        }
        data.add("players", players);
        return data;
    }

    static Stored decode(JsonObject data) {
        Set<String> excluded = new HashSet<>();
        for (JsonElement world : data.getAsJsonArray("excluded-worlds")) {
            excluded.add(world.getAsString());
        }
        SeasonRules rules = new SeasonRules(StatKey.parse(data.get("statistic").getAsString()), data.get("minimum-sample").getAsDouble(),
                excluded, data.get("track-creative").getAsBoolean(), data.get("track-spectator").getAsBoolean(), data.get("afk-millis").getAsLong());
        SeasonDefinition definition = new SeasonDefinition(data.get("id").getAsString(), data.get("name").getAsString(),
                Instant.parse(data.get("starts-at").getAsString()), Instant.parse(data.get("ends-at").getAsString()), rules);
        long since = data.get("recorded-since").getAsLong();
        long archivedAt = data.get("archived-at").getAsLong();
        if (since < definition.startsAt().toEpochMilli() || since >= definition.endsAt().toEpochMilli()
                || archivedAt != 0 && archivedAt < definition.endsAt().toEpochMilli()) {
            throw new IllegalArgumentException("Invalid season observation interval");
        }
        List<PlayerStatsSnapshot> profiles = new ArrayList<>();
        Set<UUID> ids = new HashSet<>();
        for (JsonElement element : data.getAsJsonArray("players")) {
            JsonObject player = element.getAsJsonObject();
            UUID uuid = UUID.fromString(player.get("uuid").getAsString());
            if (!ids.add(uuid)) {
                throw new IllegalArgumentException("Duplicate season participant");
            }
            Map<StatKey, Double> values = new EnumMap<>(StatKey.class);
            for (Map.Entry<String, JsonElement> entry : player.getAsJsonObject("values").entrySet()) {
                values.put(StatKey.parse(entry.getKey()), entry.getValue().getAsDouble());
            }
            profiles.add(new PlayerStatsSnapshot(uuid, player.get("name").getAsString(), values,
                    player.get("updated-at").getAsLong(), player.get("recorded-since").getAsLong()));
        }
        boolean frozen = data.get("frozen").getAsBoolean();
        if (!frozen && (archivedAt > 0 || !profiles.isEmpty())) {
            throw new IllegalArgumentException("Recorded season data requires frozen rules");
        }
        return new Stored(definition, since, archivedAt, frozen, List.copyOf(profiles));
    }

    record Stored(SeasonDefinition definition, long recordedSince, long archivedAt, boolean frozen, List<PlayerStatsSnapshot> profiles) { }
}
