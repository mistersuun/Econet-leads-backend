package com.econet.leads.integration;

import java.util.function.Predicate;

/**
 * Push-style source of import records. Implementations read their input incrementally (CSV rows,
 * JSON array elements, API pages) and hand each record to {@code sink} as soon as it is mapped, so
 * an import never needs the whole source in memory. {@code sink} returns false when the job was
 * cancelled; the stream must then stop.
 */
@FunctionalInterface
public interface RecordStream<T> {

    void forEach(Predicate<T> sink) throws Exception;
}
