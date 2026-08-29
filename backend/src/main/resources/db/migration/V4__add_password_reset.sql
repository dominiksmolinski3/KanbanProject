--
-- Password reset needs its own code and expiry, separate from the verification pair beside it.
--
-- They answer different questions - "is this address real" and "does this person still control
-- it" - and sharing one column would mean a pending reset silently cancelled a pending
-- verification, or that a code mailed for one purpose was accepted for the other.
--
-- Nullable, because most rows have no reset in flight and never will. The stored value is a hash
-- rather than the code itself: unlike a verification code, this one is a credential, and anyone
-- who could read the users table could otherwise reset any account at will.
--

ALTER TABLE users ADD COLUMN password_reset_code varchar(255);
ALTER TABLE users ADD COLUMN password_reset_expiration timestamp(6);
