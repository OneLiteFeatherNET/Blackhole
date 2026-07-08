package net.onelitefeather.blackhole.backend.connector;

import io.micronaut.data.annotation.Repository;
import io.micronaut.data.repository.PageableRepository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface ConnectorRegistrationRepository extends PageableRepository<ConnectorRegistrationEntity, UUID> {

    Optional<ConnectorRegistrationEntity> findByOauth2ClientId(String oauth2ClientId);
}
