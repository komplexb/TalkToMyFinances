import pytest

from auditor import prompt_templates
from auditor.prompt_templates import PromptTemplateError, load_prompt, render_prompt


# ---------------------------------------------------------------------------
# render_prompt
# ---------------------------------------------------------------------------

def test_render_prompt_fills_all_variables():
    rendered = render_prompt(
        "intent-parser-template-v1-0-0",
        {"query": "my question", "merchants": '["UBER"]', "categories": '["Dining"]'},
    )
    assert 'Query: "my question"' in rendered
    assert "merchants: [\"UBER\"]" in rendered
    assert "categories: [\"Dining\"]" in rendered
    assert rendered.endswith("Return ONLY the JSON object.")


def test_render_prompt_missing_variable_raises():
    with pytest.raises(PromptTemplateError):
        render_prompt(
            "intent-parser-template-v1-0-0",
            {"query": "x"},  # merchants/categories omitted
        )


def test_render_curation_prompt():
    rendered = render_prompt(
        "curation-template-v1-0-0",
        {"goal": "SAVINGS", "catalog": "[{\"id\": 1}]"},
    )
    assert "Given the user's financial goal 'SAVINGS'" in rendered
    assert rendered.count("'SAVINGS'") == 2  # goal appears in two sentences
    assert "[{\"id\": 1}]" in rendered
    assert rendered.endswith("keys 'text', 'recency', 'isSerendipity'.")


def test_render_prompt_missing_file_raises():
    with pytest.raises(PromptTemplateError):
        render_prompt("no-such-template", {"query": "x"})


# ---------------------------------------------------------------------------
# load_prompt
# ---------------------------------------------------------------------------

def test_load_prompt_returns_frontmatter_and_body():
    metadata, body = load_prompt("intent-parser-template-v1-0-0")
    assert metadata["model"] == "gemini-3.5-flash-lite"
    assert "{{query}}" in body
    assert "---" not in body


def test_load_prompt_without_frontmatter(monkeypatch, tmp_path):
    (tmp_path / "plain.prompt").write_text("Hello {{name}}", encoding="utf-8")
    monkeypatch.setattr(prompt_templates, "PROMPTS_DIR", tmp_path)
    metadata, body = load_prompt("plain")
    assert metadata == {}
    assert body == "Hello {{name}}"
