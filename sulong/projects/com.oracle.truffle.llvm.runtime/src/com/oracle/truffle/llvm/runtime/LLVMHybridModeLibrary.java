/*
 * Copyright (c) 2022, Oracle and/or its affiliates.
 *
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification, are
 * permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this list of
 * conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice, this list of
 * conditions and the following disclaimer in the documentation and/or other materials provided
 * with the distribution.
 *
 * 3. Neither the name of the copyright holder nor the names of its contributors may be used to
 * endorse or promote products derived from this software without specific prior written
 * permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS
 * OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
 * MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE
 * COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
 * EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE
 * GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED
 * AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED
 * OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package com.oracle.truffle.llvm.runtime;

import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.interop.UnknownIdentifierException;
import com.oracle.truffle.api.interop.UnsupportedMessageException;
import com.oracle.truffle.api.library.CachedLibrary;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;
import com.oracle.truffle.llvm.runtime.options.SulongEngineOption;

@ExportLibrary(InteropLibrary.class)
public final class LLVMHybridModeLibrary implements TruffleObject {
    private final TruffleObject sulongLibrary;
    private final TruffleObject libFFILibrary;

    final boolean sulongAvailable;
    final boolean nativeAvailable;

    private SelectionMode mode;

    public LLVMHybridModeLibrary(TruffleObject sulongLibrary, TruffleObject libFFILibrary) {
        this.sulongLibrary = sulongLibrary;
        this.libFFILibrary = libFFILibrary;
        this.sulongAvailable = sulongLibrary != null;
        this.nativeAvailable = libFFILibrary != null;
        this.mode = getGeneralMode();
    }

    enum SelectionMode {
        SULONG_ONLY,
        NATIVE_ONLY,
        PREFER_NATIVE,
        PREFER_SULONG
    }

    private SelectionMode getGeneralMode() {
        if (sulongAvailable) {
            boolean hybridExecution = LLVMLanguage.getContext().getEnv().getOptions().get(SulongEngineOption.HYBRID_EXECUTION);
            if (nativeAvailable && hybridExecution) {
                return SelectionMode.PREFER_NATIVE;
            } else {
                return SelectionMode.SULONG_ONLY;
            }
        } else {
            // if (nativeAvailable) {
            return SelectionMode.NATIVE_ONLY;
            // } else {
            // throw new IllegalStateException("No execution mode could be found!");
            // }
        }
    }

    private TruffleObject getFirstChoice() {
        switch (mode) {
            case NATIVE_ONLY:
            case PREFER_NATIVE:
                return libFFILibrary;
            case PREFER_SULONG:
            case SULONG_ONLY:
                return sulongLibrary;
            default:
                throw new IllegalStateException();
        }
    }

    private Object getSecondChoice() {
        switch (mode) {
            case PREFER_NATIVE:
                return sulongLibrary;
            case PREFER_SULONG:
                return libFFILibrary;
            case NATIVE_ONLY:
            case SULONG_ONLY:
                return InteropLibrary.getUncached();
            default:
                throw new IllegalStateException();
        }
    }

    @ExportMessage
    boolean hasMembers(@CachedLibrary(limit = "1") InteropLibrary interop) {
        return interop.hasMembers(getFirstChoice()) || interop.hasMembers(getSecondChoice());
    }

    @ExportMessage
    Object getMembers(boolean includeInternal, @CachedLibrary(limit = "1") InteropLibrary interop) throws UnsupportedMessageException {
        try {
            return interop.getMembers(getFirstChoice(), includeInternal);
        } catch (UnsupportedMessageException e) {
            return interop.getMembers(getSecondChoice(), includeInternal);
        }
    }

    @ExportMessage
    boolean isMemberReadable(String member, @CachedLibrary(limit = "1") InteropLibrary interop) {
        return interop.isMemberReadable(getFirstChoice(), member) || interop.isMemberReadable(getSecondChoice(), member);
    }

    @ExportMessage
    Object readMember(String member, @CachedLibrary(limit = "1") InteropLibrary interop) throws UnsupportedMessageException, UnknownIdentifierException {
        try {
            return interop.readMember(getFirstChoice(), member);
        } catch (UnsupportedMessageException | UnknownIdentifierException e) {
            return interop.readMember(getSecondChoice(), member);
        }
    }

}
