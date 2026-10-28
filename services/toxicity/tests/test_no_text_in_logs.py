import json
import logging

from toxicity_service.api import handle

SENTINEL = "SENTINEL-chat-text-7f3a9c"


def test_scoring_call_logs_metadata_but_never_the_message_text(fake_model, caplog):
    caplog.set_level(logging.DEBUG)
    body = json.dumps({"texts": [f"hello {SENTINEL} world"]}).encode("utf-8")

    status, _ = handle(fake_model, "POST", "/v1/score", body)

    assert status == 200, "the scoring call must succeed so that the logs are produced"
    scoring_records = [record for record in caplog.records if record.name == "toxicity"]
    assert scoring_records, "the scoring call must log its batch metadata, or this test proves nothing"
    assert SENTINEL not in caplog.text, "message text must never appear in the service logs"
    for record in caplog.records:
        assert SENTINEL not in record.getMessage(), f"message text leaked into log record {record.name}"
