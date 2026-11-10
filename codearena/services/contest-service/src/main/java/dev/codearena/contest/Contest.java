package dev.codearena.contest;

import java.time.Instant;
import java.util.List;

public record Contest(String id, String title, String description, Instant startAt, Instant endAt,
                      List<String> problems, boolean frozen, boolean finalized, String createdBy) {

    public String phase(Instant now) {
        if (now.isBefore(startAt)) return "UPCOMING";
        if (now.isBefore(endAt)) return "LIVE";
        return "ENDED";
    }

    /** Keys use a {hash tag} so all of one contest's keys land on the same Redis Cluster slot. */
    public static String tag(String id) {
        return "{" + id + "}";
    }
}