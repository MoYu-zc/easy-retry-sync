package com.alibaba.easyretry.extension.spring.aop;

import com.alibaba.easyretry.common.RetryConfiguration;
import com.alibaba.easyretry.common.RetryIdentify;
import com.alibaba.easyretry.common.retryer.Retryer;
import com.alibaba.easyretry.core.RetryerBuilder;
import com.alibaba.easyretry.extension.spring.SPELResultPredicate;
import lombok.Setter;
import org.apache.commons.lang3.StringUtils;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.aop.framework.Advised;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

@Aspect
public class RetryInterceptor {

	@Setter
	private RetryConfiguration retryConfiguration;

	@Setter
	private ApplicationContext applicationContext;

	@Around("@annotation(retryable)")
	public Object around(ProceedingJoinPoint invocation, EasyRetryable retryable) throws Throwable {
		if (RetryIdentify.isOnRetry()) {
			return invocation.proceed();
		}

		Retryer<Object> retryer = determineTargetRetryer(invocation, retryable);
		return retryer.call(invocation::proceed);
	}

	private Retryer<Object> determineTargetRetryer(ProceedingJoinPoint invocation, EasyRetryable retryable) {
		MethodSignature signature = (MethodSignature)invocation.getSignature();
		RetryerBuilder<Object> retryerBuilder = new RetryerBuilder<Object>()
			.withExecutorName(getBeanName(invocation))
			.withExecutorMethodName(signature.getMethod().getName())
			.withArgs(invocation.getArgs())
			.withConfiguration(retryConfiguration)
			.withReThrowException(retryable.reThrowException());
		if (StringUtils.isNotBlank(retryable.resultCondition())) {
			retryerBuilder.withResultPredicate(new SPELResultPredicate<>(retryable.resultCondition()));
		}

		return retryerBuilder.build(retryable.retryType());
	}

	private String getBeanName(ProceedingJoinPoint invocation) {
		Object beanInstance = getUltimateTarget(invocation.getTarget());
		if (beanInstance == null) {
			throw new IllegalStateException("bean instance is null");
		}
		String[] beanNames = applicationContext.getBeanNamesForType(beanInstance.getClass());

		ConfigurableListableBeanFactory beanFactory = ((ConfigurableApplicationContext) applicationContext).getBeanFactory();
		for (String name : beanNames) {
			if (beanFactory.getBeanDefinition(name).isPrototype()) {
				throw new IllegalStateException("prototype scope is not support, className: " + name);
			}
			Object bean = applicationContext.getBean(name);
			Object targetObject = getUltimateTarget(bean);
			if (beanInstance == targetObject) {
				return name;
			}
		}
		throw new IllegalStateException("bean name is not found");
	}

	private Object getUltimateTarget(Object candidate) {
		Object current = candidate;
		while (AopUtils.isAopProxy(current)) {
			try {
				if (current instanceof Advised) {
					Advised advised = (Advised) current;
					current = advised.getTargetSource().getTarget();
				} else {
					break;
				}
			} catch (Exception e) {
				throw new IllegalStateException("Failed to unwrap proxy target", e);
			}
		}
		return current;
	}
}
