import tempfile
import unittest
from pathlib import Path

from prepare_arabic_corpus import prepare


class CorpusPreparationTests(unittest.TestCase):
    def test_deterministic_split_and_exact_deduplication(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            source = root / "source"
            source.mkdir()
            lines = [f"هذا سطر عربي تجريبي مختلف رقم {i} يحتوي على نص كاف للاختبار." for i in range(400)]
            (source / "a.txt").write_text("\n".join(lines + [lines[0]]) + "\n", encoding="utf-8")
            out1, out2 = root / "out1", root / "out2"
            report1 = prepare(source, out1, "test corpus", "CC0-1.0 (test declaration)")
            report2 = prepare(source, out2, "test corpus", "CC0-1.0 (test declaration)")
            self.assertEqual(report1["counts"], report2["counts"])
            self.assertEqual(report1["unique_usable_lines"], 400)
            self.assertEqual(
                (out1 / "train/corpus.txt").read_text(encoding="utf-8"),
                (out2 / "train/corpus.txt").read_text(encoding="utf-8"),
            )
            all_lines = []
            for split in ("train", "validation", "test"):
                all_lines.extend((out1 / split / "corpus.txt").read_text(encoding="utf-8").splitlines())
            self.assertEqual(len(all_lines), len(set(all_lines)))
            self.assertFalse(report1["license_verified_by_tool"])

    def test_refuses_too_small_corpus(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            source = root / "source"
            source.mkdir()
            (source / "small.txt").write_text("هذا نص صغير لا يكفي.", encoding="utf-8")
            with self.assertRaises(ValueError):
                prepare(source, root / "out", "source", "license")


if __name__ == "__main__":
    unittest.main()
