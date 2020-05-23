/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.nacos.api.exception;

import com.alibaba.nacos.api.common.Constants;
import org.apache.commons.lang3.StringUtils;

/**
 * @author <a href="mailto:liaochuntao@live.com">liaochuntao</a>
 */
public class NacosUncheckException extends RuntimeException {

	/**
	 * serialVersionUID
	 */
	private static final long serialVersionUID = -3913902031489277776L;

	private int errCode;

	private String errMsg;

	private Throwable causeThrowable;

	public NacosUncheckException() {
	}

	public NacosUncheckException(int errCode, String errMsg) {
		super(errMsg);
		this.errCode = errCode;
		this.errMsg = errMsg;
	}

	public NacosUncheckException(int errCode, Throwable throwable) {
		super(throwable);
		this.errCode = errCode;
		setCauseThrowable(throwable);
	}

	public NacosUncheckException(int errCode, String errMsg, Throwable throwable) {
		super(errMsg, throwable);
		this.errCode = errCode;
		this.errMsg = errMsg;
		setCauseThrowable(throwable);
	}

	public int getErrCode() {
		return errCode;
	}

	public String getErrMsg() {
		if (!StringUtils.isBlank(this.errMsg)) {
			return errMsg;
		}
		if (this.causeThrowable != null) {
			return causeThrowable.getMessage();
		}
		return Constants.NULL;
	}

	public void setErrCode(int errCode) {
		this.errCode = errCode;
	}

	public void setErrMsg(String errMsg) {
		this.errMsg = errMsg;
	}

	public void setCauseThrowable(Throwable throwable) {
		this.causeThrowable = getCauseThrowable(throwable);
	}

	private Throwable getCauseThrowable(Throwable t) {
		if (t.getCause() == null) {
			return t;
		}
		return getCauseThrowable(t.getCause());
	}

	@Override
	public String toString() {
		return "ErrCode:" + getErrCode() + ", ErrMsg:" + getErrMsg();
	}


}
