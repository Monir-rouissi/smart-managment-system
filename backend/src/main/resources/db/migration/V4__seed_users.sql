-- Baseline accounts for local dev/demo login. Passwords are BCrypt-hashed
-- (cost 10); the plaintext below is documented here only, never stored.
-- Change or remove these rows before any non-local deployment.
--
--   admin@local   / admin123    (ADMIN)
--   manager@local / manager123  (MANAGER)
--   user@local    / user123     (USER)

INSERT INTO users (id, email, password_hash, full_name, role, enabled) VALUES
    (gen_random_uuid(), 'admin@local',   '$2b$10$k9YuIs0pMDa7oJuJXGDF.un6k4DrK57bSTbMQvhUTLeydbKSsE5TS', 'Admin User',   'ADMIN',   TRUE),
    (gen_random_uuid(), 'manager@local', '$2b$10$OvIOp6FXwGZG9Scq4LqMwu9o5YaTk3/npHg.33tgApi/xoV9jI61q', 'Manager User', 'MANAGER', TRUE),
    (gen_random_uuid(), 'user@local',    '$2b$10$oN2pnrn6WOuP17ht7HY5KOhjbi40Kq0bGx4IXjA7A03oGR/CuOvSG', 'Regular User', 'USER',    TRUE)
ON CONFLICT (email) DO NOTHING;
