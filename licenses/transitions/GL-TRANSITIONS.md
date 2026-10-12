# Transições adaptadas de gl-transitions

As transições com id `gl_*` em `editor-engine/src/main/assets/editor/transitions/` foram adaptadas
do projeto gl-transitions (https://github.com/gl-transitions/gl-transitions). Cada arquivo `.frag`
mantém o cabeçalho original com autor e licença. A adaptação só troca a interface
(`getFromColor`/`getToColor`/`progress` → `uTexA`/`uTexB`/`uProgress`) e expõe os uniforms float
como parâmetros `p_*` ajustáveis no editor.

## Licença MIT (projeto gl-transitions e a maioria dos shaders)

```
MIT License

Copyright (c) 2017-present gl-transitions contributors

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

Note: Individual transitions in the transitions/ directory may have their own
license specified in their file header comments. When no license is specified,
the transition is covered by this MIT license.
```

## MIT

- Advanced Mosaic (AdvancedMosaic.glsl) — Sergey Kosarevsky
- Block Dissolve (BlockDissolve.glsl) — nwoeanhinnogaehr
- Book Flip (BookFlip.glsl) — hong
- Bounce (Bounce.glsl) — Adrian Purser
- Bow Tie Horizontal (BowTieHorizontal.glsl) — huynx
- Bow Tie Vertical (BowTieVertical.glsl) — huynx
- Bow Tie With Parameter (BowTieWithParameter.glsl) — KMojek
- Box (Box.glsl) — lql
- Butterfly Wave Scrawler (ButterflyWaveScrawler.glsl) — mandubian
- Circle Crop (CircleCrop.glsl) — fkuteken
- Colour Distance (ColourDistance.glsl) — P-Seebauer
- Crazy Parametric Fun (CrazyParametricFun.glsl) — mandubian
- Cross Zoom (CrossZoom.glsl) — rectalogic
- Defocus Blur (DefocusBlur.glsl) — Sergey Kosarevsky
- Directional (Directional.glsl) — Gaëtan Renaudeau
- Directional Scaled (DirectionalScaled.glsl) — Thibaut Foussard
- Doom Screen Transition (DoomScreenTransition.glsl) — Zeh Fernando
- Dreamy (Dreamy.glsl) — mikolalysenko
- Dreamy Zoom (DreamyZoom.glsl) — Zeh Fernando
- Drop Zone Flicker (Drop_Zone_Flicker.glsl) — bread
- Edge Transition (EdgeTransition.glsl) — Woohyun Kim
- Film Burn Pro (FilmBurn.glsl) — Anastasia Dunbar
- Fold (Fold.glsl) — nwoeanhinnogaehr
- Glitch Displace (GlitchDisplace.glsl) — Matt DesLauriers
- Glitch Memories (GlitchMemories.glsl) — Gunnar Roth
- Grid Flip (GridFlip.glsl) — TimDonselaar
- HSV Fade (HSVfade.glsl) — nwoeanhinnogaehr
- Horizontal Close (HorizontalClose.glsl) — martiniti
- Horizontal Open (HorizontalOpen.glsl) — martiniti
- Left Right (LeftRight.glsl) — zhmy
- Linear Blur (LinearBlur.glsl) — gre
- Mosaic (Mosaic.glsl) — Xaychru
- Overexposure (Overexposure.glsl) — Ben Zhang
- Polka Dots Curtain (PolkaDotsCurtain.glsl) — bobylito
- Puzzle Right (PuzzleRight.glsl) — JustKirillS
- Radial (Radial.glsl) — Xaychru
- Rectangle (Rectangle.glsl) — martiniti
- Rectangle Crop (RectangleCrop.glsl) — martiniti
- Revolve Left (Revolve_Left.glsl) — bread
- Rolls (Rolls.glsl) — Mark Craig
- Rotate Scale Vanish (RotateScaleVanish.glsl) — Mark Craig
- Simple Flip (SimpleFlip.glsl) — nwoeanhinnogaehr
- Simple Zoom (SimpleZoom.glsl) — 0gust1
- Simple Zoom Out (SimpleZoomOut.glsl) — Tianshuo
- Slides (Slides.glsl) — Mark Craig
- Star Wipe (StarWipe.glsl) — Ben Lucas
- Static Fade (StaticFade.glsl) — Ben Lucas
- Strip Datamosh Glitch (StripDatamoshGlitch.glsl) — bread
- Swirl (Swirl.glsl) — Sergey Kosarevsky
- TV Static (TVStatic.glsl) — Brandon Anzaldi
- Tiles Wave (TilesWave.glsl) — numb3r23
- Top Bottom (TopBottom.glsl) — zhmy
- Vertical Close (VerticalClose.glsl) — martiniti
- Vertical Open (VerticalOpen.glsl) — martiniti
- Water Drop (WaterDrop.glsl) — Paweł Płóciennik
- Zoom In Circles (ZoomInCircles.glsl) — dycm8009
- Zoom Left Wipe (ZoomLeftWipe.glsl) — Handk
- Zoom Right Wipe (ZoomRigthWipe.glsl) — Handk
- Angular (angular.glsl) — Fernando Kuteken
- Burn (burn.glsl) — gre
- Burn Soft (burn0.glsl) — liubailin2020@gmail.com
- Chessboard (chessboard.glsl) — lql
- Circle (circle.glsl) — Fernando Kuteken
- Circleopen (circleopen.glsl) — gre
- Colorphase (colorphase.glsl) — gre
- Coord In (coord-from-in.glsl) — haiyoucuv
- Crosshatch (crosshatch.glsl) — pthrasher
- Crosswarp (crosswarp.glsl) — Eke Péter <peterekepeter@gmail.com>
- Cube (cube.glsl) — gre
- Directional Easing (directional-easing.glsl) — Max Plotnikov
- Directionalwarp (directionalwarp.glsl) — pschroen
- Directionalwipe (directionalwipe.glsl) — gre
- Dissolve (dissolve.glsl) — hjm1fb
- Doorway (doorway.glsl) — gre
- Fade Pro (fade.glsl) — gre
- Fadecolor (fadecolor.glsl) — gre
- Fadegrayscale (fadegrayscale.glsl) — gre
- Flyeye (flyeye.glsl) — gre
- Fragment (fragment.glsl) — lbl
- Heart (heart.glsl) — gre
- Hexagonalize (hexagonalize.glsl) — Fernando Kuteken
- Kaleidoscope (kaleidoscope.glsl) — nwoeanhinnogaehr
- Luminance Melt (luminance_melt.glsl) — Ian McEwan, Ashima Arts.
- Morph (morph.glsl) — paniq
- Mosaic Shift (mosaic_transition.glsl) — YueDev
- Multiply Blend (multiply_blend.glsl) — Fernando Kuteken
- Old TV Lost Signal (old_tv_lost_signal.glsl) — mernking (gitlab.com/Godswork)
- Parametric Glitch (parametric_glitch.glsl) — Yoni Maltsman @friendlyspinach
- Perlin (perlin.glsl) — Rich Harris
- Pinwheel (pinwheel.glsl) — Mr Speaker
- Pixelize (pixelize.glsl) — gre
- Polar Function (polar_function.glsl) — Fernando Kuteken
- Power Kaleido (powerKaleido.glsl) — Boundless
- Random Noise (randomNoisex.glsl) — towrabbit
- Randomsquares (randomsquares.glsl) — gre
- Ripple Pro (ripple.glsl) — gre
- Rotate Transition (rotateTransition.glsl) — haiyoucuv
- Rotate Scale Fade (rotate_scale_fade.glsl) — Fernando Kuteken
- Scale In (scale-in.glsl) — haiyoucuv
- Split Slide In Horizontal (splitSlideInHorizontal.glsl) — OllyOllyOlly
- Split Slide In Out Horizontal (splitSlideInOutHorizontal.glsl) — OllyOllyOlly
- Split Slide In Out Vertical (splitSlideInOutVertical.glsl) — OllyOllyOlly
- Split Slide In Vertical (splitSlideInVertical.glsl) — OllyOllyOlly
- Split Slide Out Horizontal (splitSlideOutHorizontal.glsl) — OllyOllyOlly
- Split Slide Out Vertical (splitSlideOutVertical.glsl) — OllyOllyOlly
- Squareswire (squareswire.glsl) — gre
- Squeeze (squeeze.glsl) — gre
- Static Wipe (static_wipe.glsl) — Ben Lucas
- Swap (swap.glsl) — gre
- Tangent Motion Blur (tangentMotionBlur.glsl) — chenkai
- Undulating Burn Out (undulatingBurnOut.glsl) — pthrasher
- Wind (wind.glsl) — gre
- Windowblinds (windowblinds.glsl) — Fabien Benetou
- Windowslice (windowslice.glsl) — gre
- Wipe Down Pro (wipeDown.glsl) — Jake Nelson
- Wipe Left Pro (wipeLeft.glsl) — Jake Nelson
- Wipe Right Pro (wipeRight.glsl) — Jake Nelson
- Wipe Up Pro (wipeUp.glsl) — Jake Nelson
- X Axis Slide (x_axis_translation.glsl) — lizhongjian
- Zoom In Out (zoomInOut.glsl) — OllyOllyOlly

## BSD 3 Clause

- Inverted Page Curl (InvertedPageCurl.glsl) — Hewlett-Packard

## BSD 2 Clause

- Stereo Viewer (StereoViewer.glsl) — Ted Schundler

Os shaders BSD (Inverted Page Curl — Hewlett-Packard, BSD 3 Clause; Stereo Viewer — Ted Schundler, BSD 2 Clause) trazem o texto completo da licença dentro do próprio arquivo `.frag`.
