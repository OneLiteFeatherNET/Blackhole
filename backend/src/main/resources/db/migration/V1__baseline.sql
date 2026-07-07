-- Baseline schema, frozen from the Hibernate-managed schema (hbm2ddl.auto=update) at the
-- point Flyway was introduced. From this point on, schema changes are versioned migrations
-- instead of relying on Hibernate auto-DDL.

CREATE TABLE `tenants` (
  `identifier` uuid NOT NULL,
  `meta_data` longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL CHECK (json_valid(`meta_data`)),
  `name` varchar(255) DEFAULT NULL,
  `slug` varchar(255) DEFAULT NULL,
  `status` tinyint(4) DEFAULT NULL CHECK (`status` between 0 and 1),
  PRIMARY KEY (`identifier`),
  UNIQUE KEY `UKkn82rs0p55luybrg4n7x7di8` (`slug`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_uca1400_ai_ci;

CREATE TABLE `punishment_templates` (
  `identifier` uuid NOT NULL,
  `meta_data` longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL CHECK (json_valid(`meta_data`)),
  `reason` varchar(255) DEFAULT NULL,
  `tenant_id` uuid DEFAULT NULL,
  `type` tinyint(4) DEFAULT NULL CHECK (`type` between 0 and 2),
  PRIMARY KEY (`identifier`),
  KEY `IDXfb0n1048swedl734iua11xgh6` (`identifier`),
  KEY `IDX6sna77t8blfhbf7vw1d949qv7` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_uca1400_ai_ci;

CREATE TABLE `punishments` (
  `identifier` varchar(255) NOT NULL,
  `meta_data` longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL CHECK (json_valid(`meta_data`)),
  `scope` varchar(255) DEFAULT NULL,
  `source` uuid DEFAULT NULL,
  `tenant_id` uuid DEFAULT NULL,
  `type` tinyint(4) DEFAULT NULL CHECK (`type` between 0 and 2),
  `template_identifier` uuid DEFAULT NULL,
  PRIMARY KEY (`identifier`),
  KEY `IDXbg1863u12hbsiqggy1kxv84gj` (`identifier`),
  KEY `IDXf248pwm8h4gskq8r86ohne5bd` (`tenant_id`),
  KEY `FKsystxtartkbg3pelwij5y444y` (`template_identifier`),
  CONSTRAINT `FKsystxtartkbg3pelwij5y444y` FOREIGN KEY (`template_identifier`) REFERENCES `punishment_templates` (`identifier`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_uca1400_ai_ci;

CREATE TABLE `punishment_profiles` (
  `owner` varchar(255) NOT NULL,
  `tenant_id` uuid NOT NULL,
  `meta_data` longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL CHECK (json_valid(`meta_data`)),
  `active_ban_identifier` varchar(255) DEFAULT NULL,
  `active_chat_ban_identifier` varchar(255) DEFAULT NULL,
  PRIMARY KEY (`owner`,`tenant_id`),
  UNIQUE KEY `UKjenhq1urh55q6qbhxbor43mpr` (`active_ban_identifier`),
  UNIQUE KEY `UK3bfpsh5gh8uybcnqn6ist0ju3` (`active_chat_ban_identifier`),
  KEY `IDXs2rqilyfwjja2eh18bvdkgssg` (`owner`),
  CONSTRAINT `FK44vycpq38twfwo4pm864edwtf` FOREIGN KEY (`active_chat_ban_identifier`) REFERENCES `punishments` (`identifier`),
  CONSTRAINT `FKrg26gk6cng7up213jiux4ugap` FOREIGN KEY (`active_ban_identifier`) REFERENCES `punishments` (`identifier`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_uca1400_ai_ci;

CREATE TABLE `punishment_profiles_punishments` (
  `punishment_profile_entity_owner` varchar(255) NOT NULL,
  `punishment_profile_entity_tenant_id` uuid NOT NULL,
  `history_identifier` varchar(255) NOT NULL,
  UNIQUE KEY `UK72hdqv4fhab29f7yg9a80d1nt` (`history_identifier`),
  KEY `FKrwhtrufbtcfrxgociysp1d1iw` (`punishment_profile_entity_owner`,`punishment_profile_entity_tenant_id`),
  CONSTRAINT `FK7yf2av2hp2nfq84philn3588l` FOREIGN KEY (`history_identifier`) REFERENCES `punishments` (`identifier`),
  CONSTRAINT `FKrwhtrufbtcfrxgociysp1d1iw` FOREIGN KEY (`punishment_profile_entity_owner`, `punishment_profile_entity_tenant_id`) REFERENCES `punishment_profiles` (`owner`, `tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_uca1400_ai_ci;
