import os
import uuid
from pathlib import Path

from fastapi.testclient import TestClient

import main

client = TestClient(main.app)


def test_health_reports_missing_ai_key(monkeypatch):
    monkeypatch.delenv("OPENAI_API_KEY", raising=False)
    response = client.get("/")
    assert response.status_code == 200
    assert response.json()["ai_ready"] is False
    assert response.json()["ffmpeg_ready"] is True


def test_process_refuses_to_fake_ai_when_key_is_missing(monkeypatch):
    monkeypatch.delenv("OPENAI_API_KEY", raising=False)
    response = client.post(
        "/process",
        files={"file": ("sample.mp4", b"not a real video", "video/mp4")},
        data={"instruction": "cari highlight", "clip_count": "1", "max_duration": "20"},
    )
    assert response.status_code == 503
    assert "OPENAI_API_KEY" in response.json()["detail"]


def test_srt_uses_real_line_breaks(tmp_path):
    target = tmp_path / "captions.srt"
    main.write_srt([{"start": 1.0, "end": 2.5, "text": "Halo dunia"}], 0.0, 3.0, target)
    content = target.read_text(encoding="utf-8")
    assert "\n" in content
    assert "\\n" not in content
    assert "00:00:01,000 --> 00:00:02,500" in content



def test_process_rejects_unsupported_extension(monkeypatch):
    monkeypatch.setenv("OPENAI_API_KEY", "test-key")
    response = client.post(
        "/process",
        files={"file": ("payload.exe", b"not a video", "video/mp4")},
        data={"instruction": "buat highlight", "clip_count": "1", "max_duration": "20"},
    )
    assert response.status_code == 415
    assert "Format video" in response.json()["detail"]


def test_probe_video_rejects_invalid_media(tmp_path):
    bad_file = tmp_path / "bad.mp4"
    bad_file.write_bytes(b"not a video")
    try:
        main.probe_video(bad_file)
    except RuntimeError as exc:
        assert "valid" in str(exc).lower() or "metadata" in str(exc).lower()
    else:
        raise AssertionError("Invalid media must be rejected")
