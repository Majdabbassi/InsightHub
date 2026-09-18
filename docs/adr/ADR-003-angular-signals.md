# ADR-003: Angular standalone components with signals

Status: Accepted

## Context

The frontend must stay maintainable as pages grow (dataset viewer reached ~800 lines; project pages share formatting/error handling). Angular has two input models: classic decorator `@Input()`/`@Output()` and the modern signal-based `input()`/`output()`.

## Decision

- Standalone components throughout (no `NgModule` except the bootstrap module).
- Signal-based `input()`/`output()` + `signal()` state for all new components (e.g. `preview-table`, `quality-analysis`, `cleaning-panel`).
- One legacy component (`Icon`) keeps decorator inputs because static template attributes like `name="folder"` cannot bind to signal inputs.
- Shared formatting and error helpers extracted to `app/shared/format.ts` and `app/shared/errors.ts`; components keep thin delegating methods so templates stay unchanged.

## Consequences

- Component APIs are explicit and typed; parent/child state flows are obvious.
- Zone independence is easier to reach in the future (signals work without Zone.js).
- Guardrails: mix of input styles is allowed only where there is a concrete reason (documented here).