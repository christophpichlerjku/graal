package com.oracle.svm.interpreter;

import com.oracle.svm.core.Uninterruptible;
import com.oracle.svm.core.thread.ThreadListener;
import org.graalvm.nativeimage.IsolateThread;

class InterpreterTrackTimePerThread implements ThreadListener {
    @Override
    @Uninterruptible(reason = "Force that all listeners are uninterruptible.")
    public void afterThreadExit(IsolateThread isolateThread, Thread javaThread) {
        Interpreter.timeTrackSumGlobal.addAndGet(Interpreter.timeTrackSum.get(isolateThread));
        Interpreter.reoptedMethodCountGlobal.addAndGet(Interpreter.reoptedMethodCount.get(isolateThread));

        // Reset thread sum, to avoid accounting twice for it in InterpreterTimeTrackHook (for
        // shutdown)
        Interpreter.timeTrackSum.set(isolateThread, 0L);
        Interpreter.reoptedMethodCount.set(isolateThread, 0L);

    }
}
