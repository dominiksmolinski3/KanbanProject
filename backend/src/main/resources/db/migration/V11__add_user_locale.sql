--
-- The column that lets a message be written in the language the reader chose.
--
-- The application speaks nine languages on screen, through t() and the bundles under
-- frontend/public/locales, and has mailed in exactly one since the first message it ever sent.
-- That was invisible while the wording lived in three services as inline HTML; putting it in one
-- place made it a single obvious gap rather than three small ones.
--
-- It has to be a column rather than a header. There is no request to read Accept-Language from at
-- the two points that matter most: the deadline sweep runs on a scheduler and has no request at
-- all, and a verification mail composed from the browser that happened to sign up is composed from
-- a fact a month out of date the moment somebody travels or borrows a machine. The account is the
-- thing that has a language; the request is only ever a guess at it.
--
-- Add, backfill, then NOT NULL, in that order, which is the order V5 established and the only one
-- that works against a database that already has rows. Everything that exists now was mailed in
-- English, so 'en' is what those accounts had rather than a default chosen for them.
--
-- A language tag rather than a full locale: 'pt-BR' fits, and eight characters leaves room for the
-- regional variants none of the nine current bundles use. The set of tags the application will
-- accept lives in SupportedLocales, checked against the client's bundle directory at build time -
-- a CHECK constraint here would have to be migrated every time a language is added, which is
-- exactly the wrong place to make that cost land.
--
ALTER TABLE users
    ADD COLUMN locale varchar(8);

UPDATE users
SET locale = 'en'
WHERE locale IS NULL;

ALTER TABLE users
    ALTER COLUMN locale SET NOT NULL;
