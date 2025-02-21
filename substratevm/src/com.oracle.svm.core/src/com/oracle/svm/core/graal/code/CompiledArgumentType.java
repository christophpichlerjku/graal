package com.oracle.svm.core.graal.code;

import com.oracle.svm.core.Uninterruptible;
import jdk.vm.ci.meta.JavaKind;

import static com.oracle.svm.core.Uninterruptible.CALLED_FROM_UNINTERRUPTIBLE_CODE;

public class CompiledArgumentType {
    private JavaKind kind;
    private int offset;
    private boolean isRegister;

    public CompiledArgumentType(JavaKind kind, int offset, boolean isRegister) {
        this.kind = kind;
        this.offset = offset;
        this.isRegister = isRegister;
    }

    @Uninterruptible(reason = CALLED_FROM_UNINTERRUPTIBLE_CODE, mayBeInlined = true)
    public boolean isRegister() {
        return isRegister;
    }

    @Uninterruptible(reason = CALLED_FROM_UNINTERRUPTIBLE_CODE, mayBeInlined = true)
    public boolean isStackSlot() {
        return !isRegister;
    }

    @Uninterruptible(reason = CALLED_FROM_UNINTERRUPTIBLE_CODE, mayBeInlined = true)
    public int getRegister() {
        assert isRegister;
        return offset;
    }

    @Uninterruptible(reason = CALLED_FROM_UNINTERRUPTIBLE_CODE, mayBeInlined = true)
    public int getStackOffset() {
        // assert !isRegister;
        return offset;
    }

    @Uninterruptible(reason = CALLED_FROM_UNINTERRUPTIBLE_CODE, mayBeInlined = true)
    public JavaKind getKind() {
        return kind;
    }
}
