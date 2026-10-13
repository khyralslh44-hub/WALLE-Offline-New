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
