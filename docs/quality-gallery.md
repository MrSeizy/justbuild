# Quality evaluation gallery

The gallery gives prompt, guide and model changes a repeatable set of scenes to compare. It records real Minecraft
screenshots, functional acceptance checks and provenance. Human review still decides whether a build looks good.

The default run uses fixed plans and never calls an AI provider. It validates the evaluation harness and placement
behavior; it is not evidence of model quality. Live and imported runs use the same scenes and checks and are labeled
separately in the report.

## Scenarios and acceptance

| Scene | Required behavior |
| --- | --- |
| Cottage | 9×7 oak cottage, spruce gable roof, complete two-block north door, clear inside/outside approaches, reachable ground-floor spaces and a straight three-wide approach. |
| Interior | Bed, crafting table and bookshelves; exact shell preservation; existing chest and seven diamonds preserved; accessible circulation on the walking floor. Furniture tops are excluded from floor reachability. |
| Bridge | Continuous three-wide oak deck at y=0 across a stream, continuous oak fence rails at y=1 along both full edges x=0..12,z=±2, and support piers; accessible crossing; water outside the work area preserved. |
| Terrain | Level stone-brick terrace on a two-level hillside, support under every floor cell, stair approach and unchanged hillside outside the work area. |
| Editing | Replace oak wall planks with bricks while keeping the complete footprint and every other block state, door, window, roof, approach and existing inventory. |
| Castle (opt-in) | Large 55×55 site, four upper towers, an open courtyard, three connected indoor levels and elevated wall walks reached from the north approach. Architecture and palette are chosen by the provider. |

The five default cases enforce 100 operations, 2,500 changed cells and a maximum 32-block axis. The opt-in castle
uses the normal mod defaults (2,000 operations, 250,000 cells, 96-block axis) within its 55×55 site and y=-2..30,
plus a short north approach. Every case enforces valid block IDs and scenario bounds.
They use the real plan rasterizer, structure completion, protection, placement ordering, neighbor refresh and
walkability code. Invalid out-of-scope requests fail acceptance and are clipped before rendering in the test world.
Screenshots show that placed result; raw candidate plans remain available for diagnosing what was rejected.
Ground-floor circulation checks require a continuous route around furniture; crossing over beds or chests is not counted.
Roof-stair/support notes remain visible as advisories because decorative roof geometry can trigger them.
The bridge's rail check inspects all 26 fence positions in the placed world; fences elsewhere or at the wrong height
cannot pass by meeting the material quota. Scene resets remove dropped item entities from the disposable gallery
world, and every screenshot clears any later drops after the view has settled. This leaves players, other entities
and retained chest inventories intact.

## Run deterministic fixtures

```sh
JUSTBUILD_GALLERY_LABEL=fixtures ./gradlew runClientGameTest -PclientTests=QualityGalleryGameTest
```

Include `GalleryChecksGameTest` in `clientTests` to exercise the cleanup regression and rail counterexamples:
enough fences at deck level, raised above the railing line, or moved away from a gap must fail the rail check.

The command prints an output directory under `build/quality-gallery/`. Each run has a unique timestamp and survives
recreation of `build/run/clientGameTest/`. It contains `results.json`, before/after PNGs, an inside-room PNG, each
candidate plan and the exact prompt. The result records provider/model, detail/review settings, revision, guide
hash, prompt hash, plan hash and a hash of the actual initial blocks and inventory. A dirty revision is explicitly
labeled; retain the corresponding checkout to reproduce it. The harness hash includes the compiled gallery,
cleanup/rail helpers, castle scene and castle route probes.

Generate a self-contained report, replacing the example directories with the printed paths:

```sh
python3 scripts/quality_gallery.py build/quality-gallery/fixtures-TIMESTAMP \
  --output build/quality-gallery/index.html
```

Pass multiple runs to compare them side by side. Use **Before**, **After** and **Inside** to inspect views; expand
**Request and provenance** for the exact scenario request and hashes. Changed source hashes mean the starting scenes
were different and should not be treated as a controlled comparison.

```sh
python3 scripts/quality_gallery.py build/quality-gallery/before-TIMESTAMP \
  build/quality-gallery/after-TIMESTAMP --output build/quality-gallery/comparison.html
```

## Opt-in live and imported plans

A live run invokes an installed, authenticated Claude, Codex or Grok Build CLI and can consume account usage.
Quick detail makes one planning call with no guide selection or review. Detailed and masterpiece runs first offer
the guide menu: the AI can select up to six references in one additional call before returning the plan. The gallery
records `selectedGuides`, `guideSelectionMillis`, the initial response and the actual plan prompt; reviews retain
those references. `planningMillis` includes selection and drafting. No clarification or world-inspection rounds are
allowed in this controlled evaluation. A CLI may also make its own title or session-summary calls.
The gallery is separate from the mod's full interactive planning workflow.
Set `JUSTBUILD_GALLERY_DETAIL=detailed` for one review pass, or `masterpiece` for two. Reviews run through the same
`ReviewRounds` loop as the mod: by default (`JUSTBUILD_GALLERY_REVIEW=separate`) a separate critique, then a repair only
when the critique or the checks call for one, as the mod runs with a reviewer profile chosen;
`JUSTBUILD_GALLERY_REVIEW=self` runs the builder's own review, the mod's default (**Same as builder**), for comparison.
With `separate`, `JUSTBUILD_GALLERY_REVIEWER_MODEL` gives the critiques their own model on the same provider, like a
reviewer profile; repairs keep `JUSTBUILD_GALLERY_MODEL`. Each call's model is in `<case>-reviews.json`.
Critiques and repairs receive the same `PlanningCheck` diagnostics and source/candidate block-color images used by the
mod, plus the candidate's facades, and a repair keeps the previous plan when it fails, exceeds limits, or fails the
production `ReviewCheck` comparison: any technical category gets worse, an existing block retained by the
draft is removed, or a bounded route comparison detects a newly blocked doorway or disconnected remaining floor space.
Each critique and repair is a separate provider call; `*-reviews.json` lists every call with its stage and time, and
`*-review-rounds.json` each round's critique, whether it repaired, and the outcome.
The gallery saves the first draft, its checks and screenshots, every review prompt/response, acceptance status and
timing, and the final plan. `<key>-evidence.txt` is the text of the first draft's checks as the reviewer receives them,
always with the route findings that reviewed requests get (even at quick detail, where production has none), and each
row's `evidenceMillis` is how long those checks took, including the hand-off to the server thread. Source blocks are
restored and their hash verified after photographing the draft.
Fixture reviews answer with a fixed one-problem critique and replay the fixture plan locally. They are labeled
`fixture replay` and never call an AI.

For a paired comparison of review modes, collect drafts once with `JUSTBUILD_GALLERY_REVIEW=none` at `detailed` (the
detail level's draft prompt, no reviews), copy each case's `<scenario>.json` and, when present,
`<scenario>-guides.json` into one directory, and review those same drafts live in separate runs with
`JUSTBUILD_GALLERY_PLANS=<that directory>`, a provider, a detail level above quick, and
`JUSTBUILD_GALLERY_REVIEW=self` or `separate`. Both modes' reviews then see the same guide text; without a guides file
an imported draft carries no guide selection.

Importing drafts with `JUSTBUILD_GALLERY_REVIEW=none` is a dry pass: with the fixture provider, each case writes
`<key>-evidence.txt` and `evidenceMillis` for the imported draft and renders it, with no provider call. Use it to read
the review evidence, such as the route findings, before spending live reviews.

Use `JUSTBUILD_GALLERY_CASES=cottage,interior,bridge` to select scenes and `JUSTBUILD_GALLERY_REPEATS=2` for repeated
independent attempts (1–5, default 1). The same source location, daylight, weather and camera are used throughout.
Quick runs exercise planning guidance only; use detailed runs to assess review changes. Each CLI call, including
selection, defaults to ten minutes without stdout/stderr output, recorded as `timeoutSeconds` with `timeoutMode=idle`.
Set `JUSTBUILD_GALLERY_TIMEOUT_SECONDS` from 1 to 3,600 seconds; `1800` allows thirty minutes of silence in each
selection, draft or review. Activity renews that deadline, so the full live run has no fixed duration. CI does not
enable live runs.

```sh
JUSTBUILD_GALLERY_PROVIDER=codex JUSTBUILD_GALLERY_MODEL=MODEL_ID \
  JUSTBUILD_GALLERY_LABEL=codex-candidate \
  ./gradlew runClientGameTest -PclientTests=QualityGalleryGameTest
```

For a repeated design-and-review comparison:

```sh
JUSTBUILD_GALLERY_PROVIDER=claude JUSTBUILD_GALLERY_MODEL=MODEL_ID \
  JUSTBUILD_GALLERY_DETAIL=detailed JUSTBUILD_GALLERY_REPEATS=2 \
  JUSTBUILD_GALLERY_CASES=cottage,interior,bridge JUSTBUILD_GALLERY_LABEL=design-candidate \
  ./gradlew runClientGameTest -PclientTests=QualityGalleryGameTest
```

This requests six drafts and six reviews, plus up to six guide-selection calls. Keep model, scene, limits, detail level and harness hashes matched between
baseline and candidate, while recording the intentional prompt/guide differences. These small, tightly specified
scenes test detail, composition within their bounds and usability; they do not establish quality across all styles.

Use `claude` for Claude CLI or `grok` for Grok Build CLI. Omitting the model uses that CLI's current default, which is recorded
as unspecified; set a model explicitly for meaningful model comparisons. Live raw responses are saved beside plans.
An initial or draft response other than the offered single guide selection or a build makes the case fail, as does failed final acceptance. A non-build review response records a failed review and retains the previous plan, which can still pass final acceptance. Completed results are saved in each case.

To compare plans obtained elsewhere without making provider calls, create a directory containing `cottage.json`,
`interior.json`, `bridge.json`, `terrain.json` and `editing.json` in the mod's build-plan format:

```sh
JUSTBUILD_GALLERY_PLANS=/absolute/path/to/plans JUSTBUILD_GALLERY_LABEL=imported-candidate \
  ./gradlew runClientGameTest -PclientTests=QualityGalleryGameTest
```

An imported run's recorded prompt describes the scenario; it does not claim to be the prompt used to produce those
external plans. Keep external model and prompt provenance alongside those files. Imported plans can be reviewed by a live
provider at detailed or masterpiece detail; at quick detail they are only rendered and checked, and their evidence is
written. These checks supplement the permission, cancellation, undo and relay tests; they do not replace them.

## Large castle test

The castle is opt-in and does not expand the default fixture suite:

```sh
JUSTBUILD_GALLERY_PROVIDER=claude JUSTBUILD_GALLERY_MODEL=claude-sonnet-5-5 \
  JUSTBUILD_GALLERY_CASES=castle JUSTBUILD_GALLERY_DETAIL=masterpiece \
  JUSTBUILD_GALLERY_LABEL=claude-castle \
  ./gradlew runClientGameTest -PclientTests=QualityGalleryGameTest
```

This makes one draft call and two reviews, plus one guide-selection call if the AI requests references. Omit the provider/model to calibrate the harness with the deterministic
castle fixture. The default site is 55×55; set `JUSTBUILD_GALLERY_CASTLE_SIZE=30` for the compact 30×30 brief.
Both sizes retain four towers, three furnished indoor levels and a 22–30-block silhouette. Bounds, fixture,
protection marker, cameras and route samples follow the selected size, which is saved as `siteSize`.

For a thirty-minute request allowance on the compact castle:

```sh
JUSTBUILD_GALLERY_PROVIDER=claude JUSTBUILD_GALLERY_MODEL=claude-sonnet-5-5 \
  JUSTBUILD_GALLERY_CASES=castle JUSTBUILD_GALLERY_CASTLE_SIZE=30 \
  JUSTBUILD_GALLERY_TIMEOUT_SECONDS=1800 JUSTBUILD_GALLERY_CASTLE_OPERATIONS=300 \
  JUSTBUILD_GALLERY_DETAIL=masterpiece JUSTBUILD_GALLERY_LABEL=claude-castle30 \
  ./gradlew runClientGameTest -PclientTests=QualityGalleryGameTest
```

Each call allows thirty minutes without CLI output and can run longer while output continues.
Set `JUSTBUILD_GALLERY_CASTLE_OPERATIONS=300` to test a more compact plan with the same physical
brief and block budget; the optional cap must be between 1 and the normal operation limit. It applies only to castle
and is recorded in each result. Provider phase, operation counts and final token totals are logged without reasoning
text. The brief asks for a dominant keep, four towers, gatehouse, courtyard, inhabited rooms and connected
stairs, while leaving the layout and palette open. The report includes front/rear exteriors, a gate view, overhead,
and explicitly labeled cutaways below y=6 and y=12 for both draft and final. The compact variant also captures
y=5, y=10 and y=15, so common five-block storeys are visible rather than hidden beneath their next floor. Cutaway blocks are restored before the
next view or check; these are inspection views of the saved plan, not claims about how the full exterior looks.

The castle probes use the production standing-space graph anchored outside the north approach. They require at
least 100 reachable central open-sky spaces, three separated covered levels with at least 24 reachable spaces each,
at least eight elevated reachable spaces in each corner-tower region, and 40 along middle wall sections. The result
records actual counts. The 30×30 variant requires a 26×26 footprint, 25 courtyard spaces and 20 wall-walk spaces;
its three covered levels still require 24 spaces each and every upper tower still requires eight. The compact
wall-walk sample uses the north, east and west middle sections, excluding the south where the keep can join the
curtain wall, so upper keep rooms cannot substitute for that route. All three sampled sides must contain reachable
spaces, with at least 20 in total. These thresholds
are fixed before live runs and are not a controlled comparison with the larger brief. Covered platforms can satisfy the covered-level probe without being enclosed rooms;
enclosure and furnishing are checked visually. These spatial samples do not prove that every named room, stair,
roof or parapet works. The castle uses a separate origin so later ordinary scenes cannot inherit its scenery.
Roof hierarchy, inhabited detail, materials and overall composition still require visual inspection. Requirements
such as kitchen versus library are assessed from the saved plans and images rather than arbitrary block quotas.
