package com.test.spring.demo.service.impl;

import com.test.spring.demo.service.IDemoService;
import com.test.spring.mvcframework.annotation.MyService;

/**
 * 核心业务逻辑
 */
@MyService
public class DemoService implements IDemoService{

	public String get(String name) {
		return "My name is " + name + ",from service.";
	}

}
