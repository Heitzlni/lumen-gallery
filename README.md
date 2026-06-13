# Lumen Gallery

A privacy-first Android gallery, forked from [Fossify Gallery](https://github.com/FossifyOrg/Gallery) and extended with on-device intelligence — semantic search, OCR / Live Text, story-style memories, encrypted vault, and more. Everything runs locally; no cloud, no Google Play Services dependency at runtime, no telemetry.

Installs side-by-side with the original Fossify Gallery (different `applicationId` — `com.heitzler.lumen`).

## What's in Lumen on top of upstream Fossify

**On-device understanding**
- **Semantic search** — type "forest hike" or "my dog on the couch" and the gallery finds matching photos. Uses [MobileCLIP-S0](https://github.com/apple/ml-mobileclip) (Apple, MIT) running fully offline via ONNX Runtime.
- **OCR text search** — every photo with text is indexed; search by what's written in the picture. Powered by ML Kit Text Recognition (bundled offline model).
- **Image content labels** — auto-tagged subjects from ML Kit Image Labeling.
- **Live Text overlay** — Apple-style "select text in a photo" mode.

**Vault**
- AES-256-GCM encrypted file vault keyed by the Android hardware Keystore (never extractable).
- Locked-folder migration from Google Photos via snapshot-diff.

**Video player**
- Picture-in-Picture with prev / play-pause / next remote actions.
- MediaSession for Bluetooth / AirPods / lock-screen controls.
- Audio focus + auto-pause when headphones disconnect (`AUDIO_BECOMING_NOISY`).
- ±10s skip indicator, smoother scrub with thumbnail preview, persistent playback queue.
- Video trim that writes the result back to the source folder (not a hidden bucket).
- Audio extraction (Media3 Transformer) — strip the audio track from a video to `.m4a`.

**Albums and memories**
- **Virtual albums** with many-to-many membership and CLIP-powered smart-add ("add more sky photos to this album").
- **Story-style memories** — Instagram-style segmented progress, CLIP clustering of past-on-this-day photos with auto-inferred activity labels.

**Editor**
- Drawing, text overlay, lasso selection, rotation, full undo/redo.
- Perceptual aHash duplicate detector with grouped review.

## Privacy & freedom

- Fully on-device. No network calls at runtime. No Google Play Services.
- Bundled ML models — CLIP (~54 MB) and ML Kit text recognition are shipped inside the APK.
- GPL-3.0 — see [LICENSE](LICENSE). All source remains open.

## Install

Download the latest signed APK from [Releases](../../releases). For automatic updates, point [Obtainium](https://github.com/ImranR98/Obtainium) at this repo.

> APK is large (~80–120 MB per ABI) because the CLIP weights and ONNX Runtime native libs are bundled. There is no way around this without giving up the offline guarantee.

## Build

```bash
source env.sh           # exports JAVA_HOME + Android SDK paths
./gradlew assembleFossRelease
```

## Attribution

This project is a fork of [Fossify Gallery](https://github.com/FossifyOrg/Gallery), itself a fork of Simple Mobile Tools Gallery. All upstream copyright notices are preserved per GPL-3.0. The Lumen-specific changes — on-device ML, vault, memories, virtual albums, the rewritten video player, the editor extensions — are the work of [@Heitzlni](https://github.com/Heitzlni) and contributors.

Translations come straight from the Fossify Weblate project and remain credited there.

## License

GPL-3.0-or-later, inherited from upstream. See [LICENSE](LICENSE).
