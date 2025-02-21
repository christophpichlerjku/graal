package com.oracle.svm.interpreter.metadata;

import com.oracle.svm.core.Uninterruptible;
import com.oracle.svm.core.graal.code.CompiledArgumentType;
import com.oracle.svm.core.jdk.UninterruptibleUtils;
import com.oracle.svm.core.util.VMError;
import jdk.vm.ci.meta.JavaKind;

import static com.oracle.svm.core.Uninterruptible.CALLED_FROM_UNINTERRUPTIBLE_CODE;

public class CompiledSignature {
    private final JavaKind returnKind;
    private final CompiledArgumentType[] compiledArgumentTypes;
    private final int stackSize;

    public CompiledSignature(JavaKind returnKind, CompiledArgumentType[] compiledArgumentTypes, int stackSize) {
        this.returnKind = returnKind;
        this.compiledArgumentTypes = compiledArgumentTypes;
        this.stackSize = stackSize;
    }

    @Uninterruptible(reason = CALLED_FROM_UNINTERRUPTIBLE_CODE, mayBeInlined = true)
    public CompiledArgumentType[] getCompiledArgumentTypes() {
        return compiledArgumentTypes;
    }

    @Uninterruptible(reason = CALLED_FROM_UNINTERRUPTIBLE_CODE, mayBeInlined = true)
    public int getCount() {
        return compiledArgumentTypes.length;
    }

    @Uninterruptible(reason = CALLED_FROM_UNINTERRUPTIBLE_CODE, mayBeInlined = true)
    public JavaKind getReturnKind() {
        return returnKind;
    }

    @Uninterruptible(reason = CALLED_FROM_UNINTERRUPTIBLE_CODE, mayBeInlined = true)
    public int getStackSize() {
        return stackSize;
    }
}
