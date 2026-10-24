package net.onelitefeather.blackhole.velocity.clientrule;

import net.onelitefeather.blackhole.client.invoker.ApiException;
import net.onelitefeather.blackhole.client.model.ClientFingerprintCreateRequest;
import net.onelitefeather.blackhole.client.model.ClientFingerprintResponse;
import net.onelitefeather.blackhole.client.model.ClientRuleCatalogueReportRequest;
import net.onelitefeather.blackhole.client.model.ClientRuleHitCreateRequest;
import net.onelitefeather.blackhole.client.model.ClientRuleHitRecorded;
import net.onelitefeather.blackhole.client.model.ClientRuleResponse;

import java.util.List;

/**
 * The backend calls the client rules need, in the proxy's terms. Kept apart from the staff command
 * gateway so the rule code and its tests depend on four methods only. Every call is allowed to fail:
 * the proxy fails open.
 */
public interface ClientRuleBackend {

    /** @return every rule, enabled or not; an {@link ApiException} with code 404 means the backend does not know the routes yet */
    List<ClientRuleResponse> rules() throws ApiException;

    /** Reports a hit. The backend stores it without any trait and applies a TEMPLATE rule's template. */
    ClientRuleHitRecorded reportHit(ClientRuleHitCreateRequest request) throws ApiException;

    /** Submits the client traits of a player for the fingerprint; 503 means fingerprinting is off on the backend. */
    ClientFingerprintResponse submitFingerprint(ClientFingerprintCreateRequest request) throws ApiException;

    /** Replaces this proxy's catalogue snapshot. */
    void reportCatalogue(ClientRuleCatalogueReportRequest request) throws ApiException;
}
