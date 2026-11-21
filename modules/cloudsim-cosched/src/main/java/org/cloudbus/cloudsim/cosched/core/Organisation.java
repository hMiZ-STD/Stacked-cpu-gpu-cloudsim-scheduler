package org.cloudbus.cloudsim.cosched.core;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Teams with GPU quotas and the priority classes jobs may carry (Philly virtual clusters,
 * Alibaba 2026 priority classes). A workload without teams uses {@link #single()}.
 */
public record Organisation(List<Team> teams, @JsonProperty("priority_classes") List<String> priorityClasses) {

    public static final String DEFAULT = "default";

    /** A team and its GPU quota; quota null means no quota. */
    public record Team(String name, @JsonProperty("gpu_quota") Integer gpuQuota) {
        public Team {
            if (name == null || name.isBlank()) throw new IllegalArgumentException("team needs a name");
            if (gpuQuota != null && gpuQuota < 0) throw new IllegalArgumentException("team '" + name + "': gpu_quota must be >= 0");
        }
    }

    public Organisation {
        teams = teams == null || teams.isEmpty() ? List.of(new Team(DEFAULT, null)) : List.copyOf(teams);
        priorityClasses = priorityClasses == null || priorityClasses.isEmpty() ? List.of(DEFAULT) : List.copyOf(priorityClasses);
        Map<String, Team> seen = new HashMap<>();
        for (Team t : teams)
            if (seen.put(t.name(), t) != null) throw new IllegalArgumentException("duplicate team: " + t.name());
    }

    /** One default team, no quotas, one priority class. */
    public static Organisation single() {
        return new Organisation(null, null);
    }

    public Team team(String name) {
        for (Team t : teams) if (t.name().equals(name)) return t;
        throw new IllegalArgumentException("unknown team: " + name);
    }

    public boolean hasTeam(String name) {
        for (Team t : teams) if (t.name().equals(name)) return true;
        return false;
    }
}
