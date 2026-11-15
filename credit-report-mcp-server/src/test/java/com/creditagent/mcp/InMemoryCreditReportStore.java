package com.creditagent.mcp;

import com.creditagent.common.Ssn;
import com.creditagent.common.bureau.Bureau;
import com.creditagent.reports.CreditReportStore;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** The store's contract in memory, for tests. */
class InMemoryCreditReportStore implements CreditReportStore {

    final Map<Ssn, Map<Bureau, String>> sets = new ConcurrentHashMap<>();
    final Map<Ssn, Instant> updatedAt = new ConcurrentHashMap<>();
    Clock clock = Clock.systemUTC();

    @Override
    public void replaceAll(Ssn ssn, Map<Bureau, String> reports) {
        if (reports.isEmpty()) {
            sets.remove(ssn);
            updatedAt.remove(ssn);
            return;
        }
        sets.put(ssn, new EnumMap<>(reports));
        updatedAt.put(ssn, clock.instant());
    }

    @Override
    public Map<Bureau, String> findAll(Ssn ssn) {
        if (sets.containsKey(ssn)) {
            updatedAt.computeIfPresent(ssn, (k, v) -> v.isBefore(clock.instant()) ? clock.instant() : v); // a read counts
        }
        return sets.getOrDefault(ssn, Map.of());
    }

    @Override
    public int purgeOlderThan(Instant cutoff, int max) {
        int deleted = 0;
        for (Map.Entry<Ssn, Instant> entry : updatedAt.entrySet()) {
            if (deleted < max && entry.getValue().isBefore(cutoff)) {
                deleted += sets.remove(entry.getKey()).size();
                updatedAt.remove(entry.getKey());
            }
        }
        return deleted;
    }

    @Override
    public String reference(Ssn ssn) {
        return "ref-" + ssn.digits().substring(5);
    }
}
