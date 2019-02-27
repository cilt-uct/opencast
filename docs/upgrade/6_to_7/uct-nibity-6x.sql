CREATE TABLE `oc_nibity_transcript_job` (
  `id` bigint(20) NOT NULL,
  `media_package_id` varchar(128) NOT NULL,
  `track_id` varchar(128) NOT NULL,
  `job_id` varchar(128) NOT NULL,
  `date_created` datetime NOT NULL,
  `date_completed` datetime DEFAULT NULL,
  `status` varchar(128) DEFAULT NULL,
  `track_duration` bigint(20) NOT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8

