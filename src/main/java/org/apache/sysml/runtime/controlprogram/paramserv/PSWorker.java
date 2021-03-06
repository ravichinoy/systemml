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

import static org.apache.sysml.runtime.controlprogram.paramserv.ParamservUtils.PS_FUNC_PREFIX;

import java.util.ArrayList;
import java.util.stream.Collectors;

import org.apache.sysml.parser.DataIdentifier;
import org.apache.sysml.parser.Expression;
import org.apache.sysml.parser.Statement;
import org.apache.sysml.runtime.DMLRuntimeException;
import org.apache.sysml.runtime.controlprogram.FunctionProgramBlock;
import org.apache.sysml.runtime.controlprogram.caching.MatrixObject;
import org.apache.sysml.runtime.controlprogram.context.ExecutionContext;
import org.apache.sysml.runtime.instructions.cp.CPOperand;
import org.apache.sysml.runtime.instructions.cp.FunctionCallCPInstruction;

public abstract class PSWorker {
	protected int _workerID;
	protected int _epochs;
	protected long _batchSize;
	protected ExecutionContext _ec;
	protected ParamServer _ps;
	protected DataIdentifier _output;
	protected FunctionCallCPInstruction _inst;
	protected MatrixObject _features;
	protected MatrixObject _labels;

	protected MatrixObject _valFeatures;
	protected MatrixObject _valLabels;
	protected String _updFunc;
	protected Statement.PSFrequency _freq;

	protected PSWorker() {

	}
	
	protected PSWorker(int workerID, String updFunc, Statement.PSFrequency freq, int epochs, long batchSize,
		MatrixObject valFeatures, MatrixObject valLabels, ExecutionContext ec, ParamServer ps) {
		_workerID = workerID;
		_updFunc = updFunc;
		_freq = freq;
		_epochs = epochs;
		_batchSize = batchSize;
		_valFeatures = valFeatures;
		_valLabels = valLabels;
		_ec = ec;
		_ps = ps;

		// Get the update function
		String[] cfn = ParamservUtils.getCompleteFuncName(updFunc, PS_FUNC_PREFIX);
		String ns = cfn[0];
		String fname = cfn[1];
		FunctionProgramBlock func = ec.getProgram().getFunctionProgramBlock(ns, fname);
		ArrayList<DataIdentifier> inputs = func.getInputParams();
		ArrayList<DataIdentifier> outputs = func.getOutputParams();
		CPOperand[] boundInputs = inputs.stream()
			.map(input -> new CPOperand(input.getName(), input.getValueType(), input.getDataType()))
			.toArray(CPOperand[]::new);
		ArrayList<String> inputNames = inputs.stream().map(DataIdentifier::getName)
			.collect(Collectors.toCollection(ArrayList::new));
		ArrayList<String> outputNames = outputs.stream().map(DataIdentifier::getName)
			.collect(Collectors.toCollection(ArrayList::new));
		_inst = new FunctionCallCPInstruction(ns, fname, boundInputs, inputNames, outputNames, "update function");

		// Check the inputs of the update function
		checkInput(false, inputs, Expression.DataType.MATRIX, Statement.PS_FEATURES);
		checkInput(false, inputs, Expression.DataType.MATRIX, Statement.PS_LABELS);
		checkInput(false, inputs, Expression.DataType.LIST, Statement.PS_MODEL);
		checkInput(true, inputs, Expression.DataType.LIST, Statement.PS_HYPER_PARAMS);

		// Check the output of the update function
		if (outputs.size() != 1) {
			throw new DMLRuntimeException(String.format("The output of the '%s' function "
				+ "should provide one list containing the gradients.", updFunc));
		}
		if (outputs.get(0).getDataType() != Expression.DataType.LIST) {
			throw new DMLRuntimeException(String.format("The output of the '%s' function should be type of list.", updFunc));
		}
		_output = outputs.get(0);
	}

	private void checkInput(boolean optional, ArrayList<DataIdentifier> inputs, Expression.DataType dt, String pname) {
		if (optional && inputs.stream().noneMatch(input -> pname.equals(input.getName()))) {
			// We do not need to check if the input is optional and is not provided
			return;
		}
		if (inputs.stream().filter(input -> input.getDataType() == dt && pname.equals(input.getName())).count() != 1) {
			throw new DMLRuntimeException(String.format("The '%s' function should provide "
				+ "an input of '%s' type named '%s'.", _updFunc, dt, pname));
		}
	}

	public void setFeatures(MatrixObject features) {
		_features = features;
	}

	public void setLabels(MatrixObject labels) {
		_labels = labels;
	}

	public MatrixObject getFeatures() {
		return _features;
	}

	public MatrixObject getLabels() {
		return _labels;
	}
}
