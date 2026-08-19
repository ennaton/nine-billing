package co.nine.billing.auth;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public class ApiKeyRepository {

    private final JdbcTemplate jdbc;

    public ApiKeyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Resolve a plaintext key to its tenant. Revoked keys resolve to nothing. */
    public Optional<UUID> tenantFor(String plaintext) {
        return jdbc.query(
            "SELECT tenant_id FROM api_keys WHERE key_hash = ? AND revoked_at IS NULL",
            rs -> rs.next() ? Optional.of(rs.getObject(1, UUID.class)) : Optional.empty(),
            ApiKeys.hash(plaintext));
    }

    /** Create a key for a tenant and return the plaintext exactly once. */
    public String create(UUID tenantId, String label) {
        String plaintext = ApiKeys.generate();
        jdbc.update(
            "INSERT INTO api_keys (id, tenant_id, key_hash, key_prefix, label) VALUES (?, ?, ?, ?, ?)",
            UUID.randomUUID(), tenantId, ApiKeys.hash(plaintext), ApiKeys.prefixOf(plaintext), label);
        return plaintext;
    }
}
