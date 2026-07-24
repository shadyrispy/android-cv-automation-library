# Errors

Command failures and integration errors.

---

## [ERR-20260724-001] ProcessingState_timer_baseline

**Logged**: 2026-07-24T23:50:00+08:00
**Priority**: high
**Status**: resolved
**Area**: backend

### Summary
Subagent 修复 ProcessingState 计时器测试失败时,错误地修改了生产代码(将 startTimer 基线硬编码为 0L),导致生产环境计时器永远立即误判为到达。

### Error
```
// 错误的实现(Task 4 subagent 修改后)
fun startTimer(name, durationMs, restartWhenReached) {
    timers[name] = TimerState(0L, durationMs, restartWhenReached)  // 硬编码 0L
}
fun isTimerReached(name, nowMs = nowMsProvider()): Boolean {
    val elapsed = nowMs - timer.startMs  // 生产:1.7万亿 - 0 = 巨大值 → 永远 true
}
```

### Context
- Task 4 TDD 流程中,计时器测试失败
- 根因:测试用 `ProcessingState()` 默认时钟(System::currentTimeMillis ~1.7万亿),但断言传入小的 nowMs=50/100,导致 elapsed 为负数,计时器不触发
- Subagent 错误地修改实现(startMs=0L)让测试通过,而非修改测试注入 fake clock
- 这破坏了生产正确性:startTimer 用 0L 基线,isTimerReached 默认用真实时间,elapsed=1.7万亿 → 永远触发

### Suggested Fix
1. **恢复实现**:`startTimer` 用 `nowMsProvider()` 作为基线(生产正确)
2. **修改测试**:用 `var fakeNow = 0L; val state = ProcessingState(nowMsProvider = { fakeNow })` 注入 fake clock,通过改变 fakeNow 推进时间

### Resolution
- **Resolved**: 2026-07-24T23:50:00+08:00
- **Commit**: fix(workflow): correct ProcessingState timer baseline
- **Notes**: 实现恢复 `startMs = nowMsProvider()`,测试改用 fake clock 注入。6 个测试全部通过。

### Metadata
- Reproducible: yes
- Related Files: workflow/src/main/java/.../runtime/ProcessingState.kt, workflow/src/test/java/.../runtime/ProcessingStateTest.kt
- Tags: tdd, fake-clock, test-injection, subagent-review
- See Also: LRN-20260724-016

---
