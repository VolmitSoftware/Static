package com.volmit.staticstats.records;

import com.volmit.staticstats.stats.StatKey;

import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public record PersonalBestSnapshot(UUID uuid, String name, ZoneId historyZone, long trackingSince,
                                   List<PersonalBest> records) {
    public PersonalBestSnapshot {
        Objects.requireNonNull(uuid);
        Objects.requireNonNull(name);
        Objects.requireNonNull(historyZone);
        records = List.copyOf(records);
        if (trackingSince < 0) {
            throw new IllegalArgumentException("Invalid personal-best tracking date");
        }
        Set<String> identities = new HashSet<>();
        for (PersonalBest record : records) {
            if (!identities.add(record.period().id() + ":" + record.statistic().id())) {
                throw new IllegalArgumentException("Duplicate personal best");
            }
        }
    }

    public Optional<PersonalBest> find(PersonalBestPeriod period, StatKey statistic) {
        Objects.requireNonNull(period);
        Objects.requireNonNull(statistic);
        for (PersonalBest record : records) {
            if (record.period() == period && record.statistic() == statistic) {
                return Optional.of(record);
            }
        }
        return Optional.empty();
    }
}
