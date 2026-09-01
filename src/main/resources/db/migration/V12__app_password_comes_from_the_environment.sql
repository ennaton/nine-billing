-- BI16.3. V4 creates nine_app with a literal password and .gitleaks.toml
-- allowlists that string, so the scanner is told to ignore a credential.
-- The placeholder resolves from SPRING_DATASOURCE_PASSWORD, the variable the
-- datasource already reads, so one variable sets the password and connects.
--
-- V4 is not edited because Flyway validates its checksum on every existing
-- database. The literal stays in that file; it is no longer what the service uses.
ALTER ROLE nine_app PASSWORD '${appPassword}';
