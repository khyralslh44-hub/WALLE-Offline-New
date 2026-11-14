#!/usr/bin/env python3
"""Train EVE's compact vision student from random initialization.

Teacher ensemble (training only):
  * YOLOv8n: object-category pseudo-labels.
  * OpenAI CLIP ViT-B/32: scene/context pseudo-labels.

The student is a small multi-label CNN trained from random weights. Teacher
weights and runtimes are never copied into the exported TFLite model.
This first student predicts image-level tags; the existing on-device detector
continues to provide object boxes. It does not perform face identification or
temporal activity recognition.
"""
from __future__ import annotations

import argparse
import json
import os
import random
import urllib.request
import zipfile
from pathlib import Path

import numpy as np
import tensorflow as tf
import torch
from PIL import Image
from transformers import CLIPModel, CLIPProcessor
from ultralytics import YOLO

ROOT = Path(__file__).resolve().parent
WORK = ROOT / "work"
DIST = ROOT / "dist"
IMAGE_DIR = WORK / "images"
ANNOTATIONS_URL = "https://images.cocodataset.org/annotations/annotations_trainval2017.zip"
COCO_ANNOTATIONS_URL = "https://images.cocodataset.org/annotations/annotations_trainval2017.zip"
COCO_IMAGE_BASE = "https://images.cocodataset.org/val2017/"
CLIP_MODEL = "openai/clip-vit-base-patch32"
YOLO_MODEL = "yolov8n.pt"
IMAGE_SIZE = 96
SEED = 42

SCENE_TAGS = [
    "snowy outdoor scene", "rainy street scene", "foggy outdoor scene",
    "sunny outdoor scene", "cloudy sky", "night street scene",
    "daytime outdoor scene", "indoor kitchen", "living room interior",
    "bedroom interior", "bathroom interior", "office interior",
    "supermarket or shop", "restaurant or cafe", "beach scene",
    "forest scene", "mountain landscape", "sports or exercise scene",
    "person eating food", "person drinking from a cup"
]


def download(url: str, destination: Path) -> None:
    destination.parent.mkdir(parents=True, exist_ok=True)
    if destination.exists() and destination.stat().st_size > 0:
        return
    print(f"Downloading {url}", flush=True)
    request = urllib.request.Request(url, headers={"User-Agent": "EVE-vision-training/1.0"})
    with urllib.request.urlopen(request, timeout=90) as response, destination.open("wb") as out:
        while True:
            chunk = response.read(1024 * 1024)
            if not chunk:
                break
            out.write(chunk)


def get_images(limit: int) -> list[Path]:
    annotation_file = WORK / "instances_val2017.json"
    if not annotation_file.exists():
        archive = WORK / "annotations_trainval2017.zip"
        download(COCO_ANNOTATIONS_URL, archive)
        with zipfile.ZipFile(archive) as bundle:
            member = "annotations/instances_val2017.json"
            if member not in bundle.namelist():
                raise RuntimeError("COCO annotation archive does not contain instances_val2017.json")
            annotation_file.write_bytes(bundle.read(member))
    data = json.loads(annotation_file.read_text(encoding="utf-8"))
    valid_ids = {ann["image_id"] for ann in data.get("annotations", [])}
    images = [item for item in data["images"] if item["id"] in valid_ids][:limit]
    paths = []
    for index, item in enumerate(images, 1):
        path = IMAGE_DIR / item["file_name"]
        download(COCO_IMAGE_BASE + item["file_name"], path)
        paths.append(path)
        if index % 50 == 0:
            print(f"Downloaded {index}/{len(images)} images", flush=True)
    if len(paths) < 40:
        raise RuntimeError(f"Only {len(paths)} usable images were downloaded")
    return paths


def teacher_labels(paths: list[Path]) -> tuple[list[str], np.ndarray, np.ndarray]:
    yolo = YOLO(YOLO_MODEL)
    clip = CLIPModel.from_pretrained(CLIP_MODEL)
    processor = CLIPProcessor.from_pretrained(CLIP_MODEL)
    clip.eval()
    clip_text = [f"A photo of {tag}." for tag in SCENE_TAGS]
    text_inputs = processor(text=clip_text, return_tensors="pt", padding=True)
    with torch.inference_mode():
        text_features = clip.get_text_features(**text_inputs)
        text_features = text_features / text_features.norm(dim=-1, keepdim=True)

    class_names = [str(v).lower().replace("_", " ") for _, v in sorted(yolo.names.items())]
    if len(class_names) != 80:
        raise RuntimeError(f"Expected 80 COCO YOLO classes, got {len(class_names)}")
    labels = class_names + SCENE_TAGS
    targets = np.zeros((len(paths), len(labels)), dtype=np.float32)
    images = []

    for i, path in enumerate(paths):
        with Image.open(path) as source:
            rgb = source.convert("RGB")
            images.append(rgb.copy().resize((IMAGE_SIZE, IMAGE_SIZE)))
        pred = yolo.predict(source=str(path), imgsz=320, conf=0.18, verbose=False, device="cpu")[0]
        if pred.boxes is not None:
            for cls_id, conf in zip(pred.boxes.cls.cpu().numpy(), pred.boxes.conf.cpu().numpy()):
                targets[i, int(cls_id)] = max(targets[i, int(cls_id)], float(conf))

        with Image.open(path) as source:
            clip_inputs = processor(images=source.convert("RGB"), return_tensors="pt")
        with torch.inference_mode():
            image_features = clip.get_image_features(**clip_inputs)
            image_features = image_features / image_features.norm(dim=-1, keepdim=True)
            scores = (100.0 * image_features @ text_features.T).softmax(dim=-1)[0].cpu().numpy()
        top = np.argsort(scores)[-3:]
        for scene_id in top:
            # Preserve teacher confidence as a soft target rather than a hard assertion.
            targets[i, 80 + int(scene_id)] = float(np.clip(scores[scene_id] * 5.0, 0.05, 0.85))

        if (i + 1) % 25 == 0:
            print(f"Teacher ensemble labelled {i + 1}/{len(paths)} images", flush=True)

    return labels, targets, np.asarray([np.asarray(im, dtype=np.float32) / 255.0 for im in images])


def build_student(num_labels: int) -> tf.keras.Model:
    # All layers are randomly initialized: no teacher/student pretrained weights.
    inputs = tf.keras.Input(shape=(IMAGE_SIZE, IMAGE_SIZE, 3), name="image")
    x = inputs
    for filters in (16, 24, 32, 48):
        x = tf.keras.layers.SeparableConv2D(filters, 3, strides=2, padding="same", use_bias=False)(x)
        x = tf.keras.layers.BatchNormalization()(x)
        x = tf.keras.layers.ReLU()(x)
    x = tf.keras.layers.GlobalAveragePooling2D()(x)
    x = tf.keras.layers.Dense(96, activation="relu")(x)
    outputs = tf.keras.layers.Dense(num_labels, activation="sigmoid", name="tags")(x)
    model = tf.keras.Model(inputs, outputs, name="eve_vision_student")
    model.compile(
        optimizer=tf.keras.optimizers.Adam(learning_rate=0.001),
        loss=tf.keras.losses.BinaryCrossentropy(),
        metrics=[tf.keras.metrics.BinaryAccuracy(threshold=0.35, name="tag_accuracy")]
    )
    return model


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--images", type=int, default=int(os.getenv("EVE_VISION_IMAGES", "300")))
    parser.add_argument("--epochs", type=int, default=int(os.getenv("EVE_VISION_EPOCHS", "18")))
    args = parser.parse_args()
    if args.images < 40 or args.images > 1500:
        raise ValueError("--images must be between 40 and 1500")
    if args.epochs < 1 or args.epochs > 60:
        raise ValueError("--epochs must be between 1 and 60")

    random.seed(SEED)
    np.random.seed(SEED)
    tf.random.set_seed(SEED)
    WORK.mkdir(parents=True, exist_ok=True)
    DIST.mkdir(parents=True, exist_ok=True)

    paths = get_images(args.images)
    labels, targets, pixels = teacher_labels(paths)
    order = np.random.permutation(len(paths))
    split = max(1, int(len(order) * 0.15))
    val_ids, train_ids = order[:split], order[split:]

    model = build_student(len(labels))
    history = model.fit(
        pixels[train_ids], targets[train_ids],
        validation_data=(pixels[val_ids], targets[val_ids]),
        epochs=args.epochs, batch_size=16, shuffle=True, verbose=2,
        callbacks=[tf.keras.callbacks.EarlyStopping(monitor="val_loss", patience=4, restore_best_weights=True)]
    )

    model_path = DIST / "eve_vision_student.keras"
    model.save(model_path)
    converter = tf.lite.TFLiteConverter.from_keras_model(model)
    converter.optimizations = [tf.lite.Optimize.DEFAULT]
    tflite = converter.convert()
    tflite_path = DIST / "eve_vision_student.tflite"
    tflite_path.write_bytes(tflite)

    metadata = {
        "model": "EVE Vision Student",
        "version": 1,
        "initialization": "random",
        "teacher_models_training_only": [YOLO_MODEL, CLIP_MODEL],
        "teacher_weights_in_student": False,
        "input": {"shape": [1, IMAGE_SIZE, IMAGE_SIZE, 3], "dtype": "float32", "range": [0, 1]},
        "output": {"shape": [1, len(labels)], "meaning": "multi-label image-level tag scores"},
        "labels": labels,
        "training_images": len(paths),
        "epochs_completed": len(history.history.get("loss", [])),
        "final_train_loss": float(history.history["loss"][-1]),
        "final_validation_loss": float(history.history["val_loss"][-1]),
        "limitations": [
            "Image-level tags only; use the existing object detector for bounding boxes.",
            "A still image cannot reliably establish a person's identity or temporal activity.",
            "Weather and scene tags are hypotheses, not verified facts."
        ]
    }
    (DIST / "eve_vision_student.json").write_text(json.dumps(metadata, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({
        "status": "trained",
        "images": len(paths),
        "labels": len(labels),
        "epochs": metadata["epochs_completed"],
        "tflite_bytes": tflite_path.stat().st_size,
        "validation_loss": metadata["final_validation_loss"]
    }, indent=2), flush=True)


if __name__ == "__main__":
    main()
