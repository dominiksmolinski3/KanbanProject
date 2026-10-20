ALTER TABLE task ALTER COLUMN deadline TYPE timestamp(6) WITH TIME ZONE USING deadline AT TIME ZONE 'Europe/Warsaw';
