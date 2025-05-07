package com.oracle.svm.interpreter;

import com.oracle.svm.core.jdk.RuntimeSupport;
import com.oracle.svm.core.log.Log;
import com.oracle.svm.core.thread.VMThreads;
import org.graalvm.nativeimage.IsolateThread;

public class InterpreterTimeTrackHook implements RuntimeSupport.Hook {
    @Override
    public void execute(boolean isFirstIsolate) {
        if (!InterpreterOptions.InterpreterTrackTimeSpent.getValue()) {
            return;
        }

        long sum = Interpreter.timeTrackSumGlobal.get();
        Log.log().string("[remove me] sanity check sum=").signed(sum).string("ns").newline();

        IsolateThread thread;
        for (thread = VMThreads.firstThreadUnsafe(); thread.isNonNull(); thread = VMThreads.nextThread(thread)) {
            sum += Interpreter.timeTrackSum.get(thread);
        }
        Log.log().string("Interpreter tracking time global sum: ").signed(sum).string("ns").newline().flush();
    }
}
