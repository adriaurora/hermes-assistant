# design-nan.md
## Helmcode-derived design system for Jarvis mobile

**Purpose:** implementation specification for the Jarvis fork  
**Target:** ChatGPT-style mobile conversational application  
**Primary mode:** dark, mobile-first  
**Specification language:** English  
**Last audited:** 2026-09-04  
**Primary design reference:** Helmcode public Brand & Media Kit + live Helmcode website  
**Voice motion reference:** user-provided recording of the animated brick field on the Helmcode homepage

---

# 0. Read this first — implementation contract

This file is intended to be consumed by an implementation agent.

The goal is **not** to create a generic dark AI app with purple accents.  
The goal is to translate Helmcode's actual visual grammar into a mobile conversational product while preserving mobile usability.

Every rule in this document belongs to one of these classes:

| Label | Meaning |
|---|---|
| **[H-CANONICAL]** | Explicitly published by Helmcode in its Brand & Media Kit. Treat as non-negotiable unless a platform accessibility constraint requires otherwise. |
| **[H-OBSERVED]** | Directly observed on the current Helmcode website or in the supplied Helmcode animation recording, but not published as a formal token. |
| **[J-ADAPTATION]** | A mobile/chat-specific design decision created to preserve Helmcode's language in Jarvis. Do not present it as an official Helmcode rule. |
| **[A11Y]** | Mobile usability or accessibility requirement. |

When rules conflict, use this priority:

```text
[A11Y] > [H-CANONICAL] > [H-OBSERVED] > [J-ADAPTATION]
```

Do not silently invent new visual tokens, radii, fonts, surface colors, shadows, gradients, or animation styles.

If a component is not described here, construct it using the primitives in this file rather than falling back to Material, Cupertino, or the original Jarvis styling.

---

# 1. What must visually survive the translation

The Jarvis UI should inherit these defining Helmcode traits:

1. warm-black canvas;
2. one indigo signal color;
3. white text expressed through three opacity levels;
4. Roboto + Roboto Mono only;
5. font weights 300 / 400 / 500 only;
6. straight corners;
7. 0.5 px hairlines;
8. spacing based on 4 px increments;
9. technical mono microcopy and `//` section eyebrows;
10. information-first composition;
11. high negative-space discipline;
12. restrained, coherent motion;
13. no decorative visual noise.

The resulting product should feel:

```text
technical
precise
quiet
engineered
editorial
dark
controlled
high-information
developer-oriented
```

It must **not** feel like:

```text
a Material You reskin
a generic ChatGPT clone with purple buttons
iMessage
Discord
a neon cyberpunk UI
glassmorphism
a dashboard made from rounded cards
a voice assistant built around a glowing orb
```

---

# 2. Canonical color system

## 2.1 Base surfaces

**[H-CANONICAL]**

```yaml
color:
  background: "#0A0A0A"
  surface: "#111111"
  surfaceRaised: "#161616"
```

Official roles:

```text
#0A0A0A  warm-black background / canvas
#111111  cards, panels, screenshots
#161616  elevated panels, terminal
```

### Jarvis semantic mapping

**[J-ADAPTATION]**

```yaml
appCanvas: "#0A0A0A"
userMessageSurface: "#111111"
inputSurface: "#111111"
menuSurface: "#161616"
toolSurface: "#161616"
codeSurface: "#161616"
modalSurface: "#111111"
```

Do not add a ladder of unrelated greys.

If visual separation is needed, first use:

1. spacing;
2. hairlines;
3. the three canonical surfaces;
4. typography hierarchy.

Do not solve hierarchy by inventing more card colors.

---

## 2.2 Text

**[H-CANONICAL]**

```yaml
text:
  primary: "#FFFFFF"
  secondary: "rgba(255,255,255,0.55)"
  tertiary: "rgba(255,255,255,0.35)"
```

Helmcode explicitly defines `0.35` opacity as the legible floor. Do not go below it for visible text.

### Jarvis roles

**[J-ADAPTATION]**

```text
primary
- assistant answer
- screen heading
- selected values
- essential labels
- important controls

secondary
- descriptions
- timestamps
- inactive navigation
- supporting metadata
- helper text

tertiary
- non-essential metadata
- disabled / unavailable chrome
- low-priority technical information
```

Do not use `tertiary` for instructions the user must read to complete a task.

Approximate composites over `#0A0A0A`:

```text
primary   ≈ #FFFFFF
secondary ≈ #919191
tertiary  ≈ #606060
```

Use the alpha tokens, not the composite greys.

---

## 2.3 Indigo accent

**[H-CANONICAL]**

```yaml
accent:
  base:
    oklch: "oklch(51.1% 0.262 276.966)"
    hexFallback: "#4934E1"

  text:
    oklch: "oklch(70% 0.18 276.966)"
    hexFallback: "#818CF8"
```

`oklch` is Helmcode's source of truth; hex is the fallback.

### Canonical accent rules

**[H-CANONICAL]**

- Accent is a **signal**, not decoration.
- Helmcode budgets approximately **4–5 strong accent moments per page**.
- Indigo text under roughly `24 px` uses `accent.text` (`#818CF8`), not base indigo.
- Base indigo must not be used for body text.
- Accent must not be used for decorative icons.
- Accent must not be used for separators / hairlines.
- Accent must not become a large section background.

### Mobile interpretation

**[J-ADAPTATION]**

Do not mechanically count every purple pixel as a separate accent moment.

A single component can contain many indigo pixels and still be one visual signal.

Examples:

```text
send button                 = one signal
selected model indicator    = one signal
voice visualizer            = one signal
active switch               = one signal
```

A normal chat viewport should usually contain fewer strong indigo focal points than a long web page.

The **voice visualizer is intentionally allowed to be the dominant accent object** on the voice screen. Do not then make all voice controls indigo too.

---

## 2.4 Status colors

**[H-CANONICAL]**

```yaml
status:
  error: "#FF5F56"
  warning: "#FFBD2E"
  success: "#27C93F"
```

Canonical rule:

```text
status colors = severity only
status colors ≠ brand colors
```

Use them for:

- error;
- warning;
- connected / healthy;
- failed tool action;
- destructive confirmation;
- actual system state.

Do not use status colors merely to create variety.

---

# 3. Contrast

Calculated against `#0A0A0A`:

```text
#FFFFFF                 ≈ 19.8:1
secondary white 55%     ≈ 6.28:1
tertiary white 35%      ≈ 3.15:1
#4934E1                 ≈ 2.70:1
#818CF8                 ≈ 6.64:1
white on #4934E1        ≈ 7.34:1
```

Implementation consequences:

**[H-CANONICAL]**

- Small indigo text uses `#818CF8`.
- Base indigo is a strong graphic/control signal, not small dark-mode copy.

**[A11Y]**

- Required body/instruction text must use primary or secondary.
- Do not encode status through color alone.
- Essential small text must not use tertiary.
- Icon-only controls require accessibility labels.

---

# 4. Typography

## 4.1 Families

**[H-CANONICAL]**

```yaml
font:
  sans: "Roboto"
  mono: "Roboto Mono"
```

Helmcode's rule is explicit:

```text
Two families. Nothing else.
```

Do not substitute Inter, SF Pro, Geist, system sans, JetBrains Mono, or another typeface unless the platform technically cannot load the required font.

---

## 4.2 Weights

**[H-CANONICAL]**

Allowed:

```text
300
400
500
```

Do not use:

```text
600
700
800
900
```

Hierarchy must come from:

- size;
- whitespace;
- contrast;
- placement;
- family choice.

Do not recreate a bold SaaS hierarchy by using 700.

---

## 4.3 Family roles

**[H-CANONICAL + J-ADAPTATION]**

### Roboto

Use for:

- assistant prose;
- user prompts;
- headings;
- normal menu items;
- settings labels;
- dialogs;
- buttons whose label is normal language;
- long-form content.

### Roboto Mono

Use for technical/system language:

- `//` eyebrows;
- model IDs;
- tool names;
- code;
- terminal output;
- token/context metrics;
- status strings;
- version/build IDs;
- short technical metadata;
- selected micro-actions where code-like language is intentional.

Do not render an entire conversation in Mono.

---

## 4.4 `//` eyebrow

**[H-CANONICAL]**

Helmcode's signature section opener:

```text
// SECTION NAME
```

Published properties:

```yaml
family: "Roboto Mono"
weight: 500
case: uppercase
prefix: "// "
tracking: 0.10em
color: "#818CF8"
```

**[J-ADAPTATION]** recommended mobile size:

```yaml
fontSize: 12
lineHeight: 16
```

Use for major structural sections, not every row.

Good:

```text
// JARVIS
// MODEL
// TOOLS
// MEMORY
// SOURCES
// SYSTEM
```

Bad:

```text
// USER
// ASSISTANT
// COPY
// RETRY
// ATTACH
```

The signature disappears if it is everywhere.

---

## 4.5 Technical UI microcopy

**[H-OBSERVED]**

The live Helmcode UI frequently uses developer-like labels such as lowercase identifiers, underscores, model IDs, terminal commands and arrow actions.

Examples of the grammar:

```text
get_started
book_a_call
read_the_docs
helmcode_
qwen3.6
→
```

**[J-ADAPTATION]**

Jarvis may use this grammar for compact technical actions:

```text
new_chat
copy
retry
stop
local_
tools
```

Do not force snake_case into conversational prose or accessibility labels.

The visible product language can be localized; the design grammar is independent of language.

---

## 4.6 Mobile type scale

The Helmcode Brand Kit publishes roles and weights, not these exact mobile sizes.

Therefore everything below is **[J-ADAPTATION]**.

```yaml
type:
  display:
    family: Roboto
    weight: 500
    size: 40
    lineHeight: 44
    tracking: -0.02em

  h1:
    family: Roboto
    weight: 500
    size: 32
    lineHeight: 36
    tracking: -0.015em

  h2:
    family: Roboto
    weight: 500
    size: 26
    lineHeight: 31
    tracking: -0.01em

  h3:
    family: Roboto
    weight: 500
    size: 20
    lineHeight: 26

  title:
    family: Roboto
    weight: 500
    size: 18
    lineHeight: 24

  bodyLarge:
    family: Roboto
    weight: 300
    size: 18
    lineHeight: 28

  body:
    family: Roboto
    weight: 400
    size: 16
    lineHeight: 24

  small:
    family: Roboto
    weight: 400
    size: 14
    lineHeight: 20

  caption:
    family: Roboto
    weight: 400
    size: 12
    lineHeight: 16

  monoLabel:
    family: Roboto Mono
    weight: 500
    size: 12
    lineHeight: 16
    tracking: 0.10em

  monoData:
    family: Roboto Mono
    weight: 400
    size: 13
    lineHeight: 18

  monoCode:
    family: Roboto Mono
    weight: 400
    size: 13
    lineHeight: 20
```

Do not reduce normal conversation body text below `16` merely to increase density.

---

# 5. Shape and geometry

## 5.1 Straight corners

**[H-CANONICAL]**

Helmcode explicitly identifies straight corners as part of its technical personality.

Canonical rule:

```text
no border-radius on cards
no border-radius on panels
no border-radius on buttons
only status dots are round
```

Jarvis implementation:

```yaml
radius:
  default: 0
  card: 0
  panel: 0
  button: 0
  input: 0
  composer: 0
  dialog: 0
  sheet: 0
  menu: 0
  chip: 0
  message: 0
```

**[A11Y / semantic exception]**

Native controls whose semantics depend on a circle may remain circular if replacing them would reduce usability:

- radio indicator;
- progress spinner;
- avatar supplied by the user/service.

Do not reinterpret this exception as permission to introduce rounded containers.

---

## 5.2 Hairlines

**[H-CANONICAL]**

```text
separator thickness: 0.5 px
```

Helmcode explicitly rejects thick grey borders.

**[J-ADAPTATION]**

Use platform hairline rendering:

```yaml
reactNative: StyleSheet.hairlineWidth
ios: 0.5pt where visually equivalent
android: one physical pixel where practical
```

Recommended neutral border opacities:

```yaml
border:
  subtle: "rgba(255,255,255,0.08)"
  default: "rgba(255,255,255,0.12)"
  strong: "rgba(255,255,255,0.20)"
```

These opacity values are Jarvis tokens, **not published Helmcode tokens**.

Never use the indigo accent as a normal divider.

---

# 6. Spacing

**[H-CANONICAL]**

Published scale:

```text
4
8
12
16
24
32
48
64
```

Use multiples from this scale whenever possible.

Semantic aliases:

```yaml
space:
  xxs: 4
  xs: 8
  sm: 12
  md: 16
  lg: 24
  xl: 32
  xxl: 48
  xxxl: 64
```

**[J-ADAPTATION]**

Common mobile values:

```yaml
screenPaddingX: 16
screenPaddingXLarge: 24
controlGap: 8
paragraphGap: 12
messageGap: 24
sectionGap: 32
```

Avoid arbitrary values such as `13`, `19`, `27`, or `30` unless a platform constraint genuinely requires them.

---

# 7. Surfaces, elevation and effects

## 7.1 Flat hierarchy

**[H-OBSERVED]**

Helmcode relies heavily on:

- surface contrast;
- hairlines;
- spacing;
- typography;
- structured data presentation.

It does not visually depend on floating rounded cards.

**[J-ADAPTATION]**

Default shadow:

```yaml
shadow: none
```

Use a platform shadow only where necessary for true transient elevation and keep it visually negligible.

---

## 7.2 Effects

The Brand Kit explicitly bans shadow / gradient / glow on the Helmcode logo.  
The broader live visual language is also notably flat.

Therefore:

**[J-ADAPTATION]**

Do not introduce as product styling:

- glow;
- glassmorphism;
- frosted pills;
- neon borders;
- large decorative gradients;
- heavy shadows;
- grain overlays;
- 3D buttons.

A gradient may only be introduced later as a separately approved product pattern. It is not part of this specification.

---

# 8. Iconography

Helmcode does not publish a canonical mobile icon library.

Everything in this section is **[J-ADAPTATION]**.

Use:

```yaml
visualSize: 20–22
stroke: 1.5–2
touchTarget: 44–48
style: outline / restrained / functional
```

Acceptable libraries if already compatible with the project:

- Lucide;
- Phosphor Regular;
- equivalent neutral outline family.

Rules:

- default icons = `text.secondary`;
- important active icon = `text.primary`;
- indigo only when the icon itself represents an active signal;
- no colored icon circles by default;
- do not wrap every icon in a rounded container.

---

# 9. General motion language

Helmcode does not publish exact transition durations for the whole website.

The following is **[H-OBSERVED + J-ADAPTATION]**.

Motion should be:

```text
continuous
controlled
low-amplitude
non-playful
state-driven
geometrically coherent
```

Avoid:

- spring overshoot;
- bounce;
- elastic easing;
- large scale pulses;
- random flicker;
- glowing pulses.

Recommended UI transition tokens:

```yaml
motion:
  fast: 120ms
  normal: 180ms
  slow: 240ms
  easing: ease-out
```

These durations are Jarvis tokens, not canonical Helmcode values.

Streaming text should appear naturally as content arrives.  
Do not add chat-style bouncing typing dots.

---

# 10. Observed Helmcode composition grammar

These are **[H-OBSERVED]** patterns from the live site.

A recurring section follows:

```text
// TECHNICAL EYEBROW

Short strong heading.

Supporting explanation.

action / data / terminal / structured content
```

Other repeated traits:

- left-aligned editorial hierarchy;
- large dark fields with deliberate negative space;
- technical data presented as a first-class visual element;
- models / IDs / metrics in Mono;
- flat sections separated through rhythm and hairlines;
- strong use of rows and tabular structures;
- sparse indigo signal;
- code and terminal motifs integrated into normal content;
- headings are strong without heavy font weights.

### Translation rule

**[J-ADAPTATION]**

A Jarvis screen should not look like the Helmcode website shrunk to phone width.

Preserve the **grammar**, not the desktop layout.

---

# 11. Application shell

## 11.1 Chat screen

**[J-ADAPTATION]**

```text
┌──────────────────────────────────────┐
│ safe area                            │
├──────────────────────────────────────┤
│ menu      Jarvis / model      new    │
│──────────────────────────────────────│
│                                      │
│             conversation             │
│                                      │
│                                      │
│──────────────────────────────────────│
│ attachments/context when present     │
│ composer                             │
│ safe area                            │
└──────────────────────────────────────┘
```

```yaml
screen: "#0A0A0A"
topBar: "#0A0A0A"
composerRegion: "#0A0A0A"
```

Do not create a permanent contrasting bottom dock.

---

## 11.2 Top app bar

**[J-ADAPTATION]**

```yaml
contentHeight: 48–52
background: "#0A0A0A"
bottomBorder: hairline
```

Recommended:

```text
[menu]  Jarvis
        model-id                         [new]
```

Typography:

```text
Jarvis title    Roboto 500 · 16–18
model ID        Roboto Mono 400/500 · 12–13
```

Icon targets must remain at least 44/48 even if the visible icon is only 20–22.

No rounded icon-button backgrounds by default.

---

# 12. Conversation history / navigation

**[J-ADAPTATION]**

Phone:

- edge drawer or full-screen history view;
- no permanent sidebar.

Section header:

```text
// CONVERSATIONS
```

Conversation row:

```yaml
minHeight: 52
paddingX: 16
paddingY: 12
radius: 0
borderBottom: hairline
```

Selected row:

```yaml
background: "#111111"
indicator:
  side: left
  width: 2
  color: "#4934E1"
```

Do not use a rounded selected pill.

Technical metadata should use Roboto Mono.

---

# 13. Empty / new-chat screen

**[J-ADAPTATION]**

Recommended composition:

```text
// JARVIS

What do you need?

[ composer ................................ ]
```

Optional suggestion rows:

```text
Analyze a document                       →
Help me with code                        →
Research a topic                         →
```

Suggestion rows:

- full width;
- flat;
- square;
- separated by hairlines;
- no colored cards;
- no pill chips.

Do not add a mascot or giant decorative illustration.

---

# 14. Conversation messages

## 14.1 Assistant

**[J-ADAPTATION]**

Do **not** put assistant content inside a chat bubble.

```yaml
background: transparent
paddingX: 16
font: Roboto
fontSize: 16
lineHeight: 24
color: text.primary
```

The assistant response should feel like technical/editorial documentation on the canvas.

Use spacing to separate paragraphs and blocks.

---

## 14.2 User

**[J-ADAPTATION]**

Use the canonical surface to establish turn-taking:

```yaml
background: "#111111"
border: hairline subtle
radius: 0
padding: 12–16
marginLeft: 32–48
marginRight: 16
font: Roboto 16/24
```

Do not use brand indigo as the user-message background.

---

## 14.3 Message actions

Prefer restrained text/icon actions:

```text
copy   retry   more
```

Technical micro-actions may use Roboto Mono.

Do not put every action into a pill.

---

# 15. Composer

**[J-ADAPTATION]**

The composer is a technical input, not a chat bubble.

```yaml
background: "#111111"
border: hairline default
radius: 0
minHeight: 52
maxTextAreaHeight: 144
paddingLeft: 12
paddingRight: 8
paddingTop: 10
paddingBottom: 10
font: Roboto 16
```

Placeholder:

```yaml
color: text.tertiary
```

Focused:

```yaml
borderColor: "rgba(129,140,248,0.65)"
```

A focus border is an interactive state, not a normal separator.

Do not add glow.

### Send

```yaml
visualSize: 40x40
touchTarget: 44–48
background: "#4934E1"
foreground: "#FFFFFF"
radius: 0
```

Disabled:

```yaml
background: "#161616"
foreground: text.tertiary
```

The send control is normally the strongest accent signal on the standard chat screen.

---

# 16. Buttons

The Brand Kit explicitly shows primary, secondary and ghost components but does not publish mobile dimensions in the page text.

Therefore dimensions below are **[J-ADAPTATION]**.

## Primary

```yaml
height: 44
paddingX: 16
background: "#4934E1"
foreground: "#FFFFFF"
radius: 0
font: Roboto 500
```

## Secondary

```yaml
height: 44
background: "#111111"
foreground: text.primary
border: hairline default
radius: 0
```

## Ghost

```yaml
height: 44
background: transparent
foreground: text.secondary
radius: 0
```

Pressed overlay:

```yaml
background: "rgba(255,255,255,0.06)"
```

Do not create rounded pill CTAs.

---

# 17. Inputs, menus, dialogs, sheets

## Text input

```yaml
height: 48
background: "#111111"
border: hairline default
radius: 0
paddingX: 12
font: Roboto 16
```

Technical label:

```yaml
font: Roboto Mono 500 · 12
color: text.secondary
```

## Menu

```yaml
background: "#161616"
border: hairline default
radius: 0
itemMinHeight: 44
```

## Dialog

```yaml
background: "#111111"
border: hairline default
radius: 0
padding: 24
```

## Bottom sheet

```yaml
background: "#111111"
topRadius: 0
borderTop: hairline
```

Do not allow platform defaults to reintroduce large rounded sheet corners.

---

# 18. Tags and selection controls

Helmcode includes tags in its component system.

**[J-ADAPTATION]**

```yaml
tag:
  height: 24–28
  paddingX: 8
  radius: 0
  font: Roboto Mono 11–12
```

Neutral:

```yaml
background: "#161616"
color: text.secondary
border: hairline subtle
```

Accent tag:

```yaml
background: transparent
color: "#818CF8"
```

Do not create rounded Material chips.

Checkboxes should remain square.

Native radio indicators may remain round for semantic familiarity.

---

# 19. Model selector

**[J-ADAPTATION]**

Treat model selection as choosing an execution target.

```text
// MODEL

qwen3.6
35B MoE · 256K ctx                         >
```

Model ID:

```yaml
font: Roboto Mono
weight: 500
size: 13
color: text.primary
```

Metadata:

```yaml
font: Roboto Mono
size: 12
color: text.secondary
```

Selected model:

- small check;
- thin accent indicator;
- or one restrained accent signal.

Do not make every model a large colored card.

---

# 20. Tool calls and agent actions

**[J-ADAPTATION based on H-OBSERVED terminal language]**

Tool use should visually echo Helmcode's terminal/data components.

```text
┌──────────────────────────────────────┐
│ tool · web_search             ● ok   │
│──────────────────────────────────────│
│ query: "..."                         │
│ 4 results                            │
└──────────────────────────────────────┘
```

```yaml
background: "#161616"
border: hairline default
radius: 0

header:
  font: Roboto Mono 12
  color: text.secondary

body:
  font: Roboto Mono 13
  color: text.primary
```

Status dot:

```text
green  healthy/completed
yellow warning/waiting
red    failed/error
```

Collapse low-value tool detail by default.

No nested rounded cards.

---

# 21. Thinking / processing

If the product exposes high-level processing stages, use a technical timeline.

```text
// PROCESSING

01  interpreting request
02  retrieving context
03  generating response
```

Numbers:

```yaml
font: Roboto Mono
color: text.tertiary
```

Current stage may use `accent.text`.

Do not expose hidden chain-of-thought. This component is for product-level status or safe reasoning summaries only.

Do not use colorful badge chains.

---

# 22. Code and terminal

**[H-OBSERVED + J-ADAPTATION]**

```yaml
background: "#161616"
border: hairline default
radius: 0
```

Header:

```text
python                               copy
```

```yaml
headerHeight: 36
paddingX: 12
borderBottom: hairline
font: Roboto Mono 12
color: text.secondary
```

Code:

```yaml
font: Roboto Mono 13/20
padding: 12
```

Syntax palette:

- white/grey remains dominant;
- indigo only for restrained emphasis;
- status colors only when semantically appropriate;
- no rainbow editor aesthetic.

---

# 23. Markdown content

## Headings

**[J-ADAPTATION]**

Inside assistant messages:

```yaml
h1: Roboto 500 · 26/31
h2: Roboto 500 · 20/26
h3: Roboto 500 · 18/24
```

## Blockquote

```yaml
borderLeft: "2px solid rgba(255,255,255,0.20)"
paddingLeft: 12
color: text.secondary
```

## Inline code

```yaml
font: Roboto Mono 13
background: "#161616"
paddingX: 4
paddingY: 2
radius: 0
```

## Links

```yaml
color: "#818CF8"
```

Use underline where needed for accessibility.

---

# 24. Tables

**[J-ADAPTATION]**

```yaml
background: transparent
headerBackground: "#111111"
rowBorder: hairline subtle
radius: 0
```

Header:

```yaml
font: Roboto Mono 500 · 12
color: text.secondary
```

Body:

```yaml
font: Roboto 14
color: text.primary
```

Wide tables should scroll horizontally.

Do not automatically turn every table row into a rounded card.

---

# 25. Attachments and images

Attachment:

```yaml
background: "#111111"
border: hairline default
radius: 0
padding: 12
```

File type can use Mono and `accent.text`.

Images:

- retain original aspect ratio when possible;
- no decorative radius;
- use a hairline where boundary definition is needed;
- metadata below the image rather than a glossy overlay.

---

# 26. Citations and sources

**[J-ADAPTATION]**

Inline markers:

```text
[1]
```

```yaml
font: Roboto Mono 11–12
color: "#818CF8"
```

Expanded source list:

```text
// SOURCES

01  Source title
    domain.example

02  Source title
    domain.example
```

Prefer this technical list grammar over rounded citation chips.

---

# 27. Search

```yaml
background: "#111111"
border: hairline default
radius: 0
height: 44
paddingX: 12
```

Results should be rows separated by hairlines:

```text
title
matching excerpt
technical metadata
```

Do not generate a cloud of rounded filter pills.

---

# 28. Toasts, loading and errors

## Informational toast

```yaml
background: "#161616"
border: hairline
radius: 0
```

For semantic severity, use a small status dot or narrow leading indicator rather than a large saturated background.

## Loading

Prefer:

- canonical dark surfaces;
- subtle opacity;
- mono system status when meaningful.

Avoid:

- bright indigo shimmer;
- giant spinner;
- bouncing typing dots.

## Error

```text
// ERROR

The request could not be completed.

retry
```

Use red for the severity indicator/label, not for the whole screen.

---

# 29. Connection, local inference, context and memory

Technical status:

```text
● local · connected
```

```yaml
font: Roboto Mono 11–12
text: text.secondary
dot: status.success
```

Do not color the entire app bar green.

Context usage:

```text
128K / 256K
```

Use Mono.

A context budget bar may use:

```text
neutral base
indigo current usage
yellow near threshold
red only at actual error/limit
```

---

# 30. Settings

**[J-ADAPTATION]**

Settings should resemble technical configuration rather than rounded iOS grouped cards.

```text
// MODEL

Default model
qwen3.6                                    >

Temperature
0.7                                        >

───────────────────────────────────────────

// INTERFACE

Text size                                    >
```

Rules:

- flat sections;
- square geometry;
- hairline row separators;
- 52–56 dp row height;
- technical values in Mono;
- no large rounded groups.

---

# 31. Touch, safe areas and responsiveness

## Touch targets

**[A11Y]**

```yaml
minimumTouchTarget:
  ios: 44
  android: 48
```

A visible 20 px icon can live inside a transparent 44–48 px hit target.

Square geometry does not mean tiny controls.

## Keyboard

The composer remains pinned above the keyboard.  
The newest conversation content must remain reachable/visible.

## Widths

**[J-ADAPTATION]**

```yaml
compact: "<360"
phone: "360–599"
tablet: "600–839"
wide: ">=840"
```

Phone:

- one pane.

Tablet:

- optional persistent history rail;
- chat reading width stays constrained.

Recommended large-screen maximums:

```yaml
assistantTextMaxWidth: 760
composerMaxWidth: 800
```

---

# 32. Accessibility

**[A11Y]**

Required:

- dynamic text scaling;
- reduced motion;
- screen-reader labels;
- minimum touch targets;
- keyboard focus where relevant;
- state not communicated by color alone;
- normal body text 16 dp by default;
- small indigo text uses `accent.text`;
- mandatory information never uses tertiary text.

If accessibility requires deviating from an aesthetic detail, accessibility wins.

---

# 33. Voice identity — Concentric Brick Visualizer

This section is deliberately more prescriptive than the rest of the file.

The voice visualizer is intended to become a distinctive Jarvis identity and must remain visibly related to the supplied Helmcode brick animation.

---

## 33.1 Source animation: what was actually observed

**[H-OBSERVED]**

Reference: the supplied recording `102729.mp4`, captured from the Helmcode homepage between the final CTA block and the FAQ.

The recording shows:

- a black/warm-black field;
- **7 horizontal rows** of square-cornered rectangular bricks;
- bricks with a generally horizontal, masonry-like rhythm;
- brick widths that are not perceived as one continuous line;
- a coherent indigo luminance field moving through the bricks;
- the majority of bricks remaining dim at any instant;
- a bright crest and broad falloff rather than random blinking;
- the luminous crest travelling predominantly **right-to-left** across the rectangular field;
- a consistent phase offset between rows, so the crest forms a diagonal/slanted front rather than a perfectly vertical column;
- seamless wraparound;
- no glow;
- no particle system;
- no blur-driven blob;
- no geometry deformation;
- no bouncing;
- no conventional audio waveform.

Measured from the supplied recording:

```yaml
observed:
  rowCount: 7
  loopPeriodApprox: 5.93s
  motionType: translating luminance field
  rowPhaseOffsetApprox: 9deg equivalent per row
```

The `~9deg` value is an approximate circular equivalent derived from the observed inter-row horizontal phase displacement. It is not a published Helmcode token.

### Critical interpretation

The animation is best understood as:

```text
STATIC BRICK GEOMETRY
+
MOVING OPACITY / LUMINANCE FIELD
```

It is **not**:

```text
bricks physically moving across the screen
random LEDs switching on/off
a waveform changing brick height
a glowing gradient blob
```

This distinction must be preserved in Jarvis.

---

## 33.2 Circular translation for Jarvis

**[J-ADAPTATION]**

Take the original 7 horizontal rows and wrap the visual logic into **7 concentric rings**.

The result must look like a circular construction made from discrete rectangular bricks.

### Non-negotiable geometry

```text
7 concentric rings by default
straight rectangular bricks
square corners
each brick rotated tangentially to its ring
empty center
no continuous circle strokes
no pie slices
no curved arc segments
no radial spokes
```

### Important: bricks stay rectangular

Do **not** draw each brick as an SVG arc segment.

Preferred primitive:

```text
a small straight rectangle,
rotated so its long axis follows the local tangent of the circle
```

The slightly stepped/jagged circular silhouette is intentional. It makes the object read as a brick structure rather than a segmented loading ring.

---

## 33.3 Recommended geometry

**[J-ADAPTATION]**

Default mobile target:

```yaml
visualizer:
  diameter: 200dp
  diameterCompact: 180dp
  diameterLarge: 220dp

  rings: 7
  innerEmptyRadius: 30–34dp

  brickHeight: 4dp
  radialGap: 5–6dp

  brickWidths:
    short: 16dp
    medium: 20dp
    long: 24dp

  tangentialGap: 3–4dp
```

Use a deterministic repeating mixture of short / medium / long bricks.

Do not randomize brick geometry every frame.

The outer rings naturally contain more bricks than the inner rings.

Approximate construction:

```text
ring 0 -> smallest circumference -> fewest bricks
...
ring 6 -> largest circumference  -> most bricks
```

Keep the perceived brick proportions similar across rings.

### Ring staggering

Each ring should be phase-shifted relative to the adjacent ring.

Use approximately:

```yaml
ringPhaseStep: 8–10deg
default: 9deg
```

This is the circular equivalent of the diagonal inter-row wavefront visible in the Helmcode recording.

Do not alternate arbitrary directions per ring.

---

## 33.4 Brick color and luminance

The Brand Kit publishes the indigo tokens but does **not** publish dedicated animation colors.

Therefore:

- hue anchor = **[H-CANONICAL]**
- opacity ladder below = **[J-ADAPTATION]**

Preferred approach: keep the same indigo hue and create the field mainly through opacity.

```yaml
brick:
  hue: "#4934E1"

  inactiveOpacity: 0.10–0.16
  lowOpacity: 0.24–0.34
  mediumOpacity: 0.42–0.58
  activeOpacity: 0.65–0.82
  crestOpacity: 0.88–1.00
```

The majority of bricks should be in `inactive` or `low` state.

Optional:

```yaml
crestHighlight: "#818CF8"
```

Use `accent.text` only on a **very small fraction of peak bricks** if visual matching requires a lighter crest.

Do not make `#818CF8` the normal brick color.

Do not introduce blue, cyan, magenta or other hues.

### Accent accounting

The whole visualizer counts as **one accent object**.

Because it is the focal object of the voice screen, surrounding controls should remain mostly neutral.

---

## 33.5 Core carrier wave — preserve the Helmcode behavior

**[J-ADAPTATION derived from H-OBSERVED]**

The visualizer must always have one coherent carrier field.

Use an angular wave with a per-ring phase offset.

Conceptual model:

```text
phase = θ + ringIndex × ringPhaseStep - ωt

carrier = 0.5 + 0.5 × cos(phase)

shaped = carrier ^ gamma
```

Recommended defaults:

```yaml
carrier:
  period: 5.93s
  ringPhaseStep: 9deg
  gamma: 2.0–2.6
  direction: one fixed direction
```

The exact math can differ, but the visual result must satisfy:

- one broad bright region;
- a brighter crest;
- a broad dim region opposite it;
- smooth progression;
- phase-shifted rings;
- seamless loop.

### Do not speed the carrier up dramatically for voice

This is important.

Listening/speaking energy should mostly change **luminance**, not turn the object into a fast spinner.

Recommended maximum carrier-speed change between states:

```yaml
speedVariation: ±10%
```

The original ~5.93 s rhythm remains the visual anchor.

---

## 33.6 Audio envelope

**[J-ADAPTATION]**

For listening:

```text
source = microphone level
```

For speaking:

```text
source = TTS/output audio level
```

Use a smoothed loudness envelope.

Recommended:

```yaml
envelope:
  attack: 50–80ms
  release: 180–260ms
  range: 0..1
```

RMS / perceptual loudness is sufficient.

Frequency bands are optional but must not map permanently to specific rings.

The visualizer must not become a spectrum analyzer.

---

## 33.7 How audio changes the bricks

Audio should modulate the existing Helmcode-like carrier.

Recommended conceptual combination:

```text
baseEnergy = shapedCarrier

voiceGain = lerp(0.55, 1.0, audioEnvelope)

brickEnergy = baseEnergy × voiceGain
              + small local audio emphasis
```

Audio may affect:

- active opacity;
- crest intensity;
- width of the bright region slightly;
- participation of adjacent rings slightly.

Audio should **not** primarily affect:

- brick size;
- brick height;
- ring radius;
- global rotation speed;
- random per-brick switching.

### Global scale

Preferred:

```yaml
globalScale: 1.0
```

Optional on strong audio peaks:

```yaml
maxScale: 1.015
```

Do not exceed roughly 1–1.5% scale variation unless explicitly re-approved.

The object should feel structurally fixed.

---

# 34. Voice states

All states use the **same geometry and continuous phase**.

Never destroy/recreate the visualizer when the state changes.

---

## 34.1 Idle

Purpose:

```text
voice mode is ready
```

Behavior:

- carrier continues at approximately the original 5.93 s period;
- very low contrast;
- no reactive audio;
- geometry completely fixed;
- no obvious pulsing.

Suggested:

```yaml
idle:
  carrierPeriod: 5.93s
  maxBrickOpacity: 0.28–0.36
  globalScale: 1.0
```

Example label:

```text
// JARVIS

ready_
```

---

## 34.2 Listening

Purpose:

```text
Jarvis is receiving the user's voice
```

Behavior:

- same carrier phase continues;
- microphone envelope increases local brightness/contrast;
- louder speech activates more neighboring bricks/rings around the carrier;
- no random flicker;
- no fast spinner;
- global size remains essentially fixed.

Suggested:

```yaml
listening:
  carrierPeriod: 5.4–5.93s
  peakOpacity: 0.90–1.00
  globalScale: 1.0–1.01
```

Example:

```text
// JARVIS

listening_
```

---

## 34.3 Thinking

Purpose:

```text
Jarvis is processing without live audio
```

This state should be the closest direct visual translation of the Helmcode source animation.

Behavior:

- use autonomous carrier only;
- preserve approximately `5.93 s` loop;
- broad coherent crest moves across the rings;
- adjacent rings remain phase-shifted by roughly `9°`;
- do not add secondary fast waves;
- do not turn it into a spinner.

Suggested:

```yaml
thinking:
  carrierPeriod: 5.93s
  peakOpacity: 0.75–0.90
  globalScale: 1.0
```

Example:

```text
// JARVIS

thinking_
```

For product-level substate text:

```text
searching_
retrieving_
executing_
```

Change the label without restarting the animation.

---

## 34.4 Speaking

Purpose:

```text
Jarvis is producing TTS/audio
```

Behavior:

- same carrier continues;
- TTS envelope modulates brightness;
- phonetic peaks create stronger local contrast;
- the visual response may spread into neighboring rings;
- it must still read as the Helmcode carrier field, not a radial explosion.

Suggested:

```yaml
speaking:
  carrierPeriod: 5.4–5.93s
  peakOpacity: 0.95–1.00
  globalScale: 1.0–1.01
```

Example:

```text
// JARVIS

speaking_
```

### Important correction

Do **not** make the default speaking animation radiate uniformly from the center outward.

That would create a different visual language.

A subtle radial influence may be layered onto audio peaks, but the primary motion remains the phase-shifted carrier wave around the rings.

---

## 34.5 Error

The visualizer remains indigo/dim.

Use status red only for the semantic error signal:

```text
● error
```

or:

```text
// ERROR
```

Do not recolor the whole visualizer red.

---

# 35. Voice state transitions

**[J-ADAPTATION]**

State sequence may be:

```text
idle → listening → thinking → speaking → idle
```

Rules:

- brick positions never change;
- ring count never changes;
- carrier phase never resets;
- state transition changes intensity parameters;
- labels change independently;
- transition is interpolated.

```yaml
stateTransition: 180–240ms
```

The user should perceive one physical object changing behavior, not separate animations replacing each other.

---

# 36. Voice screen layout

**[J-ADAPTATION]**

Recommended:

```text
┌──────────────────────────────────────┐
│                                      │
│             // JARVIS                │
│                                      │
│                                      │
│          CONCENTRIC BRICK            │
│             VISUALIZER               │
│                                      │
│                                      │
│             listening_               │
│                                      │
│                                      │
│                                      │
│     [mute]    [keyboard]      [×]    │
│                                      │
└──────────────────────────────────────┘
```

Visual hierarchy:

1. warm-black canvas;
2. visualizer = dominant accent object;
3. state label;
4. neutral controls.

Recommended:

```yaml
visualizerDiameter: 180–220
labelGap: 24
bottomControlSize: 48–52
bottomControlGap: 16
```

Use generous negative space.

Do not surround the visualizer with a card.

Do not place it inside an orb.

---

# 37. Voice controls

**[J-ADAPTATION]**

```yaml
control:
  visibleSize: 48–52
  touchTarget: >=48
  background: "#111111"
  border: hairline default
  radius: 0
  icon: text.primary
```

Muted/active states should remain restrained.

End session may use the canonical error color for the destructive indicator, preferably icon/border rather than a giant red square.

Do not build a rounded floating control dock.

---

# 38. Reduced motion for voice

**[A11Y]**

When reduced motion is enabled:

- brick geometry remains identical;
- stop angular carrier travel;
- do not scale the object;
- listening/speaking may change brick opacity based on audio;
- state labels remain available;
- error/status remains understandable without motion.

A static concentric brick object with amplitude-driven luminance is acceptable.

---

# 39. Voice implementation guidance

The visualizer must be procedural.

Do not ship it as a prerecorded video because it needs to respond to:

- microphone amplitude;
- TTS amplitude;
- Jarvis state;
- reduced-motion preference;
- theme/layout size.

Preferred rendering options:

```text
React Native Skia
Flutter CustomPainter
Jetpack Compose Canvas
Core Animation / native drawing
SVG only if performance remains stable
```

### Rendering primitive

Preferred:

```text
straight rectangle transformed by:
translate(center + polar position)
rotate(tangent angle)
```

Not preferred:

```text
arc path
rounded capsule
pie wedge
circle segment
```

### Conceptual data model

```ts
type VoiceState =
  | "idle"
  | "listening"
  | "thinking"
  | "speaking"
  | "error";

interface ConcentricBrickVisualizerProps {
  state: VoiceState;
  inputLevel?: number;       // normalized 0..1
  outputLevel?: number;      // normalized 0..1
  reducedMotion?: boolean;
  size?: number;             // dp
}
```

Each brick should have immutable geometry after layout:

```ts
interface BrickGeometry {
  ring: number;
  angle: number;
  width: number;
  height: number;
  rotation: number;
}
```

Only visual energy changes frame-to-frame.

---

# 40. Voice pseudocode

This is **reference behavior**, not a required language/runtime.

```ts
const PERIOD = 5.93;
const RING_PHASE = degToRad(9);
const GAMMA = 2.3;

function carrierEnergy(brick, timeSeconds) {
  const omega = (Math.PI * 2) / PERIOD;

  const phase =
    brick.angle +
    brick.ring * RING_PHASE -
    omega * timeSeconds;

  const wave = 0.5 + 0.5 * Math.cos(phase);

  return Math.pow(wave, GAMMA);
}

function stateGain(state, inputLevel, outputLevel) {
  switch (state) {
    case "idle":
      return 0.30;

    case "thinking":
      return 0.78;

    case "listening":
      return 0.55 + 0.45 * inputLevel;

    case "speaking":
      return 0.55 + 0.45 * outputLevel;

    case "error":
      return 0.18;
  }
}

function brickOpacity(brick, t, state, input, output) {
  const carrier = carrierEnergy(brick, t);
  const gain = stateGain(state, input, output);

  const energy = clamp01(carrier * gain);

  return lerp(0.12, 0.96, energy);
}
```

This intentionally produces:

- dark majority;
- one coherent crest;
- ring-to-ring slant;
- seamless wrapping;
- voice-reactive brightness;
- fixed geometry.

The implementation may refine the formula, but a materially different motion model requires design review.

---

# 41. Voice acceptance test

The voice implementation is **wrong** if any of the following are true:

```text
the object looks like a glowing sphere
the bricks are curved arcs instead of rectangles
the rings expand dramatically with speech
each brick flickers independently
the animation behaves like an equalizer
the carrier spins rapidly
the carrier resets on state transitions
the visualizer changes color by state
there is glow or blur around active bricks
the center is filled by a purple disc
there are radial spokes
the rings have rounded capsules
the default speaking state is a radial explosion
```

It is compliant when:

- seven concentric rings are immediately readable;
- each ring is made from discrete square-cornered rectangular bricks;
- rectangles follow the tangent of the circle;
- geometry remains fixed;
- indigo luminance moves coherently;
- the carrier loop is approximately six seconds;
- adjacent rings have a visible phase offset;
- most bricks stay dim;
- voice modifies intensity more than geometry;
- transitions preserve phase;
- the result remains recognizably related to the Helmcode recording while clearly being circular.

---

# 42. Global anti-patterns

The Jarvis redesign is off-system if any of these become dominant:

```text
12–24 px corner radii
rounded chat bubbles
rounded composer pill
floating rounded cards everywhere
Material You components left visually unchanged
glass blur
purple glow
large decorative gradients
all icons in purple
indigo body text
thick grey borders
600/700/800 font weights
a third font family
bright rainbow code syntax
large colored status backgrounds
typing-dot animation
giant branded spinner
voice orb
```

---

# 43. Reference chat screen

```text
┌──────────────────────────────────────────┐
│ ☰   Jarvis                         ＋    │
│     qwen3.6                              │
├──────────────────────────────────────────┤
│                                          │
│ ┌──────────────────────────────────────┐ │
│ │ Explain how RAG works...             │ │
│ └──────────────────────────────────────┘ │
│                                          │
│ RAG combines retrieval with generation.  │
│ Instead of answering only from the       │
│ model's internal parameters...           │
│                                          │
│ // FLOW                                  │
│                                          │
│ 01  query                                │
│ 02  retrieval                            │
│ 03  context                              │
│ 04  generation                           │
│                                          │
│ ┌──────────────────────────────────────┐ │
│ │ tool · vector_search         ● ok    │ │
│ ├──────────────────────────────────────┤ │
│ │ 6 chunks · 143 ms                    │ │
│ └──────────────────────────────────────┘ │
│                                          │
│ copy   retry   more                       │
│                                          │
├──────────────────────────────────────────┤
│ ┌──────────────────────────────────────┐ │
│ │ Message Jarvis...              [ ↑ ] │ │
│ └──────────────────────────────────────┘ │
└──────────────────────────────────────────┘
```

Key characteristics:

- assistant content is unboxed;
- user turn uses the canonical surface;
- square composer;
- one strong send accent;
- terminal/data grammar for tools;
- hairlines instead of thick card outlines;
- typography and spacing carry hierarchy.

---

# 44. Reference history screen

```text
┌──────────────────────────────────────────┐
│ ×                                        │
│                                          │
│ // CONVERSATIONS                         │
│                                          │
│ + new_chat                               │
│──────────────────────────────────────────│
│ TODAY                                    │
│                                          │
│ Helmcode design for Jarvis               │
│ 20:14                                    │
│──────────────────────────────────────────│
│ RAG architecture                         │
│ 18:42                                    │
│──────────────────────────────────────────│
│ YESTERDAY                                │
│                                          │
│ Service migration                        │
│ 23:01                                    │
│                                          │
│──────────────────────────────────────────│
│ settings                                 │
└──────────────────────────────────────────┘
```

Reserve `//` for major structural headings.

---

# 45. Foundation token bundle

This bundle deliberately separates Helmcode tokens from Jarvis-only tokens.

```json
{
  "helmcodeCanonical": {
    "color": {
      "background": "#0A0A0A",
      "surface": "#111111",
      "surfaceRaised": "#161616",

      "textPrimary": "#FFFFFF",
      "textSecondary": "rgba(255,255,255,0.55)",
      "textTertiary": "rgba(255,255,255,0.35)",

      "accent": "#4934E1",
      "accentText": "#818CF8",

      "error": "#FF5F56",
      "warning": "#FFBD2E",
      "success": "#27C93F"
    },

    "font": {
      "sans": "Roboto",
      "mono": "Roboto Mono",
      "weightLight": 300,
      "weightRegular": 400,
      "weightMedium": 500
    },

    "radius": 0,

    "spacing": [4, 8, 12, 16, 24, 32, 48, 64],

    "separator": {
      "thickness": 0.5
    }
  },

  "jarvisAdaptation": {
    "border": {
      "subtle": "rgba(255,255,255,0.08)",
      "default": "rgba(255,255,255,0.12)",
      "strong": "rgba(255,255,255,0.20)"
    },

    "control": {
      "minTouchIOS": 44,
      "minTouchAndroid": 48,
      "inputHeight": 48,
      "buttonHeight": 44,
      "composerMinHeight": 52,
      "iconVisual": 20
    },

    "motion": {
      "fast": 120,
      "normal": 180,
      "slow": 240
    },

    "voice": {
      "diameter": 200,
      "rings": 7,
      "innerEmptyRadius": 32,
      "brickHeight": 4,
      "brickWidths": [16, 20, 24],
      "radialGap": 5,
      "tangentialGap": 3,
      "carrierPeriodMs": 5930,
      "ringPhaseStepDeg": 9,
      "maxGlobalScale": 1.015
    }
  }
}
```

---

# 46. Implementation order for the Jarvis fork

Do not redesign isolated screens independently.

## Phase 1 — foundations

Replace globally:

1. background/surface tokens;
2. text tokens;
3. accent/status tokens;
4. fonts;
5. font weights;
6. spacing scale;
7. all decorative radii;
8. border/hairline behavior.

Until this is done, component work will drift.

## Phase 2 — chat core

Implement:

1. top bar;
2. assistant message;
3. user message;
4. composer;
5. send/stop states;
6. Markdown;
7. code block;
8. tool call.

## Phase 3 — navigation/system

Implement:

1. history;
2. model selector;
3. search;
4. settings;
5. memory/context;
6. attachment states;
7. dialogs/menus/sheets.

## Phase 4 — voice

Implement the concentric visualizer **after the foundation tokens are stable**.

Voice work order:

1. static 7-ring geometry;
2. visual QA of rectangular tangent bricks;
3. autonomous 5.93 s carrier;
4. 9° ring phase offsets;
5. state transitions without phase reset;
6. microphone envelope;
7. TTS envelope;
8. reduced motion;
9. performance profiling;
10. final visual comparison against the supplied recording.

---

# 47. Agent implementation rules

The implementation agent must follow these rules:

1. Do not reinterpret `radius: 0` as "small radius".
2. Do not preserve old rounded Jarvis components for convenience if they are visible.
3. Do not use system fonts if Roboto / Roboto Mono are available in the project.
4. Do not use font weight > 500.
5. Do not create new brand hues.
6. Do not use `#4934E1` for small body-like text.
7. Do not use accent in standard separators.
8. Do not turn every component into a bordered card.
9. Do not invent shadows to compensate for weak hierarchy.
10. Do not implement the voice visualizer as an orb, waveform, arc ring or video.
11. Do not change the voice brick geometry during animation.
12. Do not reset the voice wave phase when state changes.
13. Do not materially speed up the ~5.93 s carrier just because audio is active.
14. Do not use random independent brick flicker.
15. Do not claim a Jarvis adaptation is an official Helmcode token.
16. Preserve existing application functionality while replacing presentation.
17. Where the existing app architecture forces a compromise, record it explicitly instead of silently deviating.

---

# 48. Visual QA checklist

A standard screen passes only if:

- canvas is `#0A0A0A`;
- visible surfaces use only canonical base surfaces unless documented;
- typography is Roboto / Roboto Mono;
- no visible text uses weight > 500;
- normal corners are square;
- separators are hairline-weight;
- accent remains sparse and meaningful;
- small accent text uses `#818CF8`;
- status colors are semantic only;
- assistant prose is not wrapped in a chat bubble;
- composer is square;
- tool/code UI feels terminal/data-oriented;
- spacing follows the Helmcode scale;
- there is no glow/glass/neon styling;
- mobile touch targets remain accessible.

The voice screen additionally passes only if all requirements in **Voice acceptance test** pass.

---

# 49. Source hierarchy and audit notes

## Primary canonical source

Helmcode Brand & Media Kit:

```text
https://helmcode.com/brand
```

Audited 2026-09-04.

Canonical rules taken directly from that source:

```text
#0A0A0A background
#111111 surface
#161616 raised surface

#FFFFFF primary text
rgba(255,255,255,.55) secondary
rgba(255,255,255,.35) tertiary / legibility floor

oklch(51.1% .262 276.966)
#4934E1 accent

oklch(70% .18 276.966)
#818CF8 accent text

#FF5F56 red
#FFBD2E yellow
#27C93F green

Roboto
Roboto Mono
weights 300 / 400 / 500 only

// eyebrow convention
Roboto Mono
uppercase
accent-text
tracking 0.10em

straight corners
0.5px hairlines

spacing:
4 / 8 / 12 / 16 / 24 / 32 / 48 / 64

accent as signal
~4–5 strong accent moments per page
small accent text uses accent-text
no accent in body text
no accent in decorative icons
no accent in separators
no accent in large section backgrounds
```

## Live site used for observed grammar

```text
https://helmcode.com/
https://helmcode.com/es
```

Observed patterns include:

- `//` section openings throughout the homepage;
- technical model IDs and terminal presentation;
- lowercase / underscore UI grammar;
- flat dark composition;
- editorial + data/terminal hierarchy.

## Voice animation source

User-provided recording:

```text
102729.mp4
```

Observed from the recording:

```text
7 horizontal brick rows
static square-corner brick geometry
coherent luminance wave
right-to-left travel
row-to-row phase offset
approximately 5.93 s loop
no geometry motion
no glow / orb / particles
```

The circular voice visualizer is **not an official Helmcode component**.  
It is a Jarvis-specific adaptation designed to preserve the observed Helmcode motion language.

---

# 50. Known uncertainty — do not guess beyond this file

Helmcode's public Brand & Media Kit exposes the core system but does not publish every implementation detail needed by a mobile application.

The following values in this document are therefore intentionally classified as Jarvis adaptations:

- exact mobile typography sizes;
- border opacity levels;
- mobile control heights;
- icon library;
- message layout;
- composer layout;
- menu/dialog dimensions;
- transition durations;
- voice ring dimensions;
- voice audio response;
- voice renderer choice.

If implementation work uncovers a conflict between the live Helmcode site and this file, preserve canonical Brand Kit rules first, then update this document rather than silently creating a third interpretation.

---

# 51. One-sentence design test

If a proposed UI change makes Jarvis look **more rounded, more colorful, more decorative, more card-heavy, more glow-driven, or less typographically disciplined**, it is almost certainly moving away from the Helmcode system.
