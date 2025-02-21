/*
 * Copyright (c) 2024, 2024, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package com.oracle.svm.core.graal.code;

import com.oracle.svm.core.Uninterruptible;
import org.graalvm.word.Pointer;

/* Helper class to set ABI specific data */
public interface InterpreterAccessStubData {
    // TODO
    String REASON = "reason";

    void setSp(Pointer data, int stackSize, Pointer stackBuffer);

    @Uninterruptible(reason = REASON)
    long getGpArgumentAt(CompiledArgumentType cArgType, Pointer data, int pos);

    @Uninterruptible(reason = REASON)
    long setGpArgumentAt(CompiledArgumentType cArgType, Pointer data, int pos, long val);

    @Uninterruptible(reason = REASON)
    long getFpArgumentAt(CompiledArgumentType cArgType, Pointer data, int pos);

    @Uninterruptible(reason = REASON)
    void setFpArgumentAt(CompiledArgumentType cArgType, Pointer data, int pos, long val);

    @Uninterruptible(reason = REASON)
    long getGpReturn(Pointer data);

    @Uninterruptible(reason = REASON)
    void setGpReturn(Pointer data, long gpReturn);

    @Uninterruptible(reason = REASON)
    long getFpReturn(Pointer data);

    @Uninterruptible(reason = REASON)
    void setFpReturn(Pointer data, long fpReturn);

    int allocateStubDataSize();
}
