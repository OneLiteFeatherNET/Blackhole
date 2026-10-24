package net.onelitefeather.blackhole.velocity.clientrule;

import net.onelitefeather.blackhole.client.api.ClientRuleApi;
import net.onelitefeather.blackhole.client.api.EvasionApi;
import net.onelitefeather.blackhole.client.invoker.ApiClient;
import net.onelitefeather.blackhole.client.invoker.ApiException;
import net.onelitefeather.blackhole.client.model.ClientFingerprintCreateRequest;
import net.onelitefeather.blackhole.client.model.ClientFingerprintResponse;
import net.onelitefeather.blackhole.client.model.ClientRuleCatalogueReportRequest;
import net.onelitefeather.blackhole.client.model.ClientRuleHitCreateRequest;
import net.onelitefeather.blackhole.client.model.ClientRuleHitRecorded;
import net.onelitefeather.blackhole.client.model.ClientRuleResponse;

import java.util.List;

/** {@link ClientRuleBackend} over the generated API client. */
public final class HttpClientRuleBackend implements ClientRuleBackend {

    private final ClientRuleApi ruleApi;
    private final EvasionApi evasionApi;

    public HttpClientRuleBackend(ApiClient client) {
        this.ruleApi = new ClientRuleApi(client);
        this.evasionApi = new EvasionApi(client);
    }

    @Override
    public List<ClientRuleResponse> rules() throws ApiException {
        return this.ruleApi.listClientRules();
    }

    @Override
    public ClientRuleHitRecorded reportHit(ClientRuleHitCreateRequest request) throws ApiException {
        return this.ruleApi.reportClientRuleHit(request);
    }

    @Override
    public ClientFingerprintResponse submitFingerprint(ClientFingerprintCreateRequest request) throws ApiException {
        return this.evasionApi.submitClientFingerprint(request);
    }

    @Override
    public void reportCatalogue(ClientRuleCatalogueReportRequest request) throws ApiException {
        this.ruleApi.reportClientRuleCatalogue(request);
    }
}
