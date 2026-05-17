ALTER TABLE terminals
ADD COLUMN IF NOT EXISTS phone VARCHAR(30) NULL;

UPDATE terminals
SET phone = CASE id
    WHEN 1 THEN '+355 4 220 0001'
    WHEN 2 THEN '+355 4 220 0002'
    WHEN 3 THEN '+355 52 220 003'
    WHEN 4 THEN '+355 22 220 004'
    WHEN 5 THEN '+355 33 220 005'
    WHEN 6 THEN '+355 84 220 006'
    WHEN 7 THEN '+355 82 220 007'
    WHEN 8 THEN '+355 54 220 008'
    ELSE CONCAT('+355 4 220 ', LPAD(id, 4, '0'))
END
WHERE phone IS NULL OR phone = '';

CREATE TABLE IF NOT EXISTS contact_messages (
    id INT AUTO_INCREMENT PRIMARY KEY,
    passenger_id INT NOT NULL,
    operator_id INT NOT NULL,
    subject VARCHAR(150) NOT NULL,
    message VARCHAR(1000) NOT NULL,
    reply VARCHAR(1000) NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'open',
    operator_deleted BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    replied_at TIMESTAMP NULL,
    INDEX idx_contact_passenger (passenger_id),
    INDEX idx_contact_operator (operator_id),
    CONSTRAINT fk_contact_passenger FOREIGN KEY (passenger_id) REFERENCES users(id),
    CONSTRAINT fk_contact_operator FOREIGN KEY (operator_id) REFERENCES users(id)
);

ALTER TABLE contact_messages
ADD COLUMN IF NOT EXISTS operator_deleted BOOLEAN NOT NULL DEFAULT FALSE;
