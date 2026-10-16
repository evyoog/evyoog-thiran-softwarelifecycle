-- VYB-0922 (D53): the git and CI connections are fed through the service-account endpoints (POST /api/v1/ci/commits, /test-runs,
-- /deployments), not through the signed-webhook route that records a delivery, so nothing showed that they were in use: Connector health read
-- "never succeeded" for a system that had been sending for weeks. last_inbound_at is when something last arrived through such an endpoint.
-- It is written by the endpoint after the data was accepted, and the same write marks the connection connected (it demonstrably is).
ALTER TABLE integration_connection ADD COLUMN last_inbound_at TIMESTAMPTZ;
