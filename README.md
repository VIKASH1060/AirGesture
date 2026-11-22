# Air Gesture (offline hand-gesture phone control)

1. Create an Android Studio project (Kotlin, package `com.example.airgesture`, minSdk 26) and copy `app/` over it.
2. Download `hand_landmarker.task` (MediaPipe Hand Landmarker model) once and put it in `app/src/main/assets/`.
3. Install, open the app:
   - tap **Enable accessibility service** -> turn on "Air Gesture Control"
   - tap **Start gesture control** (grant camera + notifications)
4. Keep the phone in portrait, propped up so the front camera sees your hand.

| Gesture | Action |
|---|---|
| Index finger only | Move pointer |
| Index + middle together | Tap at pointer |
| Index + middle apart (V), move | Swipe up / down / left / right |
| Fist, hold ~0.5s | Pause |
| Open palm, hold still ~0.5s | Play |
| Open palm sweep left / right | Previous / Next track |
| Four fingers (thumb tucked), move hand up / down | Volume up / down (repeats while moving) |

Tuning lives at the top of `AdvancedGestureClassifier.kt` (hold times, sweep thresholds, smoothing).
