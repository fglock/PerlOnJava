# Control Flow Feature Flags

This document describes the feature flags used to control the tagged return value control flow implementation.

## Overview

Feature flags are implemented as `static final` variables that can be toggled to enable/disable specific features or debug output. This allows for:
- Gradual rollout of features
- Easy debugging by enabling/disabling specific components
- Performance tuning
- Troubleshooting issues

## Flag Locations

### EmitControlFlow.java
- **ENABLE_TAGGED_RETURNS** (true): Enables tagged return values for non-local control flow (Phase 2 - ACTIVE)
- **DEBUG_CONTROL_FLOW** (false): Enables debug output for control flow operations

### EmitterMethodCreator.java
- **ENABLE_TAILCALL_TRAMPOLINE** (true): Enables tail call trampoline at returnLabel (Phase 3)
- **DEBUG_CONTROL_FLOW** (false): Enables debug output for control flow at method level

### EmitSubroutine.java
- **ENABLE_CONTROL_FLOW_CHECKS** (false): Enables control flow checks at call sites (Phase 7 - DEFERRED)
- **DEBUG_CONTROL_FLOW** (false): Enables debug output for control flow checks

### EmitForeach.java
- **ENABLE_LOOP_HANDLERS** (false): Enables control flow handlers for foreach loops (Phase 3 - TODO)
- **DEBUG_LOOP_CONTROL_FLOW** (false): Enables debug output for loop control flow

### EmitStatement.java
- **ENABLE_LOOP_HANDLERS** (false): Enables control flow handlers for for/while/until loops (Phase 3 - TODO)
- **DEBUG_LOOP_CONTROL_FLOW** (false): Enables debug output for loop control flow

## Current Status

### Active Features (true)
- **ENABLE_TAGGED_RETURNS**: Core mechanism for non-local control flow via marked RuntimeList objects
- **ENABLE_TAILCALL_TRAMPOLINE**: Global tail call trampoline for `goto &NAME`

### Inactive Features (false)
- **ENABLE_LOOP_HANDLERS**: Per-loop handlers for last/next/redo/goto (Phase 3 - in progress)
- **ENABLE_CONTROL_FLOW_CHECKS**: Call-site checks to prevent marked RuntimeList from being POPped (Phase 7 - deferred due to ASM frame computation issues)

### Debug Flags (all false)
- **DEBUG_CONTROL_FLOW**: Various debug output flags in different modules
- **DEBUG_LOOP_CONTROL_FLOW**: Debug output for loop-specific control flow

## Usage

To enable a feature or debug output:
1. Locate the relevant class file
2. Change the `static final boolean` value from `false` to `true`
3. Rebuild with `./gradlew build`

To disable a feature:
1. Change the flag value back to `false`
2. Rebuild

## Notes

- All flags are `static final`, so changes require recompilation
- Debug flags should only be enabled for troubleshooting
- Feature flags allow incremental testing of complex control flow implementations

