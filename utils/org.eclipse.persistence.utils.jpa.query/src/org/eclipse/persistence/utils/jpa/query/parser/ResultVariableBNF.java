/*******************************************************************************
 * Copyright (c) 2006, 2011 Oracle. All rights reserved.
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 and Eclipse Distribution License v. 1.0
 * which accompanies this distribution.
 * The Eclipse Public License is available at http://www.eclipse.org/legal/epl-v10.html
 * and the Eclipse Distribution License is available at
 * http://www.eclipse.org/org/documents/edl-v10.php.
 *
 * Contributors:
 *     Oracle - initial API and implementation
 *
 ******************************************************************************/
package org.eclipse.persistence.utils.jpa.query.parser;

/**
 * The query BNF for a result variable expression.
 *
 * @version 11.2.0
 * @since 11.2.0
 * @author Pascal Filion
 */
@SuppressWarnings("nls")
final class ResultVariableBNF extends JPQLQueryBNF {

	/**
	 * The unique identifier of this BNF rule.
	 */
	static final String ID = "result_variable";

	/**
	 * Creates a new <code>ResultVariableBNF</code>.
	 */
	ResultVariableBNF() {
		super(ID);
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	void initialize() {
		super.initialize();
		registerExpressionFactory(ResultVariableFactory.ID);
	}
}