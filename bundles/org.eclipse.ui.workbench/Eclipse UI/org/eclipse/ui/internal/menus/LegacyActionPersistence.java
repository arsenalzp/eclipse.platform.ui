/*******************************************************************************
 * Copyright (c) 2005 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 ******************************************************************************/

package org.eclipse.ui.internal.menus;

import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import org.eclipse.core.commands.Category;
import org.eclipse.core.commands.Command;
import org.eclipse.core.commands.CommandManager;
import org.eclipse.core.commands.IState;
import org.eclipse.core.commands.ParameterizedCommand;
import org.eclipse.core.commands.common.NotDefinedException;
import org.eclipse.core.expressions.Expression;
import org.eclipse.core.runtime.IConfigurationElement;
import org.eclipse.core.runtime.IExtensionRegistry;
import org.eclipse.core.runtime.IRegistryChangeEvent;
import org.eclipse.core.runtime.Platform;
import org.eclipse.jface.action.Action;
import org.eclipse.jface.action.LegacyActionTools;
import org.eclipse.jface.bindings.Binding;
import org.eclipse.jface.bindings.BindingManager;
import org.eclipse.jface.bindings.Scheme;
import org.eclipse.jface.bindings.keys.IKeyLookup;
import org.eclipse.jface.bindings.keys.KeyBinding;
import org.eclipse.jface.bindings.keys.KeyLookupFactory;
import org.eclipse.jface.bindings.keys.KeySequence;
import org.eclipse.jface.bindings.keys.KeyStroke;
import org.eclipse.jface.commands.CommandImageManager;
import org.eclipse.jface.commands.RadioState;
import org.eclipse.jface.commands.ToggleState;
import org.eclipse.jface.contexts.IContextIds;
import org.eclipse.jface.menus.IMenuStateIds;
import org.eclipse.jface.menus.IWidget;
import org.eclipse.jface.menus.LeafLocationElement;
import org.eclipse.jface.menus.LocationElement;
import org.eclipse.jface.menus.SActionSet;
import org.eclipse.jface.menus.SBar;
import org.eclipse.jface.menus.SGroup;
import org.eclipse.jface.menus.SItem;
import org.eclipse.jface.menus.SLocation;
import org.eclipse.jface.menus.SMenu;
import org.eclipse.jface.menus.SReference;
import org.eclipse.jface.menus.SWidget;
import org.eclipse.ui.ISourceProvider;
import org.eclipse.ui.LegacyHandlerSubmissionExpression;
import org.eclipse.ui.SelectionEnabler;
import org.eclipse.ui.commands.ICommandImageService;
import org.eclipse.ui.handlers.IHandlerService;
import org.eclipse.ui.internal.ActionExpression;
import org.eclipse.ui.internal.WorkbenchMessages;
import org.eclipse.ui.internal.handlers.ActionDelegateHandlerProxy;
import org.eclipse.ui.internal.services.LegacySelectionEnablerWrapper;
import org.eclipse.ui.internal.services.RegistryPersistence;
import org.eclipse.ui.internal.util.BundleUtility;
import org.eclipse.ui.menus.IMenuContribution;
import org.eclipse.ui.menus.IMenuService;

/**
 * <p>
 * A static class for reading actions from the registry. Actions were the
 * mechanism in 3.1 and earlier for contributing to menus and tool bars in the
 * Eclipse workbench. They have since been replaced with commands.
 * </p>
 * <p>
 * This class is not intended for use outside of the
 * <code>org.eclipse.ui.workbench</code> plug-in.
 * </p>
 * <p>
 * <strong>EXPERIMENTAL</strong>. This class or interface has been added as
 * part of a work in progress. There is a guarantee neither that this API will
 * work nor that it will remain the same. Please do not use this API without
 * consulting with the Platform/UI team.
 * </p>
 * 
 * @since 3.2
 */
public final class LegacyActionPersistence extends RegistryPersistence {

	/**
	 * The handler activations that have come from the registry. This is used to
	 * flush the activations when the registry is re-read. This value is never
	 * <code>null</code>
	 */
	private static final Collection handlerActivations = new ArrayList();

	/**
	 * The index of the action set elements in the indexed array.
	 * 
	 * @see LegacyActionPersistence#read()
	 */
	private static final int INDEX_ACTION_SETS = 0;

	/**
	 * The index of the editor contribution elements in the indexed array.
	 * 
	 * @see LegacyActionPersistence#read()
	 */
	private static final int INDEX_EDITOR_CONTRIBUTIONS = 1;

	/**
	 * The index of the object contribution elements in the indexed array.
	 * 
	 * @see LegacyActionPersistence#read()
	 */
	private static final int INDEX_OBJECT_CONTRIBUTIONS = 2;

	/**
	 * The index of the view contribution elements in the indexed array.
	 * 
	 * @see LegacyActionPersistence#read()
	 */
	private static final int INDEX_VIEW_CONTRIBUTIONS = 3;

	/**
	 * The index of the viewer contribution elements in the indexed array.
	 * 
	 * @see LegacyActionPersistence#read()
	 */
	private static final int INDEX_VIEWER_CONTRIBUTIONS = 4;

	/**
	 * The menu contributions that have come from the registry. This is used to
	 * flush the contributions when the registry is re-read. This value is never
	 * <code>null</code>
	 */
	private static final Collection menuContributions = new ArrayList();

	/**
	 * Deactivates all of the activations made by this class, and then clears
	 * the collection. This should be called before every read.
	 * 
	 * @param handlerService
	 *            The service handling the activations; must not be
	 *            <code>null</code>.
	 */
	private static final void clearActivations(
			final IHandlerService handlerService) {
		handlerService.deactivateHandlers(handlerActivations);
		handlerActivations.clear();
	}

	/**
	 * Removes all of the contributions made by this class, and then clears the
	 * collection. This should be called before every read.
	 * 
	 * @param menuService
	 *            The service handling the contributions; must not be
	 *            <code>null</code>.
	 */
	private static final void clearContributions(final IMenuService menuService) {
		menuService.removeContributions(menuContributions);
		menuContributions.clear();
	}

	/**
	 * Extracts any key bindings from the action. If such a binding exists, it
	 * is added to the binding manager.
	 * 
	 * @param element
	 *            The action from which the binding should be read; must not be
	 *            <code>null</code>.
	 * @param command
	 *            The fully-parameterized command for which a binding should be
	 *            made; must not be <code>null</code>.
	 * @param bindingManager
	 *            The manager to which the binding should be added; must not be
	 *            <code>null</code>.
	 */
	private static final void convertActionToBinding(
			final IConfigurationElement element,
			final ParameterizedCommand command,
			final BindingManager bindingManager) {
		// Figure out which accelerator text to use.
		String acceleratorText = readOptional(element, ATTRIBUTE_ACCELERATOR);
		if (acceleratorText == null) {
			final String label = readOptional(element, ATTRIBUTE_LABEL);
			if (label != null) {
				acceleratorText = LegacyActionTools
						.extractAcceleratorText(label);
			}
		}

		// If there is some accelerator text, generate a key sequence from it.
		if (acceleratorText != null) {
			final IKeyLookup lookup = KeyLookupFactory.getSWTKeyLookup();
			final int acceleratorInt = LegacyActionTools
					.convertAccelerator(acceleratorText);
			final int modifierMask = lookup.getAlt() | lookup.getCommand()
					| lookup.getCtrl() | lookup.getShift();
			final int modifierKeys = acceleratorInt & modifierMask;
			final int naturalKey = acceleratorInt & ~modifierMask;
			final KeyStroke keyStroke = KeyStroke.getInstance(modifierKeys,
					naturalKey);
			final KeySequence keySequence = KeySequence.getInstance(keyStroke);

			final Scheme activeScheme = bindingManager.getActiveScheme();

			final Binding binding = new KeyBinding(keySequence, command,
					activeScheme.getId(), IContextIds.CONTEXT_ID_WINDOW, null,
					null, null, Binding.SYSTEM);
			bindingManager.addBinding(binding);
		}
	}

	/**
	 * Determine which command to use. This is slightly complicated as actions
	 * do not have to have commands, but the new architecture requires it. As
	 * such, we will auto-generate a command for the action if the definitionId
	 * is missing or points to a command that does not yet exist. All such
	 * command identifiers are prefixed with AUTOGENERATED_COMMAND_ID_PREFIX.
	 * 
	 * @param element
	 *            The action element from which a command must be generated;
	 *            must not be <code>null</code>.
	 * @param primaryId
	 *            The primary identifier to use when auto-generating a command;
	 *            must not be <code>null</code>.
	 * @param secondaryId
	 *            The secondary identifier to use when auto-generating a
	 *            command; must not be <code>null</code>.
	 * @param commandManager
	 *            The command manager in which the command should be defined;
	 *            must not be <code>null</code>.
	 * @param warningsToLog
	 *            The collection of warnings logged while reading the extension
	 *            point; must not be <code>null</code>.
	 * @return the fully-parameterized command; <code>null</code> if an error
	 *         occurred.
	 */
	private static final ParameterizedCommand convertActionToCommand(
			final IConfigurationElement element, final String primaryId,
			final String secondaryId, final CommandManager commandManager,
			final List warningsToLog) {
		String commandId = readOptional(element, ATTRIBUTE_DEFINITION_ID);
		Command command = null;
		if (commandId != null) {
			command = commandManager.getCommand(commandId);
		}

		String label = null;
		if ((commandId == null) || (!command.isDefined())) {
			if (commandId == null) {
				commandId = AUTOGENERATED_PREFIX + primaryId + '/'
						+ secondaryId;
			}

			// Read the label attribute.
			label = readRequired(element, ATTRIBUTE_LABEL, warningsToLog,
					"Actions require a non-empty label or definitionId", //$NON-NLS-1$
					commandId);
			if (label == null) {
				label = WorkbenchMessages.LegacyActionPersistence_AutogeneratedCommandName;
			}

			/*
			 * Read the tooltip attribute. The tooltip is really the description
			 * of the command.
			 */
			final String tooltip = readOptional(element, ATTRIBUTE_TOOLTIP);

			// Define the command.
			command = commandManager.getCommand(commandId);
			final Category category = commandManager.getCategory(null);
			final String name = LegacyActionTools.removeAcceleratorText(Action
					.removeMnemonics(label));
			command.define(name, tooltip, category, null);

			// TODO Decide the command state.
			final String style = readOptional(element, ATTRIBUTE_STYLE);
			if (STYLE_RADIO.equals(style)) {
				final IState state = new RadioState();
				// TODO How to set the id?
				final boolean checked = readBoolean(element, ATTRIBUTE_STATE,
						false);
				state.setValue((checked) ? Boolean.TRUE : Boolean.FALSE);
				command.addState(IMenuStateIds.STYLE, state);

			} else if (STYLE_TOGGLE.equals(style)) {
				final IState state = new ToggleState();
				final boolean checked = readBoolean(element, ATTRIBUTE_STATE,
						false);
				state.setValue((checked) ? Boolean.TRUE : Boolean.FALSE);
				command.addState(IMenuStateIds.STYLE, state);
			}
		}

		return new ParameterizedCommand(command, null);
	}

	/**
	 * <p>
	 * Extracts the handler information from the given action element. These are
	 * registered with the handler service. They are always active.
	 * </p>
	 * <p>
	 * TODO We need to hook up listeners for all the various things delegates
	 * listen to.
	 * </p>
	 * 
	 * @param element
	 *            The action element from which the handler should be read; must
	 *            not be <code>null</code>.
	 * @param actionId
	 *            The identifier of the action for which a handler is being
	 *            created; must not be <code>null</code>.
	 * @param command
	 *            The command for which this handler applies; must not be
	 *            <code>null</code>.
	 * @param commandManager
	 *            The manager providing the commands; must not be
	 *            <code>null</code>.
	 * @param handlerService
	 *            The handler service with which the action should be
	 *            registered; must not be <code>null</code>.
	 * @param bindingManager
	 *            The manager providing bindings for the command; must not be
	 *            <code>null</code>.
	 * @param commandImageManager
	 *            The image manager providing icons for the command; must not be
	 *            <code>null</code>.
	 * @param style
	 *            The style of images to use for this action; may be
	 *            <code>null</code>.
	 * @param activeWhenExpression
	 *            The expression controlling when the handler is active; may be
	 *            <code>null</code>.
	 * @param sourceProviders
	 *            The event providers required to support the legacy
	 *            <code>IActionDelegate</code>; must not be <code>null</code>.
	 * @param viewId
	 *            The view to which this handler is associated. This value is
	 *            required if this is a view action; otherwise it can be
	 *            <code>null</code>.
	 */
	private static final void convertActionToHandler(
			final IConfigurationElement element, final String actionId,
			final ParameterizedCommand command,
			final CommandManager commandManager,
			final IHandlerService handlerService,
			final BindingManager bindingManager,
			final CommandImageManager commandImageManager, final String style,
			final Expression activeWhenExpression,
			final ISourceProvider[] sourceProviders, final String viewId) {
		// Read the class attribute.
		final String classString = readOptional(element, ATTRIBUTE_CLASS);
		if (classString == null) {
			return;
		}

		// Read the enablesFor attribute, and enablement and selection elements.
		SelectionEnabler enabler = null;
		if (element.getAttribute(ATTRIBUTE_ENABLES_FOR) != null) {
			enabler = new SelectionEnabler(element);
		} else {
			IConfigurationElement[] kids = element
					.getChildren(ELEMENT_ENABLEMENT);
			if (kids.length > 0)
				enabler = new SelectionEnabler(element);
		}
		final Expression enabledWhenExpression;
		if (enabler == null) {
			enabledWhenExpression = null;
		} else {
			enabledWhenExpression = new LegacySelectionEnablerWrapper(enabler);
		}

		// Create the handler.
		final ActionDelegateHandlerProxy handler = new ActionDelegateHandlerProxy(
				element, ATTRIBUTE_CLASS, actionId, command, commandManager,
				handlerService, bindingManager, commandImageManager, style,
				enabledWhenExpression, viewId);
		for (int i = 0; i < sourceProviders.length; i++) {
			final ISourceProvider provider = sourceProviders[i];
			handler.addSourceProvider(provider);
		}

		// Activate the handler.
		final String commandId = command.getId();
		if (!handlerService.isConflict(commandId, activeWhenExpression)) {
			handlerActivations.add(handlerService.activateHandler(command
					.getId(), handler, activeWhenExpression));
		} else {
			handler.dispose();
		}
	}

	/**
	 * Extracts any image definitions from the action. These are defined as
	 * image bindings on the given command with an auto-generated style.
	 * 
	 * @param element
	 *            The action element from which the images should be read; must
	 *            not be <code>null</code>.
	 * @param command
	 *            The command to which the images should be bound; must not be
	 *            <code>null</code>.
	 * @param commandImageManager
	 *            The manager with which the images need to be registered; must
	 *            not be <code>null</code>.
	 * @return The image style used to define these images; may be
	 *         <code>null</code>.
	 */
	private static final String convertActionToImages(
			final IConfigurationElement element,
			final ParameterizedCommand command,
			final CommandImageManager commandImageManager) {
		final String commandId = command.getId();

		// Read the icon attributes.
		final String icon = readOptional(element, ATTRIBUTE_ICON);
		final String disabledIcon = readOptional(element,
				ATTRIBUTE_DISABLED_ICON);
		final String hoverIcon = readOptional(element, ATTRIBUTE_HOVER_ICON);

		// Check if at least one is defined.
		if ((icon == null) && (disabledIcon == null) && (hoverIcon == null)) {
			return null;
		}

		final String style = commandImageManager.generateUnusedStyle(commandId);

		// Bind the images.
		if (icon != null) {
			final URL iconURL = BundleUtility
					.find(element.getNamespace(), icon);
			commandImageManager.bind(commandId,
					ICommandImageService.TYPE_DEFAULT, style, iconURL);
		}
		if (disabledIcon != null) {
			final URL disabledIconURL = BundleUtility.find(element
					.getNamespace(), disabledIcon);
			commandImageManager.bind(commandId,
					ICommandImageService.TYPE_DISABLED, style, disabledIconURL);
		}
		if (hoverIcon != null) {
			final URL hoverIconURL = BundleUtility.find(element.getNamespace(),
					hoverIcon);
			commandImageManager.bind(commandId,
					ICommandImageService.TYPE_HOVER, style, hoverIconURL);
		}

		return style;
	}

	/**
	 * Extracts the item part of the action, and registers it with the given
	 * menu service.
	 * 
	 * @param element
	 *            The action element from which the item should be read; must
	 *            not be <code>null</code>.
	 * @param warningsToLog
	 *            The list of warnings logged while parsing this extension
	 *            point; must not be <code>null</code>.
	 * @param command
	 *            The command with which the item should be associated; must not
	 *            be <code>null</code>.
	 * @param imageStyle
	 *            The image style to use; may be <code>null</code>.
	 * @param menuService
	 *            The menu service with which to register; must not be
	 *            <code>null</code>.
	 * @param leadingPart
	 *            The <code>part</code> string for an <code>SPart</code>.
	 *            This value is <code>null</code> if it is not in an
	 *            <code>SPart</code>.
	 * @param locationInfo
	 *            The information required to create the non-leaf portion of the
	 *            location element; may be <code>null</code> if there is no
	 *            non-leaf component.
	 * @param visibleWhenExpression
	 *            The visibility crtieria for the corresponding item; may be
	 *            <code>null</code>.
	 */
	private static final void convertActionToItem(
			final IConfigurationElement element, final List warningsToLog,
			final ParameterizedCommand command, final String imageStyle,
			final IMenuService menuService,
			final LegacyLocationInfo locationInfo,
			final Expression visibleWhenExpression) {
		final String commandId = command.getId();

		// Read the id attribute.
		final String id = readRequired(element, ATTRIBUTE_ID, warningsToLog,
				"Actions require an id", commandId); //$NON-NLS-1$
		if (id == null) {
			return;
		}

		// Figure out the mnemonic, if any.
		final String label = readOptional(element, ATTRIBUTE_LABEL);
		final char mnemonic = LegacyActionTools.extractMnemonic(label);

		// Count how many locations there will be.
		final String menubarPath = readOptional(element, ATTRIBUTE_MENUBAR_PATH);
		final String toolbarPath = readOptional(element, ATTRIBUTE_TOOLBAR_PATH);
		int locationCount = 0;
		if (menubarPath != null) {
			locationCount++;
		}
		if (toolbarPath != null) {
			locationCount++;
		}

		// Create the locations.
		final SLocation[] locations;
		if (locationCount == 0) {
			locations = null;
		} else {
			locations = new SLocation[locationCount];
			int i = 0;

			if (menubarPath != null) {
				locations[i++] = createLocation(SBar.TYPE_MENU, menubarPath,
						locationInfo, mnemonic, imageStyle);
			}
			if (toolbarPath != null) {
				locations[i++] = createLocation(SBar.TYPE_TOOL, toolbarPath,
						locationInfo, mnemonic, imageStyle);
			}
		}

		/*
		 * Figure out whether this a pulldown or not. If it is a pulldown, then
		 * we are going to need to make an SWidget rather than an SItem.
		 */
		final String style = readOptional(element, ATTRIBUTE_STYLE);
		final boolean pulldown = readBoolean(element, ATTRIBUTE_PULLDOWN, false);
		if (pulldown || STYLE_PULLDOWN.equals(style)) {
			final SWidget widget = menuService.getWidget(id);
			final IWidget proxy = new PulldownDelegateWidgetProxy(element,
					ATTRIBUTE_CLASS);
			widget.define(proxy, locations);
			// TODO Cannot duplicate the class instance between handler and item
			final IMenuContribution contribution = menuService.contributeMenu(
					widget, visibleWhenExpression);
			menuContributions.add(contribution);

		} else {
			final SItem item = menuService.getItem(id);
			item.define(command, id, locations);
			final IMenuContribution contribution = menuService.contributeMenu(
					item, visibleWhenExpression);
			menuContributions.add(contribution);

		}
	}

	/**
	 * Constructs a new instance of <code>SLocation</code> with the
	 * information provided.
	 * 
	 * @param barType
	 *            The type of <code>SBar</code> to create as the leaf element;
	 *            must be one of the types defined in <code>SBar</code>
	 * @param path
	 *            The path to use with <code>SBar</code>; must not be
	 *            <code>null</code>.
	 * @param locationInfo
	 *            The information needed to construct the non-leaf portion of
	 *            the location; may be <code>null</code>.
	 * @param mnemonic
	 *            The mnemonic; may be {@link SLocation#MNEMONIC_NONE}.
	 * @param imageStyle
	 *            The image style to use; may be <code>null</code>.
	 * @return A location instance encapsulating this information; never
	 *         <code>null</code>.
	 */
	private static final SLocation createLocation(final int barType,
			final String path, final LegacyLocationInfo locationInfo,
			final char mnemonic, final String imageStyle) {
		final LeafLocationElement leafElement = new SBar(barType, path);
		final LocationElement locationElement;
		if (locationInfo == null) {
			locationElement = leafElement;
		} else {
			locationElement = locationInfo.append(leafElement);
		}
		return new SLocation(mnemonic, imageStyle, null, locationElement);
	}

	/**
	 * Reads the actions, and defines all the necessary subcomponents in terms
	 * of the command architecture. For each action, there could be a command, a
	 * command image binding, a handler and a menu item.
	 * 
	 * @param primaryId
	 *            The identifier of the primary object to which this action
	 *            belongs. This is used to auto-generate command identifiers
	 *            when required. The <code>primaryId</code> must not be
	 *            <code>null</code>.
	 * @param elements
	 *            The action elements to be read; must not be <code>null</code>.
	 * @param warningsToLog
	 *            The collection of warnings while parsing this extension point;
	 *            must not be <code>null</code>.
	 * @param commandManager
	 *            The command manager for the workbench; must not be
	 *            <code>null</code>.
	 * @param handlerService
	 *            The service to which the handler should be added; must not be
	 *            <code>null</code>.
	 * @param bindingManager
	 *            The binding manager for the workbench; must not be
	 *            <code>null</code>.
	 * @param commandImageManager
	 *            The command image manager for the workbench; must not be
	 *            <code>null</code>.
	 * @param menuService
	 *            The menu service for the workbench; must not be
	 *            <code>null</code>.
	 * @param locationInfo
	 *            The information required to create the non-leaf portion of the
	 *            location element; may be <code>null</code> if there is no
	 *            non-leaf component.
	 * @param visibleWhenExpression
	 *            The expression controlling visibility of the corresponding
	 *            menu elements; may be <code>null</code>.
	 * @param sourceProviders
	 *            The event providers required to support the legacy
	 *            <code>IActionDelegate</code>; must not be <code>null</code>.
	 * @param viewId
	 *            The view to which this handler is associated. This value is
	 *            required if this is a view action; otherwise it can be
	 *            <code>null</code>.
	 * @return References to the created menu elements; may be <code>null</code>,
	 *         and may be empty.
	 */
	private static final SReference[] readActions(final String primaryId,
			final IConfigurationElement[] elements, final List warningsToLog,
			final CommandManager commandManager,
			final IHandlerService handlerService,
			final BindingManager bindingManager,
			final CommandImageManager commandImageManager,
			final IMenuService menuService,
			final LegacyLocationInfo locationInfo,
			final Expression visibleWhenExpression,
			final ISourceProvider[] sourceProviders, final String viewId) {
		final Collection references = new ArrayList(elements.length);
		for (int i = 0; i < elements.length; i++) {
			final IConfigurationElement element = elements[i];

			/*
			 * We might need the identifier to generate the command, so we'll
			 * read it out now.
			 */
			final String id = readRequired(element, ATTRIBUTE_ID,
					warningsToLog, "Actions require an id"); //$NON-NLS-1$
			if (id == null) {
				continue;
			}

			// Try to break out the command part of the action.
			final ParameterizedCommand command = convertActionToCommand(
					element, primaryId, id, commandManager, warningsToLog);
			if (command == null) {
				continue;
			}

			// TODO Read the helpContextId attribute
			// TODO Read the overrideActionId attribute

			convertActionToBinding(element, command, bindingManager);
			final String imageStyle = convertActionToImages(element, command,
					commandImageManager);
			convertActionToHandler(element, id, command, commandManager,
					handlerService, bindingManager, commandImageManager,
					imageStyle, visibleWhenExpression, sourceProviders, viewId);
			convertActionToItem(element, warningsToLog, command, imageStyle,
					menuService, locationInfo, visibleWhenExpression);

			references.add(new SReference(SReference.TYPE_ITEM, id));
		}

		return (SReference[]) references.toArray(new SReference[references
				.size()]);
	}

	/**
	 * Reads all of the action and menu child elements from the given element.
	 * 
	 * @param element
	 *            The configuration element from which the actions and menus
	 *            should be read; must not be <code>null</code>, but may be
	 *            empty.
	 * @param id
	 *            The identifier of the contribution being made. This could be
	 *            an action set, an editor contribution, a view contribution, a
	 *            viewer contribution or an object contribution. This value must
	 *            not be <code>null</code>.
	 * @param warningsToLog
	 *            The list of warnings already logged for this extension point;
	 *            must not be <code>null</code>.
	 * @param commandManager
	 *            The command manager to which the command should be added; must
	 *            not be <code>null</code>.
	 * @param handlerService
	 *            The service to which the handler should be added; must not be
	 *            <code>null</code>.
	 * @param bindingManager
	 *            The binding manager to which the bindings should be added;
	 *            must not be <code>null</code>.
	 * @param commandImageManager
	 *            The command image service to which the images should be added;
	 *            must not be <code>null</code>.
	 * @param menuService
	 *            The menu service to which the menu elements should be added;
	 *            must not be <code>null</code>.
	 * @param locationInfo
	 *            The information required to create the non-leaf portion of the
	 *            location element; may be <code>null</code> if there is no
	 *            non-leaf component.
	 * @param visibleWhenExpression
	 *            The expression controlling visibility of the corresponding
	 *            menu elements; may be <code>null</code>.
	 * @param sourceProviders
	 *            The event providers required to support the legacy
	 *            <code>IActionDelegate</code>; must not be <code>null</code>.
	 * @param viewId
	 *            The view to which this handler is associated. This value is
	 *            required if this is a view action; otherwise it can be
	 *            <code>null</code>.
	 * @return An array of references to the created menu elements. This value
	 *         may be <code>null</code> if there was a problem parsing the
	 *         configuration element.
	 */
	private static final SReference[] readActionsAndMenus(
			final IConfigurationElement element, final String id,
			final List warningsToLog, final CommandManager commandManager,
			final IHandlerService handlerService,
			final BindingManager bindingManager,
			final CommandImageManager commandImageManager,
			final IMenuService menuService,
			final LegacyLocationInfo locationInfo,
			final Expression visibleWhenExpression,
			final ISourceProvider[] sourceProviders, final String viewId) {
		// Read its child elements.
		final IConfigurationElement[] actionElements = element
				.getChildren(ELEMENT_ACTION);
		final SReference[] itemReferences = readActions(id, actionElements,
				warningsToLog, commandManager, handlerService, bindingManager,
				commandImageManager, menuService, locationInfo,
				visibleWhenExpression, sourceProviders, viewId);

		// Read out the menus and groups, if any.
		final IConfigurationElement[] menuElements = element
				.getChildren(ELEMENT_MENU);
		if ((menuElements != null) && (menuElements.length > 0)) {
			final SReference[] menuAndGroupReferences = readMenusAndGroups(
					menuElements, id, warningsToLog, menuService, locationInfo,
					visibleWhenExpression);
			if ((itemReferences == null) || (itemReferences.length == 0)) {
				return menuAndGroupReferences;
			}

			final SReference[] references = new SReference[itemReferences.length
					+ menuAndGroupReferences.length];
			System.arraycopy(itemReferences, 0, references, 0,
					itemReferences.length);
			System.arraycopy(menuAndGroupReferences, 0, references,
					itemReferences.length, menuAndGroupReferences.length);
			return references;

		}

		// There were neither menus nor groups.
		return itemReferences;
	}

	/**
	 * Reads the deprecated actions from an array of elements from the action
	 * sets extension point.
	 * 
	 * @param configurationElements
	 *            The configuration elements in the extension point; must not be
	 *            <code>null</code>, but may be empty.
	 * @param configurationElementCount
	 *            The number of configuration elements that are really in the
	 *            array.
	 * @param commandManager
	 *            The command manager to which the command should be added; must
	 *            not be <code>null</code>.
	 * @param handlerService
	 *            The service to which the handler should be added; must not be
	 *            <code>null</code>.
	 * @param bindingManager
	 *            The binding manager to which the bindings should be added;
	 *            must not be <code>null</code>.
	 * @param commandImageManager
	 *            The command image service to which the images should be added;
	 *            must not be <code>null</code>.
	 * @param menuService
	 *            The menu service to which the menu elements should be added;
	 *            must not be <code>null</code>.
	 * @param sourceProviders
	 *            The event providers required to support the legacy
	 *            <code>IActionDelegate</code>; must not be <code>null</code>.
	 */
	private static final void readActionSets(
			final IConfigurationElement[] configurationElements,
			final int configurationElementCount,
			final CommandManager commandManager,
			final IHandlerService handlerService,
			final BindingManager bindingManager,
			final CommandImageManager commandImageManager,
			final IMenuService menuService,
			final ISourceProvider[] sourceProviders) {
		final List warningsToLog = new ArrayList(1);

		for (int i = 0; i < configurationElementCount; i++) {
			final IConfigurationElement element = configurationElements[i];

			// Read the action set identifier.
			final String id = readRequired(element, ATTRIBUTE_ID,
					warningsToLog, "Action sets need an id"); //$NON-NLS-1$
			if (id == null)
				continue;

			// Read the label.
			final String label = readRequired(element, ATTRIBUTE_LABEL,
					warningsToLog, "Actions set need a label", //$NON-NLS-1$
					id);
			if (label == null)
				continue;

			// Read the description.
			final String description = readOptional(element,
					ATTRIBUTE_DESCRIPTION);

			// Read whether the action set should be visible by default.
			final boolean visible = readBoolean(element, ATTRIBUTE_VISIBLE,
					false);

			// Read all of the child elements.
			final SReference[] references = readActionsAndMenus(element, id,
					warningsToLog, commandManager, handlerService,
					bindingManager, commandImageManager, menuService, null,
					null, sourceProviders, null);
			if (references == null) {
				continue;
			}

			// Define the action set.
			final SActionSet actionSet = menuService.getActionSet(id);
			actionSet.define(label, description, visible, references);
		}

		logWarnings(
				warningsToLog,
				"Warnings while parsing the action sets from the 'org.eclipse.ui.actionSets' extension point"); //$NON-NLS-1$
	}

	/**
	 * Reads the deprecated editor contributions from an array of elements from
	 * the editor actions extension point.
	 * 
	 * @param configurationElements
	 *            The configuration elements in the extension point; must not be
	 *            <code>null</code>, but may be empty.
	 * @param configurationElementCount
	 *            The number of configuration elements that are really in the
	 *            array.
	 * @param commandManager
	 *            The command manager to which the command should be added; must
	 *            not be <code>null</code>.
	 * @param handlerService
	 *            The service to which the handler should be added; must not be
	 *            <code>null</code>.
	 * @param bindingManager
	 *            The binding manager to which the bindings should be added;
	 *            must not be <code>null</code>.
	 * @param commandImageManager
	 *            The command image service to which the images should be added;
	 *            must not be <code>null</code>.
	 * @param menuService
	 *            The menu service to which the menu elements should be added;
	 *            must not be <code>null</code>.
	 * @param sourceProviders
	 *            The event providers required to support the legacy
	 *            <code>IActionDelegate</code>; must not be <code>null</code>.
	 */
	private static final void readEditorContributions(
			final IConfigurationElement[] configurationElements,
			final int configurationElementCount,
			final CommandManager commandManager,
			final IHandlerService handlerService,
			final BindingManager bindingManager,
			final CommandImageManager commandImageManager,
			final IMenuService menuService,
			final ISourceProvider[] sourceProviders) {
		final List warningsToLog = new ArrayList(1);

		for (int i = 0; i < configurationElementCount; i++) {
			final IConfigurationElement element = configurationElements[i];

			// Read the editor contribution identifier.
			final String id = readRequired(element, ATTRIBUTE_ID,
					warningsToLog, "Editor contributions need an id"); //$NON-NLS-1$
			if (id == null)
				continue;

			/*
			 * Read the target id. This is the identifier of the editor with
			 * which these contributions are associated.
			 */
			final String targetId = readRequired(element, ATTRIBUTE_TARGET_ID,
					warningsToLog, "Editor contributions need a target id", id); //$NON-NLS-1$
			if (targetId == null)
				continue;
			final Expression visibleWhenExpression = new LegacyEditorContributionExpression(
					targetId);

			// Read all of the child elements from the registry.
			readActionsAndMenus(element, id, warningsToLog, commandManager,
					handlerService, bindingManager, commandImageManager,
					menuService, null, visibleWhenExpression, sourceProviders,
					null);
		}

		logWarnings(
				warningsToLog,
				"Warnings while parsing the editor contributions from the 'org.eclipse.ui.editorActions' extension point"); //$NON-NLS-1$
	}

	/**
	 * Reads the separators represented by the given elements.
	 * 
	 * @param elements
	 *            The elements representing the separators; must not be
	 *            <code>null</code>.
	 * @param menuService
	 *            The menu service with which the separator needs to be
	 *            registered; must not be <code>null</code>.
	 * @param warningsToLog
	 *            The list of warnings already logged for this extension point;
	 *            must not be <code>null</code>.
	 * @param path
	 *            The path at which the separators will appear; may be
	 *            <code>null</code>.
	 * @param locationInfo
	 *            The information required to create the non-leaf portion of the
	 *            location element; may be <code>null</code> if there is no
	 *            non-leaf component.
	 * @param visibleWhenExpression
	 *            An expression controlling the visibility. If this value is
	 *            <code>null</code>, then the menus and groups are always
	 *            visible.
	 * @param separatorsVisible
	 *            Whether separators should be drawn around this group.
	 * @return References to the separators. May be empty if none of the
	 *         elements are valid, but never <code>null</code>.
	 */
	private static final SReference[] readGroups(
			final IConfigurationElement[] elements,
			final IMenuService menuService, final List warningsToLog,
			final String path, final LegacyLocationInfo locationInfo,
			final Expression visibleWhenExpression,
			final boolean separatorsVisible) {
		final int length = elements.length;
		final Collection separators = new ArrayList(length);
		for (int i = 0; i < length; i++) {
			final IConfigurationElement element = elements[i];

			// Read the name attribute.
			final String name = readRequired(element, ATTRIBUTE_NAME,
					warningsToLog, "Groups require a name"); //$NON-NLS-1$
			if (name == null) {
				continue;
			}

			// Define the group.
			final SGroup group = menuService.getGroup(name);
			final SLocation location = createLocation(SBar.TYPE_MENU, path,
					locationInfo, SLocation.MNEMONIC_NONE, null);
			SLocation[] locations = null;
			if (group.isDefined()) {
				// Add another location.
				try {
					final SLocation[] currentLocations = group.getLocations();
					final int currentLength = currentLocations.length;
					locations = new SLocation[currentLength + 1];
					System.arraycopy(currentLocations, 0, locations, 0,
							currentLength);
					locations[currentLength] = location;
				} catch (final NotDefinedException e) {
					// This should not be possible.
					addWarning(
							warningsToLog,
							"Group became undefined while loading registry (threading problem)", //$NON-NLS-1$
							element, name);
				}

			} else {
				// This is the first location.
				locations = new SLocation[] { location };

			}
			group.define(separatorsVisible, locations, null);
			final IMenuContribution contribution = menuService.contributeMenu(
					group, visibleWhenExpression);
			menuContributions.add(contribution);

			// Add a reference.
			final SReference reference = new SReference(SReference.TYPE_GROUP,
					name);
			separators.add(reference);
		}

		return (SReference[]) separators.toArray(new SReference[separators
				.size()]);
	}

	/**
	 * 
	 * @param menuElements
	 *            The menu elements to parse; must not be <code>null</code>.
	 * @param contributionId
	 *            The identifier of the contribution being made. This could be
	 *            an action set, an editor contribution, a view contribution, a
	 *            viewer contribution or an object contribution. This value must
	 *            not be <code>null</code>.
	 * @param warningsToLog
	 *            The list of warnings already logged for this extension point;
	 *            must not be <code>null</code>.
	 * @param menuService
	 *            The menu service with which the menu and group elements should
	 *            be registered; must not be <code>null</code>.
	 * @param locationInfo
	 *            The information required to create the non-leaf portion of the
	 *            location element; may be <code>null</code> if there is no
	 *            non-leaf component.
	 * @param visibleWhenExpression
	 *            An expression controlling the visibility. If this value is
	 *            <code>null</code>, then the menus and groups are always
	 *            visible.
	 * @return An array of references to the menus and groups created. This
	 *         value is never <code>null</code>.
	 */
	private static final SReference[] readMenusAndGroups(
			final IConfigurationElement[] menuElements,
			final String contributionId, final List warningsToLog,
			final IMenuService menuService,
			final LegacyLocationInfo locationInfo,
			final Expression visibleWhenExpression) {
		final int length = menuElements.length;
		final Collection references = new ArrayList(length);
		for (int i = 0; i < length; i++) {
			final IConfigurationElement menuElement = menuElements[i];

			// Read the id attribute.
			final String menuId = readRequired(menuElement, ATTRIBUTE_ID,
					warningsToLog, "Menus require an id", contributionId); //$NON-NLS-1$
			if (menuId == null) {
				continue;
			}

			// Read the label attribute, and extract the mnemonic.
			String label = readRequired(menuElement, ATTRIBUTE_LABEL,
					warningsToLog, "Menus require a label", menuId); //$NON-NLS-1$
			if (label == null) {
				continue;
			}
			final char mnemonic = LegacyActionTools.extractMnemonic(label);
			label = LegacyActionTools.removeMnemonics(label);

			// Read the path attribute.
			final String path = readOptional(menuElement, ATTRIBUTE_PATH);
			final String subpath;
			if (path == null) {
				subpath = menuId;
			} else {
				subpath = menuId + '/' + path;
			}

			// Read the separator elements. There must be at least one.
			final IConfigurationElement[] separatorElements = menuElement
					.getChildren(ELEMENT_SEPARATOR);
			final SReference[] separatorReferences = readGroups(
					separatorElements, menuService, warningsToLog, subpath,
					locationInfo, visibleWhenExpression, true);
			if (separatorReferences != null) {
				references.addAll(Arrays.asList(separatorReferences));
			}

			// Read the group elements.
			final IConfigurationElement[] groupElements = menuElement
					.getChildren(ELEMENT_GROUP);
			final SReference[] groupReferences = readGroups(groupElements,
					menuService, warningsToLog, subpath, locationInfo,
					visibleWhenExpression, false);
			if (groupReferences != null) {
				references.addAll(Arrays.asList(groupReferences));
			}

			// Define the menu.
			final SMenu menu = menuService.getMenu(menuId);
			final SLocation location = createLocation(SBar.TYPE_MENU, path,
					locationInfo, mnemonic, null);
			final SLocation[] locations = new SLocation[] { location };
			menu.define(label, locations, null);
			final IMenuContribution contribution = menuService.contributeMenu(
					menu, visibleWhenExpression);
			menuContributions.add(contribution);
			references.add(new SReference(SReference.TYPE_MENU, menuId));
		}

		return (SReference[]) references.toArray(new SReference[references
				.size()]);
	}

	/**
	 * Reads the deprecated object contributions from an array of elements from
	 * the popup menus extension point.
	 * 
	 * @param configurationElements
	 *            The configuration elements in the extension point; must not be
	 *            <code>null</code>, but may be empty.
	 * @param configurationElementCount
	 *            The number of configuration elements that are really in the
	 *            array.
	 * @param commandManager
	 *            The command manager to which the command should be added; must
	 *            not be <code>null</code>.
	 * @param handlerService
	 *            The service to which the handler should be added; must not be
	 *            <code>null</code>.
	 * @param bindingManager
	 *            The binding manager to which the bindings should be added;
	 *            must not be <code>null</code>.
	 * @param commandImageManager
	 *            The command image service to which the images should be added;
	 *            must not be <code>null</code>.
	 * @param menuService
	 *            The menu service to which the menu elements should be added;
	 *            must not be <code>null</code>.
	 * @param sourceProviders
	 *            The event providers required to support the legacy
	 *            <code>IActionDelegate</code>; must not be <code>null</code>.
	 */
	private static final void readObjectContributions(
			final IConfigurationElement[] configurationElements,
			final int configurationElementCount,
			final CommandManager commandManager,
			final IHandlerService handlerService,
			final BindingManager bindingManager,
			final CommandImageManager commandImageManager,
			final IMenuService menuService,
			final ISourceProvider[] sourceProviders) {
		final List warningsToLog = new ArrayList(1);

		for (int i = 0; i < configurationElementCount; i++) {
			final IConfigurationElement element = configurationElements[i];

			// Read the object contribution identifier.
			final String id = readRequired(element, ATTRIBUTE_ID,
					warningsToLog, "Object contributions need an id"); //$NON-NLS-1$
			if (id == null)
				continue;

			// Read the object class. This influences the visibility.
			final String objectClass = readRequired(element,
					ATTRIBUTE_OBJECT_CLASS, warningsToLog,
					"Object contributions need an object class", id); //$NON-NLS-1$
			if (objectClass == null)
				continue;

			// TODO Read the name filter. This influences the visibility.
			// final String nameFilter = readOptional(element,
			// ATTRIBUTE_NAME_FILTER);

			// TODO Read the object class. This influences the visibility.
			// final boolean adaptable = readBoolean(element,
			// ATTRIBUTE_ADAPTABLE,
			// false);

			final LegacyLocationInfo locationInfo = new LegacyLocationInfo();

			// TODO Read the filter elements.
			// TODO Read the enablement elements.

			// TODO Figure out an appropriate visibility expression.
			// Read the visibility element, if any.
			final Expression visibleWhenExpression = readVisibility(element,
					id, warningsToLog);

			// Read all of the child elements from the registry.
			readActionsAndMenus(element, id, warningsToLog, commandManager,
					handlerService, bindingManager, commandImageManager,
					menuService, locationInfo, visibleWhenExpression,
					sourceProviders, null);
		}

		logWarnings(
				warningsToLog,
				"Warnings while parsing the object contributions from the 'org.eclipse.ui.popupMenus' extension point"); //$NON-NLS-1$
	}

	/**
	 * Reads the deprecated view contributions from an array of elements from
	 * the view actions extension point.
	 * 
	 * @param configurationElements
	 *            The configuration elements in the extension point; must not be
	 *            <code>null</code>, but may be empty.
	 * @param configurationElementCount
	 *            The number of configuration elements that are really in the
	 *            array.
	 * @param commandManager
	 *            The command manager to which the command should be added; must
	 *            not be <code>null</code>.
	 * @param handlerService
	 *            The service to which the handler should be added; must not be
	 *            <code>null</code>.
	 * @param bindingManager
	 *            The binding manager to which the bindings should be added;
	 *            must not be <code>null</code>.
	 * @param commandImageManager
	 *            The command image service to which the images should be added;
	 *            must not be <code>null</code>.
	 * @param menuService
	 *            The menu service to which the menu elements should be added;
	 *            must not be <code>null</code>.
	 * @param sourceProviders
	 *            The event providers required to support the legacy
	 *            <code>IActionDelegate</code>; must not be <code>null</code>.
	 */
	private static final void readViewContributions(
			final IConfigurationElement[] configurationElements,
			final int configurationElementCount,
			final CommandManager commandManager,
			final IHandlerService handlerService,
			final BindingManager bindingManager,
			final CommandImageManager commandImageManager,
			final IMenuService menuService,
			final ISourceProvider[] sourceProviders) {
		final List warningsToLog = new ArrayList(1);

		for (int i = 0; i < configurationElementCount; i++) {
			final IConfigurationElement element = configurationElements[i];

			// Read the view contribution identifier.
			final String id = readRequired(element, ATTRIBUTE_ID,
					warningsToLog, "View contributions need an id"); //$NON-NLS-1$
			if (id == null)
				continue;

			/*
			 * Read the target id. This is the identifier of the view with which
			 * these contributions are associated.
			 */
			final String targetId = readRequired(element, ATTRIBUTE_TARGET_ID,
					warningsToLog, "View contributions need a target id", id); //$NON-NLS-1$
			if (targetId == null)
				continue;
			final Expression visibleWhenExpression = new LegacyHandlerSubmissionExpression(
					targetId, null, null);
			final LegacyLocationInfo locationInfo = new LegacyLocationInfo(
					targetId);

			// Read all of the child elements from the registry.
			readActionsAndMenus(element, id, warningsToLog, commandManager,
					handlerService, bindingManager, commandImageManager,
					menuService, locationInfo, visibleWhenExpression,
					sourceProviders, targetId);
		}

		logWarnings(
				warningsToLog,
				"Warnings while parsing the view contributions from the 'org.eclipse.ui.viewActions' extension point"); //$NON-NLS-1$
	}

	/**
	 * Reads the deprecated viewer contributions from an array of elements from
	 * the popup menus extension point.
	 * 
	 * @param configurationElements
	 *            The configuration elements in the extension point; must not be
	 *            <code>null</code>, but may be empty.
	 * @param configurationElementCount
	 *            The number of configuration elements that are really in the
	 *            array.
	 * @param commandManager
	 *            The command manager to which the command should be added; must
	 *            not be <code>null</code>.
	 * @param handlerService
	 *            The service to which the handler should be added; must not be
	 *            <code>null</code>.
	 * @param bindingManager
	 *            The binding manager to which the bindings should be added;
	 *            must not be <code>null</code>.
	 * @param commandImageManager
	 *            The command image service to which the images should be added;
	 *            must not be <code>null</code>.
	 * @param menuService
	 *            The menu service to which the menu elements should be added;
	 *            must not be <code>null</code>.
	 * @param sourceProviders
	 *            The event providers required to support the legacy
	 *            <code>IActionDelegate</code>; must not be <code>null</code>.
	 */
	private static final void readViewerContributions(
			final IConfigurationElement[] configurationElements,
			final int configurationElementCount,
			final CommandManager commandManager,
			final IHandlerService handlerService,
			final BindingManager bindingManager,
			final CommandImageManager commandImageManager,
			final IMenuService menuService,
			final ISourceProvider[] sourceProviders) {
		final List warningsToLog = new ArrayList(1);

		for (int i = 0; i < configurationElementCount; i++) {
			final IConfigurationElement element = configurationElements[i];

			// Read the viewer contribution identifier.
			final String id = readRequired(element, ATTRIBUTE_ID,
					warningsToLog, "Viewer contributions need an id"); //$NON-NLS-1$
			if (id == null)
				continue;

			/*
			 * Read the target id. This is the identifier of the view with which
			 * these contributions are associated.
			 */
			final String targetId = readRequired(element, ATTRIBUTE_TARGET_ID,
					warningsToLog, "Viewer contributions need a target id", id); //$NON-NLS-1$
			if (targetId == null)
				continue;
			final LegacyLocationInfo locationInfo = new LegacyLocationInfo(
					targetId, true);

			// Read the visibility element, if any.
			final Expression visibleWhenExpression = readVisibility(element,
					id, warningsToLog);

			// Read all of the child elements from the registry.
			readActionsAndMenus(element, id, warningsToLog, commandManager,
					handlerService, bindingManager, commandImageManager,
					menuService, locationInfo, visibleWhenExpression,
					sourceProviders, null);
		}

		logWarnings(
				warningsToLog,
				"Warnings while parsing the viewer contributions from the 'org.eclipse.ui.popupMenus' extension point"); //$NON-NLS-1$
	}

	/**
	 * Reads the visibility element for a contribution from the
	 * <code>org.eclipse.ui.popupMenus</code> extension point.
	 * 
	 * @param parentElement
	 *            The contribution element which contains a visibility
	 *            expression; must not be <code>null</code>.
	 * @param parentId
	 *            The identifier of the parent contribution; may be
	 *            <code>null</code>.
	 * @param warningsToLog
	 *            The list of warnings to be logged; must not be
	 *            <code>null</code>.
	 * @return An expression representing the visibility element; may be
	 *         <code>null</code>.
	 */
	private static final Expression readVisibility(
			final IConfigurationElement parentElement, final String parentId,
			final List warningsToLog) {
		final IConfigurationElement[] visibilityElements = parentElement
				.getChildren(ELEMENT_VISIBILITY);
		if ((visibilityElements == null) || (visibilityElements.length == 0)) {
			return null;
		}

		if (visibilityElements.length != 1) {
			addWarning(warningsToLog,
					"There can only be one visibility element", parentElement, //$NON-NLS-1$
					parentId);
		}

		final IConfigurationElement visibilityElement = visibilityElements[0];
		final ActionExpression visibilityActionExpression = new ActionExpression(
				visibilityElement);
		final LegacyActionExpressionWrapper wrapper = new LegacyActionExpressionWrapper(
				visibilityActionExpression);
		return wrapper;
	}

	/**
	 * The binding manager which should be populated with bindings from actions;
	 * must not be <code>null</code>.
	 */
	private final BindingManager bindingManager;

	/**
	 * The command image manager which should be populated with the images from
	 * the actions; must not be <code>null</code>.
	 */
	private final CommandImageManager commandImageManager;

	/**
	 * The command manager which is providing the commands for the workbench;
	 * must not be <code>null</code>.
	 */
	private final CommandManager commandManager;

	/**
	 * The service to which the handler should be added; must not be
	 * <code>null</code>.
	 */
	private final IHandlerService handlerService;

	/**
	 * The menu service which should be populated with the values from the
	 * registry; must not be <code>null</code>.
	 */
	private final IMenuService menuService;

	/**
	 * The event providers required to support the <code>IActionDelegate</code>
	 * interface; never <code>null</code>.
	 */
	private final ISourceProvider[] sourceProviders;

	/**
	 * Constructs a new instance of {@link LegacyActionPersistence}.
	 * 
	 * @param commandManager
	 *            The command manager which is providing the commands for the
	 *            workbench; must not be <code>null</code>.
	 * @param handlerService
	 *            The service to which the handler should be added; must not be
	 *            <code>null</code>.
	 * @param bindingManager
	 *            The binding manager which should be populated with bindings
	 *            from actions; must not be <code>null</code>.
	 * @param commandImageManager
	 *            The command image manager which should be populated with the
	 *            images from the actions; must not be <code>null</code>.
	 * @param menuService
	 *            The menu service which should be populated with the values
	 *            from the registry; must not be <code>null</code>.
	 * @param sourceProviders
	 *            The event providers required to support the legacy
	 *            <code>IActionDelegate</code>; must not be <code>null</code>.
	 */
	public LegacyActionPersistence(final CommandManager commandManager,
			final IHandlerService handlerService,
			final BindingManager bindingManager,
			final CommandImageManager commandImageManager,
			final IMenuService menuService,
			final ISourceProvider[] sourceProviders) {
		this.commandManager = commandManager;
		this.handlerService = handlerService;
		this.bindingManager = bindingManager;
		this.commandImageManager = commandImageManager;
		this.menuService = menuService;
		this.sourceProviders = sourceProviders;
	}

	protected final boolean isChangeImportant(final IRegistryChangeEvent event) {
		// TODO Auto-generated method stub
		return false;
	}

	/**
	 * <p>
	 * Reads all of the actions from the deprecated extension points. Actions
	 * have been replaced with commands, command images, handlers, menu elements
	 * and action sets.
	 * </p>
	 * <p>
	 * TODO Before this method is called, all of the extension points must be
	 * cleared.
	 * </p>
	 */
	public final void read() {
		super.read();

		// Create the extension registry mementos.
		final IExtensionRegistry registry = Platform.getExtensionRegistry();
		int actionSetCount = 0;
		int editorContributionCount = 0;
		int objectContributionCount = 0;
		int viewContributionCount = 0;
		int viewerContributionCount = 0;
		final IConfigurationElement[][] indexedConfigurationElements = new IConfigurationElement[5][];

		// Sort the actionSets extension point.
		final IConfigurationElement[] actionSetsExtensionPoint = registry
				.getConfigurationElementsFor(EXTENSION_ACTION_SETS);
		for (int i = 0; i < actionSetsExtensionPoint.length; i++) {
			final IConfigurationElement element = actionSetsExtensionPoint[i];
			final String name = element.getName();
			if (ELEMENT_ACTION_SET.equals(name)) {
				addElementToIndexedArray(element, indexedConfigurationElements,
						INDEX_ACTION_SETS, actionSetCount++);
			}
		}

		// Sort the editorActions extension point.
		final IConfigurationElement[] editorActionsExtensionPoint = registry
				.getConfigurationElementsFor(EXTENSION_EDITOR_ACTIONS);
		for (int i = 0; i < editorActionsExtensionPoint.length; i++) {
			final IConfigurationElement element = editorActionsExtensionPoint[i];
			final String name = element.getName();
			if (ELEMENT_EDITOR_CONTRIBUTION.equals(name)) {
				addElementToIndexedArray(element, indexedConfigurationElements,
						INDEX_EDITOR_CONTRIBUTIONS, editorContributionCount++);
			}
		}

		// Sort the popupMenus extension point.
		final IConfigurationElement[] popupMenusExtensionPoint = registry
				.getConfigurationElementsFor(EXTENSION_POPUP_MENUS);
		for (int i = 0; i < popupMenusExtensionPoint.length; i++) {
			final IConfigurationElement element = popupMenusExtensionPoint[i];
			final String name = element.getName();
			if (ELEMENT_OBJECT_CONTRIBUTION.equals(name)) {
				addElementToIndexedArray(element, indexedConfigurationElements,
						INDEX_OBJECT_CONTRIBUTIONS, objectContributionCount++);
			} else if (ELEMENT_VIEWER_CONTRIBUTION.equals(name)) {
				addElementToIndexedArray(element, indexedConfigurationElements,
						INDEX_VIEWER_CONTRIBUTIONS, viewerContributionCount++);
			}
		}

		// Sort the viewActions extension point.
		final IConfigurationElement[] viewActionsExtensionPoint = registry
				.getConfigurationElementsFor(EXTENSION_VIEW_ACTIONS);
		for (int i = 0; i < viewActionsExtensionPoint.length; i++) {
			final IConfigurationElement element = viewActionsExtensionPoint[i];
			final String name = element.getName();
			if (ELEMENT_VIEW_CONTRIBUTION.equals(name)) {
				addElementToIndexedArray(element, indexedConfigurationElements,
						INDEX_VIEW_CONTRIBUTIONS, viewContributionCount++);
			}
		}

		clearActivations(handlerService);
		clearContributions(menuService);
		readActionSets(indexedConfigurationElements[INDEX_ACTION_SETS],
				actionSetCount, commandManager, handlerService, bindingManager,
				commandImageManager, menuService, sourceProviders);
		readEditorContributions(
				indexedConfigurationElements[INDEX_EDITOR_CONTRIBUTIONS],
				editorContributionCount, commandManager, handlerService,
				bindingManager, commandImageManager, menuService,
				sourceProviders);
		readObjectContributions(
				indexedConfigurationElements[INDEX_OBJECT_CONTRIBUTIONS],
				objectContributionCount, commandManager, handlerService,
				bindingManager, commandImageManager, menuService,
				sourceProviders);
		readViewContributions(
				indexedConfigurationElements[INDEX_VIEW_CONTRIBUTIONS],
				viewContributionCount, commandManager, handlerService,
				bindingManager, commandImageManager, menuService,
				sourceProviders);
		readViewerContributions(
				indexedConfigurationElements[INDEX_VIEWER_CONTRIBUTIONS],
				viewerContributionCount, commandManager, handlerService,
				bindingManager, commandImageManager, menuService,
				sourceProviders);
	}
}
