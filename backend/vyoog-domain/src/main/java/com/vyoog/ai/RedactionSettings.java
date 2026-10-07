package com.vyoog.ai;

import com.vyoog.platform.audit.AuditService;
import java.sql.Array;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * VYB-0937: which classes of personal data an administrator has switched redaction off for. Stored in
 * {@code app_config.ai_redaction_disabled}; empty means all on. {@link DataClass#SECRET} can never be in it: it is refused
 * here, and the column's CHECK constraint refuses it for any other writer.
 */
@Service
public class RedactionSettings {

    private final JdbcTemplate jdbc;
    private final AuditService audit;

    public RedactionSettings(JdbcTemplate jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    /** Read on every call: a change takes effect on the next model call, with no restart and no cache to expire. */
    public Set<DataClass> disabled() {
        List<String> names = jdbc.query("SELECT ai_redaction_disabled FROM app_config WHERE id = 1",
            rs -> {
                if (!rs.next()) return List.<String>of();
                Array a = rs.getArray(1);
                return a == null ? List.<String>of() : List.of((String[]) a.getArray());
            });
        Set<DataClass> out = EnumSet.noneOf(DataClass.class);
        for (String n : names) {
            try {
                DataClass c = DataClass.valueOf(n);
                if (c.optOutAllowed()) out.add(c);
            } catch (IllegalArgumentException ignored) {
                // the constraint keeps this list to known names; an unknown one is ignored, never a reason to send more
            }
        }
        return out;
    }

    /**
     * Replaces the list. A name that is not a class, or that is {@link DataClass#SECRET}, refuses the whole change.
     * One audit event records the list before and after.
     */
    public Set<DataClass> setDisabled(Collection<String> names, UUID actor) {
        Set<DataClass> wanted = EnumSet.noneOf(DataClass.class);
        for (String n : names == null ? List.<String>of() : names) {
            DataClass c;
            try {
                c = DataClass.valueOf(n == null ? "" : n.strip());
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Unknown kind of data '" + n + "'. Choose from: " + optOutNames());
            }
            if (!c.optOutAllowed()) {
                throw new IllegalArgumentException("Secrets are always removed before text is sent to a model provider and cannot be switched off.");
            }
            wanted.add(c);
        }
        Set<DataClass> before = disabled();
        String[] arr = wanted.stream().map(Enum::name).sorted().toArray(String[]::new);
        jdbc.update(con -> {
            var ps = con.prepareStatement("UPDATE app_config SET ai_redaction_disabled = ? WHERE id = 1");
            ps.setArray(1, con.createArrayOf("text", arr));
            return ps;
        });
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("disabled", names(before));
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("disabled", names(wanted));
        audit.record(actor, "settings.ai-redaction-changed", "APP_CONFIG", null, b, a);
        return wanted;
    }

    public static List<String> optOutNames() {
        List<String> out = new ArrayList<>();
        for (DataClass c : DataClass.values()) if (c.optOutAllowed()) out.add(c.name());
        return out;
    }

    private static List<String> names(Set<DataClass> set) {
        return set.stream().map(Enum::name).sorted().toList();
    }
}
