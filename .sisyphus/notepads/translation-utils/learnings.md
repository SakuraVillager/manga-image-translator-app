
## Task 5 - Geometry/Image/OpenCV Utils (2026-05-03)

### Files Created
- \	ranslation/util/GeometryUtils.kt\ ！ 264 lines
- \	ranslation/util/ImageUtils.kt\ ！ 69 lines
- \	ranslation/util/OpenCVUtils.kt\ ！ 43 lines

### Key Details
- **GeometryUtils**: polygonDistance uses cross-product point-in-convex-polygon test for overlap detection, plus edge-to-edge and point-to-edge distance computations. convexHull uses Andrew's monotone chain (private cross helper). segmentToSegmentDistance uses cross-product intersection test + collinear endpoint checks + point-to-segment fallback. pointToSegmentDistance uses dot product projection clamped to [0,1].
- **ImageUtils**: letterbox scales to fit targetSize keeping aspect ratio, centers on black canvas. recycled the intermediate scaled bitmap.
- **OpenCVUtils**: thin wrappers around OpenCVLoader.initDebug(), Utils.bitmapToMat(), Utils.matToBitmap().

### Import Patterns
- \kotlin.math.abs\, \kotlin.math.sqrt\, \kotlin.math.min\, \kotlin.math.max\, \kotlin.math.roundToInt\
- \ndroid.graphics.PointF\, \ndroid.graphics.Bitmap\, \ndroid.graphics.Canvas\, \ndroid.graphics.Color\
- \org.opencv.android.OpenCVLoader\, \org.opencv.android.Utils\, \org.opencv.core.Mat\

### Gotchas
- Quadrilateral.kt has private \convexHullArea()\ and \cross()\ ！ GeometryUtils has its own separate public \convexHull()\ with private \cross()\ helper, no conflict.
- Used \min(x1, x2)\ / \max(x1, x2)\ from kotlin.math for the onSegment bounds check.
- Build: pre-existing errors in KoinInitializer, OnnxConstants, TensorConverter, TranslationPipeline ！ no errors from new util files.
