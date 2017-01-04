/*********************************************************************************
 * Copyright 2016-present trivago GmbH
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

import com.trivago.triava.tcache.TCacheJSR107;

public class TCacheConfigurationMBean extends TCacheMBean
{
	static final TCacheConfigurationMBean inst = new TCacheConfigurationMBean();
	
	public static TCacheConfigurationMBean instance()
	{
		return inst;
	}
	
	@Override
	public Object getMBean(TCacheJSR107<?, ?> jsr107cache)
	{
		return jsr107cache.getCacheConfigMBean();
	}

	@Override
	public String objectNameType()
	{
		return "Configuration";
	}

}
