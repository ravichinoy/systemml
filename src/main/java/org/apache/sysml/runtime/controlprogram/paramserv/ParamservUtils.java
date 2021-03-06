/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.sysml.runtime.controlprogram.paramserv;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.apache.commons.lang.StringUtils;
import org.apache.spark.Partitioner;
import org.apache.spark.api.java.JavaPairRDD;
import org.apache.sysml.conf.ConfigurationManager;
import org.apache.sysml.hops.Hop;
import org.apache.sysml.hops.MultiThreadedHop;
import org.apache.sysml.hops.OptimizerUtils;
import org.apache.sysml.hops.recompile.Recompiler;
import org.apache.sysml.parser.DMLProgram;
import org.apache.sysml.parser.DMLTranslator;
import org.apache.sysml.parser.Expression;
import org.apache.sysml.parser.Statement;
import org.apache.sysml.parser.StatementBlock;
import org.apache.sysml.runtime.DMLRuntimeException;
import org.apache.sysml.runtime.controlprogram.ForProgramBlock;
import org.apache.sysml.runtime.controlprogram.FunctionProgramBlock;
import org.apache.sysml.runtime.controlprogram.IfProgramBlock;
import org.apache.sysml.runtime.controlprogram.LocalVariableMap;
import org.apache.sysml.runtime.controlprogram.ParForProgramBlock;
import org.apache.sysml.runtime.controlprogram.Program;
import org.apache.sysml.runtime.controlprogram.ProgramBlock;
import org.apache.sysml.runtime.controlprogram.WhileProgramBlock;
import org.apache.sysml.runtime.controlprogram.caching.CacheableData;
import org.apache.sysml.runtime.controlprogram.caching.FrameObject;
import org.apache.sysml.runtime.controlprogram.caching.MatrixObject;
import org.apache.sysml.runtime.controlprogram.context.ExecutionContext;
import org.apache.sysml.runtime.controlprogram.context.ExecutionContextFactory;
import org.apache.sysml.runtime.controlprogram.context.SparkExecutionContext;
import org.apache.sysml.runtime.controlprogram.paramserv.spark.DataPartitionerSparkAggregator;
import org.apache.sysml.runtime.controlprogram.paramserv.spark.DataPartitionerSparkMapper;
import org.apache.sysml.runtime.functionobjects.Plus;
import org.apache.sysml.runtime.instructions.cp.Data;
import org.apache.sysml.runtime.instructions.cp.ListObject;
import org.apache.sysml.runtime.matrix.MatrixCharacteristics;
import org.apache.sysml.runtime.matrix.MetaDataFormat;
import org.apache.sysml.runtime.matrix.data.InputInfo;
import org.apache.sysml.runtime.matrix.data.MatrixBlock;
import org.apache.sysml.runtime.matrix.data.MatrixIndexes;
import org.apache.sysml.runtime.matrix.data.OutputInfo;
import org.apache.sysml.runtime.matrix.operators.BinaryOperator;
import org.apache.sysml.runtime.util.ProgramConverter;

import scala.Tuple2;

public class ParamservUtils {

	public static final String PS_FUNC_PREFIX = "_ps_";

	public static long SEED = -1; // Used for generating permutation

	/**
	 * Deep copy the list object
	 *
	 * @param lo list object
	 * @return a new copied list object
	 */
	public static ListObject copyList(ListObject lo) {
		if (lo.getLength() == 0) {
			return lo;
		}
		List<Data> newData = IntStream.range(0, lo.getLength()).mapToObj(i -> {
			Data oldData = lo.slice(i);
			if (oldData instanceof MatrixObject) {
				MatrixObject mo = (MatrixObject) oldData;
				return sliceMatrix(mo, 1, mo.getNumRows());
			} else if (oldData instanceof ListObject || oldData instanceof FrameObject) {
				throw new DMLRuntimeException("Copy list: does not support list or frame.");
			} else {
				return oldData;
			}
		}).collect(Collectors.toList());
		return new ListObject(newData, lo.getNames());
	}

	/**
	 * Clean up the list object according to its own data status
	 * @param ec execution context
	 * @param lName list var name
	 */
	public static void cleanupListObject(ExecutionContext ec, String lName) {
		ListObject lo = (ListObject) ec.removeVariable(lName);
		cleanupListObject(ec, lo, lo.getStatus());
	}

	/**
	 * Clean up the list object according to the given array of data status (i.e., false => not be removed)
	 * @param ec execution context
	 * @param lName list var name
	 * @param status data status
	 */
	public static void cleanupListObject(ExecutionContext ec, String lName, boolean[] status) {
		ListObject lo = (ListObject) ec.removeVariable(lName);
		cleanupListObject(ec, lo, status);
	}

	public static void cleanupListObject(ExecutionContext ec, ListObject lo) {
		cleanupListObject(ec, lo, lo.getStatus());
	}

	public static void cleanupListObject(ExecutionContext ec, ListObject lo, boolean[] status) {
		for (int i = 0; i < lo.getLength(); i++) {
			if (status != null && !status[i])
				continue; // data ref by other object must not be cleaned up
			ParamservUtils.cleanupData(ec, lo.getData().get(i));
		}
	}

	public static void cleanupData(ExecutionContext ec, Data data) {
		if (!(data instanceof CacheableData))
			return;
		CacheableData<?> cd = (CacheableData<?>) data;
		cd.enableCleanup(true);
		ec.cleanupCacheableData(cd);
	}

	public static MatrixObject newMatrixObject(MatrixBlock mb) {
		MatrixObject result = new MatrixObject(Expression.ValueType.DOUBLE, OptimizerUtils.getUniqueTempFileName(),
			new MetaDataFormat(new MatrixCharacteristics(-1, -1, ConfigurationManager.getBlocksize(),
			ConfigurationManager.getBlocksize()), OutputInfo.BinaryBlockOutputInfo, InputInfo.BinaryBlockInputInfo));
		result.acquireModify(mb);
		result.release();
		return result;
	}

	/**
	 * Slice the matrix
	 *
	 * @param mo input matrix
	 * @param rl low boundary
	 * @param rh high boundary
	 * @return new sliced matrix
	 */
	public static MatrixObject sliceMatrix(MatrixObject mo, long rl, long rh) {
		MatrixBlock mb = mo.acquireRead();
		MatrixObject result = newMatrixObject(sliceMatrixBlock(mb, rl, rh));
		result.enableCleanup(false);
		mo.release();
		return result;
	}

	/**
	 * Slice the matrix block and return a matrix block
	 * (used in spark)
	 *
	 * @param mb input matrix
	 * @param rl low boundary
	 * @param rh high boundary
	 * @return new sliced matrix block
	 */
	public static MatrixBlock sliceMatrixBlock(MatrixBlock mb, long rl, long rh) {
		return mb.slice((int) rl - 1, (int) rh - 1);
	}

	public static MatrixBlock generatePermutation(int numEntries, long seed) {
		// Create a sequence and sample w/o replacement
		// (no need to materialize the sequence because ctable only uses its meta data)
		MatrixBlock seq = new MatrixBlock(numEntries, 1, false);
		MatrixBlock sample = MatrixBlock.sampleOperations(numEntries, numEntries, false, seed);

		// Combine the sequence and sample as a table
		return seq.ctableSeqOperations(sample, 1.0,
			new MatrixBlock(numEntries, numEntries, true));
	}

	public static String[] getCompleteFuncName(String funcName, String prefix) {
		String[] keys = DMLProgram.splitFunctionKey(funcName);
		String ns = (keys.length==2) ? keys[0] : null;
		String name = (keys.length==2) ? keys[1] : keys[0];
		return StringUtils.isEmpty(prefix) ? 
			new String[]{ns, name} : new String[]{ns, name};
	}

	public static ExecutionContext createExecutionContext(ExecutionContext ec, LocalVariableMap varsMap, String updFunc,
		String aggFunc, int k) {
		FunctionProgramBlock updPB = getFunctionBlock(ec, updFunc);
		FunctionProgramBlock aggPB = getFunctionBlock(ec, aggFunc);

		Program prog = ec.getProgram();

		// 1. Recompile the internal program blocks
		recompileProgramBlocks(k, prog.getProgramBlocks());
		// 2. Recompile the imported function blocks
		prog.getFunctionProgramBlocks().forEach((fname, fvalue) -> recompileProgramBlocks(k, fvalue.getChildBlocks()));

		// 3. Copy function
		FunctionProgramBlock newUpdFunc = copyFunction(updFunc, updPB);
		FunctionProgramBlock newAggFunc = copyFunction(aggFunc, aggPB);
		Program newProg = new Program();
		putFunction(newProg, newUpdFunc);
		putFunction(newProg, newAggFunc);
		return ExecutionContextFactory.createContext(new LocalVariableMap(varsMap), newProg);
	}

	public static List<ExecutionContext> copyExecutionContext(ExecutionContext ec, int num) {
		return IntStream.range(0, num).mapToObj(i -> {
			Program newProg = new Program();
			ec.getProgram().getFunctionProgramBlocks().forEach((func, pb) -> putFunction(newProg, copyFunction(func, pb)));
			return ExecutionContextFactory.createContext(new LocalVariableMap(ec.getVariables()), newProg);
		}).collect(Collectors.toList());
	}

	private static FunctionProgramBlock copyFunction(String funcName, FunctionProgramBlock fpb) {
		FunctionProgramBlock copiedFunc = ProgramConverter.createDeepCopyFunctionProgramBlock(fpb, new HashSet<>(), new HashSet<>());
		String[] cfn = getCompleteFuncName(funcName, ParamservUtils.PS_FUNC_PREFIX);
		copiedFunc._namespace = cfn[0];
		copiedFunc._functionName = cfn[1];
		return copiedFunc;
	}

	private static void putFunction(Program prog, FunctionProgramBlock fpb) {
		prog.addFunctionProgramBlock(fpb._namespace, fpb._functionName, fpb);
		prog.addProgramBlock(fpb);
	}

	private static void recompileProgramBlocks(int k, ArrayList<ProgramBlock> pbs) {
		// Reset the visit status from root
		for (ProgramBlock pb : pbs)
			DMLTranslator.resetHopsDAGVisitStatus(pb.getStatementBlock());

		// Should recursively assign the level of parallelism
		// and recompile the program block
		try {
			rAssignParallelism(pbs, k, false);
		} catch (IOException e) {
			throw new DMLRuntimeException(e);
		}
	}

	private static boolean rAssignParallelism(ArrayList<ProgramBlock> pbs, int k, boolean recompiled) throws IOException {
		for (ProgramBlock pb : pbs) {
			if (pb instanceof ParForProgramBlock) {
				ParForProgramBlock pfpb = (ParForProgramBlock) pb;
				pfpb.setDegreeOfParallelism(k);
				recompiled |= rAssignParallelism(pfpb.getChildBlocks(), 1, recompiled);
			} else if (pb instanceof ForProgramBlock) {
				recompiled |= rAssignParallelism(((ForProgramBlock) pb).getChildBlocks(), k, recompiled);
			} else if (pb instanceof WhileProgramBlock) {
				recompiled |= rAssignParallelism(((WhileProgramBlock) pb).getChildBlocks(), k, recompiled);
			} else if (pb instanceof FunctionProgramBlock) {
				recompiled |= rAssignParallelism(((FunctionProgramBlock) pb).getChildBlocks(), k, recompiled);
			} else if (pb instanceof IfProgramBlock) {
				IfProgramBlock ipb = (IfProgramBlock) pb;
				recompiled |= rAssignParallelism(ipb.getChildBlocksIfBody(), k, recompiled);
				if (ipb.getChildBlocksElseBody() != null)
					recompiled |= rAssignParallelism(ipb.getChildBlocksElseBody(), k, recompiled);
			} else {
				StatementBlock sb = pb.getStatementBlock();
				for (Hop hop : sb.getHops())
					recompiled |= rAssignParallelism(hop, k, recompiled);
			}
			// Recompile the program block
			if (recompiled) {
				Recompiler.recompileProgramBlockInstructions(pb);
			}
		}
		return recompiled;
	}

	private static boolean rAssignParallelism(Hop hop, int k, boolean recompiled) {
		if (hop.isVisited()) {
			return recompiled;
		}
		if (hop instanceof MultiThreadedHop) {
			// Reassign the level of parallelism
			MultiThreadedHop mhop = (MultiThreadedHop) hop;
			mhop.setMaxNumThreads(k);
			recompiled = true;
		}
		ArrayList<Hop> inputs = hop.getInput();
		for (Hop h : inputs) {
			recompiled |= rAssignParallelism(h, k, recompiled);
		}
		hop.setVisited();
		return recompiled;
	}

	private static FunctionProgramBlock getFunctionBlock(ExecutionContext ec, String funcName) {
		String[] cfn = getCompleteFuncName(funcName, null);
		String ns = cfn[0];
		String fname = cfn[1];
		return ec.getProgram().getFunctionProgramBlock(ns, fname);
	}

	public static MatrixBlock cbindMatrix(MatrixBlock left, MatrixBlock right) {
		return left.append(right, new MatrixBlock());
	}

	/**
	 * Assemble the matrix of features and labels according to the rowID
	 *
	 * @param numRows row size of the data
	 * @param featuresRDD indexed features matrix block
	 * @param labelsRDD indexed labels matrix block
	 * @return Assembled rdd with rowID as key while matrix of features and labels as value (rowID -> features, labels)
	 */
	public static JavaPairRDD<Long, Tuple2<MatrixBlock, MatrixBlock>> assembleTrainingData(long numRows, JavaPairRDD<MatrixIndexes, MatrixBlock> featuresRDD, JavaPairRDD<MatrixIndexes, MatrixBlock> labelsRDD) {
		JavaPairRDD<Long, MatrixBlock> fRDD = groupMatrix(numRows, featuresRDD);
		JavaPairRDD<Long, MatrixBlock> lRDD = groupMatrix(numRows, labelsRDD);
		//TODO Add an additional physical operator which broadcasts the labels directly (broadcast join with features) if certain memory budgets are satisfied
		return fRDD.join(lRDD);
	}

	private static JavaPairRDD<Long, MatrixBlock> groupMatrix(long numRows, JavaPairRDD<MatrixIndexes, MatrixBlock> rdd) {
		//TODO could use join and aggregation to avoid unnecessary shuffle introduced by reduceByKey
		return rdd.mapToPair(input -> new Tuple2<>(input._1.getRowIndex(), new Tuple2<>(input._1.getColumnIndex(), input._2)))
			.aggregateByKey(new LinkedList<Tuple2<Long, MatrixBlock>>(),
				new Partitioner() {
					private static final long serialVersionUID = -7032660778344579236L;
					@Override
					public int getPartition(Object rblkID) {
						return Math.toIntExact((Long) rblkID);
					}
					@Override
					public int numPartitions() {
						return Math.toIntExact(numRows);
					}
				},
				(list, input) -> {
					list.add(input); 
					return list;
				}, 
				(l1, l2) -> {
					l1.addAll(l2);
					l1.sort((o1, o2) -> o1._1.compareTo(o2._1));
					return l1;
				})
			.mapToPair(input -> {
				LinkedList<Tuple2<Long, MatrixBlock>> list = input._2;
				MatrixBlock result = list.get(0)._2;
				for (int i = 1; i < list.size(); i++) {
					result = ParamservUtils.cbindMatrix(result, list.get(i)._2);
				}
				return new Tuple2<>(input._1, result);
			});
	}

	@SuppressWarnings("unchecked")
	public static JavaPairRDD<Integer, Tuple2<MatrixBlock, MatrixBlock>> doPartitionOnSpark(SparkExecutionContext sec, MatrixObject features, MatrixObject labels, Statement.PSScheme scheme, int workerNum) {
		// Get input RDD
		JavaPairRDD<MatrixIndexes, MatrixBlock> featuresRDD = (JavaPairRDD<MatrixIndexes, MatrixBlock>)
				sec.getRDDHandleForMatrixObject(features, InputInfo.BinaryBlockInputInfo);
		JavaPairRDD<MatrixIndexes, MatrixBlock> labelsRDD = (JavaPairRDD<MatrixIndexes, MatrixBlock>)
				sec.getRDDHandleForMatrixObject(labels, InputInfo.BinaryBlockInputInfo);

		DataPartitionerSparkMapper mapper = new DataPartitionerSparkMapper(scheme, workerNum, sec, (int) features.getNumRows());
		return ParamservUtils.assembleTrainingData(features.getNumRows(), featuresRDD, labelsRDD) // Combine features and labels into a pair (rowBlockID => (features, labels))
			.flatMapToPair(mapper) // Do the data partitioning on spark (workerID => (rowBlockID, (single row features, single row labels))
			// Aggregate the partitioned matrix according to rowID for each worker
			// i.e. (workerID => ordered list[(rowBlockID, (single row features, single row labels)]
			.aggregateByKey(new LinkedList<Tuple2<Long, Tuple2<MatrixBlock, MatrixBlock>>>(),
				new Partitioner() {
					private static final long serialVersionUID = -7937781374718031224L;
					@Override
					public int getPartition(Object workerID) {
						return (int) workerID;
					}
					@Override
					public int numPartitions() {
						return workerNum;
					}
				}, 
				(list, input) -> {
					list.add(input);
					return list;
				},
				(l1, l2) -> {
					l1.addAll(l2);
					l1.sort((o1, o2) -> o1._1.compareTo(o2._1));
					return l1;
				})
			.mapToPair(new DataPartitionerSparkAggregator(
				features.getNumColumns(), labels.getNumColumns()));
	}

	public static ListObject accrueGradients(ListObject accGradients, ListObject gradients) {
		return accrueGradients(accGradients, gradients, false);
	}
	
	public static ListObject accrueGradients(ListObject accGradients, ListObject gradients, boolean par) {
		if (accGradients == null)
			return ParamservUtils.copyList(gradients);
		IntStream range = IntStream.range(0, accGradients.getLength());
		(par ? range.parallel() : range).forEach(i -> {
			MatrixBlock mb1 = ((MatrixObject) accGradients.getData().get(i)).acquireRead();
			MatrixBlock mb2 = ((MatrixObject) gradients.getData().get(i)).acquireRead();
			mb1.binaryOperationsInPlace(new BinaryOperator(Plus.getPlusFnObject()), mb2);
			((MatrixObject) accGradients.getData().get(i)).release();
			((MatrixObject) gradients.getData().get(i)).release();
		});
		return accGradients;
	}
}
