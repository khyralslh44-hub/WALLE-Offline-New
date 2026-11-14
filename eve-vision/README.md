# EVE private vision student (first training stage)

This is a separate pipeline from the Arabic language-model workflow.

## What this stage trains
A compact multi-label image classifier with randomly initialized weights. During
training only, two independent teacher models provide soft labels:
- YOLOv8n for object categories.
- OpenAI CLIP ViT-B/32 for scene/context tags such as snow, rain, indoor rooms,
  outdoor settings, and a few broad activity-like visual contexts.
- YOLOv8n-pose for coarse human-presence, visible-pose, and raised-arm cues.

The student is exported to TensorFlow Lite. Teacher weights and teacher runtimes
are not included in the student artifact or Android app.

## Important scope limits
- The student predicts image-level tags; it does not output bounding boxes.
  EVE's existing EfficientDet-Lite0 detector remains responsible for boxes.
- Face identity must remain a separate on-device embedding + local-memory feature.
- Activities such as eating or exercising require repeated frames and task-specific
  evidence; raised arms do not prove intent, emotion, or a specific activity.
- Pose tags are distilled from a pose teacher and remain approximate.
- Weather labels are visual hypotheses, not weather forecasts or guaranteed facts.
- This is an initial student, not a claim of human-level visual understanding.

## Run
Open GitHub Actions → **Train EVE private vision student** → Run workflow.
The defaults use up to 128 COCO128 images and up to 18 epochs. This is a small starter dataset for the first training run, not a large-scale vision model. The workflow
downloads the 128-image COCO128 starter dataset from the Ultralytics GitHub release, labels them with the teacher
ensemble, trains the student from random initialization, and uploads a TFLite
model plus metadata as an Actions artifact.

The current vision teacher ensemble uses YOLOv8n, CLIP ViT-B/32, and YOLOv8n-pose.
The next specialist stages should remain separate: consent-based face embeddings plus
local identity memory, temporal action recognition, and audio-event recognition. Those
stages need their own suitable datasets, evaluation sets, and on-device runtime checks;
they are not included in this image classifier.

The first run is deliberately separate from the Android build. The model must
pass label-quality checks, false-positive tests, model-size checks, and Redmi 12
latency/offline tests before it is integrated into EVE's APK.
