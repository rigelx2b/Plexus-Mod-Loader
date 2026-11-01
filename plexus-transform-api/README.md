[English](README.md) | [한국어](README.ko.md)

# plexus-transform-api — Declarative Bytecode Transform Contract

`plexus-transform-api` is an internal interface module that declares Minecraft bytecode transform rules, access widening, and transform validation error codes as data models.

---

## 1. Design Principles and Responsibilities

1. **ASM Dependency Isolation:**
   - Does not expose any ASM library types (`ClassNode`, `MethodNode`, etc.) that directly manipulate bytecode.
   - Abstracts transform intents (Method Hook, Static Redirect, Constant Replacement, Access Widening) into pure Java immutable data records (`Record`, `Sealed Interface`).
2. **Declarative Transform Rules:**
   - Mods and version adapters declare "which hook to attach to which method of which class" as immutable objects (`TransformRuleDefinition`), while actual bytecode manipulation is handled entirely by `plexus-transform-engine`.
3. **Strict Failure Policy (`TransformFailurePolicy`):**
   - Supports fail-safe behavior through two policies: `ABORT_STARTUP` (abort startup) and `DISABLE_RULE` (disable only the affected rule).

---

## 2. Key Declaration Models

- **`TransformRuleDefinition`**: Defines the rule ID, target class, target method, applied action, failure policy, and prerequisite rules (`after`).
- **`MethodTransformAction`** (Sealed Interface):
  - `InjectStaticCallAtHead`: Injects a static method call at the start of the method
  - `InvokeHookAtHead`: Invokes an entry hook with context/arguments
  - `InvokeHookBeforeReturn`: Invokes a return hook immediately before `RETURN`
  - `InvokeHookAtCall`: Invokes a hook immediately before or after a specific call, leaving the original call intact
  - `RedirectCall`: Redirects a specific method call site to an alternative method
  - `ReplaceReturnedStringConstant`: Replaces a returned string constant value
  - `ReplaceConstantReturnWithStaticCall`: Changes a method that only returns a constant to return the result of a static hook call
- **`AccessWidening`**: Access modifier widening model declaring `public` expansion and `final` removal.

---

## 3. Verification and Build

```powershell
.\gradlew.bat :plexus-transform-api:check
```
Integration testing of the actual bytecode transform engine is performed in `plexus-transform-engine`.
