/*
 * Copyright (c) 2023, 2023, Oracle and/or its affiliates. All rights reserved.
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

package com.oracle.svm.interpreter;

import static com.oracle.svm.interpreter.EspressoFrame.setLocalDouble;
import static com.oracle.svm.interpreter.EspressoFrame.setLocalFloat;
import static com.oracle.svm.interpreter.EspressoFrame.setLocalInt;
import static com.oracle.svm.interpreter.EspressoFrame.setLocalLong;
import static com.oracle.svm.interpreter.EspressoFrame.setLocalObject;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import com.oracle.svm.core.Uninterruptible;
import com.oracle.svm.core.graal.code.CompiledArgumentType;
import com.oracle.svm.core.graal.code.SubstrateCallingConventionKind;
import com.oracle.svm.core.handles.ThreadLocalHandles;
import com.oracle.svm.core.log.Log;
import com.oracle.svm.core.threadlocal.FastThreadLocalFactory;
import com.oracle.svm.core.threadlocal.FastThreadLocalObject;
import com.oracle.svm.interpreter.metadata.CompiledSignature;
import com.oracle.svm.interpreter.metadata.InterpreterUniverse;
import com.oracle.svm.interpreter.metadata.InterpreterUnresolvedSignature;
import jdk.vm.ci.code.CallingConvention;
import jdk.vm.ci.code.StackSlot;
import jdk.vm.ci.meta.AllocatableValue;
import jdk.vm.ci.meta.JavaType;
import org.graalvm.nativeimage.ImageSingletons;
import org.graalvm.nativeimage.ObjectHandle;
import org.graalvm.nativeimage.StackValue;
import org.graalvm.nativeimage.c.function.CFunctionPointer;
import org.graalvm.nativeimage.impl.UnmanagedMemorySupport;
import org.graalvm.word.Pointer;

import com.oracle.objectfile.BasicProgbitsSectionImpl;
import com.oracle.objectfile.ObjectFile;
import com.oracle.objectfile.SectionName;
import com.oracle.svm.core.NeverInline;
import com.oracle.svm.core.SubstrateOptions;
import com.oracle.svm.core.SubstrateTargetDescription;
import com.oracle.svm.core.config.ConfigurationValues;
import com.oracle.svm.core.deopt.Deoptimizer;
import com.oracle.svm.core.graal.code.InterpreterAccessStubData;
import com.oracle.svm.core.graal.meta.KnownOffsets;
import com.oracle.svm.core.jdk.InternalVMMethod;
import com.oracle.svm.core.util.VMError;
import com.oracle.svm.hosted.image.AbstractImage;
import com.oracle.svm.hosted.image.NativeImage;
import com.oracle.svm.hosted.image.RelocatableBuffer;
import com.oracle.svm.hosted.meta.HostedMethod;
import com.oracle.svm.interpreter.metadata.InterpreterResolvedJavaMethod;

import jdk.graal.compiler.core.common.LIRKind;
import jdk.graal.compiler.core.common.NumUtil;
import jdk.graal.compiler.word.Word;
import jdk.vm.ci.code.RegisterConfig;
import jdk.vm.ci.code.ValueKindFactory;
import jdk.vm.ci.meta.JavaKind;
import jdk.vm.ci.meta.ResolvedJavaMethod;
import jdk.vm.ci.meta.ResolvedJavaType;
import org.graalvm.word.PointerBase;
import org.graalvm.word.WordFactory;

@InternalVMMethod
public abstract class InterpreterStubSection {
    public static final SectionName SVM_INTERP = new SectionName.ProgbitsSectionName("svm_interp");

    protected RegisterConfig registerConfig;
    protected SubstrateTargetDescription target;
    protected ValueKindFactory<LIRKind> valueKindFactory;

    private ObjectFile.ProgbitsSectionImpl stubsBufferImpl;

    private final Map<InterpreterResolvedJavaMethod, Integer> enterTrampolineOffsets = new HashMap<>();

    public static CompiledSignature fromSignature(InterpreterResolvedJavaMethod interpreterMethod) {
        InterpreterUnresolvedSignature signature = interpreterMethod.getSignature();
        boolean hasReceiver = interpreterMethod.hasReceiver();
        InterpreterStubSection stubSection = ImageSingletons.lookup(InterpreterStubSection.class);
        int count = signature.getParameterCount(false);
        CompiledArgumentType[] argumentTypes = new CompiledArgumentType[count + (hasReceiver ? 1 : 0)];

        var kind = SubstrateCallingConventionKind.Java.toType(true);
        ResolvedJavaType accessingClass = interpreterMethod.getDeclaringClass();
        JavaType thisType = interpreterMethod.hasReceiver() ? accessingClass : null;
        JavaType returnType = signature.getReturnType(accessingClass);
        CallingConvention callingConvention = stubSection.registerConfig.getCallingConvention(kind, returnType, signature.toParameterTypes(thisType), stubSection.valueKindFactory);

        if (hasReceiver) {
            argumentTypes[0] = new CompiledArgumentType(JavaKind.Object, 0, true);
        }
        for (int i = 0; i < count; i++) {
            int index = i + (hasReceiver ? 1 : 0);
            AllocatableValue allocatableValue = callingConvention.getArgument(index);
            JavaKind argKind = signature.getParameterKind(i);
            int offset = 0;
            if (allocatableValue instanceof StackSlot stackSlot) {
                offset = stackSlot.getOffset(0);
            }
            CompiledArgumentType compiledArgumentType = new CompiledArgumentType(argKind, offset, !(allocatableValue instanceof StackSlot));
            argumentTypes[index] = compiledArgumentType;
        }
        return new CompiledSignature(signature.getReturnKind(), argumentTypes, callingConvention.getStackSize());
    }

    public void createInterpreterEnterStubSection(AbstractImage image, Collection<InterpreterResolvedJavaMethod> methods) {
        ObjectFile objectFile = image.getObjectFile();
        byte[] stubsBlob = generateEnterStubs(methods);

        RelocatableBuffer stubsBuffer = new RelocatableBuffer(stubsBlob.length, objectFile.getByteOrder());
        stubsBufferImpl = new BasicProgbitsSectionImpl(stubsBuffer.getBackingArray());
        ObjectFile.Section stubsSection = objectFile.newProgbitsSection(SVM_INTERP.getFormatDependentName(objectFile.getFormat()), objectFile.getPageSize(), false, true, stubsBufferImpl);

        stubsBuffer.getByteBuffer().put(stubsBlob, 0, stubsBlob.length);

        boolean internalSymbolsAreGlobal = SubstrateOptions.InternalSymbolsAreGlobal.getValue();
        objectFile.createDefinedSymbol("interp_enter_trampoline", stubsSection, 0, 0, true, internalSymbolsAreGlobal);

        for (InterpreterResolvedJavaMethod method : enterTrampolineOffsets.keySet()) {
            int offset = enterTrampolineOffsets.get(method);
            objectFile.createDefinedSymbol(nameForInterpMethod(method), stubsSection, offset, ConfigurationValues.getTarget().wordSize, true, internalSymbolsAreGlobal);
        }
    }

    public static String nameForInterpMethod(InterpreterResolvedJavaMethod method) {
        return "interp_enter_" + NativeImage.localSymbolNameForMethod(method);
    }

    public void createInterpreterVtableEnterStubSection(AbstractImage image, int maxVtableIndex) {
        ObjectFile objectFile = image.getObjectFile();
        byte[] stubsBlob = generateVtableEnterStubs(maxVtableIndex);

        RelocatableBuffer stubsBuffer = new RelocatableBuffer(stubsBlob.length, objectFile.getByteOrder());
        stubsBufferImpl = new BasicProgbitsSectionImpl(stubsBuffer.getBackingArray());

        // TODO: if the section should be re-used, we need to respect the offsets into this section.
        // or just a new dedicated section?
        ObjectFile.Section stubsSection = objectFile.newProgbitsSection(SVM_INTERP.getFormatDependentName(objectFile.getFormat()), objectFile.getPageSize(), false, true, stubsBufferImpl);

        stubsBuffer.getByteBuffer().put(stubsBlob, 0, stubsBlob.length);

        boolean internalSymbolsAreGlobal = SubstrateOptions.InternalSymbolsAreGlobal.getValue();
        objectFile.createDefinedSymbol("crema_enter_trampoline", stubsSection, 0, 0, true, internalSymbolsAreGlobal);

        int vtableEntrySize = KnownOffsets.singleton().getVTableEntrySize();
        for (int index = 0; index < maxVtableIndex; index++) {
            int offset = index * vtableEntrySize;
            objectFile.createDefinedSymbol(nameForVtableOffset(offset), stubsSection, offset, ConfigurationValues.getTarget().wordSize, true, internalSymbolsAreGlobal);
        }
    }

    public static String nameForVtableOffset(int offset) {
        return "crema_enter_" + String.format("%04x", offset);
    }

    protected void recordEnterTrampoline(InterpreterResolvedJavaMethod m, int position) {
        enterTrampolineOffsets.put(m, position);
    }

    public abstract int getVTableStubSize();

    protected abstract byte[] generateEnterStubs(Collection<InterpreterResolvedJavaMethod> methods);

    protected abstract byte[] generateVtableEnterStubs(int maxVtableIndex);

    public void markEnterStubPatch(HostedMethod enterStub) {
        markEnterStubPatch(stubsBufferImpl, enterStub);
    }

    protected abstract void markEnterStubPatch(ObjectFile.ProgbitsSectionImpl pltBuffer, ResolvedJavaMethod enterStub);


    @SuppressWarnings("rawtypes") //
    public static final FastThreadLocalObject<ThreadLocalHandles> TL_HANDLES = FastThreadLocalFactory.createObject(ThreadLocalHandles.class, "Interpreter handles for enter stub");

    interface InterpreterHandle extends ObjectHandle, PointerBase {
    }

    /* must match the maximum references passed via registers (AArch64 is the highest) */
    public final static int MAX_HANDLES = 8;

    @Deoptimizer.DeoptStub(stubType = Deoptimizer.StubType.InterpreterEnterStub)
    @NeverInline("needs ABI boundary")
    @Uninterruptible(reason = "stack frame contains object references that are not known to the GC")
    public static Pointer enterInterpreterStub(int interpreterMethodESTOffset, Pointer enterData) {
        InterpreterAccessStubData accessHelper = ImageSingletons.lookup(InterpreterAccessStubData.class);
        DebuggerSupport interpreterSupport = ImageSingletons.lookup(DebuggerSupport.class);
        VMError.guarantee(interpreterSupport != null);
        //Log.log().string("[eis] #0").newline().flush();

        InterpreterUniverse interpreterUniverse = interpreterSupport.getUniverseOrNull();
        VMError.guarantee(interpreterUniverse != null);
        //Log.log().string("[eis] #1").newline().flush();

        InterpreterResolvedJavaMethod interpreterMethod = (InterpreterResolvedJavaMethod) interpreterUniverse.getMethodForESTOffset(interpreterMethodESTOffset);
        VMError.guarantee(interpreterMethod != null);
        //Log.log().string("[eis] #2").newline().flush();

        CompiledSignature compiledSignature = interpreterMethod.getCompiledSignature();
        VMError.guarantee(compiledSignature != null);
        //Log.log().string("[eis] #3").newline().flush();

        ThreadLocalHandles<InterpreterHandle> handles = TL_HANDLES.get();
        VMError.guarantee(handles.getHandleCount() == 0);
        handles.pushFrameFast(MAX_HANDLES - 1);

        int gpIdx = 0;
        int fpIdx = 0;
        int handleCount = 0;
        for (int i = 0; i < compiledSignature.getCount(); i++)  {
            CompiledArgumentType cArgType = compiledSignature.getCompiledArgumentTypes()[i];
            if (cArgType.getKind() == JavaKind.Object && cArgType.isRegister()) {
                // stack arguments are in the original stack layout, and thus the GC is aware of them.
                // handles for references stored in registers are put in place of the reference instead, so no additional memory needs to be allocated.

                long rawAddr = accessHelper.getGpArgumentAt(cArgType, enterData, gpIdx);
                Object obj = ((Pointer) Word.pointer(rawAddr)).toObject();
                if (obj == null) {
                    accessHelper.setGpArgumentAt(cArgType, enterData, gpIdx, 0L);
                } else {
                    InterpreterHandle interpreterHandle = handles.tryCreateNonNull(obj);
                    accessHelper.setGpArgumentAt(cArgType, enterData, gpIdx, interpreterHandle.rawValue());
                    handleCount++;
                }
            }

            switch (cArgType.getKind()) {
                case Float:
                case Double:
                    fpIdx++;
                    break;
                default:
                    gpIdx++;
                    break;
            }
        }
        // Log.log().string("[eis] #5").newline().flush();

        Object retVal = enterInterpreterStub0(interpreterMethod, compiledSignature, enterData, handleCount);
        // Log.log().string("[eis] #6").newline().flush();

        switch (compiledSignature.getReturnKind()) {
            case Boolean:
                assert retVal instanceof Boolean;
                accessHelper.setGpReturn(enterData, ((Boolean) retVal) ? 1 : 0);
                break;
            case Byte:
                assert retVal instanceof Byte;
                accessHelper.setGpReturn(enterData, (long) ((Byte) retVal));
                break;
            case Short:
                assert retVal instanceof Short;
                accessHelper.setGpReturn(enterData, ((Short) retVal));
                break;
            case Char:
                assert retVal instanceof Character;
                accessHelper.setGpReturn(enterData, (long) ((Character) retVal));
                break;
            case Int:
                assert retVal instanceof Integer;
                accessHelper.setGpReturn(enterData, (long) ((Integer) retVal));
                break;
            case Long:
                assert retVal instanceof Long;
                accessHelper.setGpReturn(enterData, (Long) retVal);
                break;
            case Float:
                assert retVal instanceof Float;
                accessHelper.setFpReturn(enterData, Float.floatToRawIntBits((float) retVal));
                break;
            case Double:
                assert retVal instanceof Double;
                accessHelper.setFpReturn(enterData, Double.doubleToRawLongBits((double) retVal));
                break;
            case Object:
                accessHelper.setGpReturn(enterData, Word.objectToTrackedPointer(retVal).rawValue());
                break;
            case Void:
                break;
            default:
                throw VMError.shouldNotReachHereAtRuntime();
        }
        return enterData;
    }

    @Uninterruptible(reason = "allow allocation now ", calleeMustBe = false)
    private static Object enterInterpreterStub0(InterpreterResolvedJavaMethod interpreterMethod, CompiledSignature compiledSignature, Pointer enterData, int handleCount) {
        return enterInterpreterStubCore(interpreterMethod, compiledSignature, enterData, handleCount);
    }

    @NeverInline("just debugging")
    private static Object enterInterpreterStubCore(InterpreterResolvedJavaMethod interpreterMethod, CompiledSignature compiledSignature, Pointer enterData, int handleCount) {
        InterpreterAccessStubData accessHelper = ImageSingletons.lookup(InterpreterAccessStubData.class);
        InterpreterFrame frame = EspressoFrame.allocate(interpreterMethod.getMaxLocals(), interpreterMethod.getMaxStackSize(), new Object[0]);
        CompiledArgumentType[] cArgsType = compiledSignature.getCompiledArgumentTypes();
        int wordSize = ConfigurationValues.getTarget().wordSize;
        int count = cArgsType.length;

        int interpSlot = 0;
        int gpIdx = 0;
        int fpIdx = 0;

        for (int i = 0; i < count; i++) {
            long arg = 0;
            CompiledArgumentType cArgType = cArgsType[gpIdx + fpIdx];
            JavaKind argKind = cArgType.getKind();
            switch (argKind) {
                case Float:
                case Double:
                    arg = accessHelper.getFpArgumentAt(cArgType, enterData, fpIdx);
                    fpIdx++;
                    break;
                case Object:
                    Object val;
                    arg = accessHelper.getGpArgumentAt(cArgType, enterData, gpIdx);
                    if (cArgType.isRegister()) {
                        /* reference in `enterData` has been replaced with a handle */
                        InterpreterHandle handle = Word.pointer(arg);

                        if (handle.rawValue() == 0L) {
                            val = null;
                        } else {
                            val = TL_HANDLES.get().getObject(handle);
                        }
                    } else {
                        // TODO: is there a race? I think not
                        val = ((Pointer) Word.pointer(arg)).toObject();
                    }
                    setLocalObject(frame, interpSlot, val);

                    gpIdx++;
                    break;
                default:
                    arg = accessHelper.getGpArgumentAt(cArgType, enterData, gpIdx);
                    gpIdx++;
                    break;
            }

            switch (argKind) {
                // @formatter:off
                case Boolean: setLocalInt(frame, interpSlot,  (arg & 0xff) != 0 ? 1 : 0); break;
                case Byte:    setLocalInt(frame, interpSlot, (byte) arg); break;
                case Short:   setLocalInt(frame, interpSlot, (short) arg); break;
                case Char:    setLocalInt(frame, interpSlot, (char) arg); break;
                case Int:     setLocalInt(frame, interpSlot, (int) arg); break;
                case Long:    setLocalLong(frame, interpSlot, arg); interpSlot++; break;
                case Float:   setLocalFloat(frame, interpSlot, Float.intBitsToFloat((int) arg)); break;
                case Double:  setLocalDouble(frame, interpSlot, Double.longBitsToDouble(arg)); interpSlot++; break;
                case Object: /* already handled */ break;
                // @formatter:on
                default:
                    throw VMError.shouldNotReachHereAtRuntime();
            }
            interpSlot++;
        }

        /* clear handles */
        VMError.guarantee(TL_HANDLES.get().getHandleCount() == handleCount);
        TL_HANDLES.get().popFrame();

        return invokeInterpreterHelper(interpreterMethod, frame);
    }

    @Uninterruptible(reason = "No references on stack frame anymore", calleeMustBe = false)
    private static Object invokeInterpreterHelper(InterpreterResolvedJavaMethod interpreterMethod, InterpreterFrame frame) {
        return Interpreter.execute(interpreterMethod, frame);
    }

    /*
     * reserve four slots for: 1. base address of outgoing stack args, 2. variable stack size, 3.
     * gcReferenceMap, 4. stack padding to match alignment
     */
    @Deoptimizer.DeoptStub(stubType = Deoptimizer.StubType.InterpreterLeaveStub)
    @NeverInline("needs ABI boundary")
    @Uninterruptible(reason = "stay uninterruptible")
    @SuppressWarnings("unused")
    public static Pointer leaveInterpreterStub(CFunctionPointer entryPoint, Pointer leaveData, long stackSize, long gcReferenceMap) {
        return (Pointer) entryPoint;
    }

    public static Object leaveInterpreter(CFunctionPointer compiledEntryPoint, InterpreterResolvedJavaMethod seedMethod, ResolvedJavaType accessingClass, Object[] args) {
        CompiledSignature compiledSignature = seedMethod.getCompiledSignature();
        VMError.guarantee(compiledSignature != null);
        InterpreterStubSection stubSection = ImageSingletons.lookup(InterpreterStubSection.class);

        InterpreterAccessStubData accessHelper = ImageSingletons.lookup(InterpreterAccessStubData.class);
        Pointer leaveData = StackValue.get(1, accessHelper.allocateStubDataSize());

        /* GR-54726: Reference map is currently limited to 64 arguments */
        long gcReferenceMap = 0;
        int gpIdx = 0;
        int fpIdx = 0;

        int stackSize = NumUtil.roundUp(compiledSignature.getStackSize(), stubSection.target.stackAlignment);

        Pointer stackBuffer = Word.nullPointer();
        if (stackSize > 0) {
            stackBuffer = ImageSingletons.lookup(UnmanagedMemorySupport.class).malloc(Word.unsigned(stackSize));
            accessHelper.setSp(leaveData, stackSize, stackBuffer);
        }

        try {
            // GR-55022: Stack overflow check should be done here
            return leaveInterpreter0(compiledEntryPoint, args, compiledSignature, gpIdx, fpIdx, accessHelper, leaveData, gcReferenceMap, stackSize, stackBuffer);
        } catch (Throwable e) {
            // native code threw exception, wrap it
            throw SemanticJavaException.raise(e);
        } finally {
            if (stackSize > 0) {
                VMError.guarantee(stackBuffer.isNonNull());
                ImageSingletons.lookup(UnmanagedMemorySupport.class).free(stackBuffer);
            }
        }
    }

    @Uninterruptible(reason = "References are put on the stack which the GC is unaware of.")
    private static Object leaveInterpreter0(CFunctionPointer compiledEntryPoint, Object[] args, CompiledSignature compiledSignature, int gpIdx, int fpIdx, InterpreterAccessStubData accessHelper, Pointer leaveData, long gcReferenceMap, int stackSize, Pointer stackBuffer) {
        int argCount = compiledSignature.getCount();
        for (int i = 0; i < argCount; i++) {
            Object arg = args[i];
            CompiledArgumentType cArgType = compiledSignature.getCompiledArgumentTypes()[gpIdx + fpIdx];
            // Log.log().string("[li] arg=").signed(i).string(" with kind=").string(cArgType.getKind().toString()).string(", isRegister=").bool(cArgType.isRegister()).string(", offset=").signed(cArgType.getStackOffset()).newline().flush();
            switch (cArgType.getKind()) {
                case Boolean:
                    accessHelper.setGpArgumentAt(cArgType, leaveData, gpIdx, (boolean) arg ? 1 : 0);
                    gpIdx++;
                    break;
                case Byte:
                    accessHelper.setGpArgumentAt(cArgType, leaveData, gpIdx, (byte) arg);
                    gpIdx++;
                    break;
                case Short:
                    accessHelper.setGpArgumentAt(cArgType, leaveData, gpIdx, (short) arg);
                    gpIdx++;
                    break;
                case Char:
                    accessHelper.setGpArgumentAt(cArgType, leaveData, gpIdx, (char) arg);
                    gpIdx++;
                    break;
                case Int:
                    accessHelper.setGpArgumentAt(cArgType, leaveData, gpIdx, (int) arg);
                    gpIdx++;
                    break;
                case Long:
                    accessHelper.setGpArgumentAt(cArgType, leaveData, gpIdx, (long) arg);
                    gpIdx++;
                    break;
                case Object:
                    gcReferenceMap |= accessHelper.setGpArgumentAt(cArgType, leaveData, gpIdx, Word.objectToTrackedPointer(arg).rawValue());
                    gpIdx++;
                    break;

                case Float:
                    accessHelper.setFpArgumentAt(cArgType, leaveData, fpIdx, Float.floatToRawIntBits((float) arg));
                    fpIdx++;
                    break;
                case Double:
                    accessHelper.setFpArgumentAt(cArgType, leaveData, fpIdx, Double.doubleToRawLongBits((double) arg));
                    fpIdx++;
                    break;

                default:
                    throw VMError.shouldNotReachHereAtRuntime();
            }
        }

        VMError.guarantee(compiledEntryPoint.isNonNull());

        leaveInterpreterStub(compiledEntryPoint, leaveData, stackSize, gcReferenceMap);

        // @formatter:off
        return switch (compiledSignature.getReturnKind()) {
            case Boolean -> (accessHelper.getGpReturn(leaveData) & 0xff) != 0;
            case Byte    -> (byte) accessHelper.getGpReturn(leaveData);
            case Short   -> (short) accessHelper.getGpReturn(leaveData);
            case Char    -> (char) accessHelper.getGpReturn(leaveData);
            case Int     -> (int) accessHelper.getGpReturn(leaveData);
            case Long    -> accessHelper.getGpReturn(leaveData);
            case Float   -> Float.intBitsToFloat((int) accessHelper.getFpReturn(leaveData));
            case Double  -> Double.longBitsToDouble(accessHelper.getFpReturn(leaveData));
            case Object  -> ((Pointer) Word.pointer(accessHelper.getGpReturn(leaveData))).toObject();
            case Void    -> null;
            default      -> throw VMError.shouldNotReachHereAtRuntime();
        };
        // @formatter:on
    }
}
