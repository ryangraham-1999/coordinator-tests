package com.streamlinemyjob.inspect.core

/**
 * What a finding says about its action (designs/stage-1-foundation.md 4.29): an action (a value of
 * the action list), "No action" chosen on purpose, or not chosen yet. In the database these are
 * `action_value_id` and `no_action`; this is the one type the Repository, the port and the screens
 * pass around.
 */
sealed interface ActionChoice {
    data class Value(val valueId: String) : ActionChoice
    data object NoAction : ActionChoice
    data object NotChosen : ActionChoice
}
