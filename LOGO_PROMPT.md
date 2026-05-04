# OpenTasker Logo/Icon Generation Prompt

Use this prompt with DALL-E 3, Midjourney, or your preferred AI image generator to create a professional logo/icon for OpenTasker.

---

## Main Prompt (DALL-E 3 / Midjourney)

```
Create a professional, modern app icon logo for "OpenTasker" — an open-source Android automation 
and task scheduling platform. The app allows users to create complex automation rules, trigger tasks 
based on device contexts (location, time, connectivity), and execute sequences of actions.

Style: Modern, minimalist, premium, professional, tech-forward.

Visual concept: Combine a stylized checkmark (task completion) with flowing gears or circuit paths 
(automation/processing). The design should feel intelligent, dynamic, and trustworthy.

Design requirements:
- Scalable to 48dp, 96dp, 192dp (must be crisp and recognizable at all sizes)
- Works well on both light and dark backgrounds
- Geometric, clean lines with subtle curves
- Professional color palette: Use rich purples (#a6e3a1 or #89b4fa), deep teals, or electric blues 
  as primary accent on a neutral base (white, dark gray, or transparent)
- Should convey: automation, intelligence, reliability, simplicity, open-source
- Avoid: generic task app icons, overly complex designs, shadows (too heavy), gradients (over-used)

Visual metaphors to consider:
- Stylized checkmark flowing into gear teeth or circuit paths
- Nested loops or orbiting elements suggesting continuous automation
- Interconnected nodes forming a check or circle
- Minimalist robot or agent silhouette with task-focused gesture
- Time-based rotation or cyclical flow pattern

Color suggestion (Catppuccin Mocha aesthetic):
- Primary: #a6e3a1 (green) or #89b4fa (blue) or #f38ba8 (pink)
- Secondary: #bac2de (muted lavender) or #45475a (dark gray)
- Background: transparent or white

Output: 
- Clean, icon-ready design
- Suitable for use as Android adaptive icon (at least 192x192px)
- Professional enough for app store listings
- Unique and memorable (not generic)

Reference aesthetic: Slack, Discord, or Figma logo simplicity — clean, geometric, instantly 
recognizable even at small size.
```

---

## Refined Variation (If First Attempt Doesn't Hit)

```
Create a sleek, modern icon for "OpenTasker," an Android automation app. The icon should feel:
- Intelligent and tech-forward
- Trustworthy and reliable
- Modern and minimal (not skeuomorphic)
- Unique and memorable

Core concept: A dynamic checkmark or task symbol merging with automation metaphors (gears, 
circuit paths, or flowing motion lines). The design should suggest:
- Task completion and workflow automation
- Continuous, intelligent processing
- Simplicity and clarity

Design parameters:
- Works at 48x48, 96x96, 192x192, and 512x512 pixels
- Geometric, vector-style design (not photorealistic)
- Single accent color or subtle dual-color palette
- Minimal, bold strokes and clean shapes
- Professional app store quality

Avoid:
- Overly complex details
- Heavy shadows or gradients
- Generic automation/cog imagery
- Skeuomorphic or realistic rendering

Inspiration: Logos for Zapier, IFTTT, or GitHub Actions — clean, geometric, purposeful.

Output a high-quality, scalable icon design ready for adaptive Android icon implementation.
```

---

## Quick Reference (For Batch Generation)

If you want to generate multiple variations:

1. **Variant A - Minimal Checkmark + Flow**: "A minimalist checkmark merging into flowing circuit paths. Geometric, single color, premium tech aesthetic."

2. **Variant B - Nested Loops**: "Interlocking circular loops forming a stylized checkmark. Modern, balanced, suggests continuous automation and task completion."

3. **Variant C - Gear Integration**: "Checkmark and gear teeth interlocking in a modern, geometric way. Tech-forward, professional, minimal design."

4. **Variant D - Agent/Node Concept**: "Interconnected nodes with one prominent node bearing a check mark. Suggests networking, intelligence, and task automation."

5. **Variant E - Rotation/Motion**: "A checkmark with motion lines or a rotating element suggesting continuous, intelligent processing. Dynamic but not frenetic."

---

## Implementation Notes

After generation:
1. Request the image at **512x512px minimum** for best quality
2. Ask for **PNG with transparency** (for adaptive icon use)
3. Consider generating multiple color variants:
   - Primary color (e.g., #89b4fa blue)
   - Dark variant (for light backgrounds)
   - Light variant (for dark backgrounds)

4. For Android adaptive icon, you'll need:
   - Icon image (centered, with safe zone of 66dp diameter)
   - Background color or pattern
   - Consider using `res/mipmap-anydpi-v26/ic_launcher.xml` for adaptive icon definition

---

## Catppuccin Mocha Color Palette

If using Catppuccin Mocha theme colors:

```
Primary Accent (pick one):
- Rosewater: #f5e0dc (gentle)
- Flamingo: #f2cdcd (warm red)
- Pink: #f5c2e7 (vibrant)
- Mauve: #cba6f7 (purple)
- Red: #f38ba8 (bold red)
- Maroon: #eba0ac (maroon)
- Peach: #fab387 (warm orange)
- Yellow: #f9e2af (warm yellow)
- Green: #a6e3a1 (fresh green) ← Good for "automation go"
- Teal: #94e2d5 (cool teal)
- Sky: #89dceb (sky blue)
- Sapphire: #74c7ec (deeper blue)
- Blue: #89b4fa (vibrant blue) ← Good tech forward
- Lavender: #b4befe (soft blue)

Surface/Neutral:
- Text: #cdd6f4
- Subtext: #bac2de
- Dark gray: #45475a
```

Recommended combinations:
- **Blue + Green**: #89b4fa + #a6e3a1 (tech + task completion)
- **Sapphire + Green**: #74c7ec + #a6e3a1 (professional + positive)
- **Mauve Solo**: #cba6f7 (premium, distinctive)
- **Pink Solo**: #f5c2e7 (modern, approachable)

---

## Example Result Usage

Once you have your icon:

1. **Save as**: `ic_launcher_foreground.png` (192x192px)
2. **Place in**: `app/src/main/res/mipmap-xhdpi/` and other densities
3. **Define in**: `res/mipmap-anydpi-v26/ic_launcher.xml` for adaptive icon
4. **Background**: `res/values/colors.xml` → `launcher_bg`
5. **Update**: `AndroidManifest.xml` to reference the new icon

---

## Pro Tips

- **For DALL-E**: Be specific about "icon design," "geometric," "scalable," "app store quality"
- **For Midjourney**: Use `--ar 1:1` for square output; add style code like `--niji` for illustration style or `--s 750` for higher quality
- **Iteration**: If the first result is close, ask for refinements: "Make it simpler," "Use more blue," "Larger checkmark," etc.
- **Multiple colors**: Generate the icon in multiple color variants so you can pick the best one later

---

## Final Checklist

- [ ] Icon is readable at 48x48px (smallest display)
- [ ] Icon is unique and memorable
- [ ] Colors align with your brand (Catppuccin Mocha recommended)
- [ ] Geometric, modern, professional aesthetic
- [ ] Suggests automation + task completion
- [ ] Vector-style, not photorealistic
- [ ] PNG with transparency background
- [ ] 512x512px or larger

---

Good luck! 🎨
