package net.onelitefeather.blackhole.velocity.clientrule;

import net.onelitefeather.blackhole.client.invoker.ApiException;
import net.onelitefeather.blackhole.client.model.ClientFingerprintCreateRequest;
import net.onelitefeather.blackhole.client.model.ClientFingerprintResponse;
import net.onelitefeather.blackhole.client.model.ClientRuleCatalogueReportRequest;
import net.onelitefeather.blackhole.client.model.ClientRuleHitCreateRequest;
import net.onelitefeather.blackhole.client.model.ClientRuleHitRecorded;
import net.onelitefeather.blackhole.client.model.ClientRuleResponse;

import java.util.ArrayList;
import java.util.List;

/** A backend the test controls: what it answers is set per test, what was asked is recorded. */
final class FakeClientRuleBackend implements ClientRuleBackend {

    List<ClientRuleResponse> rules = new ArrayList<>();
    ApiException rulesFailure;
    final List<ClientRuleHitCreateRequest> hits = new ArrayList<>();
    ApiException hitFailure;
    final List<ClientFingerprintCreateRequest> fingerprints = new ArrayList<>();
    ApiException fingerprintFailure;
    final List<ClientRuleCatalogueReportRequest> catalogues = new ArrayList<>();
    ApiException catalogueFailure;

    @Override
    public List<ClientRuleResponse> rules() throws ApiException {
        if (this.rulesFailure != null) {
            throw this.rulesFailure;
        }
        return this.rules;
    }

    @Override
    public ClientRuleHitRecorded reportHit(ClientRuleHitCreateRequest request) throws ApiException {
        if (this.hitFailure != null) {
            throw this.hitFailure;
        }
        this.hits.add(request);
        return new ClientRuleHitRecorded().rule(request.getRule());
    }

    @Override
    public ClientFingerprintResponse submitFingerprint(ClientFingerprintCreateRequest request) throws ApiException {
        if (this.fingerprintFailure != null) {
            throw this.fingerprintFailure;
        }
        this.fingerprints.add(request);
        return new ClientFingerprintResponse().stored(true);
    }

    @Override
    public void reportCatalogue(ClientRuleCatalogueReportRequest request) throws ApiException {
        if (this.catalogueFailure != null) {
            throw this.catalogueFailure;
        }
        this.catalogues.add(request);
    }
}
