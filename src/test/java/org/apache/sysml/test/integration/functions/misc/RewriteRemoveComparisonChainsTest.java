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

package org.apache.sysml.test.integration.functions.misc;

import org.junit.Assert;
import org.junit.Test;

import org.apache.sysml.hops.OptimizerUtils;
import org.apache.sysml.test.integration.AutomatedTestBase;
import org.apache.sysml.test.integration.TestConfiguration;
import org.apache.sysml.test.utils.TestUtils;

public class RewriteRemoveComparisonChainsTest extends AutomatedTestBase 
{
	private final static String TEST_NAME1 = "RewriteComparisons"; 
	//a) >, == 0; b) <=, == 1; c) ==, == 0; d) !=, == 1
	
	private final static String TEST_DIR = "functions/misc/";
	private final static String TEST_CLASS_DIR = TEST_DIR + RewriteRemoveComparisonChainsTest.class.getSimpleName() + "/";
	
	@Override
	public void setUp() {
		TestUtils.clearAssertionInformation();
		addTestConfiguration( TEST_NAME1, new TestConfiguration(TEST_CLASS_DIR, TEST_NAME1, new String[] { "R" }) );
	}

	@Test
	public void testComparisonGt0() {
		runComparisonChainTest( ">", 0, false );
	}
	
	@Test
	public void testComparisonGt0Rewrites() {
		runComparisonChainTest( ">", 0, true );
	}
	
	@Test
	public void testComparisonLte1() {
		runComparisonChainTest( "<=", 1, false );
	}
	
	@Test
	public void testComparisonLte1Rewrites() {
		runComparisonChainTest( "<=", 1, true );
	}
	
	@Test
	public void testComparisonEq0() {
		runComparisonChainTest( "==", 0, false );
	}
	
	@Test
	public void testComparisonEq0Rewrites() {
		runComparisonChainTest( "==", 0, true );
	}
	
	@Test
	public void testComparisonNeq1() {
		runComparisonChainTest( "!=", 1, false );
	}
	
	@Test
	public void testComparisonNeq1Rewrites() {
		runComparisonChainTest( "!=", 1, true );
	}

	private void runComparisonChainTest( String op, int compare, boolean rewrites )
	{
		boolean oldFlag = OptimizerUtils.ALLOW_ALGEBRAIC_SIMPLIFICATION;
		
		try {
			TestConfiguration config = getTestConfiguration(TEST_NAME1);
			loadTestConfiguration(config);
			String HOME = SCRIPT_DIR + TEST_DIR;
			fullDMLScriptName = HOME + TEST_NAME1 + ".dml";
			programArgs = new String[]{"-stats","-args", op, String.valueOf(compare)};
			OptimizerUtils.ALLOW_ALGEBRAIC_SIMPLIFICATION = rewrites;
			runTest(true, false, null, -1);
			
			//check for applied rewrites
			Assert.assertEquals(rewrites, heavyHittersContainsString("uaggouterchain"));
			if( compare == 1 && rewrites )
				Assert.assertTrue(!heavyHittersContainsString("=="));
		}
		finally {
			OptimizerUtils.ALLOW_ALGEBRAIC_SIMPLIFICATION = oldFlag;
		}
	}
}
