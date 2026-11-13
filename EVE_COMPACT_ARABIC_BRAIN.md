# EVE Hybrid Local Arabic Brain

## Architecture

EVE now has two local language layers:

1. **Generative layer:** Qwen3-0.6B, quantized to a LiteRT-LM `.litertlm` artifact and executed on-device through Google's LiteRT-LM Android runtime (CPU backend).
2. **Compact fallback:** the project's Arabic intent classifier and grounded response composer remain available when the generative weights have not yet been installed or if inference fails.

The generative model is used for open-ended Arabic conversation and contextual game commentary. EVE supplies bounded context from her local memory, current personality and mood, recent dialogue, and only fresh visual observations. The model is instructed not to invent visual evidence, identities, or memories.

## Installation and offline behavior

- The Android APK includes the LiteRT-LM runtime and EVE integration code, but **does not bundle the 329 MiB language-model weights**.
- On first launch, tap the existing status chip and explicitly approve the one-time Qwen3 model download. The model is stored in the app-private files directory.
- Internet permission is used only for that user-approved download. After the model is stored, text generation runs locally and does not send conversation text to a cloud service.
- If the download is skipped or fails, EVE continues using the compact offline Arabic fallback.
- The selected model is the INT4 `Qwen3-0.6B_dynamic_wi4b32_afp32.litertlm` artifact. LiteRT-LM 0.19.x is used because this export uses external weights and newer runtime support.
- The model file is separate from the APK, so it adds roughly 329 MiB of one-time device storage and does not inflate the install package by that amount.

## Compact Arabic fallback

The fallback remains project-specific and runs entirely in Kotlin:
- Arabic normalization and dialect spelling normalization.
- Hashed word, character n-gram, and adjacent-word features for supported intent classification.
- Context-grounded response composition from bounded memory, mood, personality, and fresh visual evidence.
- Honest uncertainty for unsupported questions.

This fallback is not a generative foundation model. The Qwen3 layer is the actual generative language model.

## Limits and verification

- The build and unit/instrumentation tests can verify that the LiteRT-LM runtime and integration compile and that the compact fallback works with Wi-Fi/mobile data disabled.
- Those tests do **not** prove that Qwen3 has been downloaded or that generation has been exercised on a Redmi 12.
- The first real-model smoke test must happen on the target phone after the one-time download. Memory use, response speed, heat, and battery impact depend on the Redmi 12 variant and available RAM.
- The LLM is text-only. It does not replace EVE's existing vision, speech recognition, game rules, local memory, or personality systems.
