# Drawing Minecraft's placed blocks inside Portal: research

Research only; no code was changed. Minecraft is 26.3 (Fabric API 0.161.0+26.3, fabric-rendering-v1 27.0.14).

Sources:
- `MC/` means `.gradle/mcsrc/client/` (the decompiled 26.3 client). All line numbers are from those files.
- `SDK/` means ValveSoftware/source-sdk-2013, branch `singleplayer`, folder `src/`. The `#ifdef PORTAL` blocks in it are the Portal code paths.
- Shader sources were read from `~/.gradle/caches/fabric-loom/26.3/minecraft-client.jar`.

Confidence: **H** = read directly in code, **M** = inferred from code, **L** = educated guess. **unknown** = not determined.

---

## 0. Summary and recommendation

| Question | Answer |
|---|---|
| Can Minecraft draw a transparent level with no sky? | Yes. The level clear already uses alpha 0. Only the fog colour (RGB of the clear and the fog in shaders) and the sky pass need overriding. Vanilla `end_of_frame` does not exist, so no post pass runs normally. (H) |
| Projection | MC's FOV is **vertical** (JOML `setPerspective(fovy, …)`). `vfov_mc = 2·atan(0.75·tan(fov_src/2))`. This does not depend on the aspect ratio. fov 75 gives **59.84°**, fov 90 gives **73.74°**. (H) |
| Depth convention | MC 26.3 uses **reversed Z**: near and far are swapped in `Projection.getMatrix`, depth clears to 0.0, and the depth test is `GREATER_THAN_OR_EQUAL`. The main depth buffer is D32_FLOAT. An occluder pipeline must follow this. (H) |
| Depth-only occluders | Use a custom `RenderPipeline` built from `RenderPipelines.WATER_MASK_SNIPPET` (a position-only shader that already exists) with `writeMask` 0. Draw it from an `@Inject` at the HEAD of `LevelRenderer.executeSolid(…, RenderPass)`, which runs inside the main pass before opaque terrain. Fabric's `LevelRenderEvents.START_MAIN` fires at the same point but **does not expose the RenderPass**. (H) |
| Cost | Small. About 3k brushes is roughly 36k triangles and 1.3 MB of static vertex buffer, drawn in one call. Well under 0.1 ms on the 5070 Ti. Build the mesh once per map and once per live-entity change. (M) |
| **Recommendation** | **Draw the placed blocks on the host side**, inside Portal's own frame, and keep the Minecraft overlay for hand and HUD only. SkyCraft's `WorldExporter`/`SkyAtlas` pipeline for Skyrim already does this. Use a client-side hook that runs **before the viewmodel**, not Present. Reason: blocks drawn by Minecraft can't be in step with Portal's camera. Portal turns the view natively, while Minecraft gets the angles over UDP, takes them at 20 Hz in `HostDriver.tick`, renders, and the frame is read back asynchronously (at least 1 frame). So world-locked blocks would visibly slide against Portal's walls on every mouse turn. Brush occluders also miss glass, invisible clip brushes, models and moving props, and Portal's real depth buffer handles all of those. The Minecraft-side plan is still fully specified below as a cheap prototype path. (M) |

---

## 1. Minecraft 26.3 frame flow: sky, clouds, weather, border, fog, clear

### 1.1 Order of a frame

`Minecraft.renderFrame` calls `GameRenderer.update` (camera), then `GameRenderer.extract` (render state), then `GameRenderer.render()`.

| Step | Where | Notes |
|---|---|---|
| Frame clear | `MC/net/minecraft/client/renderer/GameRenderer.java:471-475` `clearColorAndDepthTextures(color, guiRenderState.clearColorOverride, depth, 0.0)` | `clearColorOverride` defaults to `(0,0,0,0)` (`state/gui/GuiRenderState.java:30,277`). Only the loading overlay changes it. (H) |
| Lightmap | `GameRenderer.java:489` `lightmap.render(lightmapRenderState)` | |
| Level | `GameRenderer.renderLevel()` `:628-669`, which calls `LevelRenderer.render(...)` `:665-667` | Bob and hurt are multiplied into the **projection** here (`:636-642`), and so is the nausea/portal spin (`:644-656`). |
| Hand | `GameRenderer.render3dHud` `:671`. Its own perspective uses near 0.05 and `hudFov` (`:676-679`). Depth is cleared first (`:683`). | Not affected by our occluders. |
| Entity outline | `GameRenderer.java:493` `levelRenderer.blitEntityOutline()` (`LevelRenderer.java:1137`) | See 1.3. |
| Post effects | `GameRenderer.java:494` `applyPostEffects()` `:545` | See 1.3. |
| Depth clear, then GUI | `:501`, `:505` | |

Inside `LevelRenderer.render(GraphicsResourceAllocator, boolean renderOutline, CameraRenderState, GpuBufferSlice terrainFog, Vector4f fogColor, boolean shouldRenderSky, boolean consistentDepthRequired)` (`LevelRenderer.java:185`), a frame graph is built from these passes:

1. **`clear`** pass `:248-259`: `clearColorAndDepthTextures(main.color, new Vector4f(fogColor.x, fogColor.y, fogColor.z, 0.0F), main.depth, 0.0)`. **The alpha is already 0, but the RGB is the fog colour.**
2. **`sky`** pass, through `addSkyPass` `:363-381`. It is only added when `shouldRenderSky`, the fog type is not powder snow or lava, there is no blindness or darkness, and `skyRenderState.skybox != Skybox.NONE`. The void world is the overworld dimension type (flat generator, `minecraft:the_void` biome, from `run/saves/PortalCraft/data/minecraft/world_gen_settings.dat`), so its skybox is OVERWORLD and **the sky renders**. (H)
3. **`main`** pass, through `addMainPass` `:383-467`:
   - `prepareTranslucents()` `:518` prepares the clouds, world border and weather buffers.
   - A RenderPass named "Solid" or "Main" is opened on the main colour and depth targets (`:437-445`), followed by `bindDefaultUniforms` (`:446`).
   - `executeSolid(sections, featureFrame, renderPass)` `:506-516`: opaque terrain first (`:512`), then solid features such as entities and our `PortalRenderer` (`:514`).
   - When not using OIT: `executeClassicTransparency` `:665-689` draws translucent features, translucent terrain, **clouds** `:683`, **weather** `:687` and the **world border** `:688`.
   - When using OIT: `executeOit` `:542-625` draws the same, plus `cloudRenderer.renderOit` `:600`, `worldBorderRenderer.renderOit` `:589` and `weatherEffectRenderer.renderOit` `:590`.
   - Then the outline, see-through and always-on-top passes.
4. The entity-outline post chain, if any glowing entities are visible (`:274-279`).

### 1.2 Methods to mixin on (exact descriptors)

| What | Method (owner, descriptor) | How to kill it | Conf. |
|---|---|---|---|
| Sky, sun, moon, stars, sunrise, end flash | `net.minecraft.client.renderer.SkyRenderer.render(Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;Lnet/minecraft/client/renderer/state/level/SkyRenderState;)V` (`SkyRenderer.java:129`). Everything sky-related is drawn inside it (`:148-155`). | `@Inject(at=HEAD, cancellable)` when linked. Alternatives: cancel `LevelRenderer.addSkyPass(Lcom/mojang/blaze3d/framegraph/FrameGraphBuilder;Lnet/minecraft/client/renderer/state/level/CameraRenderState;Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;)V` (`:363`, private), or `@ModifyVariable(argsOnly, ordinal=1)` on `render`'s `shouldRenderSky` to set it false. | H |
| Clouds | `CloudRenderer.render(Lnet/minecraft/client/CloudStatus;Lcom/mojang/renderpearl/api/commands/RenderPass;)V` (`CloudRenderer.java:199`) and `renderOit(...)` `:209` | Easiest: force `options.cloudStatus()` to `CloudStatus.OFF`. Both paths check `cloudStatus != OFF && alpha(cloudColor) > 0` (`LevelRenderer.java:520,545,670`). | H |
| Weather (rain, snow) | `WeatherEffectRenderer.render(Lnet/minecraft/client/renderer/state/level/WeatherRenderState;Lcom/mojang/renderpearl/api/commands/RenderPass;)V` `:142` and `renderOit(...)` `:147`. Extracted in `extractRenderState` `:68`. | Cancel `render` and `renderOit`, or run `/gamerule advance_weather false` plus `/weather clear` (gamerule names checked in the world's `game_rules.dat`). Rain also dims sky light and the sky (`SkyRenderer.java:119`), so clear weather matters for lighting too. Rain splash particles go through the particle engine and stop with the weather. | H |
| World border | `WorldBorderRenderer.render(Lnet/minecraft/client/renderer/state/level/WorldBorderRenderState;Lcom/mojang/renderpearl/api/commands/RenderPass;Lnet/minecraft/world/phys/Vec3;D)V` `:157` and `renderOit(...)` `:169` | Only drawn when `state.alpha > 0`, which means near the border. Cancel both to be safe. | H |
| Fog (colour and distances) | `FogRenderer.setupFog(Lnet/minecraft/client/Camera;ILnet/minecraft/client/DeltaTracker;FLnet/minecraft/client/multiplayer/ClientLevel;)Lnet/minecraft/client/renderer/fog/FogData;` (`fog/FogRenderer.java:157`) | `@Inject(at=RETURN)`: set `color` to `(0,0,0,0)` and every distance to `Float.MAX_VALUE`. This one change fixes both the clear (its RGB comes from `cameraState.fogData.color`, passed at `GameRenderer.java:667`) and shader fog: `fog.glsl` `apply_fog` mixes by `fogValue * fogColor.a`, so alpha 0 means no fog anywhere. The static `FogRenderer.toggleFog()` (`:153`) only swaps the shader UBO and would leave the clear RGB as fog colour. | H |
| Level clear | `LevelRenderer.render` clear pass `:250-258` | Nothing to do once the fog colour is `(0,0,0,0)`. | H |

**Current mixin:** `LevelRendererMixin` cancels `render` entirely. That must change so the level runs while linked, with the overrides above.

### 1.3 Keeping alpha clean through to `FrameExporter`

`FrameExporter.capture` runs right after `GameRenderer.render()` and copies `mainRenderTarget().getColorTexture()` (RGBA8). The plugin blends it premultiplied (`ONE, INVSRCALPHA`, `overlay.cpp`). Any pixel with alpha 0 and RGB > 0 is therefore **added** to Portal's frame. Things that touch alpha:

| Pass | Effect on alpha | Verdict | Conf. |
|---|---|---|---|
| Opaque terrain and entities | `terrain.fsh` writes `color.a` from texture × vertex colour, which is 1 for solid blocks. | OK | H |
| Chunk fade-in | `terrain.fsh`: `color = mix(FogColor*vec4(1,1,1,a), color, chunkVisibility)`. With fog colour 0 a new section fades in from black. | Set `options.chunkSectionFadeInTime()` to 0 while linked. | H |
| Classic translucent (glass, water, particles) | `BlendFunction.TRANSLUCENT = (SRC_ALPHA, 1-SRC_ALPHA, ONE, 1-SRC_ALPHA)` (`BlendFunction.java:178`). On a cleared target that gives correct premultiplied output. | OK | H |
| OIT (`improvedTransparency`) | `oit_composite.fsh` writes `(rgb·norm, coverage)` with `TRANSLUCENT_PREMULTIPLIED_ALPHA`, which looks alpha-correct. It also writes `gl_FragDepth` and allocates extra targets. | Force it **off**. It isn't needed, and its alpha correctness hasn't been tested. | M |
| `end_of_frame` post effect | Always requested (`GameRenderer.java:426`), but vanilla ships no `post_effect/end_of_frame.json`. The client jar has only blur, creeper, entity_outline, invert and spider. `isPostEffectValid` fails, it goes into `failedPostEffects`, and no pass runs (`ShaderManager.java:238-252,336`). A resource pack could add it. | OK unless a resource pack provides one | H |
| Player post effects | `player.getActivePostEffects()` plus the spectator creeper/spider/invert effects (`GameRenderer.java:428-433`). These rewrite RGB over the whole screen, including alpha-0 pixels. | Rare. Clear the list while linked if wanted. | M |
| Entity outline (glowing) | `blitEntityOutline` uses `ENTITY_OUTLINE_BLIT = (SRC_ALPHA, 1-SRC_ALPHA, ZERO, ONE)` (`BlendFunction.java:185`), which **keeps dst alpha**. Outlines over empty pixels become RGB > 0 with alpha 0, which is additive glow in the overlay. | Acceptable, or cancel `LevelRenderer.blitEntityOutline()V` when linked. | H |
| Menu blur | `GuiRenderer.java:189-191` calls `processBlurEffect()` on the main target when a screen with a blurred background is open. With the level drawn, the blocks get blurred. | Set `menuBackgroundBlurriness` to 0. Whether 0 skips the pass entirely is **unknown**. | M |
| Lightning / ADDITIVE / GLINT | Add alpha. Lightning bolts would be visible. | OK | M |

**Settings to force while linked** (save and restore the user's values):
- `improvedTransparency` = false
- `cloudStatus` = OFF
- `chunkSectionFadeInTime` = 0
- `bobView` = false
- `fovEffectScale` = 0
- `screenEffectScale` = 0
- `damageTiltStrength` = 0
- `menuBackgroundBlurriness` = 0

`entityShadows` is optional: shadows only fall on Minecraft blocks, never on Portal floors. Graphics mode has no separate "fabulous" setting in 26.3; OIT is the only one that matters. (H)

---

## 2. Camera

### 2.1 Where the pose comes from

The flow is `GameRenderer.update` (`:420-423`), then `Camera.update(DeltaTracker)` (`MC/net/minecraft/client/Camera.java:93-112`):

1. `alignWithEntity(partialTicks)` `:251-297`. It calls `setRotation(entity.getViewYRot(pt), entity.getViewXRot(pt))` and `setPosition(lerp(xo,x), lerp(yo,y) + lerp(eyeHeightOld, eyeHeight), lerp(zo,z))`.
   - `eyeHeight` is smoothed 50% per tick in `tick()` (`:85-86`).
   - **The rotation is tick-interpolated.** `HostDriver.tick` sets yaw and pitch at START_CLIENT_TICK (20 Hz), so Minecraft's view trails Portal's by up to one tick on top of every other delay.
2. `fov = calculateFov(pt)` `:223-230` is `options.fov().get().intValue() * lerp(fovModifier)`, then `modifyFovBasedOnDeathOrFluid`. The FOV option is an **integer**, so it can't express 59.84°.
3. `prepareCullFrustum(...)` `:106`, using `createProjectionMatrixForCulling` `:181` (fixed near 0.05, `max(fov, option)`).
4. `setupPerspective(0.05F, depthFar, fov, windowW, windowH)` `:109`. **This builds the level projection.** `Projection.setupPerspective` `MC/.../renderer/Projection.java:19` stores the parameters. `Projection.getMatrix` `:60-73` builds `Matrix4f.setPerspective(fov·π/180, w/h, near=zFar, far=zNear, zZeroToOne)`. Note the **swap, which is what makes it reversed Z**.

`depthFar = max(renderDistance·16·4, cloudRange·16)` (`:95`).

`Camera.extractRenderState(CameraRenderState, DeltaTracker)` `:118-163` copies the following into `CameraRenderState` (`state/level/CameraRenderState.java`): `pos`, `xRot`, `yRot`, `orientation`, `cullFrustum`, `projectionMatrix`, `viewRotationMatrix`, `depthFar`, and the hurt/bob data in `entityRenderState`.

`GameRenderer.renderLevel` then **modifies the projection**:
- `bobHurt(CameraRenderState, PoseStack)` `:339` adds the death roll and hurt tilt. It is always called (`:637`); `damageTiltStrength` only scales the hurt tilt.
- `bobView(CameraRenderState, PoseStack)` `:361` is called if `options.bobView` (`:638-640`).
- The nausea/portal spin `:644-656` is scaled by `screenEffectScale²`.

`LevelRenderer.render` uses `cameraState.viewRotationMatrix` as the model-view (`:199-201`) and draws all level geometry **camera-relative** (`x - camPos`).

### 2.2 Recommended overrides (all on `Camera`, when linked)

| Need | Mixin | Conf. |
|---|---|---|
| Exact eye position and yaw/pitch every **frame**, not every tick | `@Inject(method="alignWithEntity(F)V", at=@At("TAIL"))`. Call `setRotation(mcYaw, pitch)` and `setPosition(eyeMc)` from the newest `HostLink.current()`. TAIL runs before the frustum and projection are built (`update` `:103` comes before `:106-109`), so culling stays consistent. | H |
| Roll | `setRotation(FF)V` `:341` builds `rotation.rotationYXZ(π - yaw·d, -pitch·d, 0)` and then updates `forwards/up/left` and `matrixPropertiesDirty \|= 3`. For roll, @Shadow `rotation`, `forwards`, `up`, `left` and `matrixPropertiesDirty`, set `rotationYXZ(π - yaw·d, -pitch·d, ±roll·d)` and redo the three `rotate` calls. **The sign is unknown**; check it in game. Also, the plugin currently sends only pitch and yaw (`plugin.cpp:675-677`, `VEngineClient013::GetViewAngles`), so roll would have to be added to `HostState`. Whether Portal puts its post-portal roll into those angles is **unknown**. | M |
| FOV | `@Inject(method="calculateFov(F)F", at=@At("HEAD"), cancellable=true)` returning `vfov_mc` (2.3). This bypasses sprint, speed, flying and bow FOV (`AbstractClientPlayer.getFieldOfViewModifier` `:88-110`), the **spyglass, which returns 0.1 even with `fovEffectScale` = 0** (`:104-105`), and the water and death FOV changes. Leave `calculateHudFov` alone. | H |
| Near plane | `@ModifyArg(method="update", at=@At(value="INVOKE", target="Lnet/minecraft/client/Camera;setupPerspective(FFFFF)V"), index=0)` to set it to 0.175 (7 units). Optional, see 2.4. | H |
| Bob, hurt, death roll | `@WrapOperation` on the `bobHurt` and `bobView` calls **inside `GameRenderer.renderLevel()V`** only (`:637`, `:639`), so the hand can keep its bob. Or `@Inject(HEAD, cancellable)` on both private methods to also stop the hand. Spin: force `screenEffectScale` = 0. | H |
| Sleeping / third person | `alignWithEntity` moves the camera for F5 and beds (`:271-296`). The TAIL override above replaces the position anyway. Force first person while linked. | H |

### 2.3 Projection formula (Source to Minecraft)

From the SDK (singleplayer branch):
- `fov_desired` becomes the player's default FOV, clamped to an integer between **75 and 90** (`SDK/game/shared/gamerules.cpp:858-863`).
- Hor+ scaling: `view.fov = ScaleFOVByWidthRatio(view.fov, aspect·0.75)` (`SDK/game/client/view.cpp:1127,1135`). `ScaleFOVByWidthRatio` returns `2·atan(tan(fov/2)·ratio)` (`view.cpp:1000-1007`). `aspect` is `engine->GetScreenAspectRatio()`. The 1.85 cap applies only in windowed multiplayer or when `sv_restrict_aspect_ratio_fov` = 2 (`:1129-1133`), so not in Portal single player.
- The projection uses `m_flAspectRatio = engine->GetScreenAspectRatio()` (`view.cpp:1168-1169`). `CViewSetup::fov` is a **horizontal** FOV (`SDK/public/view_shared.h:87-88`). `MatrixBuildPerspectiveX`: `xScale = 1/tan(fovx/2)`, `yScale = aspect·xScale` (`SDK/mathlib/vmatrix.cpp:1262-1271`).

So `tan(vfov/2) = tan(fovx/2)/aspect = tan(fov/2)·0.75`. The aspect ratio cancels out:

```
vfov_mc = 2 · atan( 0.75 · tan(fov_src / 2) )      // degrees; feed to Camera.calculateFov override
```

| fov_src | MC vertical | 16:9 horizontal | 16:10 horizontal |
|---|---|---|---|
| 75 | **59.840°** | 91.31° | 85.28° |
| 80 | 64.366° | 96.42° | 90.40° |
| 90 | 73.740° | 106.26° | 100.39° |

Minecraft's aspect is `windowWidth/windowHeight` (`Camera.java:107-109`). `HostDriver.matchHostWindowSize` already makes the two windows match. Prefer sending the host's **actual** FOV (the server's `CBasePlayer::GetFOV()`, which already includes zoom) in `HostState` rather than assuming 75. (H for the formula. M that Portal's client follows the SDK path exactly, since Portal's own `client.dll` source isn't public.)

### 2.4 Near and far planes

| | Near | Far | Source |
|---|---|---|---|
| Source main view | `VIEW_NEARZ` = **7** units (0.175 blocks). It is 3 only in HL1. **`r_nearz` does not exist in the SDK 2013 code**: `GetZNear()` just returns `VIEW_NEARZ`. | `r_farz` if ≥ 1. Otherwise `r_mapextents`·√3 = 16384·1.732 = **28378 units** (709 blocks), or the map's `env_fog_controller` farz if > 0. | `SDK/game/client/view.h:27-31`, `view.cpp:603-632`, `:643-653` (H) |
| Source viewmodel | 1 | same as the main view | `view.cpp:649,653`, `viewrender.cpp:1053-1054` (H) |
| Minecraft level | 0.05 blocks = 2 units (`Camera.java:43,109`) | `max(rd·64, cloudRange·16)` blocks | H |
| Minecraft hand | 0.05 | `depthFar` | `GameRenderer.java:676-679` (H) |

For occlusion, **the near and far values don't need to match**. Occluders and blocks are both drawn with Minecraft's own projection, so depth is consistent between them. Matching the near plane to 0.175 only makes Minecraft clip close blocks where Portal clips its walls. Reversed-Z D32F gives plenty of precision either way. Keep Minecraft's far plane beyond the map (render distance ≥ 8 gives 512 blocks). (H)

### 2.5 Eye position

- Source eye is origin + `m_vecViewOffset`: 64 standing, **28 ducked** (`VEC_DUCK_VIEW`, HL2 values that Portal appears to share; M). The client smooths it while ducking.
- Minecraft's eye is 1.62 blocks (64.8 units) standing and 1.27 (50.8 units) crouching.
- Don't use Minecraft's eye height. Set the camera position to `feet + hostViewOffset/40` in the `alignWithEntity` TAIL hook. That needs a ducked flag or the view-offset z in `HostState`.
- Standing differs by only 0.8 units, but crouching is off by 23 units. (M)

---

## 3. Depth-only occluders from Portal's brushes

### 3.1 Where to draw

- **Fabric API.** `LevelRenderEvents.START_MAIN` fires inside fabric-rendering-v1's `LevelRendererMixin.wrapRenderOpaqueTerrain`, just before `renderGroup(OPAQUE)` (javap of the 27.0.14 jar). Its context, `LevelTerrainRenderContext`, offers only `sectionsToRender()`, `gameRenderer()`, `levelRenderer()` and `levelState()`. **There is no RenderPass**, so you can't draw into the open pass. Every other `LevelRenderEvents` callback gets a `LevelRenderContext`, which offers `submitNodeCollector()` and `poseStack()`. Those submits are drawn as features, **after** opaque terrain. (H)
- **Cleanest option.** `@Inject(method="executeSolid(Lnet/minecraft/client/renderer/chunk/ChunkSectionsToRender;Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher$PreparedFrame;Lcom/mojang/renderpearl/api/commands/RenderPass;)V", at=@At("HEAD"))` on `LevelRenderer` (`:506`). That gives the open "Solid"/"Main" pass, already bound to the main colour and depth targets with `Projection`, `Fog` and `Globals` uniforms set (`:437-446`). Our draw then comes first, before opaque terrain. (H)
- **Alternative.** Add a separate frame-graph pass the way `addSkyPass` does (`:376-378`): `@Inject` at the HEAD of `addMainPass`, @Shadow `targets`, `frame.addPass("portalcraft_occluders")`, `readsAndWrites(targets.main)`, then open your own `RenderPass` on main colour and depth with `Optional.empty()` (no clear). (H)

**Depth-only versus holdout.** A depth-only occluder (`writeMask` 0) must be drawn **before** the blocks. A "holdout" occluder writes colour `(0,0,0,0)` with no blending, plus depth. It gives the same result **in any draw order**, because whichever surface is nearest wins the depth test. The holdout version could therefore also go through `submitCustomGeometry` as a solid feature. Both use the same shader. (H)

### 3.2 Pipeline

`WATER_MASK_SNIPPET` (`MC/.../RenderPipelines.java:130-139`, made public by Fabric's transitive access wideners) is already "Globals + Projection + DynamicTransforms, `core/rendertype_water_mask` (vsh: `ProjMat * ModelViewMat * Position`; fsh: `fragColor = ColorModulator`), `DefaultVertexFormat.POSITION`, QUADS, `DepthStencilState.DEFAULT`". `DepthStencilState.DEFAULT` is `GREATER_THAN_OR_EQUAL` with depth write on, which suits reversed Z. Vanilla's own `WATER_MASK` pipeline (`:908-912`) is exactly a depth-only pipeline: `ColorTargetState(..., writeMask 0)`.

```java
// dev/portalcraft/client/HostOccluders.java  (sketch, not compiled)
package dev.portalcraft.client;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Optional;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import dev.portalcraft.PortalCraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryUtil;

public final class HostOccluders {
	/** Depth only. For a "holdout" use WRITE_ALL (draw order then doesn't matter). */
	public static final RenderPipeline PIPELINE = RenderPipelines.register(   // register => precompiled on resource reload
		RenderPipeline.builder(RenderPipelines.WATER_MASK_SNIPPET)
			.withLocation(PortalCraft.id("pipeline/host_occluder"))
			.withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
			.withCull(false)
			.withColorTargetState(new ColorTargetState(Optional.empty(), GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_NONE))
			.withDepthStencilState(new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, true)) // reversed Z
			.build());

	private static GpuBuffer buffer;     // xyz float triangles, relative to `origin` (MC blocks)
	private static int vertexCount;
	private static Vec3 origin = Vec3.ZERO;

	/** Once per map / live-entity change, on the render thread. */
	public static void upload(float[] xyz, Vec3 meshOrigin) {
		if (buffer != null) buffer.close();
		ByteBuffer data = MemoryUtil.memAlloc(xyz.length * 4).order(ByteOrder.nativeOrder());
		data.asFloatBuffer().put(xyz);
		buffer = RenderSystem.getDevice().createBuffer(() -> "PortalCraft occluders", GpuBuffer.USAGE_VERTEX, data);
		MemoryUtil.memFree(data);
		vertexCount = xyz.length / 3;
		origin = meshOrigin;
	}

	/** Called from @Inject(HEAD) of LevelRenderer.executeSolid with its RenderPass. */
	public static void draw(RenderPass pass, CameraRenderState camera) {
		if (buffer == null || vertexCount == 0 || !HostDriver.linked()) return;
		Vec3 d = origin.subtract(camera.pos);                       // camera-relative, like all MC level geometry
		Matrix4f modelView = new Matrix4f(camera.viewRotationMatrix).translate((float) d.x, (float) d.y, (float) d.z);
		pass.setPipeline(RenderSystem.getCompiledPipeline(PIPELINE));
		pass.setUniform("DynamicTransforms", RenderSystem.getDynamicUniforms().writeTransform(modelView, new Vector4f(0, 0, 0, 0)));
		pass.setVertexBuffer(0, buffer.slice());
		pass.draw(vertexCount, 1, 0, 0);
	}
}

// mixin
@Mixin(LevelRenderer.class)
abstract class LevelRendererOccluderMixin {
	@Shadow @Final private LevelRenderState levelRenderState;
	@Inject(method = "executeSolid", at = @At("HEAD"))
	private void portalcraft$occluders(ChunkSectionsToRender sections, FeatureRenderDispatcher.PreparedFrame frame, RenderPass pass, CallbackInfo ci) {
		HostOccluders.draw(pass, levelRenderState.cameraRenderState);
	}
}
```

The API pieces were checked against the sources:
- `RenderPass.setPipeline` / `setUniform(String, GpuBufferSlice)` / `setVertexBuffer(int, GpuBufferSlice)` / `draw(int,int,int,int)`: `MC/com/mojang/renderpearl/api/commands/RenderPass.java:29-63`
- `GpuBuffer.USAGE_VERTEX = 32`: `GpuBuffer.java:15`
- `GpuDevice.createBuffer(Supplier,int,ByteBuffer)`: `GpuDevice.java:45`
- `DynamicGpuData.writeTransform(Matrix4f, Vector4f)`: `renderer/DynamicGpuData.java:63`
- `RenderSystem.getDynamicUniforms()` / `getCompiledPipeline`: `RenderSystem.java:362,117`
- `DefaultVertexFormat.POSITION` is RGB32_FLOAT (12 bytes): `DefaultVertexFormat.java:17,60`
- `withLocation(Identifier)`: `RenderPipeline.java:164`
- The same draw pattern as `SkyRenderer.renderSkyDisc`: `SkyRenderer.java:157-165`
- An unregistered pipeline also works; it compiles synchronously on first use (`PipelineCache.get`, `PipelineCache.java:27-40`).

Two things are **unknown**: whether `withPrimitiveTopology` after a snippet overrides the snippet's QUADS (likely yes, M), and whether the device must be ready when the static initializer runs (register it from the client initializer; M).

### 3.3 Building the mesh from brushes

`BspMap.Brush.planes` holds Source-space `(n, d)`, with inside where `n·p ≤ d`. For each plane, take a large quad on the plane and clip it with Sutherland–Hodgman against every other plane of the same brush. Fan-triangulate the result and convert the points with `Units.toMc`.

The data has caveats:
- `BspMap` keeps **every player-solid brush** (`MASK_PLAYERSOLID` = SOLID | WINDOW | GRATE | MOVEABLE | PLAYERCLIP, `BspMap.java:34-39`):
  - **PLAYERCLIP brushes are invisible.** Glass (WINDOW) and grates are see-through. As occluders they would hide blocks that Portal shows.
  - Keep each brush's `contents` and occlude only with `CONTENTS_SOLID` that isn't WINDOW, GRATE or PLAYERCLIP. Whether `toolsinvisible`-style brushes are flagged SOLID is **unknown**.
- Static props come from `.phy` hulls. `LiveEntities` adds `COLLISION_MARGIN` when building them (`LiveEntities.java:146`), which inflates the shape. Build occluders with margin 0.
- Visible but non-solid geometry (displacements, `func_illusionary`, overlays, props without collision) won't occlude.
- **Don't cut holes at portals.** `HostCollision.setPortals` makes holes for walking only. Where a portal is open, Portal shows the far side, so a Minecraft block behind that wall must stay hidden. Blocks seen *through* a portal can't be shown by this approach at all.
- Blocks are on a 40-unit grid and Portal walls on a 64/128-unit grid, so blocks can poke into walls. That is handled correctly by depth. To avoid z-fighting where a block face touches a wall, move occluder faces back about 0.25 units along their normals, or use `DepthStencilState`'s depth-bias fields. The sign of the bias under reversed Z is **unknown**, so test it.

---

## 4. How `PortalRenderer` submits geometry (for comparison)

`src/client/java/dev/portalcraft/client/PortalRenderer.java` is an `EntityRenderer`:
- `submit(state, poseStack, SubmitNodeCollector collector, camera)` calls `collector.submitCustomGeometry(poseStack, RenderType, (pose, buffer) -> quad(...))`.
- The lambda writes 4 vertices (position, colour, UV, overlay, `LightCoordsUtil.FULL_BRIGHT`, normal) into a `VertexConsumer` every frame, while `FeatureRenderDispatcher.prepareFrame` runs (`LevelRenderer.java:203-205`). The data is re-uploaded each frame.
- The RenderType is `RenderTypes.entityTranslucentEmissive`, so it is drawn in the translucent feature phase.
- A custom `RenderType` would be `RenderType.create(name, RenderSetup.builder(pipeline)....createRenderSetup())` (`rendertype/RenderType.java:43`, `RenderSetup.java:81`).

That path is fine for small geometry that moves. It is wrong for depth-only occluders because:
1. it re-uploads up to 100k vertices every frame;
2. features are drawn after opaque terrain (`executeSolid` `:512` then `:514`);
3. entity frustum culling uses `getBoundingBoxForCulling`.

The holdout variant (§3.1) would work through it, but the persistent GPU buffer above is better.

---

## 5. Lighting and colour in the void world

Terrain colour is `texture × vertexColor × lightmap(UV2)` (`terrain.vsh`, `sample_lightmap.glsl`). The vertex colour carries the per-face shading `CardinalLighting.DEFAULT` (down 0.5, up 1.0, N/S 0.8, W/E 0.6), plus AO and tint. The lightmap (`core/lightmap.fsh`) is `max(Ambient, NightVision) + SkyLightColor·sky_brightness·SkyFactor + block light`, then the boss darkening, darkness and the gamma ("brightness") curve. `SkyFactor` and `SkyLightColor` follow the time of day through environment attributes (`LightmapRenderStateExtractor.java:53-66`).

| Option | How | Effect | Conf. |
|---|---|---|---|
| **Full-bright, constant (recommended)** | `@Inject(method="extract(Lnet/minecraft/client/renderer/state/LightmapRenderState;F)V", at=@At("RETURN"))` on `LightmapRenderStateExtractor` (`:43`). When linked, set `ambientColor = LightmapRenderStateExtractor.WHITE` (or a slightly cool `~(0.88,0.90,0.95)`), `skyFactor = 1`, `skyLightColor = WHITE`, `brightness = 1`, and darkness, night vision and boss darkening to 0. `Lightmap.render` only redraws when `needsUpdate` (`Lightmap.java:60-61`); `tick()` sets it every tick (`:35`). | Every light level gives the same colour. Face shading and AO still give shape, which suits Portal's evenly lit chambers. The first-person hand gets the same treatment, since it uses the level lightmap. | H |
| Fixed time | `/gamerule advance_time false` and `/time set noon` (the `advance_time` / `advance_weather` names were checked in `game_rules.dat`), plus clear weather | Sky light 15 at noon is close to full-bright and needs no code. Still depends on the gamma option and dimension attributes. | M |
| Gamma | `options.gamma` gives only the `BrightnessFactor` curve | Not enough on its own at night | H |
| Match Portal's light | The BSP has per-leaf **ambient cubes** (`LUMP_LEAF_AMBIENT_LIGHTING[_HDR]`, lumps 55 and 56, with index lumps 51 and 52; these are what Source uses to light models). Sample the leaf at each block and tint. In Minecraft that needs a per-block tint hook, which is **unknown** effort. | Best match | L |

---

## 6. Cost and caching

- **Brush to polygons.** Each brush has n planes, typically 6–20, and clipping costs O(n²) work, a few microseconds per brush. A whole Portal map of about 2–10k brushes, static-prop hulls included, takes under about 20 ms on the CPU. Do it **once per map load**, next to `HostCollision.setMap`, on a worker thread, and upload on the render thread.
- **Mesh size.** A box brush is 12 triangles, or 36 vertices at 12 B = 432 B. 3k brushes is about 1.3 MB, and even 20k brushes is about 9 MB. **Draw the whole map** in one non-indexed call; there's no need to pick the brushes near the player. On the GPU this is a depth-only pass of 36k–240k triangles with early-Z: about 0.05–0.3 ms on the RTX 5070 Ti. (M)
- **Live entities** (doors, lifts, `func_brush`, cubes from `LiveEntities.update`): keep a second small buffer. Rebuild it only when `LiveEntities` reports a change, or redraw each entity's local mesh with its own model-view matrix (one `writeTransform` per entity), which avoids rebuilding.
- **Precision.** Store vertices as float relative to a mesh origin, and put `origin − camPos` into the model-view each frame (as in the sketch). Portal maps span ±410 blocks, which is fine for floats.
- **Minecraft side overall.** Turning the level back on costs one void-world level frame: a few sections, entities and particles. That is negligible next to the readback that already happens.

---

## 7. Alternative: draw the blocks host-side in Portal's D3D9 frame

### 7.1 Is Portal's depth still valid at Present?

| Issue | Finding | Conf. |
|---|---|---|
| Viewmodel depth handling (Portal-specific) | `CViewRender::DrawViewModels`: `#ifdef PORTAL bUseDepthHack = !LocalPlayerIsCloseToPortal(); if (!bUseDepthHack) pRenderContext->ClearBuffers(false, true, false);` (`SDK/game/client/viewrender.cpp:1069-1074`). Otherwise `DepthRange(0, 0.1)` (`:1085`). **Close to a portal, the depth buffer is cleared before the gun is drawn**, so at Present it holds only the gun. Away from portals the gun pixels hold depth in [0, 0.1], which correctly puts the gun in front of blocks. | H (SDK). M that Steam Portal matches. |
| Order after the scene | ViewDrawScene, `engine->DrawPortals()` (`:2014`), `SceneEnd` (`:2019`), `DoPostScreenSpaceEffects(&view)` (`:2040`), `DrawViewModels` (`:2043`), fade/overlay, engine post-processing (bloom/tonemap), screen-space effects, then the HUD/VGUI. Post and HUD passes are full-screen quads or 2D; they almost certainly don't write depth. | M |
| MSAA | With `mat_antialias`, the backbuffer and depth surface are multisampled. Drawing into the MSAA backbuffer with the matching MSAA depth bound is valid D3D9. Whether the depth surface bound at Present is the main one, and whether its sample type matches the backbuffer, is **unknown**. Check with `GetDepthStencilSurface` + `GetDesc` in the hook. `overlay.cpp` currently unbinds depth (`SetDepthStencilSurface(nullptr)`). | unknown |
| Readback | Not needed. D3D9 can't sample depth without vendor INTZ, but a depth test against the bound buffer is enough. | H |
| View matrix | No camera is available at Present. `IVEngineClient::WorldToScreenMatrix()` (VEngineClient013, already used by the plugin) is the obvious source, but whether it holds the main view at Present, and its Z mapping, is **unknown**. Source uses standard Z (`LESSEQUAL`, [0,1]), not reversed. | unknown |

**A better host hook than Present:** run **before `DrawViewModels`**. The candidates are a vtable hook on `IVRenderView::SceneEnd` (engine interface "VEngineRenderView014", reachable from the plugin's engine factory) or on `ClientModeShared::DoPostScreenSpaceEffects(const CViewSetup*)`. The latter receives the main `CViewSetup` (origin, angles, fov, zNear, zFar, aspect) at `viewrender.cpp:2040`. At that point depth holds the whole main scene, including portal contents, glass, models, cubes and doors. The viewmodel and its possible depth clear haven't run yet, and the HUD, fades and colour correction draw on top afterwards, as they should. Finding `g_pClientMode` from a server plugin needs a scan of client.dll: **unknown** effort, M.

### 7.2 Comparison

| | Minecraft draws it (§1–6) | Host draws it (SkyCraft model) |
|---|---|---|
| Lock to Portal's camera | **Latency.** UDP, then 20 Hz tick-lerped rotation (fixable with §2.2), then the MC frame, then async readback (≥1 frame, `FrameExporter`), then the next Portal Present: roughly 25–80 ms. At 200°/s that is 5–16° of error, so blocks slide on every turn. Rotation-only "timewarp" of the overlay quad in the plugin could hide most of it, but the HUD shares that image. | Exact. Same frame, same matrices. |
| Occlusion | Approximate. Static brushes plus live-entity hulls. Needs contents filtering (clip, glass). Misses non-solid visible geometry and anything else that moves. | Exact. Portal's own depth. |
| Blocks seen through portals | Impossible | Possible later: draw again inside portal views. |
| What gets drawn | Everything Minecraft draws: blocks, block entities, items, mobs, particles, outline, breaking animation. | Only what is exported. SkyCraft already exports block and fluid meshes made by Minecraft's own `ModelBlockRenderer` (tint, AO, light), the atlas with animated sprites, item drops, arrows and the player avatar (`SkyCraft/.../render/WorldExporter.java`, `SkyAtlas.java`, `AvatarExporter.java`). Mobs would be extra work. |
| Work | Small: about 6 mixins, the occluder builder and an option override. | Larger: port the exporter, a shared-memory or UDP mesh channel, and a D3D9 textured-mesh renderer in the plugin (FFP is enough: vertex colour × texture, alpha test for cutout, blending for translucent), plus the client hook. |
| Fragility | Camera matching (FOV, eye height, roll) must be exact or blocks drift. | Hooking Portal's client render, and the view matrix source. |

**Recommendation:** go host-side for blocks, drawn from a hook before `DrawViewModels`. If that hook is too hard, draw at Present after checking its depth surface; it is still better than Minecraft drawing, apart from the clear near portals. Keep the Minecraft overlay for hand and HUD. If a quick visual is wanted first, the Minecraft-side path in §1–3 is a day or two of work. Make rotation per-frame (§2.2) to judge the remaining lag before committing. Confidence: M. The latency argument is solid; the host hook's feasibility is the unknown.

---

## 8. Unknowns to verify

1. Whether Steam Portal's `client.dll` matches the SDK's hor+ FOV and `VIEW_NEARZ` = 7. Check by comparing a screenshot at fov 75 with Minecraft at 59.84°.
2. Whether the roll sign and Portal's post-portal roll show up in `GetViewAngles`.
3. Portal's duck view offset (expected 28).
4. Whether `menuBackgroundBlurriness` = 0 skips the blur pass.
5. Whether `withPrimitiveTopology` overrides the snippet's QUADS.
6. Sign of the depth bias under reversed Z.
7. For the host-side path: the depth surface at Present (MSAA, which surface is bound), what `WorldToScreenMatrix()` contains at that point, and how to reach `IVRenderView::SceneEnd` or `g_pClientMode` from the plugin.
8. Which `CONTENTS_*` flags invisible tool brushes such as `toolsinvisible` carry in Portal maps.
