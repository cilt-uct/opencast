DROP TABLE oc_scheduled_transaction;
DROP TABLE oc_scheduled_extended_event;
CREATE TABLE oc_scheduled_extended_event (
  mediapackage_id VARCHAR(128) NOT NULL,
  organization VARCHAR(128) NOT NULL,
  capture_agent_id VARCHAR(128) NOT NULL,
  start_date DATETIME NOT NULL,
  end_date DATETIME NOT NULL,
  source VARCHAR(255),
  recording_state VARCHAR(255),
  recording_last_heard BIGINT,
  review_status VARCHAR(128),
  review_date DATETIME,
  presenters TEXT(65535),
  optout TINYINT(1),
  last_modified_origin VARCHAR(255),
  last_modified_date DATETIME,
  checksum VARCHAR(64),
  capture_agent_properties MEDIUMTEXT,
  workflow_properties MEDIUMTEXT,
  PRIMARY KEY (mediapackage_id, organization),
  CONSTRAINT FK_oc_scheduled_extended_event_organization FOREIGN KEY (organization) REFERENCES oc_organization (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8;
CREATE INDEX IX_oc_scheduled_extended_event_organization ON oc_scheduled_extended_event (organization);
CREATE INDEX IX_oc_scheduled_extended_event_capture_agent_id ON oc_scheduled_extended_event (capture_agent_id);
CREATE INDEX IX_oc_scheduled_extended_event_dates ON oc_scheduled_extended_event (start_date, end_date);
DELETE FROM oc_assets_properties WHERE namespace = 'org.opencastproject.scheduler.trx';
