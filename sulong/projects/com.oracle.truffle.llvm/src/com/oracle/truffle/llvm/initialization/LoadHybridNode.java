/*
 * Copyright (c) 2020, 2021, Oracle and/or its affiliates.
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
package com.oracle.truffle.llvm.initialization;

import java.util.List;

import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.FrameSlotKind;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.api.source.Source;
import com.oracle.truffle.llvm.parser.LLVMParserResult;
import com.oracle.truffle.llvm.runtime.LLVMHybridModeLibrary;
import com.oracle.truffle.llvm.runtime.LLVMLanguage;
import com.oracle.truffle.llvm.runtime.LLVMUnsupportedException;
import com.oracle.truffle.llvm.runtime.memory.LLVMStack;
import com.oracle.truffle.llvm.runtime.types.Type;

public final class LoadHybridNode extends LoadModulesNode {

    private final RootNode loadNativeNode;

    protected LoadHybridNode(String name, LLVMParserResult parserResult, boolean isInternalSulongLibrary,
                    FrameDescriptor rootFrame, boolean lazyParsing, List<LoadDependencyNode> libraryDependencies, Source source, LLVMLanguage language, RootNode loadNativeNode)
                    throws Type.TypeOverflowException {
        super(name, parserResult, isInternalSulongLibrary, rootFrame, lazyParsing, libraryDependencies, source, language);
        this.loadNativeNode = loadNativeNode;
    }

    public static LoadHybridNode create(String soName, LLVMParserResult parserResult,
                    boolean lazyParsing, boolean isInternalSulongLibrary, List<LoadDependencyNode> libraryDependencies, Source source, LLVMLanguage language, RootNode loadNativeNode) {
        try {
            FrameDescriptor.Builder builder = FrameDescriptor.newBuilder();
            int stackId = builder.addSlot(FrameSlotKind.Object, null, null);
            assert stackId == LLVMStack.STACK_ID;
            int uniquesRegionId = builder.addSlot(FrameSlotKind.Object, null, null);
            assert uniquesRegionId == LLVMStack.UNIQUES_REGION_ID;
            int basePointerId = builder.addSlot(FrameSlotKind.Long, null, null);
            assert basePointerId == LLVMStack.BASE_POINTER_ID;
            return new LoadHybridNode(soName, parserResult, isInternalSulongLibrary, builder.build(), lazyParsing, libraryDependencies, source, language, loadNativeNode);
        } catch (Type.TypeOverflowException e) {
            throw new LLVMUnsupportedException(null, LLVMUnsupportedException.UnsupportedReason.UNSUPPORTED_VALUE_RANGE, e);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public Object execute(VirtualFrame frame) {
        TruffleObject sulongLibrary = (TruffleObject) super.execute(frame);
        TruffleObject nativeLibrary = loadNativeNode == null ? null : (TruffleObject) loadNativeNode.execute(frame);
        LLVMHybridModeLibrary hybridModeLibrary = new LLVMHybridModeLibrary(sulongLibrary, nativeLibrary);
        return hybridModeLibrary;
    }

}
