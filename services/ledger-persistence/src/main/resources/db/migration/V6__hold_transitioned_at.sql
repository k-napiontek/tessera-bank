-- V6 - when a hold stopped being a hold.
--
-- Follow-up F-21. Hold.capture, .release and .expire each took an Instant, validated it and threw it
-- away: the new aggregate was rebuilt from placed_at, so a released hold could not say when it was
-- released, and this adapter had to pass a value it knew was ignored in order to rebuild one. The
-- audit chain WP-09 adds records when a thing happened, and the placement instant is not an answer
-- to that question.
--
-- Nullable, because a hold that is still PLACED has not transitioned. The constraint below makes
-- "null" and "still placed" the same statement rather than two that can disagree - the same shape as
-- hold_captured_by_consistent in V2.

ALTER TABLE hold
    ADD COLUMN transitioned_at timestamptz NULL;

-- Rows written before this migration carry no record of when they transitioned, and inventing one
-- would be worse than admitting it. placed_at is the only instant the row actually holds, so a
-- pre-existing captured or released hold reports the moment it was placed. New rows carry the truth.
UPDATE hold
   SET transitioned_at = placed_at
 WHERE status <> 'PLACED'
   AND transitioned_at IS NULL;

ALTER TABLE hold
    ADD CONSTRAINT hold_transitioned_at_consistent
        CHECK ((status = 'PLACED') = (transitioned_at IS NULL));

-- A hold cannot end before it began. The domain refuses it too; this is the copy that survives a
-- second writer arriving at this table, which is the only kind of check a bank relies on.
ALTER TABLE hold
    ADD CONSTRAINT hold_transitioned_after_placed
        CHECK (transitioned_at IS NULL OR transitioned_at >= placed_at);
