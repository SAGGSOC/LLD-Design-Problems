# Low-Level Design — Delivery Framework

A step-by-step framework for structuring a 35-minute LLD interview.

Common failure modes:
- Diving into code before the interviewer understands your structure
- Moving too slowly through setup and running out of time
- Getting bogged down in edge cases before the happy path is clear

Follow this sequence and pacing. If the interviewer pulls you off it, follow their lead but gently guide back to the important bits.

---

## 1. Requirements (~5 minutes)

Turn a one-sentence prompt into a clear spec. Ask questions across these themes:

- **Primary capabilities** — what operations must the system support?
- **Rules and completion** — what defines success, failure, state transitions?
- **Error handling** — how to respond to invalid inputs or actions?
- **Scope boundaries** — what's in scope vs. explicitly out?

Write the spec on the whiteboard and confirm it with your interviewer.

### Example: Tic Tac Toe

```
Requirements:
1. Two players alternate placing X and O on a 3x3 grid.
2. A player wins by completing a row, column, or diagonal.
3. The game ends in a draw if all nine cells are filled with no winner.
4. Invalid moves should be rejected (occupied cell, acting after game over).
5. The system should provide a way to query state and reset the game.

Out of Scope:
- UI/rendering layer
- AI opponent or move suggestions
- Networked multiplayer
- Variable board sizes (NxN grids)
- Undo/redo
```

---

## 2. Entities and Relationships (~3 minutes)

Pull meaningful nouns from the requirements.

**Filter:**
- If something maintains changing state or enforces rules → its own entity
- If it's just information attached to something else → a field on another class

**Relationships — ask:**
- Which entity is the orchestrator (drives the main workflow)?
- Which entities own durable state?
- How do they depend on each other? (has-a, uses, contains)
- Where should specific rules logically live?

Simple list + arrows is enough. Don't force strict UML.

### Example: Tic Tac Toe

```
Entities:
- Game
- Board
- Player

Relationships:
- Game → Board
- Game → Player (2x)
```

---

## 3. Class Design (~10-15 minutes)

Go entity by entity, top-down. Start with the orchestrator, then supporting entities.

For each entity, answer two questions:
1. **State** — what does this class need to remember to enforce the requirements?
2. **Behavior** — what operations or queries does it need to expose?

### Deriving State from Requirements

| Requirement | What Game must track |
|---|---|
| "Two players alternate placing X and O on a 3x3 grid" | The two players, whose turn it is, the Board |
| "The game ends when a player wins or the board is full" | Game state (IN_PROGRESS, WON, DRAW), winner if any |

### Deriving Behavior from Requirements

| Need from requirements | Method on Game |
|---|---|
| Players need to make moves | `makeMove(player, row, col) -> bool` |
| Ask whose turn it is | `getCurrentPlayer() -> Player` |
| Check game state | `getGameState() -> GameState` |
| See who won | `getWinner() -> Player?` |
| Inspect the board | `getBoard() -> Board` |

### Encapsulation Principle: Tell, Don't Ask

Keep rules with the entity that owns the relevant state.
- Workflow/lifecycle rules ("can this operation run now?") → orchestrator
- Data-specific rules ("is this cell occupied?") → the entity that owns the data

### Example Output

```
class Game:
  - board: Board
  - playerX: Player
  - playerO: Player
  - currentPlayer: Player
  - state: GameState (IN_PROGRESS, WON, DRAW)
  - winner: Player? (null if no winner)

  + makeMove(player, row, col) -> bool
  + getCurrentPlayer() -> Player
  + getGameState() -> GameState
  + getWinner() -> Player?
  + getBoard() -> Board
```

### On UML

Skip formal UML unless the interviewer asks. Simple class notation with fields and methods is faster and just as clear. If they ask for UML, offer simplified class notation — usually accepted.

---

## 4. Implementation (~10 minutes)

Ask the interviewer what level of detail they want: pseudo-code, full code in a specific language, or just verbal walkthrough.

### Structure Each Method

1. **Happy path first** — inputs, sequence of steps, internal calls, return value or state change
2. **Edge cases next** — invalid inputs, illegal operations, out-of-range values, state violations

### Example: makeMove pseudo-code

```
makeMove(player, row, col)
    if state != IN_PROGRESS
        return false
    if player != currentPlayer
        return false
    if !board.canPlace(row, col)
        return false

    board.placeMark(row, col, player.mark)

    if board.checkWin(row, col, player.mark)
        state = WON
        winner = player
    else if board.isFull()
        state = DRAW
    else
        currentPlayer = (player == playerX) ? playerO : playerX

    return true
```

### On Design Patterns

Patterns like Singleton, Factory, Builder, Strategy, State can be impactful — but more candidates overengineer with patterns than miss them. Only add a pattern when it solves a concrete problem.

### Verification: Walk Through a Concrete Scenario

After implementing, take 1-2 minutes to trace through a specific example:

```
Initial: board empty, currentPlayer = X
makeMove(X, 0, 0) → board[0][0] = X, currentPlayer = O
makeMove(O, 1, 1) → board[1][1] = O, currentPlayer = X
...
```

This catches:
- Forgot to switch turns
- Win detection doesn't trigger
- State transitions in wrong order
- Edge case handling breaks

If you find a bug, fix it on the spot. Finding your own bugs is a positive signal.

---

## 5. Extensibility (~5 minutes, if time permits)

Interviewer-led. They propose a twist to see whether your design evolves cleanly.

**By level:**
- Junior: may get little or no extensibility discussion
- Mid: one or two small follow-ups
- Senior: several "what if we..." questions

**Pattern of a good answer:** point to the part of your design that makes the change clean, don't rewrite code.

### Example: "How would you add undo?"

> All state transitions flow through `makeMove`. To add undo, I'd introduce a command history stack. Each successful action records the previous state before modifying anything. An `undo()` method pops the stack, reverts to that state, and the rest of the system doesn't need to change.

Stay high-level. The goal is to show your initial design has clean boundaries.

---

## Time Budget Summary

| Phase                         | Time       |
|-------------------------------|------------|
| Requirements                  | ~5 min     |
| Entities and Relationships    | ~3 min     |
| Class Design                  | ~10-15 min |
| Implementation + Verification | ~10 min    |
| Extensibility                 | ~5 min     |

---

## Quick Checklist

- [ ] Asked clarifying questions across the 4 themes
- [ ] Wrote requirements + out-of-scope on the whiteboard
- [ ] Identified entities and relationships
- [ ] Defined state and behavior for each entity, tied back to requirements
- [ ] Implemented the main methods (happy path → edge cases)
- [ ] Traced through a concrete scenario to verify
- [ ] Addressed at least one extensibility question
