USE rpki_db;

ALTER TABLE rpki_cert
  ADD COLUMN subject_key_id VARCHAR(128) NULL COMMENT 'Subject Key Identifier hex' AFTER parent_cert_id;

ALTER TABLE rpki_cert
  ADD COLUMN authority_key_id VARCHAR(128) NULL COMMENT 'Authority Key Identifier hex' AFTER subject_key_id;
