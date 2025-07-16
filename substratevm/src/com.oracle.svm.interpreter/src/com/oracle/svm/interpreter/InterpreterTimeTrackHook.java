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
        long reopt = Interpreter.reoptedMethodCountGlobal.get();

        IsolateThread thread;
        for (thread = VMThreads.firstThreadUnsafe(); thread.isNonNull(); thread = VMThreads.nextThread(thread)) {
            sum += Interpreter.timeTrackSum.get(thread);
            reopt += Interpreter.reoptedMethodCount.get(thread);
        }
        final long initiallyManaged = Interpreter.initiallyManagedCount;
        Log.log().string("Interpreter tracking time global sum: ").signed(sum).string("ns").newline().flush();
        if (initiallyManaged > 0) {
            Log.log().string("Re-opt methods: ").signed(reopt).string(" of ").signed(initiallyManaged).string(": ").rational(reopt, initiallyManaged, 5).newline().flush();
        }
        double reoptThreshold = InterpreterOptions.ReoptThreshold.getValue();
        final long denom = 1_000_000;
        Log.log().string("Re-opt threshold: ").rational((long) (reoptThreshold * denom), denom, 3).newline().flush();
    }
}
