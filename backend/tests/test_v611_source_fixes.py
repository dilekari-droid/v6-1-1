from pathlib import Path

REPO = Path(__file__).resolve().parents[2]


def test_user_visible_bist_progress_uses_common_policy():
    opportunity = (REPO / "android/app/src/main/java/tr/borsatakip/v5/ui/OpportunityActivity.kt").read_text(encoding="utf-8")
    service = (REPO / "android/app/src/main/java/tr/borsatakip/v5/worker/BistScanForegroundService.kt").read_text(encoding="utf-8")
    assert "BistScanUiPolicy.progressPercent(session)" in opportunity
    assert "BistScanUiPolicy.progressPercent(session)" in service
    assert "%${session.progress}" not in opportunity
    assert "%${session.progress}" not in service
