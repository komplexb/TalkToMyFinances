import os

# Centralized Gemini Model Version Configurations (matching prompts/*.prompt definitions)
CURATION_MODEL_NAME = os.getenv("CURATION_MODEL_NAME", "gemini-3.5-flash-lite")
PARSER_MODEL_NAME = os.getenv("PARSER_MODEL_NAME", "gemini-3.5-flash-lite")

# Feature flag to return deterministic Golden Demo Chips for MINDFUL_SPENDING during the hackathon demo.
# Set ENABLE_GOLDEN_DEMO_CHIPS=false post-hackathon to route all goals through live LLM curation.
ENABLE_GOLDEN_DEMO_CHIPS = os.getenv("ENABLE_GOLDEN_DEMO_CHIPS", "true").strip().lower() in ("1", "true", "yes")

