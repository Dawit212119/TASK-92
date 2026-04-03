-- V1 seed bcrypt strings did not verify against "admin123" (Spring BCrypt + API tests).
-- Single hash for all default demo users; password remains admin123 per README.
UPDATE users SET password_hash = '$2b$10$HAEDn6WeW7Ua3qYEkNK1/eQ67mtWHstXKe8v99wJfs85WMdaQLM1a'
WHERE username IN ('admin', 'editor', 'clerk', 'driver1', 'moderator', 'dispatcher', 'auditor');
