-- IF NOT EXISTS supports adoption of the original manually applied staging tables.
CREATE TABLE IF NOT EXISTS core_ingest.customer (
    batch_date DATE NOT NULL,
    source_file TEXT NOT NULL,
    source_line BIGINT NOT NULL CHECK (source_line > 0),
    customer_id BIGINT NOT NULL CHECK (customer_id > 0),
    first_name VARCHAR(10) NOT NULL,
    last_name VARCHAR(10) NOT NULL,
    address_line1 VARCHAR(20) NOT NULL,
    city VARCHAR(10) NOT NULL,
    province VARCHAR(2) NOT NULL,
    postal_code VARCHAR(7) NOT NULL,
    country VARCHAR(10) NOT NULL,
    PRIMARY KEY (batch_date, source_line)
);
CREATE TABLE IF NOT EXISTS core_ingest.account (
    batch_date DATE NOT NULL,
    source_file TEXT NOT NULL,
    source_line BIGINT NOT NULL CHECK (source_line > 0),
    account_id BIGINT NOT NULL CHECK (account_id > 0),
    start_date DATE NOT NULL,
    end_date DATE,
    account_type VARCHAR(8) NOT NULL CHECK (account_type IN ('SAVINGS', 'CHECKING')),
    PRIMARY KEY (batch_date, source_line)
);
CREATE TABLE IF NOT EXISTS core_ingest.relationship (
    batch_date DATE NOT NULL,
    source_file TEXT NOT NULL,
    source_line BIGINT NOT NULL CHECK (source_line > 0),
    account_id BIGINT NOT NULL CHECK (account_id > 0),
    customer_id BIGINT NOT NULL CHECK (customer_id > 0),
    type VARCHAR(9) NOT NULL CHECK (type IN ('PRIMARY', 'SECONDARY')),
    PRIMARY KEY (batch_date, source_line)
);
CREATE TABLE IF NOT EXISTS core_ingest.transaction (
    batch_date DATE NOT NULL,
    source_file TEXT NOT NULL,
    source_line BIGINT NOT NULL CHECK (source_line > 0),
    transaction_id BIGINT NOT NULL CHECK (transaction_id > 0),
    account_id BIGINT NOT NULL CHECK (account_id > 0),
    type VARCHAR(6) NOT NULL CHECK (type IN ('CR', 'DR')),
    amount BIGINT NOT NULL CHECK (amount >= 0),
    PRIMARY KEY (batch_date, source_line)
);
