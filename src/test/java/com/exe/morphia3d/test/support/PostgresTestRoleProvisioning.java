package com.exe.morphia3d.test.support;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.regex.Pattern;

/** Test-only role setup for disposable PostgreSQL databases that apply migrations V15-V17. */
public final class PostgresTestRoleProvisioning {
    private static final Pattern ROLE_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_$]*");
    private static final List<String> OWNER_ROLES = List.of(
            "admin3d_owner", "admin3d_lock_owner", "admin3d_command_owner", "payos_trust_owner");

    private PostgresTestRoleProvisioning() { }

    public static void provisionMigrationRoles(JdbcTemplate bootstrap, String migrationRole) {
        String migrationRoleIdentifier = identifier(migrationRole);
        for (String role : OWNER_ROLES) {
            ensureRestrictedRole(bootstrap, role);
        }
        ensureRoleExists(bootstrap, "app_runtime");

        bootstrap.execute("GRANT " + String.join(", ", OWNER_ROLES) + " TO "
                + migrationRoleIdentifier + " WITH INHERIT FALSE, SET TRUE");
        bootstrap.execute("GRANT USAGE, CREATE ON SCHEMA public TO " + String.join(", ", OWNER_ROLES));
    }

    /** Call after V14 has created referenced tables and before applying V15. */
    public static void grantSnapshotReferencePrivileges(JdbcTemplate bootstrap) {
        bootstrap.execute("GRANT REFERENCES ON TABLE public.model_3d, public.media_asset, "
                + "public.materials, public.users TO admin3d_owner");
    }

    private static void ensureRestrictedRole(JdbcTemplate bootstrap, String role) {
        if (!exists(bootstrap, role)) {
            bootstrap.execute("CREATE ROLE " + identifier(role)
                    + " NOLOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS");
        }
        Boolean restricted = bootstrap.queryForObject("""
                SELECT NOT rolcanlogin AND NOT rolinherit AND NOT rolsuper AND NOT rolcreatedb
                       AND NOT rolcreaterole AND NOT rolreplication AND NOT rolbypassrls
                  FROM pg_catalog.pg_roles WHERE rolname = ?
                """, Boolean.class, role);
        if (!Boolean.TRUE.equals(restricted)) {
            throw new IllegalStateException("Test owner role is not restricted: " + role);
        }
    }

    private static void ensureRoleExists(JdbcTemplate bootstrap, String role) {
        if (!exists(bootstrap, role)) {
            bootstrap.execute("CREATE ROLE " + identifier(role)
                    + " NOLOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS");
        }
    }

    private static boolean exists(JdbcTemplate bootstrap, String role) {
        return Boolean.TRUE.equals(bootstrap.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM pg_catalog.pg_roles WHERE rolname = ?)", Boolean.class, role));
    }

    private static String identifier(String role) {
        if (role == null || !ROLE_NAME.matcher(role).matches()) {
            throw new IllegalArgumentException("Invalid disposable PostgreSQL role name");
        }
        return '"' + role + '"';
    }
}
