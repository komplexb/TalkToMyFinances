import os

# Centralized Gemini Model Version Configurations (matching prompts/*.prompt definitions)
CURATION_MODEL_NAME = os.getenv("CURATION_MODEL_NAME", "gemini-3.5-flash-lite")
PARSER_MODEL_NAME = os.getenv("PARSER_MODEL_NAME", "gemini-3.5-flash-lite")
