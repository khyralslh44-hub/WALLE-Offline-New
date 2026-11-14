# EVE private vision student (first training stage)

This is a separate pipeline from the Arabic language-model workflow.

## What this stage trains
A compact multi-label image classifier with randomly initialized weights. During
training only, two independent teacher models provide soft labels:
- YOLOv8n for object categories.
- OpenAI CLIP ViT-B/32 for scene/context tags such as snow, rain, indoor rooms,
  outdoor settings, and a few broad activity-like visual contexts.

The student is exported to TensorFlow Lite. Teacher weights and teacher runtimes
are not included in the student artifact or Android app.

## Important scope limits
- The student predicts image-level tags; it does not output bounding boxes.
  EVE's existing EfficientDet-Lite0 detector remains responsible for boxes.
- Face identity must remain a separate on-device embedding + local-memory feature.
- Activities such as eating or exercising require repeated frames/pose evidence;
  a single still-image classifier must not claim to know them.
- Weather labels are visual hypotheses, not weather forecasts or guaranteed facts.
- This is an initial student, not a claim of human-level visual understanding.

## Run
Open GitHub Actions → **Train EVE private vision student** → Run workflow.
The defaults use 300 COCO validation images and up to 18 epochs. The workflow
downloads the public COCO validation images, labels them with the teacher
ensemble, trains the student from random initialization, and uploads a TFLite
model plus metadata as an Actions artifact.

The first run is deliberately separate from the Android build. The model must
pass label-quality checks, false-positive tests, model-size checks, and Redmi 12
latency/offline tests before it is integrated into EVE's APK.
