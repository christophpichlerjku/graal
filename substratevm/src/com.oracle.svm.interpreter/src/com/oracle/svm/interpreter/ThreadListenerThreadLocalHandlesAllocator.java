package com.oracle.svm.interpreter;

import com.oracle.svm.core.handles.ThreadLocalHandles;
import com.oracle.svm.core.thread.ThreadListener;

class ThreadListenerThreadLocalHandlesAllocator implements ThreadListener {
    @Override
    public void beforeThreadRun() {
        InterpreterStubSection.TL_HANDLES.set(new ThreadLocalHandles<>(InterpreterStubSection.MAX_HANDLES));
        ThreadListener.super.beforeThreadRun();
    }
}
