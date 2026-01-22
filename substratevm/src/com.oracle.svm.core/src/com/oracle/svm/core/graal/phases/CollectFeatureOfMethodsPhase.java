/*
 * Copyright (c) 2015, 2020, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.svm.core.graal.phases;

import com.oracle.svm.core.interpreter.InterpreterSupport;

import jdk.graal.compiler.graph.Node;
import jdk.graal.compiler.nodes.LoopBeginNode;
import jdk.graal.compiler.nodes.ReturnNode;
import jdk.graal.compiler.nodes.StructuredGraph;
import jdk.graal.compiler.nodes.cfg.ControlFlowGraph;
import jdk.graal.compiler.nodes.cfg.HIRBlock;
import jdk.graal.compiler.phases.BasePhase;
import jdk.graal.compiler.phases.tiers.HighTierContext;

public class CollectFeatureOfMethodsPhase extends BasePhase<HighTierContext> {

    public static final int EARLY_RETURN_DISTANCE_TO_START = 10;

    @Override
    protected void run(StructuredGraph graph, HighTierContext context) {
        int loopCount = graph.getNodes(LoopBeginNode.TYPE).count();

        long[] result = estimateNodeCount(graph);
        int shortestReturn = shortestReturn(graph);
        InterpreterSupport.singleton().trackMethodFeatures(graph.method(), loopCount, result[0], (int) result[1], shortestReturn);
    }

    private static long[] estimateNodeCount(StructuredGraph graph) {
        long count = 0;
        long maxLoopDepth = 0;
        final int LOOP_FQ = 10;// currently a magic number
        ControlFlowGraph cfg = ControlFlowGraph.newBuilder(graph).computeLoops(true).connectBlocks(true).build();
        for (HIRBlock block : cfg.getBlocks()) {
            int loopDepth = block.getLoopDepth();
            if (loopDepth > maxLoopDepth) {
                maxLoopDepth = loopDepth;
            }
            for (@SuppressWarnings("unused")
            Node node : block.getNodes()) {
                count += (long) Math.pow(LOOP_FQ, loopDepth);
            }
        }

// for (Node node : graph.getNodes()) {
// TODO PROBLEM: nodeToBlock map entry is null
// int loopDepth = cfg.getNodeToBlock().get(node).getLoopDepth();
// count += Math.pow(LOOP_FQ, loopDepth);
// }
        return new long[]{count, maxLoopDepth};
    }

    private static int shortestReturn(StructuredGraph graph) {
        int min = Integer.MAX_VALUE;
        for (ReturnNode returnNode : graph.getNodes(ReturnNode.TYPE)) {
            int distanceToStart = calcDistanceToStart(returnNode);
            if (distanceToStart < min) {
                min = distanceToStart;
            }
        }
        return min;
    }

    private static int calcDistanceToStart(ReturnNode node) {
        int count = 0;
        Node cur = node;
        while (cur != null) {
            cur = cur.predecessor();
            count++;
        }
        return count;
    }

}
