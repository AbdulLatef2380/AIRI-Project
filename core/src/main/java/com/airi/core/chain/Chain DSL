package com.airi.core.chain

fun chain(block: TaskChainer.() -> Unit): TaskChainer {
    val chainer = TaskChainer()
    chainer.block()
    return chainer
}
