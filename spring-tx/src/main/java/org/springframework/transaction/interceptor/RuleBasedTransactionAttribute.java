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

package org.springframework.transaction.interceptor;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

import org.springframework.lang.Nullable;

/**
 * TransactionAttribute implementation that works out whether a given exception
 * should cause transaction rollback by applying a number of rollback rules,
 * both positive and negative. If no custom rollback rules apply, this attribute
 * behaves like DefaultTransactionAttribute (rolling back on runtime exceptions).
 *
 * <p>{@link TransactionAttributeEditor} creates objects of this class.
 *
 * @author Rod Johnson
 * @author Juergen Hoeller
 * @since 09.04.2003
 * @see TransactionAttributeEditor
 */
@SuppressWarnings("serial")
public class RuleBasedTransactionAttribute extends DefaultTransactionAttribute implements Serializable {

	/** Prefix for rollback-on-exception rules in description strings. */
	public static final String PREFIX_ROLLBACK_RULE = "-";

	/** Prefix for commit-on-exception rules in description strings. */
	public static final String PREFIX_COMMIT_RULE = "+";


	@Nullable
	private List<RollbackRuleAttribute> rollbackRules;


	/**
	 * Create a new RuleBasedTransactionAttribute, with default settings.
	 * Can be modified through bean property setters.
	 * @see #setPropagationBehavior
	 * @see #setIsolationLevel
	 * @see #setTimeout
	 * @see #setReadOnly
	 * @see #setName
	 * @see #setRollbackRules
	 */
	public RuleBasedTransactionAttribute() {
		super();
	}

	/**
	 * Copy constructor. Definition can be modified through bean property setters.
	 * @see #setPropagationBehavior
	 * @see #setIsolationLevel
	 * @see #setTimeout
	 * @see #setReadOnly
	 * @see #setName
	 * @see #setRollbackRules
	 */
	public RuleBasedTransactionAttribute(RuleBasedTransactionAttribute other) {
		super(other);
		this.rollbackRules = (other.rollbackRules != null ? new ArrayList<>(other.rollbackRules) : null);
	}

	/**
	 * Create a new DefaultTransactionAttribute with the given
	 * propagation behavior. Can be modified through bean property setters.
	 * @param propagationBehavior one of the propagation constants in the
	 * TransactionDefinition interface
	 * @param rollbackRules the list of RollbackRuleAttributes to apply
	 * @see #setIsolationLevel
	 * @see #setTimeout
	 * @see #setReadOnly
	 */
	public RuleBasedTransactionAttribute(int propagationBehavior, List<RollbackRuleAttribute> rollbackRules) {
		super(propagationBehavior);
		this.rollbackRules = rollbackRules;
	}


	/**
	 * Set the list of {@code RollbackRuleAttribute} objects
	 * (and/or {@code NoRollbackRuleAttribute} objects) to apply.
	 * @see RollbackRuleAttribute
	 * @see NoRollbackRuleAttribute
	 */
	public void setRollbackRules(List<RollbackRuleAttribute> rollbackRules) {
		this.rollbackRules = rollbackRules;
	}

	/**
	 * Return the list of {@code RollbackRuleAttribute} objects
	 * (never {@code null}).
	 */
	public List<RollbackRuleAttribute> getRollbackRules() {
		if (this.rollbackRules == null) {
			this.rollbackRules = new ArrayList<>();
		}
		return this.rollbackRules;
	}


	/**
 * 判断给定的异常是否应该触发事务回滚
 *
 * 这个方法实现了基于回滚规则的事务回滚决策逻辑：
 * 1. 遍历所有配置的回滚规则，找到最匹配的规则
 * 2. 如果找到匹配规则，则根据规则类型决定是否回滚
 * 3. 如果没有匹配规则，则使用父类的默认回滚策略
 *
 * 匹配规则采用"最浅匹配"原则：即选择继承层次中最接近异常类型的规则
 *
 * @param ex 当前抛出的异常
 * @return true表示应该回滚事务，false表示不应该回滚事务
 *
 * @see TransactionAttribute#rollbackOn(Throwable)
 * @see DefaultTransactionAttribute#rollbackOn(Throwable)
 * @see RollbackRuleAttribute#getDepth(Throwable)
 * @see NoRollbackRuleAttribute
 */
@Override
public boolean rollbackOn(Throwable ex) {
    // 最佳匹配的回滚规则
    RollbackRuleAttribute winner = null;

    // 记录最浅匹配的继承深度，初始值设为最大整数
    // 继承深度越小表示规则越具体，匹配度越高
    int deepest = Integer.MAX_VALUE;

    // 检查是否配置了回滚规则
    if (this.rollbackRules != null) {
        // 遍历所有的回滚规则，寻找最匹配的规则
        // 遍历所有的RollbackRuleAttribute，判断现在抛出的异常ex是否匹配RollbackRuleAttribute中指定的异常类型的子类或本身
        for (RollbackRuleAttribute rule : this.rollbackRules) {
            // 获取当前规则与异常的匹配深度
            // getDepth返回值含义：
            // -1: 不匹配
            // 0: 异常类型完全匹配
            // >0: 异常是规则指定类型的子类，数值表示继承层级深度
            int depth = rule.getDepth(ex);

            // 如果匹配成功且比之前的匹配更浅（更具体）
            if (depth >= 0 && depth < deepest) {
                // 更新最浅匹配深度
                deepest = depth;
                // 记录当前最佳匹配规则
                winner = rule;
            }
        }
    }

    // 如果没有找到任何匹配的规则，则使用父类的默认行为
    // User superclass behavior (rollback on unchecked) if no rule matches.
    // DefaultTransactionAttribute的默认策略：对运行时异常和错误进行回滚，对检查异常不回滚
    if (winner == null) {
        return super.rollbackOn(ex);
    }

    // 根据匹配到的最佳规则决定是否回滚
    // ex所匹配的RollbackRuleAttribute，可能是NoRollbackRuleAttribute，如果是匹配的NoRollbackRuleAttribute，那就表示现在这个异常ex不用回滚

    // 判断匹配到的规则类型：
    // 1. 如果是 RollbackRuleAttribute（普通回滚规则）：返回 true，表示需要回滚
    // 2. 如果是 NoRollbackRuleAttribute（不回滚规则）：返回 false，表示不需要回滚
    return !(winner instanceof NoRollbackRuleAttribute);
}



	@Override
	public String toString() {
		StringBuilder result = getAttributeDescription();
		if (this.rollbackRules != null) {
			for (RollbackRuleAttribute rule : this.rollbackRules) {
				String sign = (rule instanceof NoRollbackRuleAttribute ? PREFIX_COMMIT_RULE : PREFIX_ROLLBACK_RULE);
				result.append(',').append(sign).append(rule.getExceptionName());
			}
		}
		return result.toString();
	}

}
