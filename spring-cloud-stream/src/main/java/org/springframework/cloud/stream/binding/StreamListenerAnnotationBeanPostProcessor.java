/*
 * Copyright 2016-2017 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.cloud.stream.binding;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.aop.framework.Advised;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanInitializationException;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.config.BeanExpressionContext;
import org.springframework.beans.factory.config.BeanExpressionResolver;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.cloud.stream.annotation.Input;
import org.springframework.cloud.stream.annotation.Output;
import org.springframework.cloud.stream.annotation.StreamListener;
import org.springframework.cloud.stream.config.SpringIntegrationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.MethodParameter;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.Expression;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.integration.context.IntegrationContextUtils;
import org.springframework.integration.handler.AbstractReplyProducingMessageHandler;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.SubscribableChannel;
import org.springframework.messaging.core.DestinationResolver;
import org.springframework.messaging.handler.annotation.support.MessageHandlerMethodFactory;
import org.springframework.messaging.handler.invocation.InvocableHandlerMethod;
import org.springframework.util.Assert;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.ReflectionUtils;
import org.springframework.util.StringUtils;

/**
 * {@link BeanPostProcessor} that handles {@link StreamListener} annotations found on bean
 * methods.
 *
 * @author Marius Bogoevici
 * @author Ilayaperumal Gopinathan
 * @author Soby Chacko
 */
public class StreamListenerAnnotationBeanPostProcessor implements BeanPostProcessor, ApplicationContextAware, SmartInitializingSingleton {

	private static final SpelExpressionParser SPEL_EXPRESSION_PARSER = new SpelExpressionParser();

	private final MultiValueMap<String, StreamListenerHandlerMethodMapping> mappedListenerMethods = new LinkedMultiValueMap<>();

	// == dependencies that are injected in 'afterSingletonsInstantiated' to avoid early initialization
	@SuppressWarnings("rawtypes")
	private Collection<StreamListenerParameterAdapter> streamListenerParameterAdapters;

	@SuppressWarnings("rawtypes")
	private Collection<StreamListenerResultAdapter> streamListenerResultAdapters;

	private DestinationResolver<MessageChannel> binderAwareChannelResolver;

	private MessageHandlerMethodFactory messageHandlerMethodFactory;

	private SpringIntegrationProperties springIntegrationProperties;
	// == end dependencies

	private final Set<Runnable> streamListenerCallbacks = new HashSet<>();

	private ConfigurableApplicationContext applicationContext;

	private EvaluationContext evaluationContext;

	private BeanExpressionResolver resolver;

	private BeanExpressionContext expressionContext;

	@Override
	public final void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
		this.applicationContext = (ConfigurableApplicationContext) applicationContext;
		this.resolver = this.applicationContext.getBeanFactory().getBeanExpressionResolver();
		this.expressionContext = new BeanExpressionContext(this.applicationContext.getBeanFactory(), null);
	}

	@Override
	public Object postProcessBeforeInitialization(Object bean, String beanName) throws BeansException {
		return bean;
	}

	@Override
	public final void afterSingletonsInstantiated() {
		this.injectAndPostProcessDependencies();
		this.evaluationContext = IntegrationContextUtils.getEvaluationContext(this.applicationContext.getBeanFactory());
		for (Map.Entry<String, List<StreamListenerHandlerMethodMapping>> mappedBindingEntry : mappedListenerMethods
				.entrySet()) {
			ArrayList<DispatchingStreamListenerMessageHandler.ConditionalStreamListenerMessageHandlerWrapper> handlers = new ArrayList<>();
			for (StreamListenerHandlerMethodMapping mapping : mappedBindingEntry.getValue()) {
				final InvocableHandlerMethod invocableHandlerMethod = this.messageHandlerMethodFactory
						.createInvocableHandlerMethod(mapping.getTargetBean(),
								checkProxy(mapping.getMethod(), mapping.getTargetBean()));
				StreamListenerMessageHandler streamListenerMessageHandler = new StreamListenerMessageHandler(
						invocableHandlerMethod, resolveExpressionAsBoolean(mapping.getCopyHeaders(), "copyHeaders"),
						springIntegrationProperties.getMessageHandlerNotPropagatedHeaders());
				streamListenerMessageHandler.setApplicationContext(this.applicationContext);
				streamListenerMessageHandler.setBeanFactory(this.applicationContext.getBeanFactory());
				if (StringUtils.hasText(mapping.getDefaultOutputChannel())) {
					streamListenerMessageHandler.setOutputChannelName(mapping.getDefaultOutputChannel());
				}
				streamListenerMessageHandler.afterPropertiesSet();
				if (StringUtils.hasText(mapping.getCondition())) {
					String conditionAsString = resolveExpressionAsString(mapping.getCondition(), "condition");
					Expression condition = SPEL_EXPRESSION_PARSER.parseExpression(conditionAsString);
					handlers.add(
							new DispatchingStreamListenerMessageHandler.ConditionalStreamListenerMessageHandlerWrapper(
									condition, streamListenerMessageHandler));
				}
				else {
					handlers.add(
							new DispatchingStreamListenerMessageHandler.ConditionalStreamListenerMessageHandlerWrapper(
									null, streamListenerMessageHandler));
				}
			}
			if (handlers.size() > 1) {
				for (DispatchingStreamListenerMessageHandler.ConditionalStreamListenerMessageHandlerWrapper handler : handlers) {
					Assert.isTrue(handler.isVoid(), StreamListenerErrorMessages.MULTIPLE_VALUE_RETURNING_METHODS);
				}
			}
			AbstractReplyProducingMessageHandler handler;

			if (handlers.size() > 1 || handlers.get(0).getCondition() != null) {
				handler = new DispatchingStreamListenerMessageHandler(handlers, this.evaluationContext);
			}
			else {
				handler = handlers.get(0).getStreamListenerMessageHandler();
			}
			handler.setApplicationContext(this.applicationContext);
			handler.setChannelResolver(this.binderAwareChannelResolver);
			handler.afterPropertiesSet();
			this.applicationContext.getBeanFactory().registerSingleton(handler.getClass().getSimpleName() + handler.hashCode(), handler);
			applicationContext.getBean(mappedBindingEntry.getKey(), SubscribableChannel.class).subscribe(handler);
		}
		this.mappedListenerMethods.clear();
	}

	@Override
	public final Object postProcessAfterInitialization(final Object bean, final String beanName) throws BeansException {
		Class<?> targetClass = AopUtils.isAopProxy(bean) ? AopUtils.getTargetClass(bean) : bean.getClass();
		Method[] uniqueDeclaredMethods = ReflectionUtils.getUniqueDeclaredMethods(targetClass);
		for (final Method method : uniqueDeclaredMethods) {
			final StreamListener streamListener = AnnotatedElementUtils.findMergedAnnotation(method, StreamListener.class);
			if (streamListener != null && !method.isBridge()) {
				streamListenerCallbacks.add(new Runnable() {
					@Override
					public void run() {
						Assert.isTrue(method.getAnnotation(Input.class) == null, StreamListenerErrorMessages.INPUT_AT_STREAM_LISTENER);
						doPostProcess(streamListener, method, bean);
					}
				});
			}
		}
		return bean;
	}

	protected final void registerHandlerMethodOnListenedChannel(Method method, StreamListener streamListener, Object bean) {
		Assert.hasText(streamListener.value(), "The binding name cannot be null");
		if (!StringUtils.hasText(streamListener.value())) {
			throw new BeanInitializationException("A bound component name must be specified");
		}
		final String defaultOutputChannel = StreamListenerMethodUtils.getOutboundBindingTargetName(method);
		if (Void.TYPE.equals(method.getReturnType())) {
			Assert.isTrue(StringUtils.isEmpty(defaultOutputChannel),
					"An output channel cannot be specified for a method that does not return a value");
		}
		else {
			Assert.isTrue(!StringUtils.isEmpty(defaultOutputChannel),
					"An output channel must be specified for a method that can return a value");
		}
		StreamListenerMethodUtils.validateStreamListenerMessageHandler(method);
		mappedListenerMethods.add(streamListener.value(),
				new StreamListenerHandlerMethodMapping(bean, method, streamListener.condition(), defaultOutputChannel,
						streamListener.copyHeaders()));
	}

	/**
	 * Extension point, allowing subclasses to customize the {@link StreamListener}
	 * annotation detected by the postprocessor.
	 *
	 * @param originalAnnotation the original annotation
	 * @param annotatedMethod the method on which the annotation has been found
	 * @return the postprocessed {@link StreamListener} annotation
	 */
	protected StreamListener postProcessAnnotation(StreamListener originalAnnotation, Method annotatedMethod) {
		return originalAnnotation;
	}

	private boolean checkDeclarativeMethod(Method method, String methodAnnotatedInboundName, String methodAnnotatedOutboundName) {
		int methodArgumentsLength = method.getParameterTypes().length;
		for (int parameterIndex = 0; parameterIndex < methodArgumentsLength; parameterIndex++) {
			MethodParameter methodParameter = MethodParameter.forMethodOrConstructor(method, parameterIndex);
			if (methodParameter.hasParameterAnnotation(Input.class)) {
				String inboundName = (String) AnnotationUtils
						.getValue(methodParameter.getParameterAnnotation(Input.class));
				Assert.isTrue(StringUtils.hasText(inboundName), StreamListenerErrorMessages.INVALID_INBOUND_NAME);
				Assert.isTrue(isDeclarativeMethodParameter(inboundName, methodParameter),
						StreamListenerErrorMessages.INVALID_DECLARATIVE_METHOD_PARAMETERS);
				return true;
			}
			else if (methodParameter.hasParameterAnnotation(Output.class)) {
				String outboundName = (String) AnnotationUtils
						.getValue(methodParameter.getParameterAnnotation(Output.class));
				Assert.isTrue(StringUtils.hasText(outboundName), StreamListenerErrorMessages.INVALID_OUTBOUND_NAME);
				Assert.isTrue(isDeclarativeMethodParameter(outboundName, methodParameter),
						StreamListenerErrorMessages.INVALID_DECLARATIVE_METHOD_PARAMETERS);
				return true;
			}
			else if (StringUtils.hasText(methodAnnotatedOutboundName)) {
				return isDeclarativeMethodParameter(methodAnnotatedOutboundName, methodParameter);
			}
			else if (StringUtils.hasText(methodAnnotatedInboundName)) {
				return isDeclarativeMethodParameter(methodAnnotatedInboundName, methodParameter);
			}
		}
		return false;
	}

	/**
	 * Determines if method parameters signify an imperative or declarative listener definition.
	 * <br>
	 * Imperative - where handler method is invoked on each message by the handler infrastructure provided
	 * by the framework
	 * <br>
	 * Declarative - where handler  is provided by the method itself.
	 * <br>
	 * Declarative method parameter could either be {@link MessageChannel} or any other Object for which
	 * there is a {@link StreamListenerParameterAdapter} (i.e., {@link Flux}). Declarative method is invoked only
	 * once during initialization phase.
	 */
	@SuppressWarnings("rawtypes")
	private boolean isDeclarativeMethodParameter(String targetBeanName, MethodParameter methodParameter) {
		boolean declarative = false;
		if (!methodParameter.getParameterType().isAssignableFrom(Object.class) && this.applicationContext.containsBean(targetBeanName)) {
			declarative = MessageChannel.class.isAssignableFrom(methodParameter.getParameterType());
			if (!declarative) {
				Class<?> targetBeanClass = this.applicationContext.getType(targetBeanName);
				Iterator<StreamListenerParameterAdapter> slpaIter = this.streamListenerParameterAdapters.iterator();
				while (slpaIter.hasNext() && !declarative) {
					declarative = slpaIter.next().supports(targetBeanClass, methodParameter);
				}
			}
		}
		return declarative;
	}

	@SuppressWarnings({ "rawtypes", "unchecked" })
	private void invokeSetupMethodOnListenedChannel(Method method, Object bean, String inboundName, String outboundName) {
		Object[] arguments = new Object[method.getParameterTypes().length];
		for (int parameterIndex = 0; parameterIndex < arguments.length; parameterIndex++) {
			MethodParameter methodParameter = MethodParameter.forMethodOrConstructor(method, parameterIndex);
			Class<?> parameterType = methodParameter.getParameterType();
			Object targetReferenceValue = null;
			if (methodParameter.hasParameterAnnotation(Input.class)) {
				targetReferenceValue = AnnotationUtils.getValue(methodParameter.getParameterAnnotation(Input.class));
			}
			else if (methodParameter.hasParameterAnnotation(Output.class)) {
				targetReferenceValue = AnnotationUtils.getValue(methodParameter.getParameterAnnotation(Output.class));
			}
			else if (arguments.length == 1 && StringUtils.hasText(inboundName)) {
				targetReferenceValue = inboundName;
			}
			if (targetReferenceValue != null) {
				Assert.isInstanceOf(String.class, targetReferenceValue, "Annotation value must be a String");
				Object targetBean = this.applicationContext.getBean((String) targetReferenceValue);
				// Iterate existing parameter adapters first
				for (StreamListenerParameterAdapter streamListenerParameterAdapter : this.streamListenerParameterAdapters) {
					if (streamListenerParameterAdapter.supports(targetBean.getClass(), methodParameter)) {
						arguments[parameterIndex] = streamListenerParameterAdapter.adapt(targetBean, methodParameter);
						break;
					}
				}
				if (arguments[parameterIndex] == null && parameterType.isAssignableFrom(targetBean.getClass())) {
					arguments[parameterIndex] = targetBean;
				}
				Assert.notNull(arguments[parameterIndex], "Cannot convert argument " + parameterIndex + " of " + method
						+ "from " + targetBean.getClass() + " to " + parameterType);
			}
			else {
				throw new IllegalStateException(StreamListenerErrorMessages.INVALID_DECLARATIVE_METHOD_PARAMETERS);
			}
		}
		try {
			if (Void.TYPE.equals(method.getReturnType())) {
				method.invoke(bean, arguments);
			}
			else {
				Object result = method.invoke(bean, arguments);
				if (!StringUtils.hasText(outboundName)) {
					for (int parameterIndex = 0; parameterIndex < method.getParameterTypes().length; parameterIndex++) {
						MethodParameter methodParameter = MethodParameter.forMethodOrConstructor(method,
								parameterIndex);
						if (methodParameter.hasParameterAnnotation(Output.class)) {
							outboundName = methodParameter.getParameterAnnotation(Output.class).value();
						}
					}
				}
				Object targetBean = this.applicationContext.getBean(outboundName);
				for (StreamListenerResultAdapter streamListenerResultAdapter : this.streamListenerResultAdapters) {
					if (streamListenerResultAdapter.supports(result.getClass(), targetBean.getClass())) {
						streamListenerResultAdapter.adapt(result, targetBean);
						break;
					}
				}
			}
		}
		catch (Exception e) {
			throw new BeanInitializationException("Cannot setup StreamListener for " + method, e);
		}
	}

	private void doPostProcess(StreamListener streamListener, Method method, Object bean) {
		streamListener = postProcessAnnotation(streamListener, method);

		String methodAnnotatedInboundName = streamListener.value();
		String methodAnnotatedOutboundName = StreamListenerMethodUtils.getOutboundBindingTargetName(method);

		int inputAnnotationCount = StreamListenerMethodUtils.inputAnnotationCount(method);
		int outputAnnotationCount = StreamListenerMethodUtils.outputAnnotationCount(method);
		boolean isDeclarative = checkDeclarativeMethod(method, methodAnnotatedInboundName, methodAnnotatedOutboundName);
		StreamListenerMethodUtils.validateStreamListenerMethod(method,
				inputAnnotationCount, outputAnnotationCount,
				methodAnnotatedInboundName, methodAnnotatedOutboundName,
				isDeclarative, streamListener.condition());
		if (isDeclarative) {
			invokeSetupMethodOnListenedChannel(method, bean, methodAnnotatedInboundName, methodAnnotatedOutboundName);
		}
		else {
			registerHandlerMethodOnListenedChannel(method, streamListener, bean);
		}
	}

	private Method checkProxy(Method methodArg, Object bean) {
		Method method = methodArg;
		if (AopUtils.isJdkDynamicProxy(bean)) {
			try {
				// Found a @StreamListener method on the target class for this JDK proxy
				// ->
				// is it also present on the proxy itself?
				method = bean.getClass().getMethod(method.getName(), method.getParameterTypes());
				Class<?>[] proxiedInterfaces = ((Advised) bean).getProxiedInterfaces();
				for (Class<?> iface : proxiedInterfaces) {
					try {
						method = iface.getMethod(method.getName(), method.getParameterTypes());
						break;
					}
					catch (NoSuchMethodException noMethod) {
					}
				}
			}
			catch (SecurityException ex) {
				ReflectionUtils.handleReflectionException(ex);
			}
			catch (NoSuchMethodException ex) {
				throw new IllegalStateException(String.format(
						"@StreamListener method '%s' found on bean target class '%s', "
								+ "but not found in any interface(s) for bean JDK proxy. Either "
								+ "pull the method up to an interface or switch to subclass (CGLIB) "
								+ "proxies by setting proxy-target-class/proxyTargetClass attribute to 'true'",
						method.getName(), method.getDeclaringClass().getSimpleName()), ex);
			}
		}
		return method;
	}

	private String resolveExpressionAsString(String value, String property) {
		Object resolved = resolveExpression(value);
		if (resolved instanceof String) {
			return (String) resolved;
		}
		else {
			throw new IllegalStateException("Resolved " + property + " to [" + resolved.getClass()
				+ "] instead of String for [" + value + "]");
		}
	}

	private boolean resolveExpressionAsBoolean(String value, String property) {
		Object resolved = resolveExpression(value);
		if (resolved == null) {
			return false;
		}
		else if (resolved instanceof String) {
			return Boolean.parseBoolean((String) resolved);
		}
		else if (resolved instanceof Boolean) {
			return (Boolean) resolved;
		}
		else {
			throw new IllegalStateException("Resolved " + property + " to [" + resolved.getClass()
					+ "] instead of String or Boolean for [" + value + "]");
		}
	}

	private String resolveExpression(String value) {
		String resolvedValue = this.applicationContext.getBeanFactory().resolveEmbeddedValue(value);
		if (resolvedValue.startsWith("#{") && value.endsWith("}")) {
			resolvedValue = (String) this.resolver.evaluate(resolvedValue, this.expressionContext);
		}
		return resolvedValue;
	}

	/**
	 * This operations ensures that required dependencies are not accidently injected early given that this bean is BPP.
	 */
	private void injectAndPostProcessDependencies() {
		this.streamListenerParameterAdapters = this.applicationContext.getBeansOfType(StreamListenerParameterAdapter.class).values();
		this.streamListenerResultAdapters = this.applicationContext.getBeansOfType(StreamListenerResultAdapter.class).values();
		this.binderAwareChannelResolver = this.applicationContext.getBean(DestinationResolver.class);
		this.messageHandlerMethodFactory = this.applicationContext.getBean(MessageHandlerMethodFactory.class);
		this.springIntegrationProperties = this.applicationContext.getBean(SpringIntegrationProperties.class);
		for (Runnable runnable : streamListenerCallbacks) {
			runnable.run();
		}
		//this.streamListenerCallbacks.forEach(r -> r.run());
	}

	private class StreamListenerHandlerMethodMapping {

		private final Object targetBean;

		private final Method method;

		private final String condition;

		private final String defaultOutputChannel;

		private final String copyHeaders;

		StreamListenerHandlerMethodMapping(Object targetBean, Method method, String condition,
				String defaultOutputChannel, String copyHeaders) {
			this.targetBean = targetBean;
			this.method = method;
			this.condition = condition;
			this.defaultOutputChannel = defaultOutputChannel;
			this.copyHeaders = copyHeaders;
		}

		Object getTargetBean() {
			return targetBean;
		}

		Method getMethod() {
			return method;
		}

		String getCondition() {
			return condition;
		}

		String getDefaultOutputChannel() {
			return defaultOutputChannel;
		}

		public String getCopyHeaders() {
			return this.copyHeaders;
		}
	}
}
