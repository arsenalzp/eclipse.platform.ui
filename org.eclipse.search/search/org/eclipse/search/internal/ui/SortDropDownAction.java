/*
 * (c) Copyright IBM Corp. 2000, 2001.
 * All Rights Reserved.
 */
package org.eclipse.search.internal.ui;

import java.util.Iterator;

import org.eclipse.swt.custom.BusyIndicator;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Menu;

import org.eclipse.jface.action.Action;
import org.eclipse.jface.action.ActionContributionItem;
import org.eclipse.jface.action.IMenuCreator;
import org.eclipse.jface.viewers.ViewerSorter;

/**
 * Drop down action that holds the currently registered sort actions.
 */
class SortDropDownAction extends Action implements IMenuCreator {

	private SearchResultViewer fViewer;
	private String fPageId;
	private Menu fMenu;
	private String fCheckedId;

	public SortDropDownAction(SearchResultViewer viewer) {
		super(SearchMessages.getString("SortDropDownAction.label")); //$NON-NLS-1$
		SearchPluginImages.setImageDescriptors(this, SearchPluginImages.T_LCL, SearchPluginImages.IMG_LCL_SEARCH_SORT);
		fViewer= viewer;
		fCheckedId= ""; //$NON-NLS-1$
		setToolTipText(SearchMessages.getString("SortDropDownAction.tooltip")); //$NON-NLS-1$
		setMenuCreator(this);
	}

	public void dispose() {
		fViewer= null;
		fPageId= null;
		fCheckedId= null;
	}

	public Menu getMenu(Control parent) {
		return null;
	}

	void setPageId(String pageId) {
		fPageId= pageId;
	}

	public Menu getMenu(final Menu parent) {
		boolean hasEntries= false;
		Menu menu= new Menu(parent);
		Iterator iter= SearchPlugin.getDefault().getSorterDescriptors().iterator();
		while (iter.hasNext()) {
			final SorterDescriptor sorterDesc= (SorterDescriptor) iter.next();
			if (!sorterDesc.getPageId().equals(fPageId) && !sorterDesc.getPageId().equals("*")) //$NON-NLS-1$
				continue;
			final ViewerSorter sorter= sorterDesc.createObject();
			if (sorter != null) {
				final Action action= new Action() {
					public void run() {
						if (!fCheckedId.equals(sorterDesc.getId())) {
							fCheckedId= sorterDesc.getId();
							BusyIndicator.showWhile(parent.getDisplay(), new Runnable() {
								public void run() {
									fViewer.setSorter(sorter);
								}
							});
						}
					}
				};
				action.setText(sorterDesc.getLabel());
				action.setImageDescriptor(sorterDesc.getImage());
				action.setToolTipText(sorterDesc.getToolTipText());
				action.setChecked(fCheckedId.equals(sorterDesc.getId()));
				addActionToMenu(menu, action);
				hasEntries= true;
			}
		}
		setEnabled(hasEntries);
		return menu;
	}

	protected void addActionToMenu(Menu parent, Action action) {
		ActionContributionItem item= new ActionContributionItem(action);
		item.fill(parent, -1);
	}

    public void run() {
		// nothing to do
	    }
}

