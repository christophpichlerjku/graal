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

import java.util.HashSet;
import java.util.Stack;

public class CollectFeatureOfMethodsPhase extends BasePhase<HighTierContext> {

    @Override
    protected void run(StructuredGraph graph, HighTierContext context) {
        int loopCount = graph.getNodes(LoopBeginNode.TYPE).count();

        long[] result = estimateNodeCount(graph);
        int[] returnDistances = returnDistances(graph);
        InterpreterSupport.singleton().trackMethodFeatures(graph.method(), loopCount, result[0], (int) result[1], returnDistances[MIN_IDX], returnDistances[MAX_IDX]);
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

    private static final int MIN_IDX = 0;
    private static final int MAX_IDX = 1;

    private static int[] returnDistances(StructuredGraph graph) {
        int min = Integer.MAX_VALUE;
        int max = 0;
        for (ReturnNode returnNode : graph.getNodes(ReturnNode.TYPE)) {
            int[] distancesToStart = calcDistancesToStart(returnNode);
            if (distancesToStart[MIN_IDX] < min) {
                min = distancesToStart[MIN_IDX];
            }
            if (distancesToStart[MAX_IDX] > max) {
                max = distancesToStart[MAX_IDX];
            }
        }
        return new int[]{min, max};
    }

    private static int[] calcDistancesToStart(ReturnNode node) {
        Stack<Node> nodeStack = new Stack<>();
        Stack<Integer> distStack = new Stack<>();
        HashSet<Node> visited = new HashSet<>();
        nodeStack.push(node);
        distStack.push(0);
        int minDist = Integer.MAX_VALUE;
        int maxDist = 0;
        while (!nodeStack.empty()) {
            Node cur = nodeStack.pop();
            if (visited.contains(cur)) {
                continue;
            }
            int depth = distStack.pop();
            int size = nodeStack.size();
            for (Node p : cur.cfgPredecessors()) {
                nodeStack.push(p);
                distStack.push(depth + 1);
            }
            if (nodeStack.size() == size) {
                if (depth < minDist) {
                    minDist = depth;
                }
                if (depth > maxDist) {
                    maxDist = depth;
                }
            } else if (nodeStack.size() >= size + 2) {
                //if multiple predecessors, remember current node
                visited.add(cur);
            }

        }
        return new int[]{minDist, maxDist};
    }

}
