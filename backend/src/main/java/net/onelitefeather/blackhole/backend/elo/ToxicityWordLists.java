package net.onelitefeather.blackhole.backend.elo;

import io.micronaut.context.annotation.ConfigurationProperties;

import java.util.HashMap;
import java.util.Map;

/**
 * The {@code blackhole.elo.chat.toxicity.*} word lists and weights. The intent of each key is documented
 * next to its default in {@code application.yml}.
 */
@ConfigurationProperties("blackhole.elo.chat.toxicity")
public class ToxicityWordLists {

    private Map<String, String> terms = new HashMap<>();
    private String allowList = "";
    private Map<String, Double> weights = new HashMap<>();

    public Map<String, String> getTerms() {
        return terms;
    }

    public void setTerms(Map<String, String> terms) {
        this.terms = terms;
    }

    public String getAllowList() {
        return allowList;
    }

    public void setAllowList(String allowList) {
        this.allowList = allowList;
    }

    public Map<String, Double> getWeights() {
        return weights;
    }

    public void setWeights(Map<String, Double> weights) {
        this.weights = weights;
    }
}
