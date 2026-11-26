package com.streamlinemyjob.inspect.data

import com.streamlinemyjob.inspect.core.ActionChoice

/** Test helper: an action value id, or "No action" when [valueId] is null. */
fun actionOf(valueId: String?): ActionChoice = if (valueId == null) ActionChoice.NoAction else ActionChoice.Value(valueId)
