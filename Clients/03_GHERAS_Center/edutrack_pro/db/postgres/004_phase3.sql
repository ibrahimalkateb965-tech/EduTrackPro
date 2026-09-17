-- Phase 3 migration: Gender column for student gender separation
ALTER TABLE students ADD COLUMN IF NOT EXISTS gender text CONSTRAINT chk_students_gender CHECK (gender IN ('بنين', 'بنات'));
