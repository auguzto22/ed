# Auto Reframe

Auto Reframe samples up to 160 reduced-size frames from the selected video and runs the existing ML Kit face detector locally. When a sample has no face, it falls back to the existing pose detector. It does not send frames to Gemini or touch the preview decoder/renderer.

Detected boxes are normalized and use clip-local timestamps. The engine smooths subject position and zoom, then writes the camera path as the clip's existing transform keyframes. The user can choose 16:9, 9:16, 1:1, or 4:5; the selected ratio is saved in the project and the selected clip carries the animation.

The editor previews the result before applying it. Applying commits one project edit, so Undo restores both the previous canvas ratio and the original transform keyframes. Existing rotation and opacity channels on colliding transform keyframes are preserved.
