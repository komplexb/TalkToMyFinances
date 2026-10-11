# ADR 0002: Two-Screen Horizontal Flow (Insights & Tête-à-Tête Chat)

The UI separates proactive exploration from conversational deep-dives into two horizontally swipeable screens:

1. **Insights Screen**: Displays 3 proactive Prompt Chips dynamically curated from the full 6-tier recency spectrum (*This Week*, *This Pay Cycle*, *Last 30 Days*, *This Quarter*, *6 Months*, *Year / YTD*). Tapping any chip primes and routes into the chat.
2. **Tête-à-Tête Chat Screen**: Houses the message thread, inline Visual Cards triggered by `render_visual_card`, and a bottom bar that morphs between text input and a live audio waveform.

This separates initial decision-making from conversational drill-downs while keeping navigation instantaneous via bottom tabs and horizontal swipe gestures.
