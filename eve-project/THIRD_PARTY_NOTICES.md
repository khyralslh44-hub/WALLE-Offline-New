# Third-party notices

## MoodStream facial-expression TensorFlow Lite model

The bundled `eve_emotion_model.tflite` file is sourced from
[EnricoZanetti/moodstream](https://github.com/EnricoZanetti/moodstream/tree/af59e5cdbd45936051a27e694b160996526f11bb/models).

The model is a pre-trained FER2013 facial-expression classifier with six output labels:
`happy`, `sad`, `angry`, `neutral`, `surprised`, and `fearful`.
The model's upstream training pipeline excludes the FER2013 "disgust" class and expects a 48×48 grayscale face crop normalized to [0, 1].

The model is included for offline inference. It estimates visible facial-expression categories; it cannot establish a person's true internal feelings. The repository does not claim to have retrained this upstream model. The app's saved affinity values are a separate, small local personalization mechanism, not neural-network weight updates.

### License

MIT License

Copyright (c) 2026 Enrico Zanetti

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.


## Qwen2-0.5B-Instruct LiteRT-LM language model

The offline language model distributed in the production APK is the LiteRT-LM conversion
of Qwen/Qwen2-0.5B-Instruct, supplied by the litert-community/Qwen2-0.5B-Instruct repository:
https://huggingface.co/litert-community/Qwen2-0.5B-Instruct

The model repository declares the weights licensed under Apache License 2.0. The production
APK includes the upstream license as the asset `qwen2_model_license.txt`.
The model weights are verified in CI against SHA-256
`0f01cc004b8eb62b92ba6be85ed05a248ba0d2f78af94c4949b313eccfb4c157`.


## MobileFaceNet on-device face identity model

The local face comparison model is `MobileFaceNet.tflite` from
`syaringan357/Android-MobileFaceNet-MTCNN-FaceAntiSpoofing`, pinned to commit
`d635649e711f5e16e6cd3dc06cbb329cd8d1d3b7`.
The upstream repository declares an MIT license; the APK includes the license as
`mobilefacenet_model_license.txt`. The model is used only after an explicit
voice request to enrol a named face. Face reference crops remain in app-private
storage and are not uploaded.
