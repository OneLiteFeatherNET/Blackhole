import json

from toxicity_service.api import handle


def _score(model, texts):
    body = json.dumps({"texts": texts}).encode("utf-8")
    return handle(model, "POST", "/v1/score", body)


def test_batch_of_three_texts_returns_three_results(fake_model):
    status, payload = _score(fake_model, ["first message", "second", "third one"])

    assert status == 200, f"expected 200 for a valid batch, got {status}"
    assert len(payload["results"]) == 3, f"expected one result per text, got {len(payload['results'])}"


def test_every_overall_score_is_within_unit_interval(fake_model):
    _, payload = _score(fake_model, ["a", "b" * 250, "c" * 500])

    for result in payload["results"]:
        assert 0.0 <= result["overall"] <= 1.0, f"score {result['overall']} is outside [0, 1]"


def test_empty_batch_is_refused_with_400(fake_model):
    status, payload = _score(fake_model, [])

    assert status == 400, f"an empty batch must be refused with 400, got {status}"
    assert "error" in payload, "a refused batch must say why in an error field"
