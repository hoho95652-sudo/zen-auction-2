package com.example.donutauction;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class AuctionScreen extends Screen {
    private static final int COLS = 13, ROWS = 7, CELL = 20;

    private final List<ItemStack> all = new ArrayList<>();
    private List<ItemStack> filtered = new ArrayList<>();
    private EditBox search, bidField;
    private TimerSlider slider;
    private ItemStack selected = ItemStack.EMPTY;
    private int scrollRow = 0;
    private int gridX, gridY;

    public AuctionScreen() {
        super(Component.literal("Donut Auction"));
        for (Item i : BuiltInRegistries.ITEM) {
            if (i != Items.AIR) all.add(new ItemStack(i));
        }
        filtered = new ArrayList<>(all);
    }

    @Override
    protected void init() {
        int w = COLS * CELL;
        gridX = (width - w) / 2;
        gridY = 50;

        search = new EditBox(font, gridX, 24, w, 18, Component.literal("Search"));
        search.setHint(Component.literal("Search items..."));
        search.setResponder(s -> { applyFilter(s); scrollRow = 0; });
        addRenderableWidget(search);

        int by = gridY + ROWS * CELL + 30;
        slider = new TimerSlider(gridX, by, 160, 20, 29 / 59.0);
        addRenderableWidget(slider);

        bidField = new EditBox(font, gridX + 170, by, 90, 20, Component.literal("Start bid"));
        bidField.setHint(Component.literal("Start bid (5k)"));
        addRenderableWidget(bidField);

        addRenderableWidget(Button.builder(Component.literal("Start Auction"), b -> startAuction())
                .bounds(gridX + 270, by, 100, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Cancel Live"),
                b -> AuctionManager.cancel(Minecraft.getInstance()))
                .bounds(gridX + 380, by, 80, 20).build());
        addRenderableWidget(Button.builder(modeText(), b -> {
            AuctionManager.payMode = !AuctionManager.payMode;
            b.setMessage(modeText());
        }).bounds(gridX + 340, by + 24, 120, 20).build());
        setInitialFocus(search);
    }

    private Component modeText() {
        return Component.literal("Bids: " + (AuctionManager.payMode ? "/pay me" : "chat 'bid'"));
    }

    private void applyFilter(String q) {
        String needle = q.toLowerCase(Locale.ROOT).trim();
        filtered = new ArrayList<>();
        for (ItemStack s : all) {
            if (needle.isEmpty()
                    || s.getHoverName().getString().toLowerCase(Locale.ROOT).contains(needle)
                    || BuiltInRegistries.ITEM.getKey(s.getItem()).getPath().contains(needle)) filtered.add(s);
        }
    }

    private void startAuction() {
        if (selected.isEmpty()) return;
        long bid = bidField.getValue().isBlank() ? 1 : AuctionManager.parseAmount(bidField.getValue());
        if (bid < 1) return;
        AuctionManager.start(Minecraft.getInstance(), selected, slider.seconds(), bid);
        onClose();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
        super.extractRenderState(g, mx, my, delta);
        g.text(font, "Donut Auction", gridX, 8, 0xFFFFFFFF, true);

        ItemStack hover = ItemStack.EMPTY;
        int start = scrollRow * COLS;
        for (int i = 0; i < COLS * ROWS; i++) {
            int idx = start + i;
            if (idx >= filtered.size()) break;
            int x = gridX + (i % COLS) * CELL, y = gridY + (i / COLS) * CELL;
            ItemStack s = filtered.get(idx);
            boolean isSel = ItemStack.isSameItem(s, selected);
            g.fill(x, y, x + CELL - 1, y + CELL - 1, isSel ? 0xAA55FF55 : 0x66000000);
            g.item(s, x + 2, y + 2);
            if (mx >= x && mx < x + CELL && my >= y && my < y + CELL) hover = s;
        }
        if (!hover.isEmpty()) g.text(font, hover.getHoverName().getString(), gridX + 110, 8, 0xFFFFFF55, true);

        int infoY = gridY + ROWS * CELL + 6;
        String sel = selected.isEmpty() ? "None" : selected.getHoverName().getString();
        g.text(font, "Selected: " + sel + "   (" + filtered.size() + " items, scroll for more)", gridX, infoY, 0xFFFFFFFF, true);
        g.text(font, AuctionManager.payMode
                ? "Timer: 1-60s | Bidders /pay you; their payments are the bids"
                : "Timer: 1-60s | Bidders type \"bid <amount>\" in chat", gridX, infoY + 12, 0xFFAAAAAA, true);

        if (AuctionManager.active) {
            String top = AuctionManager.highestBidder == null ? "no bids"
                    : AuctionManager.highestBidder + " $" + AuctionManager.fmt(AuctionManager.highestBid);
            g.text(font, "LIVE: " + AuctionManager.secondsLeft() + "s - " + top,
                    gridX, gridY + ROWS * CELL + 58, 0xFFFFD700, true);
        }
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (super.mouseClicked(event, doubleClick)) return true;
        double mx = event.x(), my = event.y();
        int cx = (int) Math.floor((mx - gridX) / CELL), cy = (int) Math.floor((my - gridY) / CELL);
        if (mx >= gridX && my >= gridY && cx < COLS && cy < ROWS) {
            int idx = scrollRow * COLS + cy * COLS + cx;
            if (idx < filtered.size()) { selected = filtered.get(idx).copy(); return true; }
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double hAmt, double vAmt) {
        int maxRow = Math.max(0, (filtered.size() + COLS - 1) / COLS - ROWS);
        scrollRow = Math.max(0, Math.min(maxRow, scrollRow - (int) Math.signum(vAmt)));
        return true;
    }

    private static class TimerSlider extends AbstractSliderButton {
        TimerSlider(int x, int y, int w, int h, double v) {
            super(x, y, w, h, Component.empty(), v);
            updateMessage();
        }
        int seconds() { return 1 + (int) Math.round(value * 59); }
        @Override protected void updateMessage() { setMessage(Component.literal("Timer: " + seconds() + "s")); }
        @Override protected void applyValue() {}
    }
}
