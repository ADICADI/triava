/*********************************************************************************
 * Copyright 2015-present trivago GmbH
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 **********************************************************************************/

package com.trivago.triava.tcache.util;

/**
 * Interface for an ObjectSizeCalculator, that does a deep calculation of the object trees size in byte.
 * Implementations will typically use Java instrumentation for this calculation, but there are also
 * non-instrumented solutions like Twitter ObjectSizeCalculator. 
 * 
 * @author cesken
 *
 */
public interface ObjectSizeCalculatorInterface
{
	
	/**
	 * Calculates the deep memory footprint of {@code obj} in bytes, i.e. the memory taken by the 
	 * object graph using {@code obj} as a starting node of that graph. 
	 * 
	 * @param obj The object to measure
	 * @return The size in byte
	 */
	long calculateObjectSizeDeep(Object obj);
}
