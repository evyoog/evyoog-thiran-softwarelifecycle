-- VYB-0945 (F20): a person can drag a step to where it reads best, and the place is saved on the step so everyone sees the same
-- picture and the exports match. Both null means "no saved place": the automatic layout puts it. Both are set together.
ALTER TABLE design_node ADD COLUMN x INT;
ALTER TABLE design_node ADD COLUMN y INT;
ALTER TABLE design_node ADD CONSTRAINT design_node_position_both_or_neither CHECK ((x IS NULL) = (y IS NULL));
ALTER TABLE design_node ADD CONSTRAINT design_node_position_range CHECK (x BETWEEN 0 AND 100000 AND y BETWEEN 0 AND 100000);
