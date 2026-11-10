package com.creditagent.generator;

import com.creditagent.common.Ssn;
import com.creditagent.common.bureau.Bureau;
import com.creditagent.reports.CreditReportStore;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** The store's contract in memory, for tests. */
final class InMemoryCreditReportStore implements CreditReportStore {

    final Map<Ssn, Map<Bureau, String>> sets = new ConcurrentHashMap<>();

    @Override
    public void replaceAll(Ssn ssn, Map<Bureau, String> reports) {
        sets.put(ssn, new EnumMap<>(reports.isEmpty() ? Map.of() : reports));
    }

    @Override
    public Map<Bureau, String> findAll(Ssn ssn) {
        return sets.getOrDefault(ssn, Map.of());
    }
}
