# AIRI - Android Artificial Intelligence Runtime Interface

<div align="center">

**An autonomous on-device AI assistant for Android with cognitive processing capabilities**

[![Android](https://img.shields.io/badge/Platform-Android-green.svg)](https://android.com)
[![Kotlin](https://img.shields.io/badge/Language-Kotlin-purple.svg)](https://kotlinlang.org)
[![Jetpack Compose](https://img.shields.io/badge/UI-Jetpack%20Compose-blue.svg)](https://developer.android.com/jetpack/compose)
[![License](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

</div>

---

## 📖 Overview

AIRI (Android Artificial Intelligence Runtime Interface) is a next-generation AI assistant that runs entirely on-device. Unlike cloud-based assistants, AIRI processes your commands locally using advanced cognitive architecture, providing intelligent automation while maintaining complete privacy.

### Key Features

- 🧠 **Cognitive Loop Architecture** - Multi-stage processing pipeline (Input → Planning → Execution → Learning)
- 📱 **On-Device Processing** - All AI inference happens locally using llama.cpp
- 🎯 **Context-Aware** - Understands device state (battery, network, memory, active apps)
- ⚡ **Real-Time Execution** - Live feedback during command processing
- 🎨 **Modern UI** - Beautiful Jetpack Compose interface with glassmorphism effects
- 🔒 **Privacy First** - No data leaves your device
- 🌐 **RTL Support** - Full Arabic language support
- 🔄 **Extensible** - Clean architecture allows easy feature additions

---

## 🏗️ Architecture

AIRI follows **Clean Architecture** principles with clear separation of concerns across 9 distinct layers:

```
┌─────────────────────────────────────────────────────────────┐
│                         UI Layer                            │
│  Jetpack Compose screens, ViewModels, Theme, Navigation    │
└────────────────────────┬────────────────────────────────────┘
                         │
┌────────────────────────▼────────────────────────────────────┐
│                      Core Layer                             │
│    UnifiedCognitiveLoop, ServiceLocator, IntentRouter      │
└────────────────────────┬────────────────────────────────────┘
                         │
        ┌────────────────┼────────────────┐
        │                │                │
┌───────▼──────┐  ┌──────▼──────┐  ┌─────▼──────┐
│ Agent Layer  │  │ World Layer │  │ Memory     │
│              │  │             │  │ Layer      │
│ - Planning   │  │ - State     │  │            │
│ - Execution  │  │ - Context   │  │ - Room DB  │
│ - Decision   │  │ - Risk      │  │ - Cache    │
│ - Learning   │  │ - Sensors   │  │ - History  │
└───────┬──────┘  └──────┬──────┘  └─────┬──────┘
        │                │                │
        └────────────────┼────────────────┘
                         │
        ┌────────────────┼────────────────┐
        │                │                │
┌───────▼──────┐  ┌──────▼──────┐  ┌─────▼──────┐
│ Accessibility│  │  AI Layer   │  │ Tools      │
│ Layer        │  │             │  │ Layer      │
│              │  │ - LLM       │  │            │
│ - Service    │  │ - Models    │  │ - Registry │
│ - Scanner    │  │ - Prompts   │  │ - External │
│ - Executor   │  │ - Inference │  │ - Utils    │
└──────────────┘  └─────────────┘  └────────────┘
```

### Layer Descriptions

#### 1. **UI Layer** (`ui/`)
- **Purpose:** User interface and interaction
- **Components:**
  - `AiriApp.kt` - Root navigation controller
  - `WelcomeScreen.kt` - Cosmic intro screen
  - `LoginScreen.kt` - Authentication interface
  - `ChatScreen.kt` - Main chat interface with agent overlay
  - `ChatViewModel.kt` - **Critical integration point** connecting UI to cognitive loop
  - `StarBackground.kt` - Animated particle system
  - `GlassCard.kt` - Glassmorphism components
  - Theme files with cosmic color scheme
- **Files:** 9

#### 2. **Core Layer** (`core/`)
- **Purpose:** Central orchestration and cognitive processing
- **Key Components:**
  - `UnifiedCognitiveLoop.kt` - **Main execution engine**
  - `ServiceLocator.kt` - Dependency injection
  - `IntentRouter.kt` - Intent classification and routing
  - `IntentType.kt` - Intent enumeration
- **Files:** 6

#### 3. **Agent Layer** (`agent/`)
- **Purpose:** Intelligent planning, execution, and learning
- **Submodules:**
  - **Planning** (`planning/`) - Plan generation, brain management
    - `PlanGenerator.kt` - Converts LLM output to executable plans
    - `BrainManager.kt` - High-level cognitive coordination
    - `ActionPlanner.kt` - Strategic action planning
    - `IntentEngine.kt` - Intent classification
  - **Execution** (`execution/`) - Command routing and execution
    - `CommandRouter.kt` - Routes plan steps to appropriate executors
    - Node executors for UI interaction
  - **Decision** (`decision/`) - Decision making and policy
    - `DecisionEngine.kt` - Strategic decision making
    - Policy engines for action selection
  - **Learning** (`learning/`) - Reinforcement learning
    - Interaction tracking
    - Suggestion scoring
    - Experience storage
- **Files:** 57

#### 4. **Accessibility Layer** (`accessibility/`)
- **Purpose:** Android Accessibility API integration for device control
- **Components:**
  - `AiriAccessibilityService.kt` - Main accessibility service
  - `ScreenContextHolder.kt` - Screen state management
  - UI tree scanning and traversal
  - Action executors (click, type, scroll, navigate)
- **Files:** 16

#### 5. **Memory Layer** (`memory/`)
- **Purpose:** Persistent storage and context management
- **Components:**
  - `AiriDatabase.kt` - Room database configuration
  - DAOs for data access
  - Entities for database tables
  - Repositories for memory management
  - Context engines for short-term memory
- **Files:** 11

#### 6. **AI Layer** (`ai/`)
- **Purpose:** LLM integration and model management
- **Components:**
  - `LlamaManager.kt` - llama.cpp integration
  - `ModelManager.kt` - Model lifecycle management
  - `PromptBuilder.kt` - Prompt engineering
  - Model benchmarking and optimization
- **Files:** 10

#### 7. **World Layer** (`world/`)
- **Purpose:** Device context and environmental awareness
- **Components:**
  - `WorldStateManager.kt` - **Context capture** (battery, network, memory)
  - `WorldState.kt` - Device state data class
  - `RiskEstimator.kt` - Action risk assessment
  - Context snapshots
  - Screen hashing
- **Files:** 7

#### 8. **Tools Layer** (`tools/`)
- **Purpose:** External integrations and utilities
- **Components:**
  - `ToolRegistry.kt` - Tool management
  - `ToolExecutor.kt` - Tool execution
  - `ModelDownloadManager.kt` - Model acquisition
  - N8n workflow integration
- **Files:** 9

#### 9. **App Layer** (`app/`)
- **Purpose:** Application initialization
- **Components:**
  - `AIRIApplication.kt` - Application class with initialization
- **Files:** 1

**Total Files:** 127 Kotlin files

---

## 🔄 Data Flow

### Complete Execution Pipeline

```
┌──────────────────────────────────────────────────────────────┐
│ 1. USER INPUT                                                │
│    User types: "افتح Chrome"                                  │
└───────────────────────────┬──────────────────────────────────┘
                            │
┌───────────────────────────▼──────────────────────────────────┐
│ 2. UI LAYER (ChatScreen)                                     │
│    Input captured → viewModel.sendMessage(input)             │
└───────────────────────────┬──────────────────────────────────┘
                            │
┌───────────────────────────▼──────────────────────────────────┐
│ 3. VIEW MODEL (ChatViewModel)                                │
│    - Add message to UI                                       │
│    - Set agent state: "Processing..."                        │
│    - Trigger: cognitiveLoop.process(input)                   │
└───────────────────────────┬──────────────────────────────────┘
                            │
┌───────────────────────────▼──────────────────────────────────┐
│ 4. COGNITIVE LOOP (UnifiedCognitiveLoop)                     │
│    ━━━ Cognitive Loop Start ━━━                              │
│                                                               │
│    Step 4a: Capture World State                              │
│    worldStateManager.captureCurrentState()                   │
│    → Battery: 85%, Network: WIFI, Memory: 1024MB             │
│                                                               │
│    Step 4b: Generate Plan                                    │
│    planGenerator.createActionPlanFromLLM(llmResponse)        │
│    → ActionPlan { intent, List<PlanStep> }                   │
│                                                               │
│    Step 4c: Execute Plan                                     │
│    for each step: CommandRouter.execute(step)                │
└───────────────────────────┬──────────────────────────────────┘
                            │
┌───────────────────────────▼──────────────────────────────────┐
│ 5. COMMAND ROUTER                                            │
│    Match step type → route to executor                       │
│    - OpenApp → AccessibilityCommandBridge                    │
│    - Click → NodeActionExecutor                              │
│    - Type → KeyboardInput                                    │
└───────────────────────────┬──────────────────────────────────┘
                            │
┌───────────────────────────▼──────────────────────────────────┐
│ 6. ACCESSIBILITY SERVICE                                     │
│    - Find UI nodes in screen tree                            │
│    - Perform actions (click, type, scroll)                   │
│    - Return CommandResult { success, message }               │
└───────────────────────────┬──────────────────────────────────┘
                            │
┌───────────────────────────▼──────────────────────────────────┐
│ 7. RESULT AGGREGATION                                        │
│    UnifiedCognitiveLoop collects all CommandResults          │
│    → CognitiveResult.Success/Failed/PartialSuccess           │
│    ━━━ Cognitive Loop End ━━━                                │
└───────────────────────────┬──────────────────────────────────┘
                            │
┌───────────────────────────▼──────────────────────────────────┐
│ 8. UI UPDATE                                                 │
│    - ViewModel receives result                               │
│    - Add AI response to messages                             │
│    - Clear agent overlay                                     │
│    - Compose recomposes automatically                        │
└───────────────────────────┬──────────────────────────────────┘
                            │
                     ┌──────▼──────┐
                     │ USER SEES   │
                     │ RESULT      │
                     └─────────────┘
```

---

## 🎯 Core Components

### UnifiedCognitiveLoop

The brain of AIRI. Orchestrates the complete cognitive pipeline:

```kotlin
class UnifiedCognitiveLoop {
    suspend fun process(input: BrainInput, llmResponse: String): CognitiveResult {
        // 1. Capture world state
        val worldState = worldStateManager.getCurrentState()
        
        // 2. Generate action plan
        val actionPlan = planGenerator.createActionPlanFromLLM(llmResponse)
        
        // 3. Execute plan
        return executeActionPlan(actionPlan, worldState)
    }
}
```

**Capabilities:**
- Dual API support (String + BrainInput)
- World state integration
- Step-by-step execution
- Error handling and recovery
- Detailed logging

### WorldStateManager

Provides context awareness:

```kotlin
data class WorldState(
    val batteryLevel: Int,           // 0-100%
    val isCharging: Boolean,          // Charging status
    val networkType: NetworkType,     // WIFI/CELLULAR/NONE
    val isNetworkConnected: Boolean,  // Internet available
    val availableMemoryMB: Long,      // Free RAM in MB
    val topAppPackage: String?        // Active app package name
)
```

### PlanGenerator

Converts LLM JSON into executable action plans:

```kotlin
// Input (LLM JSON)
{
  "goal": "Open Chrome",
  "steps": [
    {"id": "1", "action": "open_app", "params": {"app_name": "Chrome"}}
  ]
}

// Output (ActionPlan)
ActionPlan(
    intent = "Open Chrome",
    steps = [PlanStep.OpenApp(appName = "Chrome")],
    requiresConfirmation = false
)
```

### CommandRouter

Routes plan steps to appropriate executors:

```kotlin
suspend fun execute(step: PlanStep): CommandResult {
    return when (step) {
        is PlanStep.OpenApp -> openApp(step.appName)
        is PlanStep.Click -> clickElement(step.targetText)
        is PlanStep.Type -> typeText(step.text)
        is PlanStep.Navigate -> navigate(step.destination)
        // ... more step types
    }
}
```

---

## 🎨 UI Features

### Screens

#### 1. Welcome Screen
- Animated star particle background
- Cosmic theme with gradient effects
- "Get Started" CTA button

#### 2. Login Screen
- Three animated glass cards with features
- Email and password fields
- RTL-aware layout for Arabic

#### 3. Chat Screen
- ChatGPT-style messaging interface
- User messages (right, cyan bubble)
- AI responses (left, dark bubble)
- **Agent Overlay** - Shows real-time execution status
- Fixed input bar at bottom
- Cosmic background with stars

### Visual Design

**Color Scheme:**
- Background: Deep cosmic blue-black (`#0A0E27`)
- Accent: Electric cyan (`#00F2FF`)
- Glass cards: Semi-transparent white with blur
- Stars: Animated white particles

**Animations:**
- Star particles falling vertically
- Glass cards sliding in
- Agent overlay fade in/out
- Smooth navigation transitions

---

## 🚀 Getting Started

### Prerequisites

- Android Studio Hedgehog (2023.1.1) or newer
- JDK 17 or higher
- Android SDK 26+ (Android 8.0+)
- Kotlin 1.9.22+

### Installation

1. **Clone the repository**
```bash
git clone https://github.com/AbdulLatef2380/AIRI-Project.git
cd AIRI-Project
```

2. **Open in Android Studio**
- File → Open → Select project directory
- Wait for Gradle sync to complete

3. **Build the project**
```bash
./gradlew assembleDebug
```

4. **Run on device/emulator**
- Click ▶️ Run button
- Select target device
- Wait for installation

### First Run

1. Launch app → See Welcome screen
2. Tap "Get Started" → Navigate to Login
3. Tap "دخول" (Login) → Navigate to Chat
4. Enable Accessibility Service:
   - Settings → Accessibility → AIRI → Enable
5. Type command: "افتح Chrome" (Open Chrome)
6. Watch agent overlay show execution
7. See result in chat

---

## 📦 Dependencies

### Core Libraries

```kotlin
// Jetpack Compose
androidx.compose.bom:2024.02.00
androidx.compose.material3

// Room Database
androidx.room:room-runtime:2.6.1
androidx.room:room-ktx:2.6.1

// Coroutines
org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1
org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1

// Lifecycle
androidx.lifecycle:lifecycle-viewmodel-compose
androidx.lifecycle:lifecycle-runtime-ktx

// Networking
com.squareup.okhttp3:okhttp:4.12.0
com.google.code.gson:gson:2.10.1
```

### Native Components

- **llama.cpp** - On-device LLM inference (JNI integration)
- **Android Accessibility Service** - Device control

---

## 🧪 Testing

### Manual Testing

```bash
# Test Flow
1. Launch app
2. Navigate Welcome → Login → Chat
3. Type: "افتح Chrome"
4. Verify agent overlay appears
5. Verify Chrome opens
6. Check logcat for cognitive loop logs
```

### Logcat Monitoring

```bash
adb logcat -s UnifiedCognitiveLoop:D
```

**Expected Output:**
```
━━━ Cognitive Loop Start ━━━
Input: افتح Chrome
World → Battery: 85%, Network: WIFI, Memory: 1024MB
Plan: Open Chrome (1 steps)
Step 1/1: OpenApp
  ✓ ok
All steps succeeded
━━━ Cognitive Loop End ━━━
```

---

## 🔧 Configuration

### Customizing Cognitive Loop

Edit `UnifiedCognitiveLoop.kt`:

```kotlin
// Add custom processing logic
suspend fun process(input: BrainInput, llmResponse: String): CognitiveResult {
    // Your custom pre-processing
    val worldState = captureWorldState()
    
    // Your custom plan generation
    val actionPlan = customPlanGenerator(llmResponse)
    
    // Your custom execution
    return executeActionPlan(actionPlan, worldState)
}
```

### Adding New Commands

1. **Add new PlanStep type** (`agent/planning/PlanStep.kt`)
```kotlin
sealed class PlanStep {
    // ... existing steps
    data class CustomAction(val params: Map<String, String>) : PlanStep()
}
```

2. **Add handler in CommandRouter** (`agent/execution/command/CommandRouter.kt`)
```kotlin
suspend fun execute(step: PlanStep): CommandResult {
    return when (step) {
        // ... existing handlers
        is PlanStep.CustomAction -> handleCustomAction(step.params)
    }
}
```

3. **Implement executor**
```kotlin
private suspend fun handleCustomAction(params: Map<String, String>): CommandResult {
    // Your implementation
    return CommandResult(success = true, message = "Custom action completed")
}
```

---

## 📊 Performance

### Metrics

- **App Launch:** < 2 seconds
- **Screen Navigation:** Instant
- **Cognitive Loop:** < 1 second (without accessibility)
- **With Accessibility:** < 3 seconds (varies by action)

### Memory Usage

- **Baseline:** ~50 MB
- **With UI:** ~120 MB
- **During Execution:** ~150 MB
- **With LLM Loaded:** ~300-500 MB (depends on model size)

### Battery Impact

- **Idle:** Negligible
- **Active Processing:** Moderate (similar to browser)
- **LLM Inference:** High (during model execution)

---

## 🛡️ Privacy & Security

### Privacy Features

✅ **100% On-Device Processing** - No data sent to cloud  
✅ **No Analytics** - Zero tracking or telemetry  
✅ **Local Storage** - All data stays on your device  
✅ **No Permissions Abuse** - Only requests necessary permissions  

### Permissions Required

- **Accessibility Service** - Required for device control
- **Internet** - Optional, only for model downloads
- **Storage** - For model files storage

---

## 🗺️ Roadmap

### Current Status (v1.0)

- ✅ Core cognitive loop implementation
- ✅ Jetpack Compose UI
- ✅ World state awareness
- ✅ Basic command execution
- ✅ Arabic language support

### Planned Features

#### v1.1
- [ ] Voice input integration
- [ ] Multi-language support (English, French, Spanish)
- [ ] Enhanced error recovery
- [ ] Command history

#### v1.2
- [ ] LLM model selection UI
- [ ] Custom model training
- [ ] Plugin system
- [ ] Automation workflows

#### v2.0
- [ ] Multi-modal input (voice + text + gestures)
- [ ] Cross-app workflows
- [ ] Advanced learning from user behavior
- [ ] Cloud sync (optional, encrypted)

---

## 🤝 Contributing

Contributions are welcome! Please follow these guidelines:

### How to Contribute

1. **Fork the repository**
2. **Create a feature branch**
```bash
git checkout -b feature/amazing-feature
```

3. **Make your changes**
- Follow Kotlin coding conventions
- Maintain clean architecture principles
- Add comments for complex logic
- Update documentation

4. **Test thoroughly**
- Test on multiple devices
- Verify no regressions
- Check logcat for errors

5. **Commit with clear messages**
```bash
git commit -m "Add amazing feature: detailed description"
```

6. **Push and create PR**
```bash
git push origin feature/amazing-feature
```

### Coding Standards

- **Language:** Kotlin
- **Style:** Official Kotlin style guide
- **Architecture:** Clean Architecture with SOLID principles
- **Comments:** KDoc format for public APIs
- **Naming:** Descriptive, camelCase for variables, PascalCase for classes

---

## 📄 License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

---

## 👤 Author

**AbdulLatef**

- GitHub: [@AbdulLatef2380](https://github.com/AbdulLatef2380)
- Repository: [AIRI-Project](https://github.com/AbdulLatef2380/AIRI-Project)

---

## 🙏 Acknowledgments

- **llama.cpp** - For efficient on-device LLM inference
- **Jetpack Compose** - For modern UI toolkit
- **Android Accessibility API** - For device control capabilities
- **Kotlin Coroutines** - For async processing

---

## 📞 Support

Having issues? Here are some resources:

- **Issues:** [GitHub Issues](https://github.com/AbdulLatef2380/AIRI-Project/issues)
- **Discussions:** [GitHub Discussions](https://github.com/AbdulLatef2380/AIRI-Project/discussions)
- **Documentation:** See `/docs` folder for detailed guides

---

## 🌟 Star History

If you find AIRI useful, please give it a ⭐ on GitHub!

---

<div align="center">

**Built with ❤️ for privacy-conscious Android users**

</div>
