# OWS + OWD — Full Language & Android IDE

**OWS** (Overlay Widget Script) — logic, functions, classes, HTTP  
**OWD** (Overlay Widget Design) — UI structure, appearance, layout  

This is a **real language toolchain**, not a hard-coded sample. The counter demo is only one program written *in* the language.

## Android targets

| Setting     | Value | Meaning                          |
|-------------|-------|----------------------------------|
| **minSdk**  | **29** | Android 10 (your phone floor)   |
| **targetSdk** | **35** | Latest stable Android target   |
| **compileSdk** | **35** | Build against latest SDK      |

Permissions: `SYSTEM_ALERT_WINDOW`, `INTERNET`, foreground service for overlay.

## Language (OWS)

Whitespace-insensitive, brace-based, **symbolic operators**:

```
+  -  *  /  %          arithmetic   (also: a x b for multiply)
==  !=  <  >  <=  >=   comparison
and  or  not           logic  (also & | )
=  +=  -=  *=  /=      assignment
```

### Core features

| Feature | Example |
|---------|---------|
| Variables | `number count = 0`  `string name = "hi"`  `var x = 1` |
| Arithmetic | `count = count + 1`  `area = w * h`  `n = a x b` |
| Functions | `fun add(a, b) { return a + b }` |
| Classes | `class Point { number x = 0  number y = 0 }`  `var p = new Point(1, 2)` |
| Control | `if`, `else`, `while`, `for x in list { }`, `return`, `break` |
| Lists | `var xs = [1, 2, 3]`  `xs[0]`  `len(xs)` |
| Maps | `var m = { "a": 1, "b": 2 }`  `m["a"]` |
| Events | `when Plus.clicked { ... }` |
| Built-ins | `str`, `num`, `len`, `print`, `min`/`max`/`abs`, `upper`/`lower`, `json_parse` / `json_stringify`, `now` |

### HTTP / API (built-in)

```
// GET
var res = http.get("https://api.example.com/data")
if res.ok {
    Status.txt = str(res.status)
    var data = res.json          // parsed JSON map/list
}

// POST JSON body
var payload = { "msg": "hello", "n": 42 }
var res = http.post("https://api.example.com/items", payload)

// Optional headers map
var res = http.get(url, { "Authorization": "Bearer ..." })

// Also: http.put, http.delete, http.patch, http.json(method, url, body?, headers?)
```

Response is always a map:

```
{
  ok: true/false,
  status: 200,
  body: "...raw text...",
  json: <parsed object or null>,
  headers: { ... },
  error: "..."   // on failure
}
```

Calls are **synchronous** on the VM thread (fine for button handlers). Keep timeouts modest (15–20s built-in).

### Design language (OWD)

```
Widget Root {
    width: 240
    height: 140
    radius: 24
    background: "#151515"

    Text Title {
        txt: "Hello"
        size: 28
        x: 16
        y: 20
    }

    Button Go {
        txt: "Go"
        x: 160
        y: 80
        width: 60
        height: 40
    }
}
```

Supported nodes: `Widget`, `Text`, `Button`, `Image`, `Rect`, `Circle`  
Properties: `width`, `height`, `x`, `y`, `radius`, `background`, `txt`, `size`, `src`

OWS references widgets by **id** (`Title.txt = "Hi"`).

## Architecture (shared package)

```
source → Lexer → Parser → AST
                ↓
         Compiler → Bytecode IR + SceneGraph
                ↓
              VM (stack machine)  ←→  HTTP / natives
                ↓
     Overlay / Preview renderer (Canvas; GLES-ready)
```

Module **`ows-core`** is the standalone language library (AAR). The IDE depends on it; other apps can too.

## IDE features

- OWS / OWD editors + syntax highlighting  
- Compile with line:column errors  
- Live preview (drag)  
- Floating system overlay (tap buttons → events)  
- Save / load projects  
- Samples: `counter`, `api_demo`

## Build

**Requirements:** Android Studio (recent), JDK 17, SDK 35.

```bash
# Open the project folder in Android Studio, or:
./gradlew :app:assembleDebug
# APK → app/build/outputs/apk/debug/app-debug.apk
```

No `.yml` / `.yaml` files in this repo.

## Project layout

```
OWS_OWD_Project/
  ows-core/          # language package (lexer, parser, AST, IR, compiler, VM, scene)
  app/               # Android IDE + overlay
  README.md
  settings.gradle.kts
  build.gradle.kts
```

## Extending

- Add natives in `VM.callNative`
- Add opcodes in `ir/Bytecode.kt` + compiler emit + VM case
- Swap Canvas for OpenGL ES behind the same `SceneGraph`

## License

Reference implementation of the OWS/OWD ecosystem.


## Graphics backends (OpenGL ES + Vulkan)

| Backend | Status | Notes |
|---------|--------|--------|
| **OpenGL ES 2.0** | **Implemented** | Quads + text textures; used for preview & overlays |
| **Vulkan** | **Supported path** | Hardware detection + `VulkanRenderer` API; draws via GLES until optional NDK `libows_vulkan.so` is linked |
| **Canvas 2D** | **Implemented** | Always-available fallback |

Select in the IDE menu: **Renderer: Auto / OpenGL ES / Vulkan / Canvas**.

Architecture:

```
SceneGraph  →  SceneRenderer interface
                 ├── CanvasRenderer
                 ├── GlesRenderer      (GLES 2.0 shaders)
                 └── VulkanRenderer    (detect HW → GLES today → native Vulkan later)
```

Overlay windows prefer `GlSceneView` (GLSurfaceView). If GL init fails, Canvas host is used automatically.

Manifest declares:
- `glEsVersion` 2.0 required
- Vulkan hardware features optional (`required=false`)
