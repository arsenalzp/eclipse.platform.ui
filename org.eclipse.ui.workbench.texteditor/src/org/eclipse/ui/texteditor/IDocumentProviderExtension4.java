/*******************************************************************************
 * Copyright (c) 2000, 2004 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials 
 * are made available under the terms of the Common Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/cpl-v10.html
 * 
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.ui.texteditor;

import org.eclipse.core.runtime.content.IContentDescription;


/**
 * Extension interface for {@link org.eclipse.ui.texteditor.IDocumentProvider}.
 * Extends a document provider with the ability to query the content description
 * of a given element.
 * <p>
 * This interface may be implemented by clients.
 * </p><p>
 * Not yet for public use. API under construction.
 * </p>
 * 
 * @see org.eclipse.ui.texteditor.IDocumentProvider
 * @since 3.1
 */
public interface IDocumentProviderExtension4 {
	
	/**
	 * Returns the content description for the given element or
	 * <code>null</code> if none could be found.
	 * <p>
	 * Not yet for public use. API under construction.
	 * </p>
	 * 
	 * @param element the element
	 * @return the content description or <code>null</code>
	 */
	IContentDescription getContentDescription(Object element);
}
