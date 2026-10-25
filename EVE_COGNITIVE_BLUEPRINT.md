# EVE — Custom Local Cognition Blueprint

## Design principle

EVE is not one prompt pasted onto a generic assistant. It is a local-first character architecture with distinct perception, belief, memory, drive, dialogue, and expression subsystems. A small generative model may phrase an answer, but it must not be the sole authority for facts, device actions, or the character's affect state.

## Runtime architecture

```text
Camera / microphone / touch / optional robot-base telemetry
                         |
                         v
          Perception adapters (on-device models)
                         |
                         v
        Evidence envelope: source + confidence + age
                         |
                         v
             Local belief & uncertainty checks
                         |
                         v
       Cognitive arbitration / attention & goal choice
                |                    |
                v                    v
     Affect + personality       Local memory retrieval
                |                    |
                +---------+----------+
                          v
                 Dialogue / reflection
               (bundled local language model)
                          |
                          v
             Policy and safety executive
              /          |             \
          eye/voice     memory        base motion
```

## What is now added to the source

### Custom cognition arbitration

`eve-project/app/src/main/java/com/eve/robot/EveCognitionCore.kt` introduces a deterministic decision layer that computes novelty, visual salience, uncertainty, information value, and question priority. It can choose to ask a question, observe quietly, wait for better evidence, suppress repetition, or ignore generic visual noise.

The layer is integrated into `MainActivity.maybeAskFromCuriosity`; it is not an unused sample. It adds guardrails before the existing curiosity question engine. A recent interaction takes priority, confidence must clear a threshold, and existing global/per-label cooldowns remain enforced.

### Unit tests

`eve-project/app/src/test/java/com/eve/robot/EveCognitionCoreTest.kt` checks confident novel observations, weak evidence, generic labels, active conversation, cooldowns, reduced novelty on repeated labels, clock reversal, and non-finite confidence.

The GitHub build workflow was changed to run `testDebugUnitTest` before `assembleDebug`. That change requests test execution; it does not itself prove the tests passed. Confirm the actual Actions result before distributing an APK.

## Existing components the design builds upon

- `RobotBrain.kt`: bounded affect state and goal selection.
- `EveLocalDialogue.kt`: offline fallback answers and contextual curiosity prompts.
- `EveMemory`: local dialogue, reflection, recurrent label, and personality memories.
- `EvePeopleMemory` / `EveFaceIdentityMemory`: separate relationship and identity memory paths.
- `LocalLanguageBrain.kt`: local generative language model integration.
- The camera and on-device perception adapters: object labels, face cues, gestures, pose/activity hypotheses, and room clues.
- `RobotFaceView.kt` and `RobotVoice`: expressive output.

These pieces do not all have the same capability level. A camera label is not full scene understanding; repeated sightings are not verified identity; a saved reflection is not model-weight training.

## Design rules

1. **Evidence before assertion.** Every visual conclusion should respect confidence and freshness. A stale label must not be treated as the current scene.
2. **Separate category from identity.** Seeing a generic “person” label cannot identify a family member. Identity requires the dedicated local identity path and a reliable match.
3. **Quiet attention is a valid action.** EVE should not turn every detection into speech. User conversation, low-confidence evidence, repeated questions, and low-value labels suppress autonomous interruptions.
4. **One affect state.** Vision, touch, conversation, idle needs, and game results update the same bounded character state. Language-model mood hints are advisory, not commands.
5. **Memory is not retraining.** Saving labels, dialogue, reflection, and user feedback changes retrieval/personalization; it does not by itself update the language model's weights.
6. **Local by default.** Inference and user memories should stay on-device. A build that contains a model is not enough: actual inference must be verified with network disabled.
7. **Motion fails safe.** Any future physical base must stop on stale telemetry, missing pose, uncertain localization, edge/obstacle risk, or loss of the control link. A phone camera alone cannot certify that a desk edge is safe.
8. **No claim without a test.** Build success, APK asset presence, model initialization, offline inference, perception quality, and real-phone performance are separate milestones.

## Evaluation gates before claiming EVE is improved

- **Build:** Gradle unit tests and APK packaging both pass.
- **Offline:** put the phone in airplane mode after installation; confirm model initialization and several new local generations without runtime downloads.
- **Dialogue:** a curated Arabic test set checks context retention, direct answers, varied phrasing, and no exposure of internal reflection.
- **Vision:** test people vs. dogs, common objects, difficult lighting, partial views, and low-confidence/unknown cases. Record false-positive and false-negative rates instead of relying on a few demos.
- **Curiosity:** check novelty, contextual relevance, no duplicate interruptions, and respect for user interaction.
- **Identity/privacy:** no identity claim from a generic object classifier; verify the local identity path and confirm saved identity data remains private.
- **Performance:** test on the target Redmi 12 for cold start, RAM pressure, heat, battery use, camera responsiveness, and inference latency.
- **Robot base (future):** hardware-in-the-loop tests for emergency stop, stale sensor data, obstacle avoidance, edge protection, and Bluetooth disconnect before any autonomous movement.

## Honest scope

This architecture can be specifically engineered for EVE and tested locally. It does not imply that a new foundation model has been trained from scratch or that EVE has human consciousness. A claim that it outperforms Claude requires Claude's actual source/model and a shared, reproducible evaluation set. Until then, “stronger” should mean measurable gains on the target tasks, not a slogan.
