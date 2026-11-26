# NEXORA AI Studio

One screen, four specialists that switch automatically:

| Specialist | What it does | Model / engine |
|---|---|---|
| Code | Writes code or whole projects, asks FLUX for the images it needs and wires them in (`assets/<id>.png`), live preview, ZIP export. Can read screenshots. | moonshotai/kimi-k3.1-pro + black-forest-labs/flux.3-image-klein |
| Video | Upload a video, describe the edit. Kimi looks at sampled frames, plans the edit as JSON, Media3 Transformer renders it on-device (trim, speed, mute, filters, rotate, resolution, caption). | Kimi (vision) + Media3 |
| Image | Generate images, or attach a photo and edit it. | flux.3-image-klein / flux.1-kontext-dev |
| General | Everything else, with vision. | moonshotai/kimi-k3.1-pro |

Routing is instant and on-device (`Router.kt`). You can also pin a specialist with the chips on top.

## Build on GitHub
1. Create a repo and upload this whole folder (keep the `.github/workflows` path).
2. Open the Actions tab, run "Build NEXORA APK" (it also runs on every push).
3. Download the `NEXORA-debug-apk` artifact.
4. Open the app, Settings, paste your NVIDIA API key (nvapi-...).

## Notes
- applicationId / package: `com.nexorastudio.app`. Change it everywhere (build.gradle.kts, the folder `java/com/nexorastudio/app` and the `package` lines) to a domain you own before publishing.
- Play Store needs a signed release AAB (upload key) and a privacy policy. This workflow builds a debug APK only.
- The hosted preview API may only accept sample images for image editing. If editing fails, run a FLUX Kontext NIM yourself and set its URL as "Image API base URL" in Settings.
- The model ids use the API spelling `flux.3-image-klein` (dot). The app auto-corrects `flux-3-image-klein`.
