package io.github.syrou.reaktiv.introspection.capture

import io.github.syrou.reaktiv.core.tracing.LogicMethodCompleted
import io.github.syrou.reaktiv.core.tracing.LogicMethodFailed
import io.github.syrou.reaktiv.core.tracing.LogicMethodStart

public sealed interface CapturedLogicEvent {
    public data class Started(val event: LogicMethodStart) : CapturedLogicEvent
    public data class Completed(val event: LogicMethodCompleted) : CapturedLogicEvent
    public data class Failed(val event: LogicMethodFailed) : CapturedLogicEvent
}
