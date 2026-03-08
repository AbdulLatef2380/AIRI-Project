sealed class Action {

    data class FindNode(val text: String?) : Action()

    object Click : Action()

    data class Type(val text: String?) : Action()

    object Back : Action()

    object FindInput : Action()

}
