"""Loads LLM prompts from the repo-root prompts/*.prompt template files.

The .prompt format — YAML frontmatter (model / tools / input metadata) plus a
Jinja2 body with {{variables}} — matches the app's existing prompt templates
(see prompts/). Prompts are edited and versioned as content files; Python
source only supplies the variables and the API call.
"""

import re
from pathlib import Path
from typing import Any, Dict, Tuple

import yaml
from jinja2 import Environment, StrictUndefined, TemplateError

# backend/auditor/ -> backend/ -> repo root (prompts/ lives at the repo root).
PROMPTS_DIR = Path(__file__).resolve().parents[2] / "prompts"

# StrictUndefined: a variable the template expects but the caller omitted
# renders as an error instead of a silently-blank prompt.
_env = Environment(undefined=StrictUndefined, autoescape=False, keep_trailing_newline=True)

_FRONTMATTER = re.compile(r"\A---\s*\n(.*?)\n---\s*\n?(.*)\Z", re.DOTALL)


class PromptTemplateError(Exception):
    """Raised when a .prompt file is missing or malformed."""


def load_prompt(name: str) -> Tuple[Dict[str, Any], str]:
    """Returns (frontmatter metadata, template body) for prompts/<name>.prompt.

    A file without frontmatter is valid: metadata is just {}.
    """
    path = PROMPTS_DIR / f"{name}.prompt"
    try:
        raw = path.read_text(encoding="utf-8")
    except OSError as e:
        raise PromptTemplateError(f"Prompt template '{name}' not found at {path}") from e

    match = _FRONTMATTER.match(raw)
    if match is None:
        return {}, raw
    try:
        metadata = yaml.safe_load(match.group(1)) or {}
    except yaml.YAMLError as e:
        raise PromptTemplateError(f"Prompt template '{name}' has invalid YAML frontmatter: {e}") from e
    return metadata, match.group(2)


def render_prompt(name: str, variables: Dict[str, str]) -> str:
    """Loads prompts/<name>.prompt and renders its body with the given variables.

    The body's trailing newline is stripped: the inline prompts it replaced
    never had one.
    """
    _, body = load_prompt(name)
    try:
        return _env.from_string(body).render(**variables).rstrip()
    except TemplateError as e:  # includes UndefinedError (missing variable)
        raise PromptTemplateError(f"Prompt template '{name}' failed to render: {e}") from e
