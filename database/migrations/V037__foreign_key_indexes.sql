-- VYB-0910 (F36): indexes for foreign keys that had none, and for the two purge jobs.
--
-- Why a foreign key wants an index even when nothing selects by it: deleting or re-keying a
-- parent row makes Postgres look for referencing rows, and without an index on the child column
-- that is a scan of the whole child table. The columns below are also the ones the application
-- joins and filters on (a requirement's comments, a flow's nodes, a user's notifications).
--
-- Not indexed on purpose (19 columns, listed in ForeignKeyIndexIT with the reason): the
-- "who did it" columns that point at app_user (granted_by, updated_by, changed_by, decided_by,
-- raised_by, ...). Nothing in the application filters on them and app_user rows are never deleted,
-- so an index there would only slow every write. A test fails if a future foreign key is neither
-- indexed nor on that list.
--
-- A nullable column gets a partial index (WHERE col IS NOT NULL): foreign-key checks and lookups
-- always compare to a value, so they can use it, and it does not carry the NULL rows.
--
-- Plain CREATE INDEX, not CONCURRENTLY: Flyway runs a migration in one transaction and
-- CONCURRENTLY cannot. Each takes a short write lock on its table while it builds; these tables are
-- small relative to the time that takes. Run during a quiet period on a large database.

CREATE INDEX app_user_delegate_id_idx ON app_user (delegate_id) WHERE delegate_id IS NOT NULL;
CREATE INDEX app_user_manager_id_idx ON app_user (manager_id) WHERE manager_id IS NOT NULL;
CREATE INDEX product_owner_id_idx ON product (owner_id) WHERE owner_id IS NOT NULL;
CREATE INDEX capability_owner_id_idx ON capability (owner_id) WHERE owner_id IS NOT NULL;
CREATE INDEX glossary_term_owner_id_idx ON glossary_term (owner_id) WHERE owner_id IS NOT NULL;
CREATE INDEX requirement_created_by_idx ON requirement (created_by) WHERE created_by IS NOT NULL;
CREATE INDEX requirement_developer_id_idx ON requirement (developer_id) WHERE developer_id IS NOT NULL;
CREATE INDEX requirement_tester_id_idx ON requirement (tester_id) WHERE tester_id IS NOT NULL;
CREATE INDEX requirement_comment_requirement_id_idx ON requirement_comment (requirement_id);
CREATE INDEX attachment_requirement_id_idx ON attachment (requirement_id);
CREATE INDEX clarification_assigned_to_idx ON clarification (assigned_to) WHERE assigned_to IS NOT NULL;
CREATE INDEX clarification_requirement_id_idx ON clarification (requirement_id);
CREATE INDEX design_node_flow_id_idx ON design_node (flow_id);
CREATE INDEX design_edge_flow_id_idx ON design_edge (flow_id);
CREATE INDEX design_edge_to_node_idx ON design_edge (to_node);
CREATE INDEX design_node_requirement_requirement_id_idx ON design_node_requirement (requirement_id);
CREATE INDEX review_item_requirement_id_idx ON review_item (requirement_id);
CREATE INDEX review_participant_user_id_idx ON review_participant (user_id);
CREATE INDEX verification_test_case_id_idx ON verification (test_case_id) WHERE test_case_id IS NOT NULL;
CREATE INDEX verification_test_run_id_idx ON verification (test_run_id) WHERE test_run_id IS NOT NULL;
CREATE INDEX defect_developer_id_idx ON defect (developer_id) WHERE developer_id IS NOT NULL;
CREATE INDEX defect_requirement_id_idx ON defect (requirement_id) WHERE requirement_id IS NOT NULL;
CREATE INDEX defect_tester_id_idx ON defect (tester_id) WHERE tester_id IS NOT NULL;
CREATE INDEX brief_application_id_idx ON brief (application_id);
CREATE INDEX brief_developer_id_idx ON brief (developer_id) WHERE developer_id IS NOT NULL;
CREATE INDEX brief_requirement_requirement_id_idx ON brief_requirement (requirement_id);
CREATE INDEX release_scope_item_requirement_id_idx ON release_scope_item (requirement_id);
CREATE INDEX scope_movement_release_id_idx ON scope_movement (release_id);
CREATE INDEX scope_movement_requirement_id_idx ON scope_movement (requirement_id);
CREATE INDEX baseline_release_id_idx ON baseline (release_id) WHERE release_id IS NOT NULL;
CREATE INDEX baseline_item_requirement_id_idx ON baseline_item (requirement_id);
CREATE INDEX variant_applicability_requirement_id_idx ON variant_applicability (requirement_id);
CREATE INDEX deployment_environment_id_idx ON deployment (environment_id);
CREATE INDEX deployment_requirement_requirement_id_idx ON deployment_requirement (requirement_id);
CREATE INDEX document_product_id_idx ON document (product_id) WHERE product_id IS NOT NULL;
CREATE INDEX import_batch_application_id_idx ON import_batch (application_id) WHERE application_id IS NOT NULL;
CREATE INDEX import_candidate_application_id_idx ON import_candidate (application_id) WHERE application_id IS NOT NULL;
CREATE INDEX import_candidate_batch_id_idx ON import_candidate (batch_id);
CREATE INDEX import_candidate_capability_id_idx ON import_candidate (capability_id) WHERE capability_id IS NOT NULL;
CREATE INDEX import_candidate_committed_requirement_id_idx ON import_candidate (committed_requirement_id) WHERE committed_requirement_id IS NOT NULL;
CREATE INDEX import_candidate_product_id_idx ON import_candidate (product_id) WHERE product_id IS NOT NULL;
CREATE INDEX notification_user_id_idx ON notification (user_id);
CREATE INDEX glossary_term_usage_application_id_idx ON glossary_term_usage (application_id);
CREATE INDEX review_comment_requirement_id_idx ON review_comment (requirement_id) WHERE requirement_id IS NOT NULL;
CREATE INDEX change_request_requirement_requirement_id_idx ON change_request_requirement (requirement_id);
CREATE INDEX document_requirement_requirement_id_idx ON document_requirement (requirement_id);
CREATE INDEX team_member_user_id_idx ON team_member (user_id);
CREATE INDEX saved_view_capability_id_idx ON saved_view (capability_id) WHERE capability_id IS NOT NULL;
CREATE INDEX brief_capability_capability_id_idx ON brief_capability (capability_id);

-- The purge jobs (PurgeService) delete by age. Without these the daily DELETE is a scan.
CREATE INDEX idempotency_key_created_at_idx   ON idempotency_key (created_at);
CREATE INDEX webhook_delivery_received_at_idx ON webhook_delivery (received_at);
