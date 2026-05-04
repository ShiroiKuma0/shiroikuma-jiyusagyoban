# OpenTasker Logo/Icon Generation Prompts

**5-Prompt Branding Flow** for OpenTasker: an open-source Android automation and task scheduling app.

Project tagline: "Empower your Android with intelligent automation and task execution."

---

## ⚠️ CRITICAL REQUIREMENTS (Non-Negotiable)

Every prompt below MUST output:
- **PNG with alpha channel (RGBA)** — transparent background (α=0) everywhere outside the glyph
- **NO flattened/white backgrounds** — icon must composite cleanly onto any surface (dark, light, colored)
- **High contrast** — readable on BOTH light and dark backgrounds
- **SVG-friendly geometry** — clean shapes, minimal/no gradients at small sizes
- **Minimum 512x512px** for highest quality; request PNG with transparency explicitly
- **NO text** in Prompts 1-5 (Wordmark is typography-only, but still transparent bg)

**After generation, verify with**:
```bash
magick identify -format '%[channels]' icon.png
# must return: rgba or srgba (NOT rgb — that means transparency was lost)
```

If the generator returns a flattened PNG, request regeneration or post-process with background removal before shipping.

---

## Prompt 1: Minimal Icon

**Use case**: Favicons, toolbar icons, small UI elements (16-128px)

```
Create a minimal, single-color icon glyph for "OpenTasker" (open-source Android automation app).
The icon should be a simple, flat checkmark merged with an automation/circuit element.

Requirements:
- Transparent background (PNG RGBA, no white/color fill)
- Single color or monochrome (works on any background)
- Geometric, SVG-friendly geometry
- Recognizable at 16x16, 32x32, 64x64, 128x128 pixels
- High contrast with both light and dark surfaces

Visual concept: Stylized checkmark flowing into or interlocking with a simple gear tooth or 
circuit path. Minimalist, bold strokes, no gradients.

Reference: Icons from Figma, Slack, or GitHub Actions — ultra-simple, geometric, timeless.

Output: PNG 512x512px with full alpha transparency. No background fill.
```

---

## Prompt 2: App Icon

**Use case**: Android adaptive icon, app store listings, launcher shortcut (192-512px)

```
Create a professional app icon for "OpenTasker" — an open-source Android automation platform 
that lets users create complex task automations triggered by device context (location, time, 
network state) and execute sequences of actions.

Style: Modern, premium, minimalist, tech-forward, geometric.

Visual metaphor: A stylized checkmark (task completion) merged with flowing circuit paths or 
nested gears (automation/processing). The icon should convey intelligence, reliability, and 
elegant simplicity.

Design requirements:
- Transparent background (PNG RGBA)
- Rounded square or circle format (works as Android adaptive icon)
- High contrast on light and dark surfaces
- Geometric, clean lines, minimal gradients
- Single accent color or complementary dual-color palette
- Professional enough for app store display
- Recognizable at 192x192, 256x256, 512x512 pixels

Color palette (Catppuccin Mocha theme):
- Primary accent: #89b4fa (vibrant blue) or #a6e3a1 (fresh green)
- Secondary: #bac2de (muted lavender) or #cba6f7 (purple)
- Background: TRANSPARENT (not white, not dark — alpha channel required)

Reference aesthetic: Logos of Zapier, IFTTT, Discord, or GitHub Actions. Instantly recognizable, 
geometric, premium feel.

Output: PNG 512x512px with full alpha transparency.
```

---

## Prompt 3: Wordmark

**Use case**: README headers, splash screens, branding contexts where the name needs to be visible

```
Create a modern, clean wordmark for "OpenTasker" using the project name and a subtle automation 
symbol.

Requirements:
- Transparent background (PNG RGBA)
- Typography-focused but includes a small geometric glyph or icon to the left of the text
- The glyph should suggest automation or task completion (checkmark, circuit, or simple gear)
- High contrast, readable at 128x128px and larger
- Professional, tech-forward aesthetic
- Geometric, minimalist style (not script, not serif unless very modern)

Font style: Modern sans-serif (geometric, clean) — think Inter, Outfit, or DM Sans. 
Glyph color: #89b4fa (blue) or #a6e3a1 (green), contrasting with the text.
Text color: #cdd6f4 (light) or #2d2c3c (dark) depending on intended background.

The wordmark should work on dark backgrounds (README hero) and light backgrounds (documentation).

Output: PNG 512x512px with full alpha transparency. Text and glyph must be opaque, background 
must be transparent.
```

---

## Prompt 4: Emblem

**Use case**: README hero sections, splash screens, feature badges, about/credits screens

```
Create a modern emblem or badge for "OpenTasker" — a stylized checkmark merged with automation 
metaphors, framed as a geometric crest, circle, or shield.

Style: Premium, balanced, bold, symmetrical, iconic.

Visual concept:
- Center: Checkmark flowing into or interlocking with gears, circuit paths, or nested loops
- Frame: Clean geometric border (circle, hexagon, rounded square, or shield)
- Feeling: Trustworthy, intelligent, open-source, tech-forward

Design requirements:
- Transparent background (PNG RGBA)
- Works at 256x256, 512x512, 1024x1024 pixels
- High contrast on light and dark surfaces
- Geometric, balanced composition
- Color palette: #89b4fa (blue) or #a6e3a1 (green) with subtle secondary color

The emblem should feel premium enough for:
- README header
- App splash screen
- Feature announcements
- About/credits screens

Reference: Emblems of Firefox, Blender, or GitLab — bold, recognizable, professional.

Output: PNG 512x512px (or larger) with full alpha transparency.
```

---

## Prompt 5: Abstract/Conceptual

**Use case**: Social media, blog headers, concept art for documentation

```
Create an abstract, conceptual representation of "OpenTasker" — capturing the essence of 
intelligent automation and task execution.

Concept: Show the flow of tasks being automated, processed, and completed. The image should 
suggest:
- Continuous processing and workflow execution
- Intelligence and automated decision-making
- Simplicity and elegance despite complexity
- Open-source (connected, distributed, collaborative)

Visual metaphors to consider:
- Flowing lines or paths forming checkmarks
- Orbiting or interconnected nodes representing tasks
- Gears or circuit patterns in motion
- Nested loops or cyclical flow patterns
- A stylized "check engine light" or task completion beacon

Style: Modern, geometric, minimalist, premium, tech-forward.

Design requirements:
- Transparent background (PNG RGBA)
- Works at 512x512, 1024x1024 pixels
- High contrast, visually striking
- Color palette: #89b4fa or #a6e3a1 as primary, with complementary accents

The abstract should feel:
- Conceptual but not cryptic
- Modern and forward-thinking
- Suitable for blog headers, social media, and concept presentations

Reference: Abstract illustrations from Slack, Linear, or Notion. Geometric, meaningful, 
visually interesting.

Output: PNG 512x512px or larger with full alpha transparency.
```

---

## Summary: What to Generate

| Prompt | Use Case | Size | Priority |
|--------|----------|------|----------|
| Minimal Icon | Favicons, 16-128px UI | 512x512px | High |
| App Icon | Launcher, store listings | 512x512px | High |
| Wordmark | README headers, branding | 512x512px | Medium |
| Emblem | Feature badges, splash | 512x512px or larger | Medium |
| Abstract | Social media, blog | 512x512px+ | Low |

---

## Color Palette Reference (Catppuccin Mocha)

Primary accents to use (pick one per logo):
- **Blue**: #89b4fa (vibrant, tech-forward) ← Recommended for most variants
- **Green**: #a6e3a1 (fresh, positive, "automation go")
- **Mauve**: #cba6f7 (premium, distinctive)
- **Pink**: #f5c2e7 (modern, approachable)

Secondary/context colors:
- Lavender: #bac2de (muted, background)
- Dark gray: #45475a (structure, edges)
- Text light: #cdd6f4
- Text dark: #2d2c3c

---

## Generator Instructions

### For DALL-E 3:
- Paste the prompt directly
- Specify "transparent PNG with alpha channel"
- Request "512x512px minimum"
- If result has white background, use background removal tool (e.g., remove.bg) before committing

### For Midjourney:
- Use `--ar 1:1` for square output
- Add `--niji` for illustration style or `--s 750` for higher quality
- After generation, remove background if needed
- Request regeneration with "Make background transparent" if flattened

### For Any Generator:
- Iterate if needed: "Make it simpler," "Use more blue," "Larger checkmark," "More geometric"
- Always verify output has alpha channel before committing
- Generate multiple color variants (blue, green, etc.) so you can pick later

---

## Post-Generation Verification Checklist

Before committing any logo:

- [ ] PNG file (not JPEG)
- [ ] Alpha channel present: `magick identify -format '%[channels]' icon.png` returns `rgba` or `srgba`
- [ ] Readable at 16x16px (minimal icon)
- [ ] Readable at 128x128px (standard icon)
- [ ] High contrast on WHITE background
- [ ] High contrast on #1e1e2e (dark background)
- [ ] No gradients breaking at small sizes
- [ ] Geometric, clean shapes
- [ ] Professional/premium feel
- [ ] Unique and memorable (not generic)

---

## Integration Into Repo

Once logos are generated and verified:

1. **Create icon directory**: `app/src/main/res/mipmap-xhdpi/`
2. **Add variants** for all densities:
   - `mipmap-ldpi/` (36x36)
   - `mipmap-mdpi/` (48x48)
   - `mipmap-hdpi/` (72x72)
   - `mipmap-xhdpi/` (96x96)
   - `mipmap-xxhdpi/` (144x144)
   - `mipmap-xxxhdpi/` (192x192)

3. **Android adaptive icon setup**:
   - `ic_launcher_foreground.png` (all sizes)
   - `ic_launcher_background.xml` (solid color: #1e1e2e or transparent)
   - `res/mipmap-anydpi-v26/ic_launcher.xml` (references foreground+background)

4. **Update manifest**: `AndroidManifest.xml` → `android:icon="@mipmap/ic_launcher"`

5. **README header**: Center the app icon at top of README.md

---

## Final Checklist Before Committing

- [ ] All 5 logos generated (Minimal, App, Wordmark, Emblem, Abstract)
- [ ] All PNGs have alpha channel (verify with `magick`)
- [ ] Icon placed in correct mipmap directories
- [ ] AndroidManifest.xml updated
- [ ] README.md header updated with logo
- [ ] `git add` all icon files
- [ ] Commit: "Add OpenTasker app icon and branding assets"
- [ ] Push to main branch

---

**Good luck generating your logos!** 🎨
