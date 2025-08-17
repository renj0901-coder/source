/*
 * Copyright 2002-2021 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.transaction.support;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.Serializable;
import java.util.List;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.core.Constants;
import org.springframework.lang.Nullable;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.InvalidTimeoutException;
import org.springframework.transaction.NestedTransactionNotSupportedException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.TransactionSuspensionNotSupportedException;
import org.springframework.transaction.UnexpectedRollbackException;

/**
 * Abstract base class that implements Spring's standard transaction workflow,
 * serving as basis for concrete platform transaction managers like
 * {@link org.springframework.transaction.jta.JtaTransactionManager}.
 *
 * <p>This base class provides the following workflow handling:
 * <ul>
 * <li>determines if there is an existing transaction;
 * <li>applies the appropriate propagation behavior;
 * <li>suspends and resumes transactions if necessary;
 * <li>checks the rollback-only flag on commit;
 * <li>applies the appropriate modification on rollback
 * (actual rollback or setting rollback-only);
 * <li>triggers registered synchronization callbacks
 * (if transaction synchronization is active).
 * </ul>
 *
 * <p>Subclasses have to implement specific template methods for specific
 * states of a transaction, e.g.: begin, suspend, resume, commit, rollback.
 * The most important of them are abstract and must be provided by a concrete
 * implementation; for the rest, defaults are provided, so overriding is optional.
 *
 * <p>Transaction synchronization is a generic mechanism for registering callbacks
 * that get invoked at transaction completion time. This is mainly used internally
 * by the data access support classes for JDBC, Hibernate, JPA, etc when running
 * within a JTA transaction: They register resources that are opened within the
 * transaction for closing at transaction completion time, allowing e.g. for reuse
 * of the same Hibernate Session within the transaction. The same mechanism can
 * also be leveraged for custom synchronization needs in an application.
 *
 * <p>The state of this class is serializable, to allow for serializing the
 * transaction strategy along with proxies that carry a transaction interceptor.
 * It is up to subclasses if they wish to make their state to be serializable too.
 * They should implement the {@code java.io.Serializable} marker interface in
 * that case, and potentially a private {@code readObject()} method (according
 * to Java serialization rules) if they need to restore any transient state.
 *
 * @author Juergen Hoeller
 * @see #setTransactionSynchronization
 * @see TransactionSynchronizationManager
 * @see org.springframework.transaction.jta.JtaTransactionManager
 * @since 28.03.2003
 */
@SuppressWarnings("serial")
public abstract class AbstractPlatformTransactionManager implements PlatformTransactionManager, Serializable {

	/**
	 * Always activate transaction synchronization, even for "empty" transactions
	 * that result from PROPAGATION_SUPPORTS with no existing backend transaction.
	 *
	 * @see org.springframework.transaction.TransactionDefinition#PROPAGATION_SUPPORTS
	 * @see org.springframework.transaction.TransactionDefinition#PROPAGATION_NOT_SUPPORTED
	 * @see org.springframework.transaction.TransactionDefinition#PROPAGATION_NEVER
	 */
	public static final int SYNCHRONIZATION_ALWAYS = 0;

	/**
	 * Activate transaction synchronization only for actual transactions,
	 * that is, not for empty ones that result from PROPAGATION_SUPPORTS with
	 * no existing backend transaction.
	 *
	 * @see org.springframework.transaction.TransactionDefinition#PROPAGATION_REQUIRED
	 * @see org.springframework.transaction.TransactionDefinition#PROPAGATION_MANDATORY
	 * @see org.springframework.transaction.TransactionDefinition#PROPAGATION_REQUIRES_NEW
	 */
	public static final int SYNCHRONIZATION_ON_ACTUAL_TRANSACTION = 1;

	/**
	 * Never active transaction synchronization, not even for actual transactions.
	 */
	public static final int SYNCHRONIZATION_NEVER = 2;


	/**
	 * Constants instance for AbstractPlatformTransactionManager.
	 */
	private static final Constants constants = new Constants(AbstractPlatformTransactionManager.class);


	protected transient Log logger = LogFactory.getLog(getClass());

	private int transactionSynchronization = SYNCHRONIZATION_ALWAYS;

	private int defaultTimeout = TransactionDefinition.TIMEOUT_DEFAULT;

	private boolean nestedTransactionAllowed = false;

	private boolean validateExistingTransaction = false;

	// 一个事务中部分失败了，要不要整个事务都回滚
	private boolean globalRollbackOnParticipationFailure = true;

	private boolean failEarlyOnGlobalRollbackOnly = false;

	private boolean rollbackOnCommitFailure = false;


	/**
	 * Set the transaction synchronization by the name of the corresponding constant
	 * in this class, e.g. "SYNCHRONIZATION_ALWAYS".
	 *
	 * @param constantName name of the constant
	 * @see #SYNCHRONIZATION_ALWAYS
	 */
	public final void setTransactionSynchronizationName(String constantName) {
		setTransactionSynchronization(constants.asNumber(constantName).intValue());
	}

	/**
	 * Set when this transaction manager should activate the thread-bound
	 * transaction synchronization support. Default is "always".
	 * <p>Note that transaction synchronization isn't supported for
	 * multiple concurrent transactions by different transaction managers.
	 * Only one transaction manager is allowed to activate it at any time.
	 *
	 * @see #SYNCHRONIZATION_ALWAYS
	 * @see #SYNCHRONIZATION_ON_ACTUAL_TRANSACTION
	 * @see #SYNCHRONIZATION_NEVER
	 * @see TransactionSynchronizationManager
	 * @see TransactionSynchronization
	 */
	public final void setTransactionSynchronization(int transactionSynchronization) {
		this.transactionSynchronization = transactionSynchronization;
	}

	/**
	 * Return if this transaction manager should activate the thread-bound
	 * transaction synchronization support.
	 */
	public final int getTransactionSynchronization() {
		return this.transactionSynchronization;
	}

	/**
	 * Specify the default timeout that this transaction manager should apply
	 * if there is no timeout specified at the transaction level, in seconds.
	 * <p>Default is the underlying transaction infrastructure's default timeout,
	 * e.g. typically 30 seconds in case of a JTA provider, indicated by the
	 * {@code TransactionDefinition.TIMEOUT_DEFAULT} value.
	 *
	 * @see org.springframework.transaction.TransactionDefinition#TIMEOUT_DEFAULT
	 */
	public final void setDefaultTimeout(int defaultTimeout) {
		if (defaultTimeout < TransactionDefinition.TIMEOUT_DEFAULT) {
			throw new InvalidTimeoutException("Invalid default timeout", defaultTimeout);
		}
		this.defaultTimeout = defaultTimeout;
	}

	/**
	 * Return the default timeout that this transaction manager should apply
	 * if there is no timeout specified at the transaction level, in seconds.
	 * <p>Returns {@code TransactionDefinition.TIMEOUT_DEFAULT} to indicate
	 * the underlying transaction infrastructure's default timeout.
	 */
	public final int getDefaultTimeout() {
		return this.defaultTimeout;
	}

	/**
	 * Set whether nested transactions are allowed. Default is "false".
	 * <p>Typically initialized with an appropriate default by the
	 * concrete transaction manager subclass.
	 */
	public final void setNestedTransactionAllowed(boolean nestedTransactionAllowed) {
		this.nestedTransactionAllowed = nestedTransactionAllowed;
	}

	/**
	 * Return whether nested transactions are allowed.
	 */
	public final boolean isNestedTransactionAllowed() {
		return this.nestedTransactionAllowed;
	}

	/**
	 * Set whether existing transactions should be validated before participating
	 * in them.
	 * <p>When participating in an existing transaction (e.g. with
	 * PROPAGATION_REQUIRED or PROPAGATION_SUPPORTS encountering an existing
	 * transaction), this outer transaction's characteristics will apply even
	 * to the inner transaction scope. Validation will detect incompatible
	 * isolation level and read-only settings on the inner transaction definition
	 * and reject participation accordingly through throwing a corresponding exception.
	 * <p>Default is "false", leniently ignoring inner transaction settings,
	 * simply overriding them with the outer transaction's characteristics.
	 * Switch this flag to "true" in order to enforce strict validation.
	 *
	 * @since 2.5.1
	 */
	public final void setValidateExistingTransaction(boolean validateExistingTransaction) {
		this.validateExistingTransaction = validateExistingTransaction;
	}

	/**
	 * Return whether existing transactions should be validated before participating
	 * in them.
	 *
	 * @since 2.5.1
	 */
	public final boolean isValidateExistingTransaction() {
		return this.validateExistingTransaction;
	}

	/**
	 * Set whether to globally mark an existing transaction as rollback-only
	 * after a participating transaction failed.
	 * <p>Default is "true": If a participating transaction (e.g. with
	 * PROPAGATION_REQUIRED or PROPAGATION_SUPPORTS encountering an existing
	 * transaction) fails, the transaction will be globally marked as rollback-only.
	 * The only possible outcome of such a transaction is a rollback: The
	 * transaction originator <i>cannot</i> make the transaction commit anymore.
	 * <p>Switch this to "false" to let the transaction originator make the rollback
	 * decision. If a participating transaction fails with an exception, the caller
	 * can still decide to continue with a different path within the transaction.
	 * However, note that this will only work as long as all participating resources
	 * are capable of continuing towards a transaction commit even after a data access
	 * failure: This is generally not the case for a Hibernate Session, for example;
	 * neither is it for a sequence of JDBC insert/update/delete operations.
	 * <p><b>Note:</b>This flag only applies to an explicit rollback attempt for a
	 * subtransaction, typically caused by an exception thrown by a data access operation
	 * (where TransactionInterceptor will trigger a {@code PlatformTransactionManager.rollback()}
	 * call according to a rollback rule). If the flag is off, the caller can handle the exception
	 * and decide on a rollback, independent of the rollback rules of the subtransaction.
	 * This flag does, however, <i>not</i> apply to explicit {@code setRollbackOnly}
	 * calls on a {@code TransactionStatus}, which will always cause an eventual
	 * global rollback (as it might not throw an exception after the rollback-only call).
	 * <p>The recommended solution for handling failure of a subtransaction
	 * is a "nested transaction", where the global transaction can be rolled
	 * back to a savepoint taken at the beginning of the subtransaction.
	 * PROPAGATION_NESTED provides exactly those semantics; however, it will
	 * only work when nested transaction support is available. This is the case
	 * with DataSourceTransactionManager, but not with JtaTransactionManager.
	 *
	 * @see #setNestedTransactionAllowed
	 * @see org.springframework.transaction.jta.JtaTransactionManager
	 */
	public final void setGlobalRollbackOnParticipationFailure(boolean globalRollbackOnParticipationFailure) {
		this.globalRollbackOnParticipationFailure = globalRollbackOnParticipationFailure;
	}

	/**
	 * Return whether to globally mark an existing transaction as rollback-only
	 * after a participating transaction failed.
	 */
	public final boolean isGlobalRollbackOnParticipationFailure() {
		return this.globalRollbackOnParticipationFailure;
	}

	/**
	 * Set whether to fail early in case of the transaction being globally marked
	 * as rollback-only.
	 * <p>Default is "false", only causing an UnexpectedRollbackException at the
	 * outermost transaction boundary. Switch this flag on to cause an
	 * UnexpectedRollbackException as early as the global rollback-only marker
	 * has been first detected, even from within an inner transaction boundary.
	 * <p>Note that, as of Spring 2.0, the fail-early behavior for global
	 * rollback-only markers has been unified: All transaction managers will by
	 * default only cause UnexpectedRollbackException at the outermost transaction
	 * boundary. This allows, for example, to continue unit tests even after an
	 * operation failed and the transaction will never be completed. All transaction
	 * managers will only fail earlier if this flag has explicitly been set to "true".
	 *
	 * @see org.springframework.transaction.UnexpectedRollbackException
	 * @since 2.0
	 */
	public final void setFailEarlyOnGlobalRollbackOnly(boolean failEarlyOnGlobalRollbackOnly) {
		this.failEarlyOnGlobalRollbackOnly = failEarlyOnGlobalRollbackOnly;
	}

	/**
	 * Return whether to fail early in case of the transaction being globally marked
	 * as rollback-only.
	 *
	 * @since 2.0
	 */
	public final boolean isFailEarlyOnGlobalRollbackOnly() {
		return this.failEarlyOnGlobalRollbackOnly;
	}

	/**
	 * Set whether {@code doRollback} should be performed on failure of the
	 * {@code doCommit} call. Typically not necessary and thus to be avoided,
	 * as it can potentially override the commit exception with a subsequent
	 * rollback exception.
	 * <p>Default is "false".
	 *
	 * @see #doCommit
	 * @see #doRollback
	 */
	public final void setRollbackOnCommitFailure(boolean rollbackOnCommitFailure) {
		this.rollbackOnCommitFailure = rollbackOnCommitFailure;
	}

	/**
	 * Return whether {@code doRollback} should be performed on failure of the
	 * {@code doCommit} call.
	 */
	public final boolean isRollbackOnCommitFailure() {
		return this.rollbackOnCommitFailure;
	}


	//---------------------------------------------------------------------
	// Implementation of PlatformTransactionManager
	//---------------------------------------------------------------------

	/**
	 * This implementation handles propagation behavior. Delegates to
	 * {@code doGetTransaction}, {@code isExistingTransaction}
	 * and {@code doBegin}.
	 *
	 * @see #doGetTransaction
	 * @see #isExistingTransaction
	 * @see #doBegin
	 */
	@Override
	public final TransactionStatus getTransaction(@Nullable TransactionDefinition definition)
			throws TransactionException {

		// Use defaults if no transaction definition given.
		TransactionDefinition def = (definition != null ? definition : TransactionDefinition.withDefaults());

		// 得到一个新的DataSourceTransactionObject对象，
		// 并从 Spring 的事务同步管理器中获取与当前数据源关联的数据库连接持有者对象。设置到DataSourceTransactionObject对象
		// new DataSourceTransactionObject  txObject
		Object transaction = doGetTransaction();
		boolean debugEnabled = logger.isDebugEnabled();

		// transaction.getConnectionHolder().isTransactionActive()
		// 如果当前线程中已经存在一个事务，那么根据事务传播行为决定如何处理，判断方法isExistingTransaction
		if (isExistingTransaction(transaction)) {
			// Existing transaction found -> check propagation behavior to find out how to behave.
			// 如果当前线程中已经存在一个事务，那么根据事务传播行为决定如何处理
			return handleExistingTransaction(def, transaction, debugEnabled);
		}

		// Check definition settings for new transaction.
		// 检查事务超时值的有效性：确保事务定义中的超时时间不小于默认超时值
		if (def.getTimeout() < TransactionDefinition.TIMEOUT_DEFAULT) {
			throw new InvalidTimeoutException("Invalid transaction timeout", def.getTimeout());
		}

		// No existing transaction found -> check propagation behavior to find out how to proceed.
		if (def.getPropagationBehavior() == TransactionDefinition.PROPAGATION_MANDATORY) {
			throw new IllegalTransactionStateException(
					"No existing transaction found for transaction marked with propagation 'mandatory'");
		}
		// 在当前Thread中没有事务的前提下，以下三个是等价的
		else if (def.getPropagationBehavior() == TransactionDefinition.PROPAGATION_REQUIRED ||
				def.getPropagationBehavior() == TransactionDefinition.PROPAGATION_REQUIRES_NEW ||
				def.getPropagationBehavior() == TransactionDefinition.PROPAGATION_NESTED) {
			// 没有事务需要挂起，不过TransactionSynchronization有可能需要挂起
			// suspendedResources表示当前线程被挂起的资源持有对象（数据库连接、TransactionSynchronization）
			SuspendedResourcesHolder suspendedResources = suspend(null);
			if (debugEnabled) {
				logger.debug("Creating new transaction with name [" + def.getName() + "]: " + def);
			}
			try {
				// 开启事务后，transaction中就会有数据库连接了，并且isTransactionActive为true
				// 并返回TransactionStatus对象，该对象保存了很多信息，包括被挂起的资源
				return startTransaction(def, transaction, debugEnabled, suspendedResources);
			} catch (RuntimeException | Error ex) {
				resume(null, suspendedResources);
				throw ex;
			}
		} else {
			// Create "empty" transaction: no actual transaction, but potentially synchronization.
			if (def.getIsolationLevel() != TransactionDefinition.ISOLATION_DEFAULT && logger.isWarnEnabled()) {
				logger.warn("Custom isolation level specified but no actual transaction initiated; " +
						"isolation level will effectively be ignored: " + def);
			}
			boolean newSynchronization = (getTransactionSynchronization() == SYNCHRONIZATION_ALWAYS);
			return prepareTransactionStatus(def, null, true, newSynchronization, debugEnabled, null);
		}
	}

	/**
	 * Start a new transaction.
	 */
	private TransactionStatus startTransaction(TransactionDefinition definition, Object transaction,
											   boolean debugEnabled, @Nullable SuspendedResourcesHolder suspendedResources) {

		// 是否开启一个新的TransactionSynchronization
		boolean newSynchronization = (getTransactionSynchronization() != SYNCHRONIZATION_NEVER);

		// 开启的这个事务的状态信息：
		// 事务的定义、用来保存数据库连接的对象、是否是新事务，是否是新的TransactionSynchronization
		DefaultTransactionStatus status = newTransactionStatus(
				definition, transaction, true, newSynchronization, debugEnabled, suspendedResources);

		// 开启事务
		doBegin(transaction, definition);

		// 如果需要新开一个TransactionSynchronization，就把新创建的事务的一些状态信息设置到TransactionSynchronizationManager中
		prepareSynchronization(status, definition);
		return status;
	}

	/**
	 * 处理已存在事务的情况，根据不同的事务传播行为采取相应的处理策略
	 *
	 * @param definition   当前事务的定义信息（包括传播行为、隔离级别、超时等）
	 * @param transaction  当前已存在的事务对象
	 * @param debugEnabled 是否启用调试日志
	 * @return 事务状态对象，表示如何处理当前事务场景
	 * @throws TransactionException 事务处理过程中可能抛出的异常
	 */
	private TransactionStatus handleExistingTransaction(
			TransactionDefinition definition, Object transaction, boolean debugEnabled)
			throws TransactionException {

		// PROPAGATION_NEVER: 如果当前存在事务，则抛出异常
		// 适用于明确要求不能在事务环境中执行的方法
		if (definition.getPropagationBehavior() == TransactionDefinition.PROPAGATION_NEVER) {
			throw new IllegalTransactionStateException(
					"Existing transaction found for transaction marked with propagation 'never'");
		}

		// PROPAGATION_NOT_SUPPORTED: 不支持当前事务，需要挂起现有事务，以非事务方式执行
		// 适用于不需要事务支持的操作，如查询操作
		if (definition.getPropagationBehavior() == TransactionDefinition.PROPAGATION_NOT_SUPPORTED) {
			if (debugEnabled) {
				logger.debug("Suspending current transaction");
			}
			// 挂起当前事务，将事务资源（如数据库连接）从当前线程中解绑
			// suspendedResources 保存了被挂起的事务资源，以便后续恢复
			Object suspendedResources = suspend(transaction);

			// 根据事务同步设置决定是否创建新的同步环境
			// 如果设置为 SYNCHRONIZATION_ALWAYS，则即使在非事务环境下也创建同步环境,默认true
			boolean newSynchronization = (getTransactionSynchronization() == SYNCHRONIZATION_ALWAYS);

			// 准备并返回事务状态对象
			// 参数说明：
			// - definition: 当前事务定义
			// - null: 没有实际的事务对象（因为是以非事务方式执行）
			// - false: 不是新事务
			// - newSynchronization: 是否创建新的同步环境
			// - debugEnabled: 调试标志
			// - suspendedResources: 被挂起的事务资源
			return prepareTransactionStatus(
					definition, null, false, newSynchronization, debugEnabled, suspendedResources);
		}

		// PROPAGATION_REQUIRES_NEW: 需要新事务，挂起当前事务并创建一个全新的事务
		// 适用于需要独立事务边界的操作，如日志记录、消息发送等
		if (definition.getPropagationBehavior() == TransactionDefinition.PROPAGATION_REQUIRES_NEW) {
			if (debugEnabled) {
				logger.debug("Suspending current transaction, creating new transaction with name [" +
						definition.getName() + "]");
			}
			// 挂起当前事务并保存其资源
			SuspendedResourcesHolder suspendedResources = suspend(transaction);
			try {
				// 启动一个全新的事务
				// suspendedResources 会被传递给新事务，以便在新事务完成后恢复原事务
				return startTransaction(definition, transaction, debugEnabled, suspendedResources);
			} catch (RuntimeException | Error beginEx) {
				// 如果新事务启动失败，则恢复之前被挂起的事务
				resumeAfterBeginException(transaction, suspendedResources, beginEx);
				throw beginEx;
			}
		}

		// PROPAGATION_NESTED: 嵌套事务，如果当前存在事务，则在嵌套事务中执行
		// 适用于需要保存点（savepoint）的场景，可以部分回滚
		if (definition.getPropagationBehavior() == TransactionDefinition.PROPAGATION_NESTED) {
			// 检查事务管理器是否支持嵌套事务
			if (!isNestedTransactionAllowed()) {
				throw new NestedTransactionNotSupportedException(
						"Transaction manager does not allow nested transactions by default - " +
								"specify 'nestedTransactionAllowed' property with value 'true'");
			}
			if (debugEnabled) {
				logger.debug("Creating nested transaction with name [" + definition.getName() + "]");
			}

			// 判断是否使用保存点实现嵌套事务（通常为 true，适用于 JDBC）
			if (useSavepointForNestedTransaction()) {
				// 在现有事务中创建保存点来实现嵌套事务
				// 创建事务状态对象，但不激活同步机制（因为使用的是现有事务）
				DefaultTransactionStatus status =
						prepareTransactionStatus(definition, transaction, false, false, debugEnabled, null);
				// 创建并持有保存点
				status.createAndHoldSavepoint();
				return status;
			} else {
				// 对于 JTA 等不支持保存点的场景，通过嵌套的 begin/commit/rollback 调用实现
				// 这种情况下会启动一个真正的嵌套事务
				return startTransaction(definition, transaction, debugEnabled, null);
			}
		}

		// PROPAGATION_SUPPORTS 或 PROPAGATION_REQUIRED: 参与现有事务
		// 这是最常见的场景，直接使用当前已存在的事务
		if (debugEnabled) {
			logger.debug("Participating in existing transaction");
		}

		// 如果启用了现有事务验证，则检查事务属性兼容性
		if (isValidateExistingTransaction()) {
			// 验证隔离级别兼容性
			if (definition.getIsolationLevel() != TransactionDefinition.ISOLATION_DEFAULT) {
				// 获取当前事务的隔离级别
				Integer currentIsolationLevel = TransactionSynchronizationManager.getCurrentTransactionIsolationLevel();
				// 如果定义的隔离级别与当前事务不兼容，则抛出异常
				if (currentIsolationLevel == null || currentIsolationLevel != definition.getIsolationLevel()) {
					Constants isoConstants = DefaultTransactionDefinition.constants;
					throw new IllegalTransactionStateException("Participating transaction with definition [" +
							definition + "] specifies isolation level which is incompatible with existing transaction: " +
							(currentIsolationLevel != null ?
									isoConstants.toCode(currentIsolationLevel, DefaultTransactionDefinition.PREFIX_ISOLATION) :
									"(unknown)"));
				}
			}
			// 验证只读属性兼容性
			if (!definition.isReadOnly()) {
				// 如果当前事务是只读的，但新操作要求读写，则抛出异常
				if (TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
					throw new IllegalTransactionStateException("Participating transaction with definition [" +
							definition + "] is not marked as read-only but existing transaction is");
				}
			}
		}

		// 对于 PROPAGATION_SUPPORTS 和 PROPAGATION_REQUIRED，参与现有事务
		// 判断是否需要创建新的同步环境
		boolean newSynchronization = (getTransactionSynchronization() != SYNCHRONIZATION_NEVER);

		// 准备事务状态对象，参与现有事务（newTransaction = false）
		return prepareTransactionStatus(definition, transaction, false, newSynchronization, debugEnabled, null);
	}


	/**
	 * 为给定的参数创建一个新的 TransactionStatus 对象，同时根据需要初始化事务同步机制
	 * <p>
	 * 这是一个最终方法，用于统一创建事务状态对象并准备事务同步环境
	 *
	 * @param definition         当前事务的定义信息，包含传播行为、隔离级别、超时设置、只读标志等
	 * @param transaction        事务对象，表示当前事务的具体实现（如 DataSourceTransactionObject）
	 *                           可能为 null，例如在 PROPAGATION_NOT_SUPPORTED 传播行为下
	 * @param newTransaction     标识是否为新事务的标志
	 *                           true: 表示这是一个新创建的事务（如 PROPAGATION_REQUIRED 创建新事务或 PROPAGATION_REQUIRES_NEW）
	 *                           false: 表示参与现有事务或无事务环境（如 PROPAGATION_SUPPORTS 或 PROPAGATION_NOT_SUPPORTED）
	 * @param newSynchronization 标识是否需要创建新的事务同步环境
	 *                           true: 需要初始化新的同步环境（如创建新的事务时）
	 *                           false: 不需要同步环境（如使用现有事务或明确禁用同步）
	 * @param debug              调试标志，指示是否应生成调试日志
	 *                           true: 启用调试日志输出
	 *                           false: 禁用调试日志输出
	 * @param suspendedResources 被挂起的事务资源持有者
	 *                           包含之前被挂起的事务资源信息（如数据库连接、同步回调等）
	 *                           在事务完成后用于恢复被挂起的外部事务
	 *                           可能为 null，表示没有被挂起的资源
	 * @return DefaultTransactionStatus 事务状态对象，包含事务的所有相关信息
	 * @see #newTransactionStatus(TransactionDefinition, Object, boolean, boolean, boolean, Object)
	 * @see #prepareSynchronization(DefaultTransactionStatus, TransactionDefinition)
	 * @see DefaultTransactionStatus
	 * @see TransactionSynchronizationManager
	 */
	protected final DefaultTransactionStatus prepareTransactionStatus(
			TransactionDefinition definition,
			@Nullable Object transaction,
			boolean newTransaction,
			boolean newSynchronization,
			boolean debug,
			@Nullable Object suspendedResources) {

		// 1. 调用 newTransactionStatus 方法创建基本的事务状态对象
		//    该方法会根据参数创建 DefaultTransactionStatus 实例，并处理同步环境的逻辑
		DefaultTransactionStatus status = newTransactionStatus(
				definition, transaction, newTransaction, newSynchronization, debug, suspendedResources);

		// 2. 调用 prepareSynchronization 方法准备事务同步环境
		//    如果需要新的同步环境，会初始化 TransactionSynchronizationManager 的相关设置
		prepareSynchronization(status, definition);

		// 3. 返回完整的事务状态对象
		return status;
	}


	/**
	 * 根据给定的参数创建一个新的 DefaultTransactionStatus 实例
	 * <p>
	 * 这个方法负责创建事务状态对象，并确定是否需要激活事务同步机制
	 *
	 * @param definition         当前事务的定义信息，包含传播行为、隔离级别、超时设置、只读标志等
	 * @param transaction        事务对象，表示当前事务的具体实现（如 DataSourceTransactionObject）
	 *                           可能为 null，例如在 PROPAGATION_NOT_SUPPORTED 传播行为下
	 * @param newTransaction     标识是否为新事务的标志
	 *                           true: 表示这是一个新创建的事务（如 PROPAGATION_REQUIRED 创建新事务或 PROPAGATION_REQUIRES_NEW）
	 *                           false: 表示参与现有事务或无事务环境（如 PROPAGATION_SUPPORTS 或 PROPAGATION_NOT_SUPPORTED）
	 * @param newSynchronization 标识是否需要创建新的事务同步环境的请求
	 *                           true: 请求初始化新的同步环境（如创建新的事务时）
	 *                           false: 不需要同步环境（如使用现有事务或明确禁用同步）
	 * @param debug              调试标志，指示是否应生成调试日志
	 *                           true: 启用调试日志输出
	 *                           false: 禁用调试日志输出
	 * @param suspendedResources 被挂起的事务资源持有者
	 *                           包含之前被挂起的事务资源信息（如数据库连接、同步回调等）
	 *                           在事务完成后用于恢复被挂起的外部事务
	 *                           可能为 null，表示没有被挂起的资源
	 * @return DefaultTransactionStatus 事务状态对象，包含事务的所有相关信息
	 * @see DefaultTransactionStatus
	 * @see TransactionSynchronizationManager
	 */
	protected DefaultTransactionStatus newTransactionStatus(
			TransactionDefinition definition,
			@Nullable Object transaction,
			boolean newTransaction,
			boolean newSynchronization,
			boolean debug,
			@Nullable Object suspendedResources) {

		// 确定是否实际需要创建新的同步环境
		// 条件1: newSynchronization 为 true（请求创建同步环境）
		// 条件2: 当前没有活跃的同步环境（!TransactionSynchronizationManager.isSynchronizationActive()）
		// 只有当两个条件都满足时，才实际创建新的同步环境
		// 这样避免了在已存在同步环境时重复创建
		boolean actualNewSynchronization = newSynchronization &&
				!TransactionSynchronizationManager.isSynchronizationActive();

		// 创建并返回 DefaultTransactionStatus 实例
		return new DefaultTransactionStatus(
				transaction,                    // 事务对象
				newTransaction,                 // 是否为新事务
				actualNewSynchronization,       // 是否实际创建新的同步环境
				definition.isReadOnly(),        // 事务是否只读
				debug,                          // 调试标志
				suspendedResources              // 被挂起的资源
		);
	}

	/**
	 * 根据事务状态初始化事务同步环境
	 * <p>
	 * 这个方法负责设置事务同步管理器的相关属性，为事务同步回调做好准备
	 * 只有在需要新的同步环境时才会执行初始化操作
	 *
	 * @param status     当前事务状态对象，包含事务的各种信息
	 * @param definition 当前事务的定义信息
	 * @see DefaultTransactionStatus#isNewSynchronization()
	 * @see TransactionSynchronizationManager
	 */
	protected void prepareSynchronization(DefaultTransactionStatus status, TransactionDefinition definition) {
		// 检查是否需要初始化新的同步环境
		// 只有在 status.isNewSynchronization() 返回 true 时才执行初始化
		if (status.isNewSynchronization()) {
			// 设置实际事务活跃状态
			// 如果 status.hasTransaction() 为 true，表示存在实际的事务
			TransactionSynchronizationManager.setActualTransactionActive(status.hasTransaction());

			// 设置当前事务的隔离级别
			// 如果定义的隔离级别不是默认值，则设置；否则设置为 null
			TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(
					definition.getIsolationLevel() != TransactionDefinition.ISOLATION_DEFAULT ?
							definition.getIsolationLevel() : null);

			// 设置当前事务的只读状态
			// 将事务定义中的只读标志设置到同步管理器中
			TransactionSynchronizationManager.setCurrentTransactionReadOnly(definition.isReadOnly());

			// 设置当前事务的名称
			// 将事务定义中的名称设置到同步管理器中，用于日志和调试
			TransactionSynchronizationManager.setCurrentTransactionName(definition.getName());

			// 初始化同步环境
			// 这会初始化 TransactionSynchronizationManager 中的 synchronizations ThreadLocal
			// 为后续注册同步回调做好准备
			TransactionSynchronizationManager.initSynchronization();
		}
	}

	/**
	 * Determine the actual timeout to use for the given definition.
	 * Will fall back to this manager's default timeout if the
	 * transaction definition doesn't specify a non-default value.
	 *
	 * @param definition the transaction definition
	 * @return the actual timeout to use
	 * @see org.springframework.transaction.TransactionDefinition#getTimeout()
	 * @see #setDefaultTimeout
	 */
	protected int determineTimeout(TransactionDefinition definition) {
		if (definition.getTimeout() != TransactionDefinition.TIMEOUT_DEFAULT) {
			return definition.getTimeout();
		}
		return getDefaultTimeout();
	}


	/**
	 * Suspend the given transaction. Suspends transaction synchronization first,
	 * then delegates to the {@code doSuspend} template method.
	 *
	 * @param transaction the current transaction object
	 *                    (or {@code null} to just suspend active synchronizations, if any)
	 * @return an object that holds suspended resources
	 * (or {@code null} if neither transaction nor synchronization active)
	 * @see #doSuspend
	 * @see #resume
	 */
	@Nullable
	protected final SuspendedResourcesHolder suspend(@Nullable Object transaction) throws TransactionException {
		// synchronizations是一个ThreadLocal<Set<TransactionSynchronization>>
		// 我们可以在任何地方通过TransactionSynchronizationManager给当前线程添加TransactionSynchronization，
		if (TransactionSynchronizationManager.isSynchronizationActive()) {
			// 调用TransactionSynchronization的suspend方法，并清空和返回当前线程中所有的TransactionSynchronization对象
			List<TransactionSynchronization> suspendedSynchronizations = doSuspendSynchronization();
			try {
				Object suspendedResources = null;
				if (transaction != null) {
					// 挂起事务，把transaction中的Connection清空，并把resources中的key-value进行移除，并返回数据库连接Connection对象
					suspendedResources = doSuspend(transaction);
				}

				// 获取并清空当前线程中关于TransactionSynchronizationManager的设置
				String name = TransactionSynchronizationManager.getCurrentTransactionName();
				TransactionSynchronizationManager.setCurrentTransactionName(null);
				boolean readOnly = TransactionSynchronizationManager.isCurrentTransactionReadOnly();
				TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
				Integer isolationLevel = TransactionSynchronizationManager.getCurrentTransactionIsolationLevel();
				TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(null);
				boolean wasActive = TransactionSynchronizationManager.isActualTransactionActive();
				TransactionSynchronizationManager.setActualTransactionActive(false);

				// 将当前线程中的数据库连接对象、TransactionSynchronization对象、TransactionSynchronizationManager中的设置构造成一个对象
				// 表示被挂起的资源持有对象，持有了当前线程中的事务对象、TransactionSynchronization对象
				return new SuspendedResourcesHolder(
						suspendedResources, suspendedSynchronizations, name, readOnly, isolationLevel, wasActive);
			} catch (RuntimeException | Error ex) {
				// doSuspend failed - original transaction is still active...
				doResumeSynchronization(suspendedSynchronizations);
				throw ex;
			}
		} else if (transaction != null) {
			// Transaction active but no synchronization active.
			Object suspendedResources = doSuspend(transaction);
			return new SuspendedResourcesHolder(suspendedResources);
		} else {
			// Neither transaction nor synchronization active.
			return null;
		}
	}

	/**
	 * Resume the given transaction. Delegates to the {@code doResume}
	 * template method first, then resuming transaction synchronization.
	 *
	 * @param transaction     the current transaction object
	 * @param resourcesHolder the object that holds suspended resources,
	 *                        as returned by {@code suspend} (or {@code null} to just
	 *                        resume synchronizations, if any)
	 * @see #doResume
	 * @see #suspend
	 */
	protected final void resume(@Nullable Object transaction, @Nullable SuspendedResourcesHolder resourcesHolder)
			throws TransactionException {

		if (resourcesHolder != null) {
			Object suspendedResources = resourcesHolder.suspendedResources;
			if (suspendedResources != null) {
				doResume(transaction, suspendedResources);
			}
			List<TransactionSynchronization> suspendedSynchronizations = resourcesHolder.suspendedSynchronizations;
			if (suspendedSynchronizations != null) {
				TransactionSynchronizationManager.setActualTransactionActive(resourcesHolder.wasActive);
				TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(resourcesHolder.isolationLevel);
				TransactionSynchronizationManager.setCurrentTransactionReadOnly(resourcesHolder.readOnly);
				TransactionSynchronizationManager.setCurrentTransactionName(resourcesHolder.name);
				// 执行resume()
				doResumeSynchronization(suspendedSynchronizations);
			}
		}
	}

	/**
	 * Resume outer transaction after inner transaction begin failed.
	 */
	private void resumeAfterBeginException(
			Object transaction, @Nullable SuspendedResourcesHolder suspendedResources, Throwable beginEx) {

		try {
			resume(transaction, suspendedResources);
		} catch (RuntimeException | Error resumeEx) {
			String exMessage = "Inner transaction begin exception overridden by outer transaction resume exception";
			logger.error(exMessage, beginEx);
			throw resumeEx;
		}
	}

	/**
	 * Suspend all current synchronizations and deactivate transaction
	 * synchronization for the current thread.
	 *
	 * @return the List of suspended TransactionSynchronization objects
	 */
	private List<TransactionSynchronization> doSuspendSynchronization() {
		// 从synchronizations（一个ThreadLocal）中拿到所设置的TransactionSynchronization对象
		List<TransactionSynchronization> suspendedSynchronizations =
				TransactionSynchronizationManager.getSynchronizations();

		// 调用TransactionSynchronization对象的suspend()
		for (TransactionSynchronization synchronization : suspendedSynchronizations) {
			synchronization.suspend();
		}

		// 清空synchronizations
		TransactionSynchronizationManager.clearSynchronization();

		// 把获取到的TransactionSynchronization返回
		return suspendedSynchronizations;
	}

	/**
	 * Reactivate transaction synchronization for the current thread
	 * and resume all given synchronizations.
	 *
	 * @param suspendedSynchronizations a List of TransactionSynchronization objects
	 */
	private void doResumeSynchronization(List<TransactionSynchronization> suspendedSynchronizations) {
		TransactionSynchronizationManager.initSynchronization();
		for (TransactionSynchronization synchronization : suspendedSynchronizations) {
			synchronization.resume();
			TransactionSynchronizationManager.registerSynchronization(synchronization);
		}
	}


	/**
	 * 提交事务的核心实现方法
	 * <p>
	 * 这个方法处理事务提交的完整流程，包括：
	 * 1. 检查事务状态有效性
	 * 2. 处理各种回滚请求（本地和全局）
	 * 3. 执行实际的事务提交操作
	 * <p>
	 * 该方法是 final 的，确保事务提交的一致性和安全性
	 *
	 * @param status 要提交的事务状态对象
	 * @throws TransactionException 事务提交过程中可能出现的异常
	 * @see #processCommit(DefaultTransactionStatus)
	 * @see #processRollback(DefaultTransactionStatus, boolean)
	 * @see TransactionStatus#isCompleted()
	 * @see DefaultTransactionStatus#isLocalRollbackOnly()
	 * @see DefaultTransactionStatus#isGlobalRollbackOnly()
	 */
	@Override
	public final void commit(TransactionStatus status) throws TransactionException {
		// 1. 检查事务是否已经完成
		// 防止重复提交或回滚同一个事务
		if (status.isCompleted()) {
			throw new IllegalTransactionStateException(
					"Transaction is already completed - do not call commit or rollback more than once per transaction");
		}

		// 2. 转换为默认事务状态实现
		// 为了访问 DefaultTransactionStatus 特有的方法
		DefaultTransactionStatus defStatus = (DefaultTransactionStatus) status;

		// 3. 检查本地回滚请求
		// 可以通过TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();来设置
		// 事务本来是可以要提交的，但是可以强制回滚
		if (defStatus.isLocalRollbackOnly()) {
			// 本地代码明确请求回滚事务
			if (defStatus.isDebug()) {
				logger.debug("Transactional code has requested rollback");
			}
			// 处理回滚，unexpected 参数设为 false 表示这是预期的回滚
			processRollback(defStatus, false);
			return; // 直接返回，不执行提交逻辑
		}

		// 4. 检查全局回滚标记
		// 判断此事务在之前是否设置了需要回滚，跟globalRollbackOnParticipationFailure有关
		// shouldCommitOnGlobalRollbackOnly() 通常返回 false
		if (!shouldCommitOnGlobalRollbackOnly() && defStatus.isGlobalRollbackOnly()) {
			// 事务被全局标记为 rollback-only，但代码尝试提交
			// 这种情况主要发生在事务传播场景中，特别是当一个方法参与（participate）到一个已存在的事务中，
			// 但该参与过程中发生了异常，导致整个事务被标记为"rollback-only"。
			if (defStatus.isDebug()) {
				logger.debug("Global transaction is marked as rollback-only but transactional code requested commit");
			}
			// 处理回滚，unexpected 参数设为 true 表示这是意外的回滚
			processRollback(defStatus, true);
			return; // 直接返回，不执行提交逻辑
		}

		// 5. 执行实际的事务提交
		// 提交
		processCommit(defStatus);
	}


	/**
	 * 处理实际的事务提交过程
	 * 在调用此方法之前，回滚标志已经检查并应用过了
	 * <p>
	 * 这个方法是事务提交的核心实现，负责完整的提交流程，包括：
	 * 1. 触发提交前的回调
	 * 2. 执行实际的提交操作
	 * 3. 处理各种异常情况
	 * 4. 触发提交后的回调
	 * 5. 清理事务资源
	 *
	 * @param status 代表事务的对象，包含事务的所有状态信息
	 * @throws TransactionException 提交失败时抛出的事务异常
	 * @see #commit(TransactionStatus)
	 * @see DefaultTransactionStatus
	 */
	private void processCommit(DefaultTransactionStatus status) throws TransactionException {
		// 使用 try-finally 确保清理工作总是被执行
		try {
			// 标记 beforeCompletion 回调是否已被调用
			// 用于在发生异常时决定是否需要再次调用
			boolean beforeCompletionInvoked = false;

			// 内层 try-catch 处理提交过程中的各种异常
			try {
				// 标记是否发生了意外的回滚
				boolean unexpectedRollback = false;

				// 1. 提交前准备阶段
				// 执行提交前的准备工作，子类可以重写此方法进行自定义准备
				prepareForCommit(status);

				// 2. 触发 beforeCommit 回调
				// 通知所有注册的同步回调即将提交事务
				triggerBeforeCommit(status);

				// 3. 触发 beforeCompletion 回调
				// 通知所有注册的同步回调事务即将完成（提交或回滚前的最后机会）
				triggerBeforeCompletion(status);

				// 标记 beforeCompletion 回调已成功调用
				beforeCompletionInvoked = true;

				// 4. 根据事务状态执行相应操作

				// 4.1 如果是保存点事务（嵌套事务）
				if (status.hasSavepoint()) {
					if (status.isDebug()) {
						logger.debug("Releasing transaction savepoint");
					}
					// 检查是否有全局回滚标志
					unexpectedRollback = status.isGlobalRollbackOnly();
					// 释放保存点，相当于提交嵌套事务
					status.releaseHeldSavepoint();
				}
				// 4.2 如果是新事务（顶层事务）
				else if (status.isNewTransaction()) {
					if (status.isDebug()) {
						logger.debug("Initiating transaction commit");
					}
					// 检查是否有全局回滚标志
					unexpectedRollback = status.isGlobalRollbackOnly();
					// 执行实际的提交操作，调用具体事务管理器的提交实现
					doCommit(status);
				}
				// 4.3 如果是参与现有事务且需要提前失败
				else if (isFailEarlyOnGlobalRollbackOnly()) {
					// 检查是否有全局回滚标志
					unexpectedRollback = status.isGlobalRollbackOnly();
				}

				// 5. 处理意外回滚情况
				// 如果标记了全局回滚但没有抛出异常，则抛出 UnexpectedRollbackException
				if (unexpectedRollback) {
					throw new UnexpectedRollbackException(
							"Transaction silently rolled back because it has been marked as rollback-only");
				}
			}
			// 处理显式的意外回滚异常
			catch (UnexpectedRollbackException ex) {
				// 只能由 doCommit 引起
				// 触发事务完成后的回调，状态为已回滚
				triggerAfterCompletion(status, TransactionSynchronization.STATUS_ROLLED_BACK);
				throw ex;
			}
			// 处理事务提交异常
			catch (TransactionException ex) {
				// 只能由 doCommit 引起
				// 根据配置决定是回滚还是标记为未知状态
				if (isRollbackOnCommitFailure()) {
					// 提交失败时执行回滚
					doRollbackOnCommitException(status, ex);
				} else {
					// 触发事务完成后的回调，状态为未知
					triggerAfterCompletion(status, TransactionSynchronization.STATUS_UNKNOWN);
				}
				throw ex;
			}
			// 处理运行时异常和错误
			catch (RuntimeException | Error ex) {
				// 如果 beforeCompletion 回调尚未调用，则调用它
				if (!beforeCompletionInvoked) {
					triggerBeforeCompletion(status);
				}
				// 提交异常时执行回滚
				doRollbackOnCommitException(status, ex);
				throw ex;
			}

			// 6. 提交成功后的处理

			// 触发 afterCommit 回调
			// 通知所有注册的同步回调事务已成功提交
			try {
				triggerAfterCommit(status);
			} finally {
				// 无论 afterCommit 是否成功，都要触发 afterCompletion 回调
				// 状态为已提交
				triggerAfterCompletion(status, TransactionSynchronization.STATUS_COMMITTED);
			}

		}
		// 7. 最终清理工作
		finally {
			// 恢复被挂起的资源到当前线程中
			// 这通常发生在事务传播场景中，如 PROPAGATION_REQUIRES_NEW
			cleanupAfterCompletion(status);
		}
	}


	/**
	 * This implementation of rollback handles participating in existing
	 * transactions. Delegates to {@code doRollback} and
	 * {@code doSetRollbackOnly}.
	 *
	 * @see #doRollback
	 * @see #doSetRollbackOnly
	 */
	@Override
	public final void rollback(TransactionStatus status) throws TransactionException {
		if (status.isCompleted()) {
			throw new IllegalTransactionStateException(
					"Transaction is already completed - do not call commit or rollback more than once per transaction");
		}

		DefaultTransactionStatus defStatus = (DefaultTransactionStatus) status;
		// 处理回滚
		processRollback(defStatus, false);
	}

	/**
	 * 处理实际的事务回滚操作
	 * <p>
	 * 这个方法是事务回滚的核心实现，负责完整的回滚流程，包括：
	 * 1. 触发回滚前的回调
	 * 2. 根据事务类型执行相应的回滚操作
	 * 3. 处理各种异常情况
	 * 4. 触发回滚后的回调
	 * 5. 清理事务资源
	 *
	 * @param status     代表事务的对象，包含事务的所有状态信息
	 * @param unexpected 是否为意外回滚（由全局回滚标记引起的回滚）
	 * @throws TransactionException 回滚失败时抛出的事务异常
	 * @see #rollback(TransactionStatus)
	 * @see DefaultTransactionStatus
	 */
	private void processRollback(DefaultTransactionStatus status, boolean unexpected) {
		// 使用 try-finally 确保清理工作总是被执行
		try {
			// 初始化意外回滚标志，可能在后续处理中被修改
			boolean unexpectedRollback = unexpected;

			// 内层 try-catch 处理回滚过程中的异常
			try {
				// 1. 触发 beforeCompletion 回调
				// 通知所有注册的同步回调事务即将完成（这是回滚前的最后机会）
				// 提交会执行两个回调
				triggerBeforeCompletion(status);

				// 2. 根据事务状态执行相应的回滚操作

				// 2.1 如果是保存点事务（嵌套事务）
				// 比如mysql中的savepoint
				if (status.hasSavepoint()) {
					if (status.isDebug()) {
						logger.debug("Rolling back transaction to savepoint");
					}
					// 回滚到上一个保存点位置，相当于回滚嵌套事务
					// 这不会影响外层事务，只是回滚到保存点的状态
					status.rollbackToHeldSavepoint();
				}
				// 2.2 如果是新事务（顶层事务）
				else if (status.isNewTransaction()) {
					if (status.isDebug()) {
						logger.debug("Initiating transaction rollback");
					}
					// 执行实际的回滚操作，调用具体事务管理器的回滚实现
					// 这会触发真正的数据库回滚操作
					doRollback(status);
				}
				// 2.3 如果是参与现有事务的情况
				else {
					// Participating in larger transaction
					// 如果当前执行的方法，是公用了一个已存在的事务，而当前执行的方法抛了异常，则要判断整个事务到底要不要回滚，看具体配置

					// 检查是否存在事务对象
					if (status.hasTransaction()) {
						// 判断是否需要将现有事务标记为回滚-only

						// 如果一个事务中有两个方法，第二个方法抛异常了，那么第二个方法就相当于执行失败需要回滚，
						// 如果globalRollbackOnParticipationFailure为true，那么第一个方法在没有抛异常的情况下也要回滚
						if (status.isLocalRollbackOnly() || isGlobalRollbackOnParticipationFailure()) {
							if (status.isDebug()) {
								logger.debug("Participating transaction failed - marking existing transaction as rollback-only");
							}
							// 直接将rollbackOnly设置到ConnectionHolder中去，表示整个事务的sql都要回滚
							// 不执行实际回滚，而是标记事务为回滚-only状态
							// 外层事务在提交时会检查这个标记并执行回滚
							doSetRollbackOnly(status);
						} else {
							if (status.isDebug()) {
								logger.debug("Participating transaction failed - letting transaction originator decide on rollback");
							}
							// 不设置回滚标记，让外层事务的发起者决定是否回滚
							// 这允许更细粒度的事务控制
						}
					} else {
						// 没有可用的事务对象
						logger.debug("Should roll back transaction but cannot - no transaction available");
					}

					// Unexpected rollback only matters here if we're asked to fail early
					// 如果不需要提前失败，则重置意外回滚标志
					if (!isFailEarlyOnGlobalRollbackOnly()) {
						unexpectedRollback = false;
					}
				}
			}
			// 处理运行时异常和错误
			catch (RuntimeException | Error ex) {
				// 触发事务完成后的回调，状态为未知
				// 这确保即使在回滚过程中出现异常，同步回调也能得到通知
				triggerAfterCompletion(status, TransactionSynchronization.STATUS_UNKNOWN);
				throw ex;
			}

			// 3. 回滚成功后的处理

			// 触发 afterCompletion 回调，状态为已回滚
			// 通知所有注册的同步回调事务已经回滚完成
			triggerAfterCompletion(status, TransactionSynchronization.STATUS_ROLLED_BACK);

			// 4. 处理意外回滚情况

			// Raise UnexpectedRollbackException if we had a global rollback-only marker
			// 如果是意外回滚（由全局回滚标记引起的），则抛出 UnexpectedRollbackException
			// 这通常发生在参与现有事务且被标记为回滚-only的情况下
			if (unexpectedRollback) {
				throw new UnexpectedRollbackException(
						"Transaction rolled back because it has been marked as rollback-only");
			}
		}
		// 5. 最终清理工作
		finally {
			// 无论回滚成功与否，都要执行清理工作
			// 这包括清除同步状态、关闭资源、恢复挂起的事务等
			cleanupAfterCompletion(status);
		}
	}


	/**
	 * Invoke {@code doRollback}, handling rollback exceptions properly.
	 *
	 * @param status object representing the transaction
	 * @param ex     the thrown application exception or error
	 * @throws TransactionException in case of rollback failure
	 * @see #doRollback
	 */
	private void doRollbackOnCommitException(DefaultTransactionStatus status, Throwable ex) throws TransactionException {
		try {
			if (status.isNewTransaction()) {
				if (status.isDebug()) {
					logger.debug("Initiating transaction rollback after commit exception", ex);
				}
				doRollback(status);
			} else if (status.hasTransaction() && isGlobalRollbackOnParticipationFailure()) {
				if (status.isDebug()) {
					logger.debug("Marking existing transaction as rollback-only after commit exception", ex);
				}
				doSetRollbackOnly(status);
			}
		} catch (RuntimeException | Error rbex) {
			logger.error("Commit exception overridden by rollback exception", ex);
			triggerAfterCompletion(status, TransactionSynchronization.STATUS_UNKNOWN);
			throw rbex;
		}
		triggerAfterCompletion(status, TransactionSynchronization.STATUS_ROLLED_BACK);
	}


	/**
	 * Trigger {@code beforeCommit} callbacks.
	 *
	 * @param status object representing the transaction
	 */
	protected final void triggerBeforeCommit(DefaultTransactionStatus status) {
		if (status.isNewSynchronization()) {
			TransactionSynchronizationUtils.triggerBeforeCommit(status.isReadOnly());
		}
	}

	/**
	 * Trigger {@code beforeCompletion} callbacks.
	 *
	 * @param status object representing the transaction
	 */
	protected final void triggerBeforeCompletion(DefaultTransactionStatus status) {
		if (status.isNewSynchronization()) {
			TransactionSynchronizationUtils.triggerBeforeCompletion();
		}
	}

	/**
	 * Trigger {@code afterCommit} callbacks.
	 *
	 * @param status object representing the transaction
	 */
	private void triggerAfterCommit(DefaultTransactionStatus status) {
		if (status.isNewSynchronization()) {
			TransactionSynchronizationUtils.triggerAfterCommit();
		}
	}

	/**
	 * Trigger {@code afterCompletion} callbacks.
	 *
	 * @param status           object representing the transaction
	 * @param completionStatus completion status according to TransactionSynchronization constants
	 */
	private void triggerAfterCompletion(DefaultTransactionStatus status, int completionStatus) {
		if (status.isNewSynchronization()) {
			List<TransactionSynchronization> synchronizations = TransactionSynchronizationManager.getSynchronizations();
			TransactionSynchronizationManager.clearSynchronization();
			if (!status.hasTransaction() || status.isNewTransaction()) {
				// No transaction or new transaction for the current scope ->
				// invoke the afterCompletion callbacks immediately
				invokeAfterCompletion(synchronizations, completionStatus);
			} else if (!synchronizations.isEmpty()) {
				// Existing transaction that we participate in, controlled outside
				// of the scope of this Spring transaction manager -> try to register
				// an afterCompletion callback with the existing (JTA) transaction.
				registerAfterCompletionWithExistingTransaction(status.getTransaction(), synchronizations);
			}
		}
	}

	/**
	 * Actually invoke the {@code afterCompletion} methods of the
	 * given Spring TransactionSynchronization objects.
	 * <p>To be called by this abstract manager itself, or by special implementations
	 * of the {@code registerAfterCompletionWithExistingTransaction} callback.
	 *
	 * @param synchronizations a List of TransactionSynchronization objects
	 * @param completionStatus the completion status according to the
	 *                         constants in the TransactionSynchronization interface
	 * @see #registerAfterCompletionWithExistingTransaction(Object, java.util.List)
	 * @see TransactionSynchronization#STATUS_COMMITTED
	 * @see TransactionSynchronization#STATUS_ROLLED_BACK
	 * @see TransactionSynchronization#STATUS_UNKNOWN
	 */
	protected final void invokeAfterCompletion(List<TransactionSynchronization> synchronizations, int completionStatus) {
		TransactionSynchronizationUtils.invokeAfterCompletion(synchronizations, completionStatus);
	}

	/**
	 * 事务完成后进行清理工作，包括清除同步状态、执行资源清理和恢复被挂起的事务
	 * <p>
	 * 这是事务处理流程的最后一步，确保所有资源得到正确释放和清理
	 *
	 * @param status 代表已完成事务的对象，包含事务的所有状态信息
	 * @see DefaultTransactionStatus#setCompleted()
	 * @see TransactionSynchronizationManager#clear()
	 * @see #doCleanupAfterCompletion(Object)
	 * @see #resume(Object, SuspendedResourcesHolder)
	 */
	private void cleanupAfterCompletion(DefaultTransactionStatus status) {
		// 1. 标记事务为已完成状态
		// 设置 status.completed = true，防止重复提交或回滚
		status.setCompleted();

		// 2. 清理事务同步环境
		// 如果当前事务创建了新的同步环境，则需要清理
		if (status.isNewSynchronization()) {
			// 清除当前线程的事务同步状态
			// 包括：清空 synchronizations ThreadLocal
			//      重置事务相关属性（名称、只读状态、隔离级别等）
			TransactionSynchronizationManager.clear();
		}

		// 3. 执行具体事务管理器的清理工作
		// 如果是新事务（顶层事务），则执行资源清理
		if (status.isNewTransaction()) {
			// 调用具体事务管理器的清理方法
			// 对于 DataSourceTransactionManager，这里会：
			// - 恢复连接的自动提交设置
			// - 重置连接的隔离级别和只读状态
			// - 关闭数据库连接（返回给连接池）
			// - 清理 ConnectionHolder
			doCleanupAfterCompletion(status.getTransaction());
		}

		// 4. 恢复被挂起的事务
		// 检查是否有被挂起的资源需要恢复
		// 这通常发生在事务传播场景中，如 PROPAGATION_REQUIRES_NEW
		if (status.getSuspendedResources() != null) {
			if (status.isDebug()) {
				logger.debug("Resuming suspended transaction after completion of inner transaction");
			}
			// 确定要恢复的事务对象
			// 如果当前状态还有事务对象则使用它，否则为 null
			Object transaction = (status.hasTransaction() ? status.getTransaction() : null);

			// 恢复被挂起的事务资源
			// 这包括：
			// - 恢复数据库连接到当前线程
			// - 恢复事务同步回调
			// - 恢复事务属性（名称、只读状态、隔离级别等）
			resume(transaction, (SuspendedResourcesHolder) status.getSuspendedResources());
		}
	}


	//---------------------------------------------------------------------
	// Template methods to be implemented in subclasses
	//---------------------------------------------------------------------

	/**
	 * Return a transaction object for the current transaction state.
	 * <p>The returned object will usually be specific to the concrete transaction
	 * manager implementation, carrying corresponding transaction state in a
	 * modifiable fashion. This object will be passed into the other template
	 * methods (e.g. doBegin and doCommit), either directly or as part of a
	 * DefaultTransactionStatus instance.
	 * <p>The returned object should contain information about any existing
	 * transaction, that is, a transaction that has already started before the
	 * current {@code getTransaction} call on the transaction manager.
	 * Consequently, a {@code doGetTransaction} implementation will usually
	 * look for an existing transaction and store corresponding state in the
	 * returned transaction object.
	 *
	 * @return the current transaction object
	 * @throws org.springframework.transaction.CannotCreateTransactionException if transaction support is not available
	 * @throws TransactionException                                             in case of lookup or system errors
	 * @see #doBegin
	 * @see #doCommit
	 * @see #doRollback
	 * @see DefaultTransactionStatus#getTransaction
	 */
	protected abstract Object doGetTransaction() throws TransactionException;

	/**
	 * Check if the given transaction object indicates an existing transaction
	 * (that is, a transaction which has already started).
	 * <p>The result will be evaluated according to the specified propagation
	 * behavior for the new transaction. An existing transaction might get
	 * suspended (in case of PROPAGATION_REQUIRES_NEW), or the new transaction
	 * might participate in the existing one (in case of PROPAGATION_REQUIRED).
	 * <p>The default implementation returns {@code false}, assuming that
	 * participating in existing transactions is generally not supported.
	 * Subclasses are of course encouraged to provide such support.
	 *
	 * @param transaction the transaction object returned by doGetTransaction
	 * @return if there is an existing transaction
	 * @throws TransactionException in case of system errors
	 * @see #doGetTransaction
	 */
	protected boolean isExistingTransaction(Object transaction) throws TransactionException {
		return false;
	}

	/**
	 * Return whether to use a savepoint for a nested transaction.
	 * <p>Default is {@code true}, which causes delegation to DefaultTransactionStatus
	 * for creating and holding a savepoint. If the transaction object does not implement
	 * the SavepointManager interface, a NestedTransactionNotSupportedException will be
	 * thrown. Else, the SavepointManager will be asked to create a new savepoint to
	 * demarcate the start of the nested transaction.
	 * <p>Subclasses can override this to return {@code false}, causing a further
	 * call to {@code doBegin} - within the context of an already existing transaction.
	 * The {@code doBegin} implementation needs to handle this accordingly in such
	 * a scenario. This is appropriate for JTA, for example.
	 *
	 * @see DefaultTransactionStatus#createAndHoldSavepoint
	 * @see DefaultTransactionStatus#rollbackToHeldSavepoint
	 * @see DefaultTransactionStatus#releaseHeldSavepoint
	 * @see #doBegin
	 */
	protected boolean useSavepointForNestedTransaction() {
		return true;
	}

	/**
	 * Begin a new transaction with semantics according to the given transaction
	 * definition. Does not have to care about applying the propagation behavior,
	 * as this has already been handled by this abstract manager.
	 * <p>This method gets called when the transaction manager has decided to actually
	 * start a new transaction. Either there wasn't any transaction before, or the
	 * previous transaction has been suspended.
	 * <p>A special scenario is a nested transaction without savepoint: If
	 * {@code useSavepointForNestedTransaction()} returns "false", this method
	 * will be called to start a nested transaction when necessary. In such a context,
	 * there will be an active transaction: The implementation of this method has
	 * to detect this and start an appropriate nested transaction.
	 *
	 * @param transaction the transaction object returned by {@code doGetTransaction}
	 * @param definition  a TransactionDefinition instance, describing propagation
	 *                    behavior, isolation level, read-only flag, timeout, and transaction name
	 * @throws TransactionException                                                   in case of creation or system errors
	 * @throws org.springframework.transaction.NestedTransactionNotSupportedException if the underlying transaction does not support nesting
	 */
	protected abstract void doBegin(Object transaction, TransactionDefinition definition)
			throws TransactionException;

	/**
	 * Suspend the resources of the current transaction.
	 * Transaction synchronization will already have been suspended.
	 * <p>The default implementation throws a TransactionSuspensionNotSupportedException,
	 * assuming that transaction suspension is generally not supported.
	 *
	 * @param transaction the transaction object returned by {@code doGetTransaction}
	 * @return an object that holds suspended resources
	 * (will be kept unexamined for passing it into doResume)
	 * @throws org.springframework.transaction.TransactionSuspensionNotSupportedException if suspending is not supported by the transaction manager implementation
	 * @throws TransactionException                                                       in case of system errors
	 * @see #doResume
	 */
	protected Object doSuspend(Object transaction) throws TransactionException {
		throw new TransactionSuspensionNotSupportedException(
				"Transaction manager [" + getClass().getName() + "] does not support transaction suspension");
	}

	/**
	 * Resume the resources of the current transaction.
	 * Transaction synchronization will be resumed afterwards.
	 * <p>The default implementation throws a TransactionSuspensionNotSupportedException,
	 * assuming that transaction suspension is generally not supported.
	 *
	 * @param transaction        the transaction object returned by {@code doGetTransaction}
	 * @param suspendedResources the object that holds suspended resources,
	 *                           as returned by doSuspend
	 * @throws org.springframework.transaction.TransactionSuspensionNotSupportedException if resuming is not supported by the transaction manager implementation
	 * @throws TransactionException                                                       in case of system errors
	 * @see #doSuspend
	 */
	protected void doResume(@Nullable Object transaction, Object suspendedResources) throws TransactionException {
		throw new TransactionSuspensionNotSupportedException(
				"Transaction manager [" + getClass().getName() + "] does not support transaction suspension");
	}

	/**
	 * Return whether to call {@code doCommit} on a transaction that has been
	 * marked as rollback-only in a global fashion.
	 * <p>Does not apply if an application locally sets the transaction to rollback-only
	 * via the TransactionStatus, but only to the transaction itself being marked as
	 * rollback-only by the transaction coordinator.
	 * <p>Default is "false": Local transaction strategies usually don't hold the rollback-only
	 * marker in the transaction itself, therefore they can't handle rollback-only transactions
	 * as part of transaction commit. Hence, AbstractPlatformTransactionManager will trigger
	 * a rollback in that case, throwing an UnexpectedRollbackException afterwards.
	 * <p>Override this to return "true" if the concrete transaction manager expects a
	 * {@code doCommit} call even for a rollback-only transaction, allowing for
	 * special handling there. This will, for example, be the case for JTA, where
	 * {@code UserTransaction.commit} will check the read-only flag itself and
	 * throw a corresponding RollbackException, which might include the specific reason
	 * (such as a transaction timeout).
	 * <p>If this method returns "true" but the {@code doCommit} implementation does not
	 * throw an exception, this transaction manager will throw an UnexpectedRollbackException
	 * itself. This should not be the typical case; it is mainly checked to cover misbehaving
	 * JTA providers that silently roll back even when the rollback has not been requested
	 * by the calling code.
	 *
	 * @see #doCommit
	 * @see DefaultTransactionStatus#isGlobalRollbackOnly()
	 * @see DefaultTransactionStatus#isLocalRollbackOnly()
	 * @see org.springframework.transaction.TransactionStatus#setRollbackOnly()
	 * @see org.springframework.transaction.UnexpectedRollbackException
	 * @see javax.transaction.UserTransaction#commit()
	 * @see javax.transaction.RollbackException
	 */
	protected boolean shouldCommitOnGlobalRollbackOnly() {
		return false;
	}

	/**
	 * Make preparations for commit, to be performed before the
	 * {@code beforeCommit} synchronization callbacks occur.
	 * <p>Note that exceptions will get propagated to the commit caller
	 * and cause a rollback of the transaction.
	 *
	 * @param status the status representation of the transaction
	 * @throws RuntimeException in case of errors; will be <b>propagated to the caller</b>
	 *                          (note: do not throw TransactionException subclasses here!)
	 */
	protected void prepareForCommit(DefaultTransactionStatus status) {
	}

	/**
	 * Perform an actual commit of the given transaction.
	 * <p>An implementation does not need to check the "new transaction" flag
	 * or the rollback-only flag; this will already have been handled before.
	 * Usually, a straight commit will be performed on the transaction object
	 * contained in the passed-in status.
	 *
	 * @param status the status representation of the transaction
	 * @throws TransactionException in case of commit or system errors
	 * @see DefaultTransactionStatus#getTransaction
	 */
	protected abstract void doCommit(DefaultTransactionStatus status) throws TransactionException;

	/**
	 * Perform an actual rollback of the given transaction.
	 * <p>An implementation does not need to check the "new transaction" flag;
	 * this will already have been handled before. Usually, a straight rollback
	 * will be performed on the transaction object contained in the passed-in status.
	 *
	 * @param status the status representation of the transaction
	 * @throws TransactionException in case of system errors
	 * @see DefaultTransactionStatus#getTransaction
	 */
	protected abstract void doRollback(DefaultTransactionStatus status) throws TransactionException;

	/**
	 * Set the given transaction rollback-only. Only called on rollback
	 * if the current transaction participates in an existing one.
	 * <p>The default implementation throws an IllegalTransactionStateException,
	 * assuming that participating in existing transactions is generally not
	 * supported. Subclasses are of course encouraged to provide such support.
	 *
	 * @param status the status representation of the transaction
	 * @throws TransactionException in case of system errors
	 */
	protected void doSetRollbackOnly(DefaultTransactionStatus status) throws TransactionException {
		throw new IllegalTransactionStateException(
				"Participating in existing transactions is not supported - when 'isExistingTransaction' " +
						"returns true, appropriate 'doSetRollbackOnly' behavior must be provided");
	}

	/**
	 * Register the given list of transaction synchronizations with the existing transaction.
	 * <p>Invoked when the control of the Spring transaction manager and thus all Spring
	 * transaction synchronizations end, without the transaction being completed yet. This
	 * is for example the case when participating in an existing JTA or EJB CMT transaction.
	 * <p>The default implementation simply invokes the {@code afterCompletion} methods
	 * immediately, passing in "STATUS_UNKNOWN". This is the best we can do if there's no
	 * chance to determine the actual outcome of the outer transaction.
	 *
	 * @param transaction      the transaction object returned by {@code doGetTransaction}
	 * @param synchronizations a List of TransactionSynchronization objects
	 * @throws TransactionException in case of system errors
	 * @see #invokeAfterCompletion(java.util.List, int)
	 * @see TransactionSynchronization#afterCompletion(int)
	 * @see TransactionSynchronization#STATUS_UNKNOWN
	 */
	protected void registerAfterCompletionWithExistingTransaction(
			Object transaction, List<TransactionSynchronization> synchronizations) throws TransactionException {

		logger.debug("Cannot register Spring after-completion synchronization with existing transaction - " +
				"processing Spring after-completion callbacks immediately, with outcome status 'unknown'");
		invokeAfterCompletion(synchronizations, TransactionSynchronization.STATUS_UNKNOWN);
	}

	/**
	 * Cleanup resources after transaction completion.
	 * <p>Called after {@code doCommit} and {@code doRollback} execution,
	 * on any outcome. The default implementation does nothing.
	 * <p>Should not throw any exceptions but just issue warnings on errors.
	 *
	 * @param transaction the transaction object returned by {@code doGetTransaction}
	 */
	protected void doCleanupAfterCompletion(Object transaction) {
	}


	//---------------------------------------------------------------------
	// Serialization support
	//---------------------------------------------------------------------

	private void readObject(ObjectInputStream ois) throws IOException, ClassNotFoundException {
		// Rely on default serialization; just initialize state after deserialization.
		ois.defaultReadObject();

		// Initialize transient fields.
		this.logger = LogFactory.getLog(getClass());
	}


	/**
	 * Holder for suspended resources.
	 * Used internally by {@code suspend} and {@code resume}.
	 */
	protected static final class SuspendedResourcesHolder {

		@Nullable
		private final Object suspendedResources;

		@Nullable
		private List<TransactionSynchronization> suspendedSynchronizations;

		@Nullable
		private String name;

		private boolean readOnly;

		@Nullable
		private Integer isolationLevel;

		private boolean wasActive;

		private SuspendedResourcesHolder(Object suspendedResources) {
			this.suspendedResources = suspendedResources;
		}

		/**
		 * 构造函数，用于创建保存被挂起事务资源的持有者对象
		 *
		 * @param suspendedResources        被挂起的事务资源（通常是数据库连接持有者ConnectionHolder）
		 * @param suspendedSynchronizations 被挂起的事务同步回调列表
		 * @param name                      被挂起事务的名称
		 * @param readOnly                  被挂起事务的只读状态
		 * @param isolationLevel            被挂起事务的隔离级别
		 * @param wasActive                 被挂起事务的活跃状态（是否是实际的事务）
		 */
		private SuspendedResourcesHolder(
				@Nullable Object suspendedResources,
				List<TransactionSynchronization> suspendedSynchronizations,
				@Nullable String name,
				boolean readOnly,
				@Nullable Integer isolationLevel,
				boolean wasActive) {

			// 被挂起的事务资源（如数据库连接持有者）
			this.suspendedResources = suspendedResources;

			// 被挂起的事务同步回调对象列表
			this.suspendedSynchronizations = suspendedSynchronizations;

			// 被挂起事务的名称
			this.name = name;

			// 被挂起事务的只读状态
			this.readOnly = readOnly;

			// 被挂起事务的隔离级别
			this.isolationLevel = isolationLevel;

			// 被挂起事务的活跃状态标识
			this.wasActive = wasActive;
		}

	}

}
