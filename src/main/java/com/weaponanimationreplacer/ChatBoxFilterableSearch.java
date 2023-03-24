/*
 * Copyright (c) 2019, Ron Young <https://github.com/raiyni>
 * All rights reserved.
 *
 *  Redistribution and use in source and binary forms, with or without
 *  modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *     list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *     this list of conditions and the following disclaimer in the documentation
 *     and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package com.weaponanimationreplacer;

import com.google.common.collect.ImmutableMap;
import com.google.common.primitives.Ints;
import com.google.inject.Inject;
import static com.weaponanimationreplacer.Constants.HiddenSlot;
import static com.weaponanimationreplacer.Constants.NegativeId;
import static com.weaponanimationreplacer.Constants.NegativeIdsMap;
import static com.weaponanimationreplacer.Constants.ShownSlot;
import static com.weaponanimationreplacer.Constants.mapNegativeId;
import static com.weaponanimationreplacer.WeaponAnimationReplacerPlugin.SearchType.*;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import javax.inject.Singleton;
import lombok.Getter;
import net.runelite.api.Client;
import net.runelite.api.EquipmentInventorySlot;
import net.runelite.api.InventoryID;
import net.runelite.api.Item;
import net.runelite.api.ItemComposition;
import net.runelite.api.ItemContainer;
import net.runelite.api.ItemID;
import static net.runelite.api.ItemID.*;
import net.runelite.api.SpriteID;
import net.runelite.api.kit.KitType;
import net.runelite.api.widgets.ItemQuantityMode;
import net.runelite.api.widgets.JavaScriptCallback;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetPositionMode;
import net.runelite.api.widgets.WidgetSizeMode;
import net.runelite.api.widgets.WidgetTextAlignment;
import net.runelite.api.widgets.WidgetType;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.chatbox.ChatboxPanelManager;
import net.runelite.client.game.chatbox.ChatboxTextInput;
import net.runelite.client.ui.JagexColors;
import net.runelite.http.api.item.ItemStats;

@Singleton
public class ChatBoxFilterableSearch extends ChatboxTextInput
{
    private static final int ICON_HEIGHT = 32;
    private static final int ICON_WIDTH = 36;
    private static final int PADDING = 6;
    private static final int RESULTS_PER_PAGE = 24;
    private static final int FONT_SIZE = 16;
    private static final int HOVERED_OPACITY = 128;

    private final ChatboxPanelManager chatboxPanelManager;
    private final ItemManager itemManager;
    private final Client client;

    private final Map<Integer, ItemComposition> results = new LinkedHashMap<>();
	private final List<String> spells = new ArrayList<>();
	private String tooltipText;
    private int index = -1;

    @Getter
    private Consumer<Integer> onItemSelected;

	private Consumer<Integer> onItemMouseOvered;
	private Runnable onItemDeleted;
	private WeaponAnimationReplacerPlugin.SearchType searchType;

	public void setType(WeaponAnimationReplacerPlugin.SearchType searchType)
	{
		this.searchType = searchType;
		mode = 0;
	}

    @Inject
    private ChatBoxFilterableSearch(ChatboxPanelManager chatboxPanelManager, ClientThread clientThread,
                                    ItemManager itemManager, Client client)
    {
        super(chatboxPanelManager, clientThread);
        this.chatboxPanelManager = chatboxPanelManager;
        this.itemManager = itemManager;
        this.client = client;

        lines(1);
        prompt("Item Search");
        onChanged(searchString ->
                clientThread.invokeLater(() ->
                {
                    resetPage();
                    update();
                }));
    }

	@Override
	protected void open()
	{
		resetPage();
		super.open();
	}

	private void resetPage()
	{
		page = 0;
		lastPage = -1;
		filteredPageIndexes.clear();
		filterResults();
	}

	@Override
    protected void update()
    {
        Widget container = chatboxPanelManager.getContainerWidget();
        container.deleteAllChildren();

//		addPromptWidget(container);

		buildEdit(0, 5 + FONT_SIZE, container.getWidth(), FONT_SIZE);

		addSeparator(container);

		createCloseInterfaceWidget(container);
		createDeleteItemWidget(container);
		createPageButtons(container);
		if (searchType == MODEL_SWAP) {
			createHideSlotWidget(container, "Items", 0, 10, 50);
			createHideSlotWidget(container, "Hide/Show slot", 1, 60, 80);
		}

		int x = PADDING;
		int y = PADDING * 3;
		int idx = 0;
		if (searchType == TRIGGER_ITEM || searchType == MODEL_SWAP)
		{
			if (mode == 0) // items
			{
				if (results.size() == 0)
				{
					addText(container, "Type to search items.", 0xff000000, 170, 50);
//				addText(container, "shift-click items to add them without closing this dialog", 0xff555555, 80, 70);
				}
				else
				{
					for (ItemComposition itemComposition : results.values())
					{
						addItemWidgetItem(
							itemComposition.getId(),
							itemComposition.getId(),
							itemComposition.getName(),
							container,
							x,
							y,
							() -> itemSelected(itemComposition.getId()), idx
						);

						x += ICON_WIDTH + PADDING;
						if (x + ICON_WIDTH >= container.getWidth())
						{
							y += ICON_HEIGHT + PADDING;
							x = PADDING;
						}

						++idx;
					}
				}
			}
			else if (mode == 1)
			{ // hide slots.
				List<Integer> iconIds = new ArrayList<>();
				List<String> names = new ArrayList<>();
				List<Integer> hideSlotIds = new ArrayList<>();
				for (HiddenSlot hiddenSlot : HiddenSlot.values())
				{
					iconIds.add(hiddenSlot.iconIdToShow);
					names.add(hiddenSlot.actionName);
					hideSlotIds.add(mapNegativeId(new NegativeId(NegativeIdsMap.HIDE_SLOT, hiddenSlot.ordinal())));
				}

				iconIds.add(ShownSlot.ARMS.iconIdToShow);
				names.add("Show arms");
				hideSlotIds.add(mapNegativeId(new NegativeId(NegativeIdsMap.SHOW_SLOT, KitType.ARMS.getIndex())));
				iconIds.add(ShownSlot.HAIR.iconIdToShow);
				names.add("Show hair");
				hideSlotIds.add(mapNegativeId(new NegativeId(NegativeIdsMap.SHOW_SLOT, KitType.HAIR.getIndex())));
				iconIds.add(ShownSlot.JAW.iconIdToShow);
				names.add("Show jaw");
				hideSlotIds.add(mapNegativeId(new NegativeId(NegativeIdsMap.SHOW_SLOT, KitType.JAW.getIndex())));
				for (int i = 0; i < iconIds.size(); i++)
				{
					final int finalI = i;
					addItemWidgetItem(
						hideSlotIds.get(i),
						iconIds.get(i),
						names.get(i),
						container,
						x,
						y,
						() -> itemSelected(hideSlotIds.get(finalI)), idx
					);

					x += ICON_WIDTH + PADDING;
					if (x + ICON_WIDTH >= container.getWidth())
					{
						y += ICON_HEIGHT + PADDING;
						x = PADDING;
					}

					++idx;
				}
			}
		} else { // spell
			for (String spell : spells)
			{
				for (int i = 0; i < ProjectileCast.projectiles.size(); i++)
				{
					ProjectileCast projectile = ProjectileCast.projectiles.get(i);

					if (projectile.getName(itemManager).equals(spell)) {
						int finalI = i;
						System.out.println(projectile.getName(itemManager) + " " + projectile.getItemIdIcon() + " " + projectile.getSpriteIdIcon());
						addItemWidget(-1, projectile.getItemIdIcon(), projectile.getSpriteIdIcon(), projectile.getName(itemManager), container, x, y, () ->
						{
							onItemSelected.accept(finalI);
							chatboxPanelManager.close();
						}, idx);

						x += ICON_WIDTH + PADDING;
						if (x + ICON_WIDTH >= container.getWidth())
						{
							y += ICON_HEIGHT + PADDING;
							x = PADDING;
						}

						++idx;
						break;
					}
				}
			}
		}
	}

	private void createPageButtons(Widget container)
	{
		if (page != lastPage)
		{
			Widget rightArrow = container.createChild(-1, WidgetType.GRAPHIC);
			rightArrow.setSpriteId(SpriteID.FORWARD_ARROW_BUTTON_SMALL);
			rightArrow.setXPositionMode(WidgetPositionMode.ABSOLUTE_RIGHT);
			rightArrow.setYPositionMode(WidgetPositionMode.ABSOLUTE_TOP);
			rightArrow.setOriginalX(90);
			rightArrow.setOriginalY(5);
			rightArrow.setOriginalHeight(20);
			rightArrow.setOriginalWidth(20);
			rightArrow.setBorderType(1);
			rightArrow.setAction(0, tooltipText);
			rightArrow.setHasListener(true);
			rightArrow.setOnMouseOverListener((JavaScriptCallback) ev -> rightArrow.setOpacity(HOVERED_OPACITY));
			rightArrow.setOnMouseLeaveListener((JavaScriptCallback) ev -> rightArrow.setOpacity(0));
			rightArrow.setOnOpListener((JavaScriptCallback) ev -> {
				clientThread.invoke(() -> {
					page++;
					filterResults();
					update();
				});
			});
			rightArrow.revalidate();
		}

		if (lastPage != 0) {
			Widget leftArrow = container.createChild(-1, WidgetType.TEXT);
			leftArrow.setText("" + (page + 1));
			leftArrow.setTextColor(0x000000);
			leftArrow.setFontId(getFontID());
			leftArrow.setXPositionMode(WidgetPositionMode.ABSOLUTE_RIGHT);
			leftArrow.setYPositionMode(WidgetPositionMode.ABSOLUTE_TOP);
			leftArrow.setOriginalX(109 + ((page + 1 >= 10) ? 0 : -5));
			leftArrow.setOriginalY(5);
			leftArrow.setOriginalHeight(20);
			leftArrow.setOriginalWidth(20);
			leftArrow.setBorderType(1);
			leftArrow.setAction(0, tooltipText);
			leftArrow.revalidate();
		}

		if (page != 0)
		{
			Widget leftArrow = container.createChild(-1, WidgetType.GRAPHIC);
			leftArrow.setSpriteId(SpriteID.BACK_ARROW_BUTTON_SMALL);
			leftArrow.setXPositionMode(WidgetPositionMode.ABSOLUTE_RIGHT);
			leftArrow.setYPositionMode(WidgetPositionMode.ABSOLUTE_TOP);
			leftArrow.setOriginalX(130);
			leftArrow.setOriginalY(5);
			leftArrow.setOriginalHeight(20);
			leftArrow.setOriginalWidth(20);
			leftArrow.setBorderType(1);
			leftArrow.setAction(0, tooltipText);
			leftArrow.setHasListener(true);
			leftArrow.setOnMouseOverListener((JavaScriptCallback) ev -> leftArrow.setOpacity(HOVERED_OPACITY));
			leftArrow.setOnMouseLeaveListener((JavaScriptCallback) ev -> leftArrow.setOpacity(0));
			leftArrow.setOnOpListener((JavaScriptCallback) ev -> {
				clientThread.invoke(() -> {
					page--;
					filterResults();
					update();
				});
			});
			leftArrow.revalidate();
		}
	}

	private void addText(Widget container, String text, int textColor, int x, int y)
	{
		Widget item = container.createChild(-1, WidgetType.TEXT);
		item.setTextColor(textColor);
		item.setText(text);
		item.setFontId(getFontID());
		item.setXPositionMode(WidgetPositionMode.ABSOLUTE_LEFT);
		item.setYPositionMode(WidgetPositionMode.ABSOLUTE_TOP);
		item.setOriginalX(x);
		item.setOriginalY(y);
		item.setOriginalHeight(40);
		item.setOriginalWidth(1000);
		item.setBorderType(1);
		item.revalidate();
	}

	private void itemSelected(int itemId)
	{
		if (onItemSelected != null) onItemSelected.accept(itemId);
	}

	private void addItemWidgetSprite(int id, int spriteId, String name, Widget container, int x, int y, Runnable runnable, int idx)
	{
		addItemWidget(id, -1, spriteId, name, container, x, y, runnable, idx);
	}

	private void addItemWidgetItem(int id, int iconId, String name, Widget container, int x, int y, Runnable runnable, int idx)
	{
		addItemWidget(id, iconId, -1, name, container, x, y, runnable, idx);
	}

	private void addItemWidget(int id, int iconId, int spriteId, String name, Widget container, int x, int y, Runnable runnable, int idx)
	{
		Widget item = container.createChild(-1, WidgetType.GRAPHIC);
		item.setXPositionMode(WidgetPositionMode.ABSOLUTE_LEFT);
		item.setYPositionMode(WidgetPositionMode.ABSOLUTE_TOP);
		item.setOriginalX(x);
		item.setOriginalY(y + FONT_SIZE * 2);
		item.setOriginalHeight(ICON_HEIGHT);
		item.setOriginalWidth(ICON_WIDTH);
		item.setName(JagexColors.MENU_TARGET_TAG + name);
		if (iconId != -1) item.setItemId(iconId);
		else if (spriteId != -1) item.setSpriteId(spriteId);
		item.setItemQuantity(10000);
		item.setItemQuantityMode(ItemQuantityMode.NEVER);
		item.setBorderType(1);
		item.setAction(0, tooltipText);
		item.setHasListener(true);

		if (index == idx)
		{
			item.setOpacity(HOVERED_OPACITY);
		}
		else
		{
			item.setOnMouseOverListener((JavaScriptCallback) ev -> {
				item.setOpacity(HOVERED_OPACITY);
				if (onItemMouseOvered != null) onItemMouseOvered.accept(id);
			});
			item.setOnMouseLeaveListener((JavaScriptCallback) ev -> {
				item.setOpacity(0);
				if (onItemMouseOvered != null) onItemMouseOvered.accept(-1);
			});
		}

		item.setOnOpListener((JavaScriptCallback) ev -> runnable.run());
		item.revalidate();
	}

	private void addSeparator(Widget container)
	{
		Widget separator = container.createChild(-1, WidgetType.LINE);
		separator.setOriginalX(0);
		separator.setOriginalY(8 + (FONT_SIZE * 2));
		separator.setXPositionMode(WidgetPositionMode.ABSOLUTE_CENTER);
		separator.setYPositionMode(WidgetPositionMode.ABSOLUTE_TOP);
		separator.setOriginalHeight(0);
		separator.setOriginalWidth(16);
		separator.setWidthMode(WidgetSizeMode.MINUS);
		separator.setTextColor(0x666666);
		separator.revalidate();
	}

	private void addPromptWidget(Widget container)
	{
		Widget promptWidget = container.createChild(-1, WidgetType.TEXT);
		promptWidget.setText(getPrompt());
		promptWidget.setTextColor(0x800000);
		promptWidget.setFontId(getFontID());
		promptWidget.setOriginalX(0);
		promptWidget.setOriginalY(5);
		promptWidget.setXPositionMode(WidgetPositionMode.ABSOLUTE_CENTER);
		promptWidget.setYPositionMode(WidgetPositionMode.ABSOLUTE_TOP);
		promptWidget.setOriginalHeight(FONT_SIZE);
		promptWidget.setXTextAlignment(WidgetTextAlignment.CENTER);
		promptWidget.setYTextAlignment(WidgetTextAlignment.CENTER);
		promptWidget.setWidthMode(WidgetSizeMode.MINUS);
		promptWidget.revalidate();
	}

	private Widget createDeleteItemWidget(Widget container)
	{
		Widget item = container.createChild(-1, WidgetType.GRAPHIC);
		item.setXPositionMode(WidgetPositionMode.ABSOLUTE_LEFT);
		item.setYPositionMode(WidgetPositionMode.ABSOLUTE_TOP);
		item.setOriginalX(430);
		item.setOriginalY(5);
		item.setOriginalHeight(ICON_HEIGHT);
		item.setOriginalWidth(ICON_WIDTH);
		ItemComposition itemComposition = itemManager.getItemComposition(ItemID.BANK_FILLER);
		item.setName("delete");
		item.setItemId(itemComposition.getId());
		item.setItemQuantity(10000);
		item.setItemQuantityMode(ItemQuantityMode.NEVER);
		item.setBorderType(1);
		item.setAction(0, tooltipText);
		item.setHasListener(true);

		item.setOnMouseOverListener((JavaScriptCallback) ev -> item.setOpacity(HOVERED_OPACITY));
		item.setOnMouseLeaveListener((JavaScriptCallback) ev -> item.setOpacity(0));

		item.setOnOpListener((JavaScriptCallback) ev -> {
			onItemDeleted.run();
			chatboxPanelManager.close();
		});

		item.revalidate();

		return item;
	}

	private Widget createCloseInterfaceWidget(Widget container)
	{
		Widget item = container.createChild(-1, WidgetType.TEXT);
		item.setTextColor(0xff000000);
		item.setFontId(getFontID());
		item.setText("X");
		item.setName("Close (Esc");
		item.setXPositionMode(WidgetPositionMode.ABSOLUTE_LEFT);
		item.setYPositionMode(WidgetPositionMode.ABSOLUTE_TOP);
		item.setOriginalX(470); // Further left than it should be to prevent scrolling up the chat.
		item.setOriginalY(2);
		item.setOriginalHeight(ICON_HEIGHT);
		item.setOriginalWidth(15);
		item.setBorderType(1);
		item.setAction(0, tooltipText);
		item.setHasListener(true);

		item.setOnMouseOverListener((JavaScriptCallback) ev -> item.setOpacity(HOVERED_OPACITY));
		item.setOnMouseLeaveListener((JavaScriptCallback) ev -> item.setOpacity(0));

		item.setOnOpListener((JavaScriptCallback) ev -> {
			chatboxPanelManager.close();
		});

		item.revalidate();

		return item;
	}

	private int mode = 0; // 0 items, 1 hide slots.

	private Widget createHideSlotWidget(Widget container, String name, int modeToSwitchTo, int x, int width)
	{
		Widget item = container.createChild(-1, WidgetType.TEXT);
		item.setTextColor(mode == modeToSwitchTo ? 0xffaa0000 : 0xff000000);
		item.setText(name);
		item.setFontId(getFontID());
		item.setXPositionMode(WidgetPositionMode.ABSOLUTE_LEFT);
		item.setYPositionMode(WidgetPositionMode.ABSOLUTE_TOP);
		item.setOriginalX(x);
		item.setOriginalY(5);
		item.setOriginalHeight(ICON_HEIGHT);
		item.setOriginalWidth(width);
//		ItemComposition itemComposition = itemManager.getItemComposition(ItemID.BANK_FILLER);
		item.setName(name);
//		item.setItemId(itemComposition.getId());
//		item.setItemQuantity(10000);
//		item.setItemQuantityMode(ItemQuantityMode.NEVER);
		item.setBorderType(1);
		item.setAction(0, tooltipText);
		item.setHasListener(true);

		item.setOnMouseOverListener((JavaScriptCallback) ev -> item.setTextColor(mode == modeToSwitchTo ? 0xffaa0000 : 0xff666666));
		item.setOnMouseLeaveListener((JavaScriptCallback) ev -> item.setTextColor(mode == modeToSwitchTo ? 0xffaa0000 : 0xff000000));

		item.setOnOpListener((JavaScriptCallback) ev ->
		{
			mode = modeToSwitchTo;
			update();
		});

		item.revalidate();

		return item;
	}

	@Override
	public void keyPressed(KeyEvent ev)
	{
		if (!chatboxPanelManager.shouldTakeInput())
		{
			return;
		}

		switch (ev.getKeyCode())
		{
			case KeyEvent.VK_ENTER:
				ev.consume();
				if (index > -1)
				{
					itemSelected(results.keySet().toArray(new Integer[results.size()])[index]);
                }
                break;
            case KeyEvent.VK_TAB:
            case KeyEvent.VK_RIGHT:
                ev.consume();
                if (!results.isEmpty())
                {
                    index++;
                    if (index >= results.size())
                    {
                        index = 0;
                    }
                    clientThread.invokeLater(this::update);
                }
                break;
            case KeyEvent.VK_LEFT:
                ev.consume();
                if (!results.isEmpty())
                {
                    index--;
                    if (index < 0)
                    {
                        index = results.size() - 1;
                    }
                    clientThread.invokeLater(this::update);
                }
                break;
            case KeyEvent.VK_UP:
                ev.consume();
                if (results.size() >= (RESULTS_PER_PAGE / 2))
                {
                    index -= RESULTS_PER_PAGE / 2;
                    if (index < 0)
                    {
                        if (results.size() == RESULTS_PER_PAGE)
                        {
                            index += results.size();
                        }
                        else
                        {
                            index += RESULTS_PER_PAGE;
                        }
                        index = Ints.constrainToRange(index, 0, results.size() - 1);
                    }

                    clientThread.invokeLater(this::update);
                }
                break;
            case KeyEvent.VK_DOWN:
                ev.consume();
                if (results.size() >= (RESULTS_PER_PAGE / 2))
                {
                    index += RESULTS_PER_PAGE / 2;
                    if (index >= RESULTS_PER_PAGE)
                    {
                        if (results.size() == RESULTS_PER_PAGE)
                        {
                            index -= results.size();
                        }
                        else
                        {
                            index -= RESULTS_PER_PAGE;
                        }
                        index = Ints.constrainToRange(index, 0, results.size() - 1);
                    }

                    clientThread.invokeLater(this::update);
                }
                break;
            default:
                super.keyPressed(ev);
        }
    }

    @Override
    protected void close()
    {
    	if (onItemMouseOvered != null) onItemMouseOvered.accept(-1);

        // Clear search string when closed
        value("");
        results.clear();
        spells.clear();
        index = -1;
        mode = 0;
        super.close();
    }

    @Override
    @Deprecated
    public ChatboxTextInput onDone(Consumer<String> onDone)
    {
        throw new UnsupportedOperationException();
    }

    private int page = 0;
	private int lastPage = -1;
	/** For faster searches on pages past the first. */
	private Map<Integer, Integer> filteredPageIndexes = new HashMap<>();

    private void filterResults()
    {
        results.clear();
        spells.clear();
        index = -1;

        String search = getValue().toLowerCase();
        if (search.isEmpty())
        {
        	if (searchType == TRIGGER_ITEM) {
        		// Add equipped items to the list for easy access.
				ItemContainer itemContainer = client.getItemContainer(InventoryID.EQUIPMENT);
				Item[] items = itemContainer.getItems();
				for (int i = 0; i < items.length; i++)
				{
					if (items[i].getId() == -1 || i == EquipmentInventorySlot.RING.getSlotIdx() || i == EquipmentInventorySlot.AMMO.getSlotIdx()) continue;

					ItemComposition itemComposition = itemManager.getItemComposition(itemManager.canonicalize(items[i].getId()));
					results.put(itemComposition.getId(), itemComposition);
				}
				lastPage = 0; // Do not show page change arrows.
				return;
			} else if (searchType == MODEL_SWAP) {
				lastPage = 0; // Do not show page change arrows.
				return;
			} else if (searchType == SPELL_L) {

			} else if (searchType == SPELL_R) {

			}
        }

        // For finding members items in f2p.
		Integer integer = -1;
		try
		{
			integer = Integer.valueOf(search);
		} catch (NumberFormatException e) {
			// that's fine.
		}

		Integer start = filteredPageIndexes.getOrDefault(page - 1, 0);
		if (searchType == TRIGGER_ITEM || searchType == MODEL_SWAP)
		{
			for (int i = start; i < client.getItemCount(); i++)
			{
				ItemComposition itemComposition = getItemCompositionIfUsable(i);
				if (itemComposition == null) continue;

				String name = itemComposition.getName().toLowerCase();
				if (i == integer || name.contains(search))
				{
					if (results.size() == RESULTS_PER_PAGE)
					{
						filteredPageIndexes.put(page, i);
						return; // skip the lastPage setting, since there is at least 1 item on the next page.
					}
					results.put(itemComposition.getId(), itemComposition);
				}
			}
			// We ran out of items to search.
			lastPage = page;
		} else { // is spell.
			int skip = page * RESULTS_PER_PAGE;
			for (ProjectileCast projectile : ProjectileCast.projectiles)
			{
				if (searchType == SPELL_L && projectile.isArtificial()) continue;
				String projectileName = projectile.getName(itemManager);
				if (projectileName.toLowerCase().contains(search) && !spells.contains(projectileName)) {
					if (spells.size() >= RESULTS_PER_PAGE) {
						return; // skip the lastPage setting, since there is at least 1 item on the next page.
					}

					skip--;
					if (skip <= 0)
					{
						spells.add(projectileName);
					}
				}
			}
			// We ran out of items to search.
			lastPage = page;
		}
    }

	private ItemComposition getItemCompositionIfUsable(int i)
	{
		ItemComposition itemComposition = itemManager.getItemComposition(i);
		// skip notes, placeholders, and weight-reducing item equipped version.
		if (itemComposition.getNote() != -1 || itemComposition.getPlaceholderTemplateId() != -1 || WORN_ITEMS.get(i) != null)
		{
			return null;
		}

		ItemStats itemStats = itemManager.getItemStats(itemComposition.getId(), false);
		if (Constants.OVERRIDE_EQUIPPABILITY_OR_SLOT.containsKey(i)) {
			// don't need to check anything else.
		}
		else if (itemStats == null || !itemStats.isEquipable())
		{
			return null;
		} else {
			int slot = itemStats.getEquipment().getSlot();
			if (slot == EquipmentInventorySlot.RING.getSlotIdx() || slot == EquipmentInventorySlot.AMMO.getSlotIdx()) {
				return null;
			}
		}
		return itemComposition;
	}

    public ChatBoxFilterableSearch onItemSelected(Consumer<Integer> onItemSelected)
    {
        this.onItemSelected = onItemSelected;
        return this;
    }

	public ChatBoxFilterableSearch onItemMouseOvered(Consumer<Integer> onItemMouseOvered)
	{
		this.onItemMouseOvered = onItemMouseOvered;
		return this;
	}

	public ChatBoxFilterableSearch onItemDeleted(Runnable onItemDeleted)
	{
		this.onItemDeleted = onItemDeleted;
		return this;
	}

	public ChatBoxFilterableSearch tooltipText(final String text)
    {
        tooltipText = text;
        return this;
    }

    // Copied from ItemManager.
	private static final ImmutableMap<Integer, Integer> WORN_ITEMS = ImmutableMap.<Integer, Integer>builder().
		put(BOOTS_OF_LIGHTNESS_89, BOOTS_OF_LIGHTNESS).
		put(PENANCE_GLOVES_10554, PENANCE_GLOVES).

		put(GRACEFUL_HOOD_11851, GRACEFUL_HOOD).
		put(GRACEFUL_CAPE_11853, GRACEFUL_CAPE).
		put(GRACEFUL_TOP_11855, GRACEFUL_TOP).
		put(GRACEFUL_LEGS_11857, GRACEFUL_LEGS).
		put(GRACEFUL_GLOVES_11859, GRACEFUL_GLOVES).
		put(GRACEFUL_BOOTS_11861, GRACEFUL_BOOTS).
		put(GRACEFUL_HOOD_13580, GRACEFUL_HOOD_13579).
		put(GRACEFUL_CAPE_13582, GRACEFUL_CAPE_13581).
		put(GRACEFUL_TOP_13584, GRACEFUL_TOP_13583).
		put(GRACEFUL_LEGS_13586, GRACEFUL_LEGS_13585).
		put(GRACEFUL_GLOVES_13588, GRACEFUL_GLOVES_13587).
		put(GRACEFUL_BOOTS_13590, GRACEFUL_BOOTS_13589).
		put(GRACEFUL_HOOD_13592, GRACEFUL_HOOD_13591).
		put(GRACEFUL_CAPE_13594, GRACEFUL_CAPE_13593).
		put(GRACEFUL_TOP_13596, GRACEFUL_TOP_13595).
		put(GRACEFUL_LEGS_13598, GRACEFUL_LEGS_13597).
		put(GRACEFUL_GLOVES_13600, GRACEFUL_GLOVES_13599).
		put(GRACEFUL_BOOTS_13602, GRACEFUL_BOOTS_13601).
		put(GRACEFUL_HOOD_13604, GRACEFUL_HOOD_13603).
		put(GRACEFUL_CAPE_13606, GRACEFUL_CAPE_13605).
		put(GRACEFUL_TOP_13608, GRACEFUL_TOP_13607).
		put(GRACEFUL_LEGS_13610, GRACEFUL_LEGS_13609).
		put(GRACEFUL_GLOVES_13612, GRACEFUL_GLOVES_13611).
		put(GRACEFUL_BOOTS_13614, GRACEFUL_BOOTS_13613).
		put(GRACEFUL_HOOD_13616, GRACEFUL_HOOD_13615).
		put(GRACEFUL_CAPE_13618, GRACEFUL_CAPE_13617).
		put(GRACEFUL_TOP_13620, GRACEFUL_TOP_13619).
		put(GRACEFUL_LEGS_13622, GRACEFUL_LEGS_13621).
		put(GRACEFUL_GLOVES_13624, GRACEFUL_GLOVES_13623).
		put(GRACEFUL_BOOTS_13626, GRACEFUL_BOOTS_13625).
		put(GRACEFUL_HOOD_13628, GRACEFUL_HOOD_13627).
		put(GRACEFUL_CAPE_13630, GRACEFUL_CAPE_13629).
		put(GRACEFUL_TOP_13632, GRACEFUL_TOP_13631).
		put(GRACEFUL_LEGS_13634, GRACEFUL_LEGS_13633).
		put(GRACEFUL_GLOVES_13636, GRACEFUL_GLOVES_13635).
		put(GRACEFUL_BOOTS_13638, GRACEFUL_BOOTS_13637).
		put(GRACEFUL_HOOD_13668, GRACEFUL_HOOD_13667).
		put(GRACEFUL_CAPE_13670, GRACEFUL_CAPE_13669).
		put(GRACEFUL_TOP_13672, GRACEFUL_TOP_13671).
		put(GRACEFUL_LEGS_13674, GRACEFUL_LEGS_13673).
		put(GRACEFUL_GLOVES_13676, GRACEFUL_GLOVES_13675).
		put(GRACEFUL_BOOTS_13678, GRACEFUL_BOOTS_13677).
		put(GRACEFUL_HOOD_21063, GRACEFUL_HOOD_21061).
		put(GRACEFUL_CAPE_21066, GRACEFUL_CAPE_21064).
		put(GRACEFUL_TOP_21069, GRACEFUL_TOP_21067).
		put(GRACEFUL_LEGS_21072, GRACEFUL_LEGS_21070).
		put(GRACEFUL_GLOVES_21075, GRACEFUL_GLOVES_21073).
		put(GRACEFUL_BOOTS_21078, GRACEFUL_BOOTS_21076).
		put(GRACEFUL_HOOD_24745, GRACEFUL_HOOD_24743).
		put(GRACEFUL_CAPE_24748, GRACEFUL_CAPE_24746).
		put(GRACEFUL_TOP_24751, GRACEFUL_TOP_24749).
		put(GRACEFUL_LEGS_24754, GRACEFUL_LEGS_24752).
		put(GRACEFUL_GLOVES_24757, GRACEFUL_GLOVES_24755).
		put(GRACEFUL_BOOTS_24760, GRACEFUL_BOOTS_24758).
		put(GRACEFUL_HOOD_25071, GRACEFUL_HOOD_25069).
		put(GRACEFUL_CAPE_25074, GRACEFUL_CAPE_25072).
		put(GRACEFUL_TOP_25077, GRACEFUL_TOP_25075).
		put(GRACEFUL_LEGS_25080, GRACEFUL_LEGS_25078).
		put(GRACEFUL_GLOVES_25083, GRACEFUL_GLOVES_25081).
		put(GRACEFUL_BOOTS_25086, GRACEFUL_BOOTS_25084).
		put(GRACEFUL_HOOD_27446, GRACEFUL_HOOD_27444).
		put(GRACEFUL_CAPE_27449, GRACEFUL_CAPE_27447).
		put(GRACEFUL_TOP_27452, GRACEFUL_TOP_27450).
		put(GRACEFUL_LEGS_27455, GRACEFUL_LEGS_27453).
		put(GRACEFUL_GLOVES_27458, GRACEFUL_GLOVES_27456).
		put(GRACEFUL_BOOTS_27461, GRACEFUL_BOOTS_27459).

		put(MAX_CAPE_13342, MAX_CAPE).

		put(SPOTTED_CAPE_10073, SPOTTED_CAPE).
		put(SPOTTIER_CAPE_10074, SPOTTIER_CAPE).

		put(AGILITY_CAPET_13341, AGILITY_CAPET).
		put(AGILITY_CAPE_13340, AGILITY_CAPE).
		build();
}
