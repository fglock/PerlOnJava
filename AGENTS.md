# PerlOnJava Agent Guidelines

## Project Rules

### Progress Tracking for Multi-Phase Work

When working on multi-phase projects (like the Shared AST Transformer), **always update the design document when completing a phase**:

1. **Mark the phase as completed** with date
2. **Document what was done** (files changed, key decisions)
3. **Update "Next Steps"** section so the user knows where to resume
4. **Note any blockers or open questions**

Example format at the end of a design doc:

```markdown
## Progress Tracking

### Current Status: Phase 2 in progress

### Completed Phases
- [x] Phase 1: Infrastructure (2024-03-09)
  - Created ASTAnnotation class
  - Added typed fields to AbstractNode
  - Files: AbstractNode.java, ASTAnnotation.java

### Next Steps
1. Implement VariableResolver visitor
2. Add closure capture detection
3. Run differential tests

### Open Questions
- Should we cache lvalue analysis results?
```

### Design Documents

- Design documents live in `dev/design/`
- Each major feature should have its own design doc
- Keep docs updated as implementation progresses
- Reference related docs and skills at the end

### Testing

- Run `./gradlew test` before committing
- For interpreter changes, test with both backends:
  ```bash
  java -jar target/perlonjava.jar -e 'code'           # JVM backend
  java -jar target/perlonjava.jar --int -e 'code'     # Interpreter
  ```

### Commits

- Reference the design doc or issue in commit messages when relevant
- Use conventional commit format when possible

## Available Skills

See `.cognition/skills/` for specialized debugging and development skills:
- `debug-perlonjava` - General debugging
- `interpreter-parity` - JVM vs interpreter parity issues
- `debug-exiftool` - ExifTool test debugging
- `profile-perlonjava` - Performance profiling
