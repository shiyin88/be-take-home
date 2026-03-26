-- ============================================================================
-- Stripe payment tables
-- ============================================================================

CREATE TABLE stripe_customers (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    stripe_customer_id VARCHAR(255) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_stripe_customers_tenant FOREIGN KEY (tenant_id) REFERENCES tenants (id),
    CONSTRAINT uq_stripe_customers_tenant UNIQUE (tenant_id)
);

CREATE TABLE saved_cards (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    stripe_payment_method_id VARCHAR(255) NOT NULL,
    last4 VARCHAR(4) NOT NULL,
    brand VARCHAR(50) NOT NULL,
    exp_month INT NOT NULL,
    exp_year INT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_saved_cards_tenant FOREIGN KEY (tenant_id) REFERENCES tenants (id),
    CONSTRAINT uq_saved_cards_pm_id UNIQUE (stripe_payment_method_id)
);

CREATE INDEX idx_saved_cards_tenant_id ON saved_cards (tenant_id);

CREATE TABLE credit_card_payments (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    rent_charge_id BIGINT NOT NULL,
    saved_card_id BIGINT NOT NULL,
    stripe_payment_intent_id VARCHAR(255) NOT NULL,
    amount DECIMAL(10, 2) NOT NULL,
    status VARCHAR(20) NOT NULL,
    failure_reason TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_ccp_rent_charge FOREIGN KEY (rent_charge_id) REFERENCES rent_charges (id),
    CONSTRAINT fk_ccp_saved_card FOREIGN KEY (saved_card_id) REFERENCES saved_cards (id),
    CONSTRAINT chk_ccp_status CHECK (status IN ('INITIATED', 'SUCCEEDED', 'FAILED', 'REFUNDED')),
    CONSTRAINT uq_ccp_payment_intent UNIQUE (stripe_payment_intent_id)
);

CREATE INDEX idx_ccp_rent_charge_id ON credit_card_payments (rent_charge_id);
