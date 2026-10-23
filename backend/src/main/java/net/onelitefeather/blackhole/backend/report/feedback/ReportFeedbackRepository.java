package net.onelitefeather.blackhole.backend.report.feedback;

import io.micronaut.data.annotation.Repository;
import io.micronaut.data.repository.CrudRepository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ReportFeedbackRepository extends CrudRepository<ReportFeedbackEntity, UUID> {

    /** The messages of the reporter that have not expired, oldest first. */
    List<ReportFeedbackEntity> findByReporterHashAndExpiresAtGreaterThanOrderByCreatedAt(String reporterHash, long now);

    /** Deletes the expired messages. */
    long deleteByExpiresAtLessThanEquals(long now);
}
