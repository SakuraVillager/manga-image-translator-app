# Decisions - HorizontalTextRenderer Implementation

## Two-Pass Draw for Font Border
- **Decision**: Use STROKE then FILL two-pass draw instead of FILL_AND_STROKE with setStrokeColor
- **Rationale**: `Paint.setStrokeColor()` requires API 29+, but minSdk is 28
- **Trade-off**: Slightly more code, but fully compatible with all supported API levels

## TextBlock.minRect Computation from lines
- **Decision**: Compute minRect bounding box from the `lines` field instead of returning stub RectF()
- **Rationale**: Needed for renderer to know where to draw text; also enables meaningful tests
- **Alternative considered**: Adding minRect as a constructor parameter — rejected because it should be derived from geometry data

## Font Size Minimum
- **Decision**: Apply fontSizeMinimum when > 0, otherwise floor at 1f
- **Rationale**: Follows the spec: "constrained to >= config.fontSizeMinimum (or 1f if minimum is -1)"
