# HDR Studio للحاسوب

تحسين الفيديو بالذكاء الاصطناعي وإخراج HDR10 على Windows. نسخة الحاسوب من [تطبيق الهاتف](https://github.com/6thd/HdrStudio): نفس مسار الألوان والتنظيف، مع نماذج AI أثقل وأولوية للجودة.

## التشغيل

انقر مرتين على **`HDR Studio.bat`**. أول مرة يثبّت المكتبات، ثم يفتح نافذة التطبيق.

المتطلبات: Windows 10/11، ‏Python 3.12 أو أحدث (مختبَر في CI على 3.12 و3.13 و3.14)، ‏FFmpeg (`winget install Gyan.FFmpeg`).

## الاستخدام

1. اختر الفيديو.
2. اختر الإعداد (TikTok HDR10 مثلاً) والدقة ومعدل الإطارات ومستوى AI.
3. حرّك شريط الزمن، انقر على الإطار لاختيار منطقة، ثم **معاينة** لمقارنة قبل/بعد بدقة الإخراج.
4. **تصدير**. يمكن الإيقاف في أي وقت والاستئناف لاحقاً بالضغط على «استئناف التصدير» بنفس الإعدادات.

## سطر الأوامر

```
.venv\Scripts\python -m hdrstudio export in.mp4 -o out.mp4 --preset tiktok_hdr --quality fast --resolution 2160
.venv\Scripts\python -m hdrstudio probe in.mp4
.venv\Scripts\python -m hdrstudio bench general x2plus x4plus
```

`--quality`: ‏`off` · `fast` · `detail` · `high` · `max`. ‏`--fps x2` يولّد إطارات بينية (30 ← 60). أي إعداد آخر عبر `--set`، مثل `--set sharpen=0.5`.

## الاختبارات

```
.venv\Scripts\python -m pip install -r requirements-dev.txt
.venv\Scripts\python -m pytest tests -q
```

تعمل على CPU فقط: حماية واجهة API المحلية، التخطيط، وسائط المرمّز، ترقيع HDR10 SEI، وتحويل النماذج إلى ONNX. `tests/test_export.py` يشغّل التصدير الحقيقي (HDR10، ‏SDR، مضاعفة الإطارات، والاستئناف بعد قتل العملية) ويحتاج GPU وFFmpeg، فيعمل محلياً ويُتخطّى في CI.

## النماذج

| الملف | المصدر | الترخيص |
|---|---|---|
| `srvgg_general_x4v3.bin` | من مستودع تطبيق الهاتف | BSD-3-Clause |
| `RealESRGAN_x2plus.pth` | github.com/xinntao/Real-ESRGAN v0.2.1 · sha256 `49fafd45…266abb` | BSD-3-Clause |
| `rife-v4.6/flownet.*` | github.com/nihui/rife-ncnn-vulkan إصدار 20221029 (النموذج: hzwer/RIFE) | MIT |
| `RealESRGAN_x4plus.pth` | github.com/xinntao/Real-ESRGAN v0.1.0 · sha256 `4fa0d389…d682f1` | BSD-3-Clause |

ملفات `.onnx` تُبنى محلياً من هذه الأوزان عند أول استخدام.

التصميم والقرارات وخارطة الطريق (ومنها 60fps): [docs/DESIGN.md](docs/DESIGN.md).
